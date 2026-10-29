package dev.corral.ui

import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class ScrcpyLauncherTest {
    /**
     * A fake scrcpy the JVM can actually spawn on this host. Windows has no shebang: a `.bat` is
     * what CreateProcess (and therefore ProcessBuilder) accepts, so the suite is honest on the
     * Windows box that gates releases instead of failing there for a POSIX-only reason.
     */
    private fun fakeScrcpy(dir: java.nio.file.Path, posix: String, windows: String): java.nio.file.Path {
        val win = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val file = dir.resolve(if (win) "fake-scrcpy.bat" else "fake-scrcpy")
        Files.writeString(file, if (win) windows else posix)
        file.toFile().setExecutable(true)
        return file
    }

    @Test fun spawnsReadOnlyScrcpyPinnedToTheSerial() = runTest {
        val dir = createTempDirectory("scrcpy")
        val log = dir.resolve("invocations")
        val fakeScript = fakeScrcpy(dir,
            posix = "#!/usr/bin/env bash\necho \"$*\" >> '$log'\n",
            windows = "@echo off\r\necho %* >> \"$log\"\r\n")

        val p = ScrcpyLauncher(mapOf("CORRAL_SCRCPY" to fakeScript.toString())).launch("dev1")

        assertTrue(p.waitFor() == 0)
        assertEquals("-s dev1 --no-control --no-clipboard-autosync --no-audio",
            Files.readString(log).trim())
    }

    @Test fun bareNameOverrideFallsThroughToTheSpawn() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            ScrcpyLauncher(mapOf("CORRAL_SCRCPY" to "corral-no-such-scrcpy-bin")).launch("dev1")
        }
        assertTrue(e.message!!.contains("CORRAL_SCRCPY"))
    }

    @Test fun missingBinaryTeachesTheOverride() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            ScrcpyLauncher(mapOf("CORRAL_SCRCPY" to "/no/such/scrcpy")).launch("dev1")
        }
        assertTrue(e.message!!.contains("CORRAL_SCRCPY"))
    }

    @Test fun earlyNonZeroExitIsReported() = runTest {
        val dir = createTempDirectory("scrcpy")
        val fakeScript = fakeScrcpy(dir,
            posix = "#!/usr/bin/env bash\nexit 3\n",
            windows = "@echo off\r\nexit /b 3\r\n")

        val e = assertFailsWith<IllegalStateException> {
            ScrcpyLauncher(mapOf("CORRAL_SCRCPY" to fakeScript.toString())).launch("dev1")
        }
        assertTrue(e.message!!.contains("code 3"), "expected the exit code in: ${e.message}")
    }

    @Test fun longLivedScrcpyDoesNotBlockTheCallerThread() = runBlocking {
        val dir = createTempDirectory("scrcpy")
        val fakeScript = fakeScrcpy(dir,
            posix = "#!/usr/bin/env bash\nexec sleep 30\n",   // outlives the early-exit window
            windows = "@echo off\r\nping -n 5 127.0.0.1 >NUL\r\n")
        val launcher = ScrcpyLauncher(mapOf("CORRAL_SCRCPY" to fakeScript.toString()))

        // one thread: the probe is queued BEHIND launch on it, so a launch() that blocks the
        // thread can only release the probe after the full 700 ms early-exit wait — suspending
        // lets it through at once
        val caller = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(caller)
        val process = CompletableDeferred<Process>()
        try {
            scope.launch { process.complete(launcher.launch("dev1")) }
            val probe = CompletableDeferred<Unit>()
            scope.launch { probe.complete(Unit) }
            withTimeout(650) { probe.await() }
        } finally {
            process.await().destroy()
            caller.close()
        }
    }
}
