package dev.corral

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L1 hermeticity: the root build.gradle.kts redirects every test JVM's `user.home` to that module's
 * `build/test-home`, so no test can read or write the developer's real home. Without that
 * redirection this test fails — `user.home` is the real home and `~/.corral` lands outside `build/`.
 */
class TestHomeTest {
    @Test
    fun `user home points into the build directory, never the developer real home`() {
        val projectDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val buildDir = projectDir.resolve("build").normalize()
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()

        assertEquals(buildDir.resolve("test-home"), home, "user.home was not redirected to build/test-home")
        assertTrue(Files.isDirectory(home), "the redirected test home does not exist: $home")

        val corral = home.resolve(".corral")
        Files.createDirectories(corral)
        assertTrue(corral.startsWith(buildDir), "~/.corral escaped the build directory: $corral")
    }
}
