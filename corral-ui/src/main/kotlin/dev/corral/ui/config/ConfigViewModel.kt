package dev.corral.ui.config

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
