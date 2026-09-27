package dev.corral.ui

import dev.corral.ui.resources.JetBrainsMono_Regular
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.app_name
import dev.corral.ui.resources.banner_screenshots_denied
import dev.corral.ui.resources.queue_waited
import dev.corral.ui.resources.rail_devices
import dev.corral.ui.theme.Language
import dev.corral.ui.theme.locale
import java.nio.file.Path
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString

class ComposeResourcesTest {
    @Test fun resourceAccessorsAndFontAreGenerated() {
        assertNotNull(Res.string.app_name)
        assertNotNull(Res.string.queue_waited)
        assertNotNull(Res.font.JetBrainsMono_Regular)
    }

    @Test fun screenshotsDeniedStringHasNoLiteralEscape() = runBlocking {
        val text = getString(Res.string.banner_screenshots_denied)
        assertFalse(text.contains('\\'), "apostrophe must not be backslash-escaped: $text")
        assertTrue(text.contains("CORRAL_SCREENSHOT_OBSERVERS"), text)
    }

    @Test fun resourceResolutionHonorsTheSelectedLocale() = runBlocking {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Language.BELARUSIAN.locale())
            assertEquals("Прылады", getString(Res.string.rail_devices))
            Locale.setDefault(Language.ENGLISH.locale())
            assertEquals("Devices", getString(Res.string.rail_devices))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun belarusianHasEveryEnglishKey() {
        assertEquals(stringKeys(Path.of("src/main/composeResources/values/strings.xml")),
            stringKeys(Path.of("src/main/composeResources/values-be/strings.xml")))
    }

    private fun stringKeys(path: Path): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile())
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").nodeValue }.toSet()
    }
}
