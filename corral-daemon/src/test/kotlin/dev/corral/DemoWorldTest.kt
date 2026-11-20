package dev.corral

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * The demo world is the pool, the screenshots and the emulator host of a demo daemon — all in-JVM.
 * These rows pin the parts the safety contract leans on: a device is exactly what the world file
 * says (the real DTO's field names), an emulator only becomes claimable when its boot flip is due,
 * and a screenshot never reaches a process.
 */
class DemoWorldTest {
    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)

    private fun world(text: String, now: () -> Long = { 0L }): DemoWorld {
        val file = corralJson.decodeFromString<DemoWorldFile>(text)
        return DemoWorld(file, scope, now, resource = { name ->
            if (name == "/demo/screen.png") "PNG".toByteArray() else null
        })
    }

    private val threeDevices = """
        {"devices":[
          {"serial":"DEMO01234567","state":"device","model":"Pixel 7 (demo)","osVersion":"14"},
          {"serial":"192.168.1.50:5555","state":"device","model":"Galaxy A54 (demo)","osVersion":"13"},
          {"serial":"emulator-5554","state":"device","model":"sdk_gphone64 (demo)","avd":"DemoAVD","booted":false}],
         "avds":[{"name":"DemoAVD","bootSeconds":6},{"name":"DemoTabletAVD","bootSeconds":9}]}
    """.trimIndent()

    @Test fun theShippedWorldParsesWithTheRealDtoFieldNamesAndNeverCollidesWithRealShapes() {
        val shipped = DemoWorld::class.java.getResourceAsStream("/demo/world.json")?.bufferedReader()?.use { it.readText() }
        assertNotNull(shipped, "demo/world.json must ship inside the daemon jar")
        val w = world(shipped)
        val devices = w.devices
        assertEquals(3, devices.size)
        val usb = devices.first { it.serial == "DEMO01234567" }
        assertEquals("Pixel 7 (demo)", usb.model, "the label is what keeps a demo device unmistakable")
        assertEquals("14", usb.osVersion, "osVersion, not the plan's indicative 'os'")
        assertEquals("usb", usb.transport)
        assertTrue(usb.booted)
        val wifi = devices.first { it.transport == "wifi" }
        assertEquals("192.168.1.50:5555", wifi.serial, "a wifi serial keeps its real shape")
        val emu = devices.first { it.transport == "emulator" }
        assertFalse(emu.booted, "an emulator the world has not booted must not be claimable")
        assertEquals("DemoAVD", emu.avd)
        assertEquals(listOf("DemoAVD", "DemoTabletAVD"), w.list())
    }

    @Test fun bootFlipIsAValueNotASleep() {
        var clock = 1_000L
        val w = world(threeDevices) { clock }
        val emu = w.devices.first { it.transport == "emulator" }
        assertFalse(emu.booted)

        assertTrue(w.start("DemoAVD"))
        assertTrue(w.isRunning("DemoAVD"), "a started AVD reports live before adb lists it")
        assertFalse(w.devices.first { it.transport == "emulator" }.booted, "a fresh boot starts unbooted")
        assertFalse(w.refresh(), "nothing is due yet")

        clock += 5_999
        assertFalse(w.refresh())
        assertFalse(w.devices.first { it.transport == "emulator" }.booted)

        clock += 1
        assertTrue(w.refresh(), "the boot flip is due at bootSeconds")
        assertTrue(w.devices.first { it.transport == "emulator" }.booted)
        assertFalse(w.refresh(), "a flip happens once")
    }

    @Test fun aSecondStartAfterAStopIsAFreshColdBoot() {
        var clock = 0L
        val w = world(threeDevices) { clock }
        assertTrue(w.start("DemoAVD"))
        clock += 6_000
        assertTrue(w.refresh())
        assertTrue(w.devices.first { it.transport == "emulator" }.booted)
        assertFalse(w.start("DemoAVD"), "while it is already running the start is a no-op")

        assertNotNull(w.stopAvd("DemoAVD"))
        assertNull(w.devices.firstOrNull { it.transport == "emulator" })
        assertTrue(w.start("DemoAVD"), "a stopped AVD can be started again")
        assertFalse(w.devices.first { it.transport == "emulator" }.booted, "a restart is a new cold boot")
        assertEquals("emulator-5554", w.devices.first { it.transport == "emulator" }.serial)
    }

    @Test fun anAvdOutsideTheWorldIsAnErrorNotAnInvention() {
        val w = world(threeDevices)
        val e = assertFailsWith<EmulatorException> { w.start("Pixel9AVD") }
        assertContains(e.message ?: "", "Pixel9AVD")
    }

    @Test fun stopRemovesTheInstanceAndItsAvd() {
        val w = world(threeDevices)
        w.start("DemoAVD")
        w.stop("emulator-5554")
        assertNull(w.devices.firstOrNull { it.serial == "emulator-5554" })
        assertFalse(w.isRunning("DemoAVD"))
        assertEquals(emptyList(), w.logSerials("DemoAVD"))
        assertFailsWith<EmulatorException> { w.stop("emulator-5554") }
        assertFailsWith<EmulatorException> { w.stop("DEMO01234567") }
    }

    @Test fun theDemoPoolNeverSuppressesASerialItKilledAndNeverReportsAWedge() {
        val w = world(threeDevices)
        val before = w.devices
        assertEquals(before, w.liveDevices(before))
        assertEquals(emptyMap(), w.launchedPastBootDeadline(), "no wedge beat: wedgeReason stays off every device")
        assertNull(w.lastError("DemoAVD"))
    }

    @Test fun screenshotsServeBytesAndKeepTheRealPreChecks() = runTest {
        val w = world(threeDevices)
        assertEquals("PNG", w.capture("DEMO01234567").decodeToString())
        assertFailsWith<CaptureException.UnknownDevice> { w.capture("nope") }
        val unavailable = world("""{"devices":[{"serial":"off1","state":"offline"}]}""")
        assertFailsWith<CaptureException.UnusableDevice> { unavailable.capture("off1") }
    }

    @Test fun aMissingScreenAssetFailsLoudly() = runTest {
        val file = corralJson.decodeFromString<DemoWorldFile>(
            """{"devices":[{"serial":"dev1"}],"screens":{"dev1":"/demo/nope.png"}}""")
        val w = DemoWorld(file, scope, { 0L }, resource = { null })
        val e = assertFailsWith<CaptureException.AdbFailure> { w.capture("dev1") }
        assertContains(e.message ?: "", "/demo/nope.png")
    }

    @Test fun aWorldFileOverrideIsReadAndAnUnreadableOneTeaches() {
        val dir = createTempDirectory("demoworld")
        val good = dir.resolve("world.json")
        Files.writeString(good, """{"devices":[{"serial":"dev9","model":"Pixel 9 (demo)"}]}""")
        val w = DemoWorld.load(scope, mapOf("CORRAL_DEMO_WORLD" to good.toString()))
        assertEquals("dev9", w.devices.single().serial)

        val e = assertFailsWith<IllegalStateException> {
            DemoWorld.load(scope, mapOf("CORRAL_DEMO_WORLD" to dir.resolve("absent.json").toString()))
        }
        assertContains(e.message ?: "", "unreadable")
        val bad = dir.resolve("bad.json")
        Files.writeString(bad, "not json")
        assertContains(assertFailsWith<IllegalStateException> {
            DemoWorld.load(scope, mapOf("CORRAL_DEMO_WORLD" to bad.toString()))
        }.message ?: "", "not valid JSON")
    }
}
