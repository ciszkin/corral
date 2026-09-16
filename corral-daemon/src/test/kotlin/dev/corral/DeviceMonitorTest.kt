package dev.corral

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DeviceMonitorTest {
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
}
