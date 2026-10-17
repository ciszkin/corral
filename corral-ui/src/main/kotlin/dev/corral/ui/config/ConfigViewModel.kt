package dev.corral.ui.config

import dev.corral.ApplyResult
import dev.corral.CONSOLE_RELAUNCH_UNKNOWN
import dev.corral.UpdateApplier
import dev.corral.UpdateState
import dev.corral.ui.ConfigApiException
import dev.corral.ui.CorralApi
import dev.corral.ui.architecture.BaseMviViewModel
import dev.corral.ui.data.ConfigResponse
import dev.corral.ui.data.ConfigRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ConfigViewModel(
    private val apiFactory: () -> CorralApi?,
    vmScope: CoroutineScope,
    /**
     * The confirmed apply, injected so a test never downloads or spawns anything. The default is the
     * real applier, bound to this console's own relaunch. It runs only on [ConfigIntent.ConfirmInstall].
     */
    private val applyUpdate: (String) -> ApplyResult = { tag ->
        UpdateApplier.forConsole(tag)?.run() ?: ApplyResult.Failed(CONSOLE_RELAUNCH_UNKNOWN)
    },
) : BaseMviViewModel<ConfigIntent, ConfigState, ConfigEffect>(vmScope) {

    override fun initialState() = ConfigState()

    override fun onIntent(intent: ConfigIntent) {
        when (intent) {
            ConfigIntent.Load -> load()
            is ConfigIntent.EditRules -> reduceState {
                copy(editing = intent.rules, dirty = true, errors = emptyList(), clearBlocked = false)
            }
            is ConfigIntent.EditSetting -> reduceState {
                copy(
                    settings = settings + (intent.key to intent.value),
                    dirty = true,
                    errors = emptyList(),
                    clearBlocked = false,
                )
            }
            ConfigIntent.Save -> save()
            ConfigIntent.Discard -> discard()
            ConfigIntent.ResetToDefaults -> resetToDefaults()
            ConfigIntent.ResetRules -> resetRules()
            ConfigIntent.CheckUpdate -> checkUpdate(force = true)
            ConfigIntent.AskInstall -> reduceState { copy(confirmInstall = true, installError = null) }
            ConfigIntent.CancelInstall -> reduceState { copy(confirmInstall = false) }
            ConfigIntent.ConfirmInstall -> installUpdate()
        }
    }

    private fun load() {
        if (state.value.dirty) return
        vmScope.launch {
            val api = apiFactory() ?: return@launch
            try {
                val loaded = api.config()
                reduceState {
                    if (dirty) {
                        this
                    } else {
                        copy(
                            loaded = loaded,
                            loadFailed = false,
                            editing = loaded.rules,
                            settings = valuesOf(loaded),
                            dirty = false,
                            saving = false,
                            errors = emptyList(),
                            warnings = emptyList(),
                            restartRequired = false,
                            clearBlocked = false,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reduceState { copy(loadFailed = true) }
            }
            // the check is automatic: populate the update section from the daemon's cached result
            refreshUpdate(api, force = false)
        }
    }

    private fun checkUpdate(force: Boolean) {
        val api = apiFactory()
        if (api == null) {
            reduceState { copy(updateChecking = false, updateCheckFailed = true) }
            return
        }
        reduceState { copy(updateChecking = true, updateCheckFailed = false) }
        vmScope.launch { refreshUpdate(api, force) }
    }

    private suspend fun refreshUpdate(api: CorralApi, force: Boolean) {
        try {
            val info = api.update(force)
            // A fresh result invalidates the "installed" caption: it described the previous state.
            reduceState { copy(update = info, updateChecking = false, updateCheckFailed = false, installed = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reduceState { copy(updateChecking = false, updateCheckFailed = true) }
        }
    }

    private fun installUpdate() {
        if (state.value.installing) return
        // Re-check the STATE at confirm time, not just the tag: it can flip under an open dialog.
        val info = state.value.update
        if (info?.state != UpdateState.UPDATE_AVAILABLE) {
            reduceState { copy(confirmInstall = false, installError = "no release to install") }
            return
        }
        val tag = info.latestVersion ?: run {
            reduceState { copy(confirmInstall = false, installError = "no release to install") }
            return
        }
        reduceState { copy(confirmInstall = false, installing = true, installed = false, installError = null) }
        vmScope.launch {
            // The applier is built for ApplyResult, but a throw must not escape into the scope and
            // leave installing latched with no error: turn any exception into an honest Failed.
            val result = try {
                applyUpdate(tag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ApplyResult.Failed("update failed: ${e.message ?: e::class.simpleName ?: "unknown error"}")
            }
            reduceState {
                when (result) {
                    is ApplyResult.Completed -> copy(installing = false, installed = true, installError = null)
                    is ApplyResult.Refused -> copy(installing = false, installError = result.reason)
                    is ApplyResult.Failed -> copy(installing = false, installError = result.reason)
                }
            }
        }
    }

    private fun discard() {
        val loaded = state.value.loaded ?: return
        reduceState {
            copy(
                editing = loaded.rules,
                settings = valuesOf(loaded),
                dirty = false,
                saving = false,
                errors = emptyList(),
                warnings = emptyList(),
                restartRequired = false,
                clearBlocked = false,
            )
        }
    }

    private fun resetToDefaults() {
        val loaded = state.value.loaded ?: return
        val defaults = loaded.settings.associate { it.key to it.default }
        reduceState {
            copy(
                settings = defaults,
                dirty = defaults != valuesOf(loaded) || editing != loaded.rules,
                errors = emptyList(),
                clearBlocked = false,
            )
        }
    }

    private fun resetRules() {
        val loaded = state.value.loaded ?: return
        reduceState {
            val baseline = valuesOf(loaded)
            copy(
                editing = loaded.rules,
                dirty = editing != loaded.rules || settings != baseline,
                clearBlocked = false,
            )
        }
    }

    private fun save() {
        val snapshot = state.value
        val loaded = snapshot.loaded
        if (loaded == null) {
            load()
            return
        }
        val api = apiFactory()
        if (api == null) {
            sendEffect(ConfigEffect.SaveFailed(emptyList()))
            return
        }
        val baseline = valuesOf(loaded)
        val settingsDiff = snapshot.settings.filter { (k, v) -> v != baseline[k] }
        val rulesDiff = snapshot.editing?.takeIf { it != loaded.rules }
        if (rulesDiff != null && clearsAList(loaded.rules, rulesDiff)) {
            reduceState { copy(clearBlocked = true, errors = emptyList()) }
            return
        }
        if (settingsDiff.isEmpty() && rulesDiff == null) {
            reduceState { copy(dirty = false, errors = emptyList(), clearBlocked = false) }
            sendEffect(ConfigEffect.Saved(false))
            return
        }
        reduceState { copy(saving = true, errors = emptyList(), warnings = emptyList(), clearBlocked = false) }
        val applied = rulesDiff ?: loaded.rules
        val snapshotEditing = snapshot.editing
        vmScope.launch {
            try {
                val response = api.putConfig(settingsDiff, rulesDiff).getOrThrow()
                reduceState {
                    val newLoaded = loaded.copy(
                        settings = loaded.settings.map { s ->
                            settingsDiff[s.key]?.let { s.copy(value = it) } ?: s
                        },
                        rules = applied,
                    )
                    val nextEditing = if (editing == snapshotEditing) newLoaded.rules else editing
                    copy(
                        loaded = newLoaded,
                        editing = nextEditing,
                        saving = false,
                        dirty = settings != valuesOf(newLoaded) || nextEditing != newLoaded.rules,
                        errors = emptyList(),
                        warnings = response.warnings,
                        restartRequired = response.restartRequired,
                        clearBlocked = false,
                    )
                }
                sendEffect(ConfigEffect.Saved(response.restartRequired))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errors = (e as? ConfigApiException)?.errors ?: emptyList()
                reduceState { copy(saving = false, errors = errors, dirty = true) }
                sendEffect(ConfigEffect.SaveFailed(errors))
            }
        }
    }

    private fun clearsAList(loaded: ConfigRules, edited: ConfigRules): Boolean =
        (loaded.readonly.isNotEmpty() && edited.readonly.isEmpty()) ||
            (loaded.valueflags.isNotEmpty() && edited.valueflags.isEmpty())

    private fun valuesOf(loaded: ConfigResponse): Map<String, String> =
        loaded.settings.associate { it.key to it.value }
}
