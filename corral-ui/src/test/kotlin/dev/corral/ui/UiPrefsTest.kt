package dev.corral.ui

import dev.corral.ui.data.Prefs
import dev.corral.ui.data.UiPrefs
import dev.corral.ui.theme.ThemeMode
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiPrefsTest {
    @Test fun roundTripsEveryField() {
        val prefs = UiPrefs(createTempDirectory("prefs").resolve("ui.conf"))
        val saved = Prefs(
            url = "http://127.0.0.1:9123",
            owner = "claude:abc",
            scrcpyPath = "/opt/scrcpy",
            themeMode = ThemeMode.LIGHT,
            windowX = 120,
            windowY = 48,
            windowW = 1440,
            windowH = 900,
        )
        prefs.save(saved)
        assertEquals(saved, prefs.load())
    }

    @Test fun absentFileYieldsDefaults() {
        val path = createTempDirectory("prefs").resolve("nested").resolve("ui.conf")
        assertEquals(Prefs(), UiPrefs(path).load())
    }

    @Test fun malformedFileYieldsDefaults() {
        val dir = createTempDirectory("prefs")
        val path = dir.resolve("ui.conf")
        Files.writeString(path, "{ this is not json")
        assertEquals(Prefs(), UiPrefs(path).load())
        Files.writeString(path, """{"windowW": "wide"}""")
        assertEquals(Prefs(), UiPrefs(path).load())
    }

    @Test fun legacyLanguageKeyLoadsWithoutLosingOtherPrefs() {
        val dir = createTempDirectory("prefs")
        val path = dir.resolve("ui.conf")
        Files.writeString(path, """
            {
              "url": "http://127.0.0.1:9123",
              "owner": "claude:abc",
              "scrcpyPath": "/opt/scrcpy",
              "themeMode": "LIGHT",
              "language": "BELARUSIAN",
              "windowW": 1440
            }
        """.trimIndent())
        val loaded = UiPrefs(path).load()
        assertEquals("http://127.0.0.1:9123", loaded.url)
        assertEquals("claude:abc", loaded.owner)
        assertEquals("/opt/scrcpy", loaded.scrcpyPath)
        assertEquals(ThemeMode.LIGHT, loaded.themeMode)
        assertEquals(1440, loaded.windowW)
        assertTrue(loaded != Prefs(), "a legacy language key must not force the defaults")
    }

    @Test fun saveCreatesParentsAndLeavesNoTempFile() {
        val dir = createTempDirectory("prefs")
        val path = dir.resolve("nested").resolve("ui.conf")
        UiPrefs(path).save(Prefs(url = "http://example:1"))
        assertTrue(Files.exists(path))
        assertTrue(dir.resolve("nested").listDirectoryEntries().none { it.fileName.toString().contains(".tmp") })
    }
}
