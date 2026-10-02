package dev.corral

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DeviceMonitorTest {
    private val fakeAdb: File
        get() {
            var d: File? = File(System.getProperty("user.dir")).absoluteFile
            while (d != null && !File(d, "scripts/fake-adb").exists()) d = d.parentFile
            return d?.resolve("scripts/fake-adb")
                ?: error("scripts/fake-adb not found above ${System.getProperty("user.dir")}")
        }

    @Test fun parsesFullSnapshotMessages() {
        val payload = "dev1\tdevice\n192.168.1.5:5555\toffline\nemulator-5554\tdevice\n"
        val hex = payload.length.toString(16).padStart(4, '0')
        assertEquals(
            listOf("dev1" to "device", "192.168.1.5:5555" to "offline", "emulator-5554" to "device"),
            TrackParser.parse("$hex$payload"))
    }

    @Test fun transportClassified() {
        val d = TrackParser.parse("0011emulator-1\tdevice").first().let { Device(it.first, it.second) }
        assertEquals("emulator", d.transport)
        assertEquals("wifi", Device("192.168.1.5:5555", "device").transport)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun zeroDevicesEmptyAndClaimFails() = runTest {
        val dir = createTempDirectory("corral").toFile()
        val f = dir.resolve("devices")
        f.writeText("")
        val m = DeviceMonitor(fakeAdb.path, f.path, backgroundScope)
        m.start()
        runCurrent()
        assertTrue(m.devices.isEmpty())

        // the same empty inventory at the lease seam: no device to claim, and the
        // requestClaim failure the CLI renders as "no connected device matches"
        val lm = LeaseManager({ emptyList() }, null, { 1000L })
        assertNull(lm.claim("dev1", "agentA"))
        val busy = runCatching { lm.requestClaim(Selector(serial = "dev1"), "agentA") }
            .exceptionOrNull() as LeaseBusyException
        assertEquals(0, busy.matching)
        assertTrue(busy.busy.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun fixtureEditsAppearAndDisappear() = runTest {
        val dir = createTempDirectory("corral").toFile()
        val f = dir.resolve("devices")
        f.writeText("dev1 device\ndev2 device\n")
        val m = DeviceMonitor(fakeAdb.path, f.path, backgroundScope)
        m.start()
        runCurrent()
        assertEquals(listOf("dev1", "dev2"), m.devices.map { it.serial })

        f.writeText("dev1 device\ndev3 device\n")          // dev3 connects, dev2 disconnects
        advanceTimeBy(2001); runCurrent()
        assertEquals(listOf("dev1", "dev3"), m.devices.map { it.serial })

        f.writeText("dev3 device\n")                       // dev1 disconnects
        advanceTimeBy(2001); runCurrent()
        assertEquals(listOf("dev3"), m.devices.map { it.serial })

        f.writeText("dev1 device\n")                       // dev1 reconnects, same serial
        advanceTimeBy(2001); runCurrent()
        assertEquals(listOf("dev1"), m.devices.map { it.serial })   // exactly one entry, not a duplicate
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun unauthorizedAndOfflineExcluded() = runTest {
        val dir = createTempDirectory("corral").toFile()
        val f = dir.resolve("devices")
        f.writeText("dev1 unauthorized\ndev2 offline\ndev3 device\n")
        val m = DeviceMonitor(fakeAdb.path, f.path, backgroundScope)
        m.start()
        runCurrent()
        val bySerial = m.devices.associateBy { it.serial }
        assertFalse(bySerial.getValue("dev1").usable())
        assertFalse(bySerial.getValue("dev2").usable())
        assertTrue(bySerial.getValue("dev3").usable())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun fakeColumnsParseAndOnChangeFires() = runTest {
        val dir = createTempDirectory("corral").toFile()
        val f = dir.resolve("devices")
        f.writeText("dev1 device Pixel_6 13\nemulator-5554 device Pixel_6 13 Pixel6AVD\n")
        var fired = 0
        val m = DeviceMonitor("adb", f.path, backgroundScope)
        m.onChange = { fired++ }
        m.start()
        runCurrent()
        val d1 = m.devices.first { it.serial == "dev1" }
        assertEquals("device", d1.state); assertEquals("Pixel_6", d1.model)
        assertEquals("13", d1.osVersion); assertNull(d1.avd)
        val emu = m.devices.first { it.serial == "emulator-5554" }
        assertEquals("emulator", emu.transport); assertEquals("Pixel6AVD", emu.avd)
        assertTrue(fired >= 1)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun emulatorAvdPopulated() = runTest {
        val dir = createTempDirectory("corral").toFile()
        val f = dir.resolve("devices")
        f.writeText("emulator-5554 device\n")
        val m = DeviceMonitor(fakeAdb.path, f.path, backgroundScope)
        m.start()
        runCurrent()
        val emu = m.devices.first { it.serial == "emulator-5554" }
        assertEquals("emulator", emu.transport)
        assertEquals("Pixel_6", emu.model)
        assertEquals("13", emu.osVersion)
        assertEquals("Pixel6AVD", emu.avd)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun enrichmentFailureStillLists() = runTest {
        val dir = createTempDirectory("corral").toFile()
        val f = dir.resolve("devices")
        f.writeText("emulator-5554 device\n")
        // a wrapper injects the fail serial into the fake adb's env (System.getenv can't be changed)
        val failing = dir.resolve("fake-adb-fail")
        failing.writeText("#!/usr/bin/env bash\nFAKE_ADB_FAIL_SERIAL=emulator-5554 exec '${fakeAdb.path}' \"\$@\"\n")
        failing.setExecutable(true)
        val m = DeviceMonitor(failing.path, f.path, backgroundScope)
        m.start()
        runCurrent()
        val emu = m.devices.first { it.serial == "emulator-5554" }
        assertEquals("device", emu.state)
        assertNull(emu.model)
    }

    private fun bootWrapper(dir: File, value: String) = dir.resolve("fake-adb-boot").apply {
        writeText("#!/usr/bin/env bash\nFAKE_ADB_BOOTED=$value exec '${fakeAdb.path}' \"\$@\"\n")
        setExecutable(true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun emulatorFirstSightIsUnbootedThenFlips() = runTest {
        val dir = createTempDirectory("corral").toFile()
        val f = dir.resolve("devices")
        f.writeText("emulator-5554 device\n")
        val m = DeviceMonitor(bootWrapper(dir, "0").path, f.path, backgroundScope)
        m.start()
        runCurrent()
        assertFalse(m.devices.first { it.serial == "emulator-5554" }.booted)

        bootWrapper(dir, "1")
        advanceTimeBy(2001); runCurrent()
        assertTrue(m.devices.first { it.serial == "emulator-5554" }.booted)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun physicalDevicesAreNeverBootPolled() = runTest {
        val dir = createTempDirectory("corral").toFile()
        val f = dir.resolve("devices")
        f.writeText("dev1 device\ndev2 device\n")
        val log = dir.resolve("adb.log")
        val w = dir.resolve("fake-adb-log").apply {
            writeText("#!/usr/bin/env bash\nFAKE_ADB_LOG='${log.path}' exec '${fakeAdb.path}' \"\$@\"\n")
            setExecutable(true)
        }
        val m = DeviceMonitor(w.path, f.path, backgroundScope)
        m.start()
        runCurrent()
        advanceTimeBy(2001); runCurrent()
        val calls = log.readText()
        assertTrue(calls.isNotBlank())
        assertFalse(calls.lines().any { it.contains("sys.boot_completed") })
    }
}
