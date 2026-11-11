package dev.corral.ui.config

import dev.corral.UpdateInfo
import dev.corral.ui.architecture.MviEffect
import dev.corral.ui.architecture.MviIntent
import dev.corral.ui.architecture.MviState
import dev.corral.ui.data.ConfigResponse
import dev.corral.ui.data.ConfigRules

data class ConfigState(
    val loaded: ConfigResponse? = null,
    val loadFailed: Boolean = false,
    val editing: ConfigRules? = null,
    val settings: Map<String, String> = emptyMap(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val restartRequired: Boolean = false,
    val clearBlocked: Boolean = false,
    // --- update surface: automatic check, explicit apply ---
    val update: UpdateInfo? = null,
    val updateChecking: Boolean = false,
    val updateCheckFailed: Boolean = false,
    val confirmInstall: Boolean = false,
    val installing: Boolean = false,
    val installed: Boolean = false,
    /** The window must exit because a console on the new version has been started in its place. */
    val relaunching: Boolean = false,
    /** The applier's own honest failure, shown verbatim. */
    val installError: String? = null,
) : MviState

private val RulesConcepts = listOf("readonly", "valueflags", "shell_skip", "init_floor", "-s")

internal fun String.isRulesMessage(): Boolean = RulesConcepts.any { contains(it) }


sealed interface ConfigIntent : MviIntent {
    data object Load : ConfigIntent
    data class EditRules(val rules: ConfigRules) : ConfigIntent
    data class EditSetting(val key: String, val value: String) : ConfigIntent
    data object Save : ConfigIntent
    data object Discard : ConfigIntent
    data object ResetToDefaults : ConfigIntent
    data object ResetRules : ConfigIntent
    /** A user-pressed "Check now": asks the daemon for a fresh check. */
    data object CheckUpdate : ConfigIntent
    data object AskInstall : ConfigIntent
    data object CancelInstall : ConfigIntent
    /** The deliberate, user-confirmed apply. Nothing downloads on any other intent. */
    data object ConfirmInstall : ConfigIntent
}

sealed interface ConfigEffect : MviEffect {
    data class Saved(val restartRequired: Boolean) : ConfigEffect
    data class SaveFailed(val errors: List<String>) : ConfigEffect
}
