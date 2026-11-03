package dev.corral.ui

import dev.corral.ui.resources.JetBrainsMono_Regular
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.app_name
import dev.corral.ui.resources.queue_waited
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

class ComposeResourcesTest {
    private val enStrings = Path.of("src/main/composeResources/values/strings.xml")
    private val beStrings = Path.of("src/main/composeResources/values-be/strings.xml")

    @Test fun resourceAccessorsAndFontAreGenerated() {
        assertNotNull(Res.string.app_name)
        assertNotNull(Res.string.queue_waited)
        assertNotNull(Res.font.JetBrainsMono_Regular)
    }

    // Compose's getString() resolves through an AWT/Skiko environment (needs a display),
    // so these assert the resource XML directly — the suite stays headless/offline.
    @Test fun screenshotsDeniedStringHasNoLiteralEscape() {
        val text = stringValue(enStrings, "banner_screenshots_denied")
        assertFalse(text.contains('\\'), "apostrophe must not be backslash-escaped: $text")
        assertTrue(text.contains("CORRAL_SCREENSHOT_OBSERVERS"), text)
    }

    @Test fun belarusianRailLabelIsTranslated() {
        assertEquals("Devices", stringValue(enStrings, "rail_devices"))
        assertEquals("Прылады", stringValue(beStrings, "rail_devices"))
    }

    @Test fun belarusianHasEveryEnglishKey() {
        assertEquals(stringKeys(enStrings), stringKeys(beStrings))
    }

    @Test fun versionStringsExistInBothLanguages() {
        listOf("settings_version", "settings_version_unknown").forEach { name ->
            assertTrue(stringValue(enStrings, name).isNotBlank(), "en missing or blank: $name")
            assertTrue(stringValue(beStrings, name).isNotBlank(), "be missing or blank: $name")
        }
    }

    @Test fun updateStringsExistInBothLanguages() {
        val names = listOf(
            "update_section", "update_source", "update_source_desc",
            "update_current", "update_latest", "update_check_now", "update_install",
            "update_state_current", "update_state_available", "update_state_disabled",
            "update_state_not_checked", "update_state_unreachable", "update_state_http",
            "update_state_unparseable", "update_state_cause_unknown", "update_state_unknown",
            "update_check_failed", "update_installing", "update_installed", "update_install_failed",
            "update_confirm_title", "update_confirm_body", "update_pill",
        )
        names.forEach { name ->
            assertTrue(stringValue(enStrings, name).isNotBlank(), "en missing or blank: $name")
            assertTrue(stringValue(beStrings, name).isNotBlank(), "be missing or blank: $name")
        }
    }

    @Test fun pluralQuantitiesMatchTheLanguage() {
        val en = pluralQuantities(enStrings)
        val be = pluralQuantities(beStrings)
        assertTrue(en.isNotEmpty(), "expected plurals in the English table")
        assertEquals(en.keys, be.keys, "plural names must match across languages")
        en.forEach { (name, quantities) -> assertEquals(setOf("one", "other"), quantities, "en plural $name") }
        be.forEach { (name, quantities) ->
            assertEquals(setOf("one", "few", "many", "other"), quantities, "be plural $name")
        }
    }

    private fun stringKeys(path: Path): Set<String> =
        elements(path, "string").map { it.attributes.getNamedItem("name").nodeValue }.toSet()

    private fun stringValue(path: Path, name: String): String =
        elements(path, "string").first { it.attributes.getNamedItem("name").nodeValue == name }.textContent

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
