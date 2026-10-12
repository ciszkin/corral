package dev.corral

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val argv = commandArgv(listOf("adb", "-s", "dev1", "shell", "echo", "hi"), windows = false,
            resolve = { error("must not resolve on a non-Windows host") })
        assertEquals(listOf("adb", "-s", "dev1", "shell", "echo", "hi"), argv)
    }

    @Test fun bareNameResolvesToBatOnPath() {
        val dir = Files.createTempDirectory("corral-path")
        val bat = Files.createFile(dir.resolve("adb.bat"))
        val resolved = resolvePathProgram("adb",
            mapOf("PATH" to dir.toString(), "PATHEXT" to ".EXE;.BAT;.CMD"))
        assertEquals(bat.toString(), resolved)
    }

    @Test fun bareNameWithNoMatchIsNull() {
        val dir = Files.createTempDirectory("corral-path")
        assertEquals(null, resolvePathProgram("adb",
            mapOf("PATH" to dir.toString(), "PATHEXT" to ".EXE;.BAT;.CMD")))
    }

    // --- the lease is handed back however the wrapped command ends ---

    @Test fun leaseReleasedWhenChildFailsToStart() {
        val err = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(err))
        var released = false
        val rc = try {
            runLeased(listOf("/no/such/corral-program"),
                { ProcessBuilder("/no/such/corral-program") }) { released = true }
        } finally {
            System.setErr(original)
        }
        assertEquals(127, rc)
        assertTrue(released, "a start failure must still free the lease")
        val message = err.toString()
        assertTrue(message.contains("corral: failed to run /no/such/corral-program"), message)
        assertFalse(message.contains("daemon unreachable"), message)
    }

    @Test fun leaseReleasedWhenChildExitsNonZero() {
        var released = false
        val rc = runLeased(listOf("sh"), { ProcessBuilder("sh", "-c", "exit 7") }) { released = true }
        assertEquals(7, rc)
        assertTrue(released, "a non-zero exit must still free the lease")
    }
}
