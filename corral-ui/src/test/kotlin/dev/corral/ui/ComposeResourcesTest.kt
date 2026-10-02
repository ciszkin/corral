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
import org.w3c.dom.Element

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

    @Test fun pluralQuantitiesMatchTheLanguage() {
        val en = pluralQuantities(Path.of("src/main/composeResources/values/strings.xml"))
        val be = pluralQuantities(Path.of("src/main/composeResources/values-be/strings.xml"))
        assertTrue(en.isNotEmpty(), "expected plurals in the English table")
        assertEquals(en.keys, be.keys, "plural names must match across languages")
        en.forEach { (name, quantities) -> assertEquals(setOf("one", "other"), quantities, "en plural $name") }
        be.forEach { (name, quantities) ->
            assertEquals(setOf("one", "few", "many", "other"), quantities, "be plural $name")
        }
    }

    private fun stringKeys(path: Path): Set<String> =
        elements(path, "string").map { it.attributes.getNamedItem("name").nodeValue }.toSet()

    private fun pluralQuantities(path: Path): Map<String, Set<String>> =
        elements(path, "plurals").associate { node ->
            val name = node.attributes.getNamedItem("name").nodeValue
            val items = node.getElementsByTagName("item")
            name to (0 until items.length)
                .map { items.item(it).attributes.getNamedItem("quantity").nodeValue }.toSet()
        }

    private fun elements(path: Path, tag: String): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile())
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
}
