package dev.corral.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UiVersionSourceTest {
    @Test fun uiVersionReadsTheUiJarsOwnResource() {
        val raw = UiVersionSource::class.java.getResourceAsStream("/corral-version")
            ?.bufferedReader()?.use { it.readText() }
        assertNotNull(raw, "the UI must stamp its own corral-version resource")
        assertEquals(raw.trim(), UiVersionSource.version)
    }
}
