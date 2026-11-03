package dev.corral.ui.config

import dev.corral.ui.data.ConfigResponse
import dev.corral.ui.data.ConfigRules
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
