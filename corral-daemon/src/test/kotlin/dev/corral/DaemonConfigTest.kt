package dev.corral

import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonConfigTest {
    @Test fun precedenceFileBeatsEnvBeatsDefault() {
        val c = DaemonConfig(
            fileValues = mapOf("CORRAL_PORT" to "9000"),
            inherited = mapOf("CORRAL_PORT" to "8000", "CORRAL_SCREENSHOTS" to "open"))
        assertEquals("9000", c.get("CORRAL_PORT"))          // file wins
        assertEquals("open", c.get("CORRAL_SCREENSHOTS"))   // env beats default
        assertEquals("strict", DaemonConfig(fileValues = emptyMap(), inherited = emptyMap()).get("CORRAL_SCREENSHOTS"))  // default
    }

    @Test fun registryCoversTheDaemonKeysWithRestartFlags() {
        assertEquals(
            listOf("CORRAL_PORT", "CORRAL_SCREENSHOTS", "CORRAL_SCREENSHOT_OBSERVERS",
                   "CORRAL_ADB", "CORRAL_EMULATOR", "CORRAL_EMULATOR_LAUNCHER", "CORRAL_DB", "CORRAL_GRADLE_TASKS"),
            DaemonConfig.KEYS.map { it.key })
        assertTrue(DaemonConfig.KEYS.all { it.restartRequired })
    }

    @Test fun emulatorLauncherDefaultsToBlankAndAcceptsOnlyKnownValues() {
        assertEquals("", DaemonConfig(fileValues = emptyMap(), inherited = emptyMap()).get("CORRAL_EMULATOR_LAUNCHER"))
        assertEquals("systemd", DaemonConfig(fileValues = mapOf("CORRAL_EMULATOR_LAUNCHER" to "systemd"))
            .get("CORRAL_EMULATOR_LAUNCHER"))
        assertEquals("SystemD", DaemonConfig(fileValues = mapOf("CORRAL_EMULATOR_LAUNCHER" to "SystemD"))
            .get("CORRAL_EMULATOR_LAUNCHER"))   // usable case-insensitively, returned as written
        val c = DaemonConfig(fileValues = emptyMap())
        assertTrue(c.usable("CORRAL_EMULATOR_LAUNCHER", ""))
        assertTrue(c.usable("CORRAL_EMULATOR_LAUNCHER", "systemd"))
        assertTrue(c.usable("CORRAL_EMULATOR_LAUNCHER", "direct"))
        assertTrue(c.usable("CORRAL_EMULATOR_LAUNCHER", "SystemD"))
        assertFalse(c.usable("CORRAL_EMULATOR_LAUNCHER", "auto"))
    }

    @Test fun emulatorDefaultsToBlankAndTakesABareName() {
        assertEquals("", DaemonConfig(fileValues = emptyMap(), inherited = emptyMap()).get("CORRAL_EMULATOR"))
        val c = DaemonConfig(fileValues = mapOf("CORRAL_EMULATOR" to "my-emulator"))
        assertEquals("my-emulator", c.get("CORRAL_EMULATOR"))
        assertFalse(c.usable("CORRAL_EMULATOR", "/opt/missing/emulator"))
    }

    @Test fun envFileRoundTripsAndPreservesUnrelatedLines() {
        val dir = createTempDirectory("env")
        val p = dir.resolve("daemon.env")
        Files.writeString(p, "# managed by corral\nCORRAL_PORT=8798\nOTHER_TOOL=keep-me\n")
        EnvFile.write(p, mapOf("CORRAL_PORT" to "9001", "CORRAL_SCREENSHOTS" to "open"))
        val text = p.readText()
        assertTrue("# managed by corral" in text && "OTHER_TOOL=keep-me" in text)
        assertEquals("9001", EnvFile.read(p)["CORRAL_PORT"])
        assertEquals("open", EnvFile.read(p)["CORRAL_SCREENSHOTS"])
    }

    @Test fun envFileWriteIsAtomicAndCreatesParents() {
        val p = createTempDirectory("env").resolve("nested/daemon.env")
        EnvFile.write(p, mapOf("CORRAL_PORT" to "1"))
        assertEquals("1", EnvFile.read(p)["CORRAL_PORT"])
    }

    @Test fun readOfAbsentFileIsEmpty() =
        assertTrue(EnvFile.read(createTempDirectory("env").resolve("nope")).isEmpty())

    @Test fun readStripsSurroundingQuotesLikeSystemd() {
        val p = createTempDirectory("env").resolve("daemon.env")
        Files.writeString(p, "CORRAL_PORT=\"8798\"\nCORRAL_ADB='/opt/adb'\nCORRAL_DB=plain\n")
        val v = EnvFile.read(p)
        assertEquals("8798", v["CORRAL_PORT"])
        assertEquals("/opt/adb", v["CORRAL_ADB"])
        assertEquals("plain", v["CORRAL_DB"])
    }

    @Test fun getSkipsAnUnusableFileValueAndReportsTheEffectiveOne() {
        val c = DaemonConfig(
            fileValues = mapOf("CORRAL_PORT" to "22"),
            inherited = mapOf("CORRAL_PORT" to "9000"))
        assertEquals("9000", c.get("CORRAL_PORT"))                                  // unusable file skipped
        assertEquals("9000", c.settings().first { it.key == "CORRAL_PORT" }.value)  // GET reports the value in use
        assertFalse(c.usable("CORRAL_SCREENSHOTS", "weird"))
        assertTrue(c.usable("CORRAL_SCREENSHOTS", "open"))
        assertTrue(c.usable("CORRAL_SCREENSHOTS", "OPEN"))   // same case rule as the launcher key
        assertTrue(c.usable("CORRAL_SCREENSHOTS", " Open "))
    }

    @Test fun blankValuesFallThroughToEnvThenDefault() {
        val c = DaemonConfig(
            fileValues = mapOf("CORRAL_PORT" to "", "CORRAL_ADB" to ""),
            inherited = mapOf("CORRAL_PORT" to "9000", "CORRAL_ADB" to "  "))
        assertEquals("9000", c.get("CORRAL_PORT"))   // blank file -> inherited
        assertEquals("adb", c.get("CORRAL_ADB"))     // blank file + blank env -> default
        assertEquals("", c.get("CORRAL_SCREENSHOT_OBSERVERS"))  // blank default stays blank
    }

    @Test fun writeTreatsSpacedKeysAsTheSameKey() {
        val p = createTempDirectory("env").resolve("daemon.env")
        Files.writeString(p, "CORRAL_PORT = 8798\nNOT_A_PAIR\n")
        EnvFile.write(p, mapOf("CORRAL_PORT" to "9001"))
        val text = p.readText()
        assertEquals(1, text.lines().count {
            it.substringBefore('#').substringBefore('=').trim() == "CORRAL_PORT" })
        assertEquals("9001", EnvFile.read(p)["CORRAL_PORT"])
        assertTrue("NOT_A_PAIR" in text)
    }

    @Test fun blankObserversFileValueDisablesObservers() {
        val c = DaemonConfig(
            fileValues = mapOf("CORRAL_SCREENSHOT_OBSERVERS" to ""),
            inherited = mapOf("CORRAL_SCREENSHOT_OBSERVERS" to "corral-ui"))
        assertEquals("", c.get("CORRAL_SCREENSHOT_OBSERVERS"))          // file blank wins (observers off)
        assertEquals("", DaemonConfig(fileValues = mapOf("CORRAL_GRADLE_TASKS" to "")).get("CORRAL_GRADLE_TASKS"))
    }

    @Test fun writeFailsInsteadOfClobberingAnUnreadableTarget() {
        val p = createTempDirectory("env").resolve("daemon.env")
        Files.createDirectory(p)                 // present but not a readable file
        assertFailsWith<IOException> { EnvFile.write(p, mapOf("CORRAL_PORT" to "1")) }
    }
}
