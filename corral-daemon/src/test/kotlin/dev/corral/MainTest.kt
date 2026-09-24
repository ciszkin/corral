package dev.corral

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainTest {
    @Test fun parsePortAcceptsTheBindableRange() {
        assertEquals(8798, parsePort("8798"))
        assertEquals(1024, parsePort("1024"))
        assertEquals(65535, parsePort("65535"))
    }

    @Test fun parsePortRejectsOutOfRangeAndGarbage() {
        assertNull(parsePort("0")); assertNull(parsePort("1")); assertNull(parsePort("22"))
        assertNull(parsePort("1023")); assertNull(parsePort("65536"))
        assertNull(parsePort("")); assertNull(parsePort("not-a-number"))
    }

    @Test fun resolvePortFallsBackToEnvThenDefault() {
        fun cfg(file: String, inherited: String) = DaemonConfig(
            fileValues = mapOf("CORRAL_PORT" to file),
            inherited = mapOf("CORRAL_PORT" to inherited))
        assertEquals(9000, resolvePort(cfg("abc", "9000")))   // env wins over the bad file value
        assertEquals(9000, resolvePort(cfg("22", "9000")))    // a privileged file port is skipped too
        assertEquals(8798, resolvePort(cfg("abc", "0")))      // env also bad -> built-in default
        assertEquals(9001, resolvePort(cfg("9001", "9000")))  // good file value is kept
    }

    @Test fun resolveDbPathFallsBackWhenTheFileValueIsUnusable() {
        val dir = createTempDirectory("db")
        val f = Files.createFile(dir.resolve("f"))                 // nearest existing ancestor is a file
        val good = dir.resolve("nested/corral.db").toString()      // nearest existing ancestor is a writable dir
        val cfg = DaemonConfig(
            fileValues = mapOf("CORRAL_DB" to "$f/child/corral.db"),
            inherited = mapOf("CORRAL_DB" to good))
        assertEquals(Path.of(good), resolveDbPath(cfg))

        val bothBad = DaemonConfig(
            fileValues = mapOf("CORRAL_DB" to "$f/child/corral.db"),
            inherited = mapOf("CORRAL_DB" to "relative/db"))
        assertEquals(Path.of(System.getProperty("user.home"), ".corral", "corral.db"), resolveDbPath(bothBad))
    }

    @Test fun resolveAdbFallsBackWhenPathShapedButNotExecutable() {
        val bad = DaemonConfig(
            fileValues = mapOf("CORRAL_ADB" to "../x"),
            inherited = mapOf("CORRAL_ADB" to "adb"))
        assertEquals("adb", resolveAdb(bad))

        val exe = Files.createTempFile("adb", "").also { it.toFile().setExecutable(true) }
        assertEquals(exe.toAbsolutePath().toString(),
            resolveAdb(DaemonConfig(fileValues = mapOf("CORRAL_ADB" to exe.toAbsolutePath().toString()))))

        assertEquals("adb", resolveAdb(DaemonConfig(fileValues = mapOf("CORRAL_ADB" to "adb"))))
    }

    @Test fun resolveAdbKeepsAnEnvProvidedPathSoDoctorCanSurfaceIt() {
        val envOnly = DaemonConfig(fileValues = emptyMap(), inherited = mapOf("CORRAL_ADB" to "/gone/adb.real"))
        assertEquals("/gone/adb.real", resolveAdb(envOnly))   // bad value came from env, not the file
    }

    @Test fun dbPathUsableRejectsParentlessRelativeAndDirectoryPaths() {
        assertFalse(dbPathUsable("/"))                     // parentless path is a directory, not a DB
        assertFalse(dbPathUsable("relative/x"))            // not absolute
        val dir = createTempDirectory("db")
        assertFalse(dbPathUsable(dir.toString()))          // existing directory, not a DB file
        assertTrue(dbPathUsable(dir.resolve("nested/corral.db").toString()))
    }
}
