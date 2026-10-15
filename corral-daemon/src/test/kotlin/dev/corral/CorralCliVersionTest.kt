package dev.corral

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorralCliVersionTest {
    @Test fun versionFlagPrintsVersionAndExitsZeroWithoutADaemon() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        val process = ProcessBuilder(java, "-cp", classpath, "dev.corral.CorralCli", "--version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        assertEquals(0, exitCode, "corral --version must exit 0 without a daemon, output: $output")
        val version = output.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        assertTrue(Regex("""\d+\.\d+\.\d+.*""").matches(version),
            "expected a version-shaped line, got: $output")
    }
}
