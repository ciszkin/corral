package dev.corral.ui.config

import dev.corral.ui.data.ConfigResponse
import dev.corral.ui.data.ConfigRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSectionTest {
    private fun response(updateRepo: String) = ConfigResponse(
        settings = emptyList(),
        rules = ConfigRules(),
        rulesPath = "/home/u/.config/corral/rules",
        envPath = "/home/u/.config/corral/daemon.env",
        updateRepo = updateRepo,
    )

    @Test fun updateSourceRowShowsTheDaemonsValue() {
        assertEquals("acme/corral", updateRepoText(response("acme/corral"), "unknown"))
    }

    @Test fun anOldDaemonWithoutTheFieldShowsThePlaceholder() {
        assertEquals("unknown", updateRepoText(response(""), "unknown"))
    }

    @Test fun aMismatchedConsoleVersionExplainsItself() {
        val hint = versionMismatchHint("0.1.0", "0.1.1")
        assertNotNull(hint)
        assertTrue(hint.contains("0.1.0") && hint.contains("0.1.1"), hint)
        assertTrue(hint.contains("restart the console"), hint)
    }

    @Test fun equalOrDevVersionsHaveNothingToExplain() {
        assertNull(versionMismatchHint("0.1.1", "0.1.1"))
        assertNull(versionMismatchHint("", "0.1.1"))
        assertNull(versionMismatchHint("0.1.0", " "))
        assertNull(versionMismatchHint("0.0.0-dev", "0.1.1"))
        assertNull(versionMismatchHint("0.1.0", "0.0.0-dev"))
    }
}
