package dev.corral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VersionSourceTest {
    private fun resourceText(): String? =
        VersionSource::class.java.getResourceAsStream("/corral-version")
            ?.bufferedReader()?.use { it.readText() }

    @Test fun resourceIsOnTestRuntimeClasspath() {
        val raw = resourceText()
        assertNotNull(raw, "the corral-version resource must be on the test runtime classpath")
        assertTrue(raw.isNotBlank(), "the corral-version resource must not be blank")
        assertEquals(raw.trim(), raw, "the corral-version resource must be written trimmed")
    }

    @Test fun versionReadsTheStampedResource() {
        assertEquals(resourceText()?.trim(), VersionSource.version)
    }
}
