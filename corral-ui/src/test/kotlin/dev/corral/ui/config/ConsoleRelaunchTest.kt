package dev.corral.ui.config

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsoleRelaunchTest {
    @Test fun jarVersionReadsTheStampInsideAJar() {
        val jar = Files.createTempFile("corral-ui", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(ZipEntry("corral-version"))
            out.write("0.1.3\n".toByteArray())
            out.closeEntry()
        }
        assertEquals("0.1.3", jarVersion(jar))
    }

    @Test fun jarVersionOfSomethingThatIsNotAZipIsNullNotAThrow() {
        val notAZip = Files.createTempFile("not-a-jar", ".jar")
        Files.writeString(notAZip, "definitely not a zip")
        assertNull(jarVersion(notAZip))
        assertNull(jarVersion(notAZip.resolveSibling("missing.jar")))
    }

    @Test fun jarWithoutTheStampIsNull() {
        val jar = Files.createTempFile("corral-ui", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(ZipEntry("other"))
            out.write(1)
            out.closeEntry()
        }
        assertNull(jarVersion(jar))
    }

    @Test fun relaunchesOnlyWhenTheDiskJarIsTheTargetAndThisProcessIsNot() {
        assertTrue(shouldRelaunch("0.1.2", "0.1.3", "0.1.3"))
        assertTrue(shouldRelaunch("0.1.2", "v0.1.3", "0.1.3"))
        assertFalse(shouldRelaunch("0.1.3", "0.1.3", "0.1.3"), "already on the target")
        assertFalse(shouldRelaunch("0.1.2", "0.1.3", null), "no jar on disk")
        assertFalse(shouldRelaunch("0.1.2", "0.1.3", "  "), "a blank stamp is no claim")
        assertFalse(shouldRelaunch("0.1.2", "0.1.3", "0.1.4"), "a different release on disk")
        assertFalse(shouldRelaunch("0.0.0-dev", "0.1.3", "0.1.3"), "a dev tree is never replaced")
        assertFalse(shouldRelaunch("0.1.2", "", "0.1.3"), "no target")
    }
}
