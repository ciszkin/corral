package dev.corral

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorralCliExecTest {
    // --- Windows .bat/.cmd launch: pinned on the constructed argv, meaningful on Linux CI too ---

    @Test fun windowsBatchRunsThroughCmd() {
        val argv = commandArgv(
            listOf("adb", "-s", "dev1", "shell", "echo", "hi"),
            windows = true,
            resolve = { "C:\\Users\\me\\bin\\adb.bat" })
        assertEquals(
            listOf("cmd.exe", "/c", "\"\"C:\\Users\\me\\bin\\adb.bat\" -s dev1 shell echo hi\""),
            argv)
    }

    @Test fun windowsSpacedProgramAndSpacedArgumentKeepsBoundary() {
        // One /c command string; the outer pair is what rule 2 strips, so the arg keeps its own
        // quotes and a batch shim's %1 is the whole "-Pmsg=a b", not "-Pmsg=a" plus "b".
        val argv = commandArgv(
            listOf("adb", "-Pmsg=a b"),
            windows = true,
            resolve = { "C:\\Program Files\\x\\adb.bat" })
        assertEquals(
            listOf("cmd.exe", "/c", "\"\"C:\\Program Files\\x\\adb.bat\" \"-Pmsg=a b\"\""),
            argv)
    }

    @Test fun windowsPlainProgramAndSpacedArgumentKeepsBoundary() {
        val argv = commandArgv(
            listOf("adb", "-Pmsg=a b"),
            windows = true,
            resolve = { "C:\\sdk\\adb.bat" })
        assertEquals(
            listOf("cmd.exe", "/c", "\"\"C:\\sdk\\adb.bat\" \"-Pmsg=a b\"\""),
            argv)
    }

    @Test fun windowsPlainProgramAndPlainArgsStayUnquoted() {
        val argv = commandArgv(
            listOf("adb", "-s", "dev1", "shell", "echo", "hi"),
            windows = true,
            resolve = { "C:\\sdk\\adb.bat" })
        assertEquals(
            listOf("cmd.exe", "/c", "\"\"C:\\sdk\\adb.bat\" -s dev1 shell echo hi\""),
            argv)
    }

    @Test fun windowsCmdFileAlsoRunsThroughCmd() {
        val argv = commandArgv(listOf("gradlew", "installDebug"), windows = true,
            resolve = { "C:\\proj\\gradlew.cmd" })
        assertEquals(
            listOf("cmd.exe", "/c", "\"\"C:\\proj\\gradlew.cmd\" installDebug\""),
            argv)
    }

    @Test fun windowsExeRunsDirectly() {
        val argv = commandArgv(listOf("adb", "devices"), windows = true,
            resolve = { "C:\\sdk\\adb.exe" })
        assertEquals(listOf("C:\\sdk\\adb.exe", "devices"), argv)
    }

    @Test fun linuxArgvIsUnchanged() {
        var notified = false
        val argv = commandArgv(listOf("adb", "-s", "dev1", "shell", "echo", "hi"), windows = false,
            resolve = { error("must not resolve on a non-Windows host") },
            onResolved = { notified = true })
        assertEquals(listOf("adb", "-s", "dev1", "shell", "echo", "hi"), argv)
        assertFalse(notified, "the resolver and its callback must not run on a non-Windows host")
    }

    @Test fun commandArgvReportsUnresolvedWindowsProgram() {
        var resolved: Boolean? = null
        commandArgv(listOf("adb", "devices"), windows = true, resolve = { null },
            onResolved = { resolved = it })
        assertEquals(false, resolved)
    }

    @Test fun commandArgvReportsResolvedWindowsProgram() {
        var resolved: Boolean? = null
        commandArgv(listOf("adb", "devices"), windows = true, resolve = { "C:\\sdk\\adb.exe" },
            onResolved = { resolved = it })
        assertEquals(true, resolved)
    }

    // --- launch-failure wording: "not found" and "found but refused" must read apart, and neither
    // --- may reprint the JDK's `Cannot run program "X"` phrase that once read as an unreachable daemon.

    @Test fun notFoundLaunchFailureNamesShimDirectory() {
        val msg = launchFailure("adb", resolved = false, detail = "CreateProcess error=2")
        assertTrue(msg.contains("cannot find adb"), msg)
        assertTrue(msg.contains("%USERPROFILE%\\bin"), msg)
        assertFalse(msg.contains("Cannot run program"), msg)
    }

    @Test fun foundButRefusedLaunchFailureKeepsErrorCode() {
        val msg = launchFailure("adb", resolved = true, detail = "CreateProcess error=2")
        assertTrue(msg.contains("found adb but could not start it"), msg)
        assertTrue(msg.contains("CreateProcess error=2"), msg)
        assertFalse(msg.contains("Cannot run program"), msg)
    }

    @Test fun unknownResolutionLaunchFailureDoesNotClaimFound() {
        val msg = launchFailure("adb", resolved = null, detail = "error=2, No such file or directory")
        assertTrue(msg.contains("could not start adb"), msg)
        assertFalse(msg.contains("found"), msg)
        assertFalse(msg.contains("Cannot run program"), msg)
    }

    @Test fun bareNameResolvesToBatOnPath() {
        val dir = Files.createTempDirectory("corral-path")
        val bat = Files.createFile(dir.resolve("adb.bat"))
        val resolved = resolvePathProgram("adb",
            mapOf("PATH" to dir.toString(), "PATHEXT" to ".EXE;.BAT;.CMD"),
            cwd = Files.createTempDirectory("corral-cwd").toString())
        assertEquals(bat.toString(), resolved)
    }

    @Test fun bareNameWithNoMatchIsNull() {
        val dir = Files.createTempDirectory("corral-path")
        assertEquals(null, resolvePathProgram("adb",
            mapOf("PATH" to dir.toString(), "PATHEXT" to ".EXE;.BAT;.CMD"),
            cwd = Files.createTempDirectory("corral-cwd").toString()))
    }

    @Test fun programResolvesFromCurrentDirectoryBeforePath() {
        // Windows resolves the CWD first; a shim that lives only there is found by cmd.exe.
        val cwd = Files.createTempDirectory("corral-cwd")
        val bat = Files.createFile(cwd.resolve("adb.bat"))
        val path = Files.createTempDirectory("corral-path")
        val resolved = resolvePathProgram("adb",
            mapOf("PATH" to path.toString(), "PATHEXT" to ".EXE;.BAT;.CMD"),
            cwd = cwd.toString())
        assertEquals(bat.toString(), resolved)
    }

    @Test fun currentDirectoryWinsOverPath() {
        val cwd = Files.createTempDirectory("corral-cwd")
        val fromCwd = Files.createFile(cwd.resolve("adb.bat"))
        val path = Files.createTempDirectory("corral-path")
        Files.createFile(path.resolve("adb.bat"))
        val resolved = resolvePathProgram("adb",
            mapOf("PATH" to path.toString(), "PATHEXT" to ".EXE;.BAT;.CMD"),
            cwd = cwd.toString())
        assertEquals(fromCwd.toString(), resolved)
    }

    @Test fun programExistsNowhereDoesNotResolve() {
        assertEquals(null, resolvePathProgram("adb",
            mapOf("PATH" to Files.createTempDirectory("corral-path").toString(), "PATHEXT" to ".EXE;.BAT;.CMD"),
            cwd = Files.createTempDirectory("corral-cwd").toString()))
    }

    @Test fun unresolvedProgramKeepsBareName() {
        // The caller falls back to the bare name when the resolver finds nothing, launching it directly.
        val argv = commandArgv(listOf("adb", "devices"), windows = true, resolve = { null })
        assertEquals(listOf("adb", "devices"), argv)
    }

    // --- doctor: the empty-pool hint reaches the CLI only for a reachable, genuinely empty pool ---

    @Test fun emptyPoolHintOnlyForAnEmptyReachablePool() {
        assertNull(emptyPoolHint(null))
        assertNull(emptyPoolHint(listOf(Device("dev1", "device"))))
        assertEquals(EMPTY_POOL_HINT, emptyPoolHint(emptyList()))
    }

    // --- the lease is handed back however the wrapped command ends ---

    @Test fun leaseReleasedWhenChildFailsToStart() {
        val err = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(err))
        var released = false
        val cmd = listOf("/no/such/corral-program")
        val rc = try {
            runLeased(cmd, true,
                { ProcessBuilder("/no/such/corral-program") }) { released = true }
        } finally {
            System.setErr(original)
        }
        assertEquals(127, rc)
        assertTrue(released, "a start failure must still free the lease")
        val message = err.toString()
        assertTrue(message.contains("corral: found /no/such/corral-program but could not start it"), message)
        assertFalse(message.contains("Cannot run program"), message)
        assertFalse(message.contains("daemon unreachable"), message)
    }

    @Test fun leaseReleasedWhenLaunchRejectsEmbeddedQuote() {
        // allowAmbiguousCommands=false rejects our start-and-end-quoted command in ProcessImpl.start
        // before CreateProcess; that is an IllegalArgumentException, not an IOException. ProcessBuilder
        // is final, so inject the launch-time throw at the build() seam to pin the path without a
        // special JVM flag.
        val err = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(err))
        var released = false
        val cmd = listOf("\"C:\\sdk\\adb.bat\" -s dev1 shell echo hi")
        val rc = try {
            runLeased(cmd, true, {
                throw IllegalArgumentException("Malformed argument has embedded quote: ${cmd.first()}")
            }) { released = true }
        } finally {
            System.setErr(original)
        }
        assertEquals(127, rc)
        assertTrue(released, "a launch rejection must still free the lease")
        val message = err.toString()
        assertTrue(message.contains("corral: found ${cmd.first()} but could not start it"), message)
        assertFalse(message.contains("Cannot run program"), message)
        assertFalse(message.contains("daemon unreachable"), message)
        assertFalse(message.contains("Exception"), "the teaching message, not a stack trace: $message")
    }

    @Test fun leaseReleasedWhenChildExitsNonZero() {
        var released = false
        val rc = runLeased(listOf("sh"), null, { ProcessBuilder("sh", "-c", "exit 7") }) { released = true }
        assertEquals(7, rc)
        assertTrue(released, "a non-zero exit must still free the lease")
    }

    // --- row 33 F7: the doctor's unreachable-daemon hint names the platform's own supervisor ------

    @Test fun daemonDownHintIsPlatformAppropriate() {
        val posix = daemonDownHint(windows = false)
        assertTrue("systemctl --user status corral" in posix, posix)
        assertFalse("schtasks" in posix, "POSIX hint must not name the Windows task: $posix")
        val win = daemonDownHint(windows = true)
        assertTrue("schtasks /query /tn CorralDaemon" in win, win)
        assertTrue("CorralDaemon" in win, win)
        assertFalse("systemctl" in win, "Windows hint must not name systemd: $win")
    }
}
