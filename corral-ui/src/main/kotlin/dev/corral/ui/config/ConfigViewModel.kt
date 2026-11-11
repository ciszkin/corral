package dev.corral.ui.config

import dev.corral.ApplyResult
import dev.corral.UpdateInfo
import dev.corral.UpdateReason
import dev.corral.UpdateState
import dev.corral.ui.ConfigApiException
import dev.corral.ui.CorralApi
import dev.corral.ui.UiVersionSource
import dev.corral.ui.architecture.BaseMviViewModel
import dev.corral.ui.data.ConfigResponse
import dev.corral.ui.data.ConfigRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** ~5 minutes: long enough for a download + install, short enough not to poll forever. */
private const val UPDATE_POLL_ATTEMPTS = 200
private const val UPDATE_POLL_INTERVAL_MS = 1_500L

/** ~10 seconds: the daemon's post-restart cache is empty for a moment, not forever. */
private const val UPDATE_REFRESH_ATTEMPTS = 7

private const val UPDATE_BACKGROUND_MESSAGE =
    "the update is still running in the background — see ~/.corral/update-<tag>.log for progress or errors, then restart the console once it finishes"

class ConfigViewModel(
    private val apiFactory: () -> CorralApi?,
    vmScope: CoroutineScope,
    /** This process's own version, and the version of the jar the launcher would run next. */
    private val runningVersion: String = UiVersionSource.version,
    private val onDiskVersion: () -> String? = { jarVersion(consoleJarPath()) },
    /** Starts the new console and returns whether it started; injected so tests spawn nothing. */
    private val relaunchConsole: () -> Boolean = ::relaunchCurrentConsole,
    /**
     * The confirmed apply, injected so a test never spawns anything. The default starts the CLI's
     * detached apply; it is called synchronously from [ConfigIntent.ConfirmInstall], before any
     * coroutine, so closing the window cannot abort it.
     */
    private val startUpdate: (tag: String, repo: String?) -> ApplyResult = { tag, repo ->
        UpdateLaunch.start(tag, repo)
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
        var last: UpdateInfo? = null
        var failed = false
        repeat(UPDATE_REFRESH_ATTEMPTS) { attempt ->
            val info = try {
                api.update(force && attempt == 0)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
                null
            }
            if (info != null) {
                last = info
                failed = false
                // NOT_CHECKED is transient right after a restart; every other state is an answer.
                val transient = info.state == UpdateState.UNKNOWN && info.reason == UpdateReason.NOT_CHECKED
                if (!transient) {
                    reduceState { copy(update = info, updateChecking = false, updateCheckFailed = false, installed = false) }
                    return
                }
            }
            if (attempt < UPDATE_REFRESH_ATTEMPTS - 1) delay(UPDATE_POLL_INTERVAL_MS)
        }
        reduceState {
            copy(update = last ?: update, updateChecking = false, updateCheckFailed = failed, installed = false)
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
        // Synchronous and before any coroutine: the CLI owns the apply, so a window close has
        // nothing left to abort.
        val started = try {
            startUpdate(tag, repoFromReleaseUrl(info.releaseUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApplyResult.Failed("update failed: ${e.message ?: e::class.simpleName ?: "unknown error"}")
        }
        when (started) {
            is ApplyResult.Completed -> {}
            is ApplyResult.Refused -> {
                reduceState { copy(installing = false, installError = started.reason) }
                return
            }
            is ApplyResult.Failed -> {
                reduceState { copy(installing = false, installError = started.reason) }
                return
            }
        }
        val api = apiFactory() ?: run {
            reduceState { copy(installing = false, installError = UPDATE_BACKGROUND_MESSAGE) }
            return
        }
        vmScope.launch { awaitInstalled(api, tag.trim().removePrefix("v").removePrefix("V")) }
    }

    /** Polls the daemon through its restart; a throw is expected there, so it means "keep polling". */
    private suspend fun awaitInstalled(api: CorralApi, target: String) {
        repeat(UPDATE_POLL_ATTEMPTS) {
            delay(UPDATE_POLL_INTERVAL_MS)
            val info = try {
                api.update(force = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (info != null && info.currentVersion.trim() == target) {
                reduceState {
                    copy(
                        installing = false, installed = true, update = info,
                        updateChecking = false, updateCheckFailed = false,
                    )
                }
                maybeRelaunch(target)
                return
            }
        }
        reduceState { copy(installing = false, installError = UPDATE_BACKGROUND_MESSAGE) }
    }

    /**
     * The daemon now runs the target but this window still runs the old code. Replace it only when
     * the jar on disk is that same release; otherwise keep the window and let the mismatch hint say
     * so. One-shot: a second poll must not start a second console.
     */
    private fun maybeRelaunch(target: String) {
        if (state.value.relaunching) return
        if (!shouldRelaunch(runningVersion, target, onDiskVersion())) return
        if (relaunchConsole()) reduceState { copy(relaunching = true) }
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
