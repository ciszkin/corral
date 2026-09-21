package dev.corral

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScreenshotterTest {
    private fun fixture(vararg devices: Device): Triple<Screenshotter, String, () -> Int> {
        val dir = createTempDirectory("shot")
        val log = dir.resolve("invocations")
        val fakeAdb = dir.resolve("fake-adb")
        Files.writeString(fakeAdb, """
            #!/usr/bin/env bash
            echo "$*" >> '$log'
            [ "$2" = "${'$'}{FAKE_ADB_FAIL_SERIAL:-}" ] && exit 3
            printf 'fake-screen'
        """.trimIndent())
        fakeAdb.toFile().setExecutable(true)
        var now = 1_000L
        val shot = Screenshotter({ devices.toList() }, fakeAdb.toString()) { now }
        return Triple(shot, log.toString(), { Files.readAllLines(dir.resolve("invocations")).size })
    }

    @Test fun capturesPngBytes() = runBlocking {
        val (shot, _, _) = fixture(Device("dev1", "device"))
        assertEquals("fake-screen".encodeToByteArray().toList(), shot.capture("dev1").toList())
    }

    @Test fun concurrentCallsShareOneFlight() = runBlocking(Dispatchers.Default) {
        val (shot, _, count) = fixture(Device("dev1", "device"))
        (1..5).map { async { shot.capture("dev1") } }.awaitAll()
        assertEquals(1, count())
    }

    @Test fun windowReusesFreshResult() = runBlocking {
        val (shot, _, count) = fixture(Device("dev1", "device"))
        shot.capture("dev1"); shot.capture("dev1")
        assertEquals(1, count())
    }

    @Test fun pastWindowSpawnsAgain() = runBlocking {
        val dir = createTempDirectory("shot"); val log = dir.resolve("invocations")
        val fakeAdb = dir.resolve("fake-adb")
        Files.writeString(fakeAdb, "#!/usr/bin/env bash\necho \"$*\" >> '$log'\nprintf 'fake-screen'\n")
        fakeAdb.toFile().setExecutable(true)
        var now = 1_000L
        val shot = Screenshotter({ listOf(Device("dev1", "device")) }, fakeAdb.toString(), { now })
        shot.capture("dev1"); now += SCREENSHOT_WINDOW_MS + 1; shot.capture("dev1")
        assertEquals(2, Files.readAllLines(log).size)
    }

    @Test fun unknownSerialFails(): Unit = runBlocking {
        val (shot, _, _) = fixture(Device("dev1", "device"))
        assertFailsWith<CaptureException.UnknownDevice> { shot.capture("nope") }
    }

    @Test fun unusableDeviceFails(): Unit = runBlocking {
        val (shot, _, _) = fixture(Device("dev1", "offline"))
        assertFailsWith<CaptureException.UnusableDevice> { shot.capture("dev1") }
    }

    @Test fun adbFailurePropagates() = runBlocking {
        val dir = createTempDirectory("shot")
        val fakeAdb = dir.resolve("fake-adb")
        Files.writeString(fakeAdb, "#!/usr/bin/env bash\necho boom >&2\nexit 3\n")
        fakeAdb.toFile().setExecutable(true)
        val shot = Screenshotter({ listOf(Device("dev1", "device")) }, fakeAdb.toString())
        val e = assertFailsWith<CaptureException.AdbFailure> { shot.capture("dev1") }
        assertTrue("boom" in (e.message ?: ""))
    }

    @Test fun cancelledCaptureDoesNotPoisonFlight() = runBlocking {
        val dir = createTempDirectory("shot")
        val marker = dir.resolve("started")
        val fakeAdb = dir.resolve("fake-adb")
        Files.writeString(fakeAdb, """
            #!/usr/bin/env bash
            if [ ! -f '$marker' ]; then touch '$marker'; exec sleep 30; fi
            printf 'fake-screen'
        """.trimIndent())
        fakeAdb.toFile().setExecutable(true)
        val shot = Screenshotter({ listOf(Device("dev1", "device")) }, fakeAdb.toString())
        val hanging = async(Dispatchers.Default) { shot.capture("dev1") }
        var waited = 0
        while (!Files.exists(marker) && waited < 5_000) { delay(10); waited += 10 }
        assertTrue(Files.exists(marker), "fake adb never started")
        hanging.cancel()
        val stopped = withTimeoutOrNull(2_000) { runCatching { hanging.await() }.isFailure }
        assertEquals(true, stopped, "cancelled capture still blocked on adb")
        assertEquals("fake-screen", shot.capture("dev1").decodeToString())
    }
}
