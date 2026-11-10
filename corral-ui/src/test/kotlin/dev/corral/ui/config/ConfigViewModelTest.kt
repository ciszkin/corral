package dev.corral.ui.config

import dev.corral.Device
import dev.corral.QueueEntry
import dev.corral.StatusResponse
import dev.corral.ApplyResult
import dev.corral.UpdateInfo
import dev.corral.UpdateReason
import dev.corral.UpdateState
import dev.corral.ui.AvdStatus
import dev.corral.ui.Capabilities
import dev.corral.ui.ConfigApiException
import dev.corral.ui.CorralApi
import dev.corral.ui.data.ConfigResponse
import dev.corral.ui.data.ConfigRules
import dev.corral.ui.data.ConfigSetting
import dev.corral.ui.data.DoctorResponse
import dev.corral.ui.data.PutConfigResponse
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelTest {

    private class FakeApi(
        var configResponse: ConfigResponse = response(),
        var configError: Exception? = null,
        var putResult: Result<PutConfigResponse> = Result.success(PutConfigResponse(restartRequired = false)),
        var updateResponse: UpdateInfo = UpdateInfo(currentVersion = "1.0.0", state = UpdateState.DISABLED),
        var updateError: Exception? = null,
    ) : CorralApi {
        val putCalls = mutableListOf<Pair<Map<String, String>, ConfigRules?>>()
        val updateForces = mutableListOf<Boolean>()
        val updateQueue = ArrayDeque<UpdateInfo>()
        var putGate: CompletableDeferred<Unit>? = null

        override suspend fun config(): ConfigResponse = configError?.let { throw it } ?: configResponse
        override suspend fun update(force: Boolean): UpdateInfo {
            updateForces += force
            updateQueue.removeFirstOrNull()?.let { return it }
            return updateError?.let { throw it } ?: updateResponse
        }
        override suspend fun putConfig(settings: Map<String, String>, rules: ConfigRules?): Result<PutConfigResponse> {
            putCalls += settings to rules
            putGate?.await()
            return putResult
        }

        override suspend fun status(): StatusResponse = error("unused")
        override suspend fun devices(): List<Device> = error("unused")
        override suspend fun queue(): List<QueueEntry> = error("unused")
        override suspend fun screenshot(serial: String): ByteArray = error("unused")
        override suspend fun forceFree(serial: String) = error("unused")
        override suspend fun doctor(): DoctorResponse = error("unused")
        override suspend fun emulators(): List<AvdStatus> = error("unused")
        override suspend fun emulatorStart(avd: String) = error("unused")
        override suspend fun emulatorStop(avd: String, force: Boolean) = error("unused")
        override fun events(): Flow<String> = flow { error("unused") }
        override fun capabilities(): Capabilities = Capabilities(true)
    }

    private fun TestScope.newVm(api: FakeApi) = ConfigViewModel({ api }, backgroundScope)

    @Test fun loadPopulatesAndIsNotDirty() = runTest {
        val api = FakeApi()
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        val loaded = vm.state.value.loaded
        assertNotNull(loaded)
        assertEquals(loaded.rules, vm.state.value.editing)
        assertEquals(mapOf("CORRAL_PORT" to "8798"), vm.state.value.settings)
        assertFalse(vm.state.value.dirty)
    }

    @Test fun failedLoadMarksLoadFailedAndLeavesLoadedNull() = runTest {
        val api = FakeApi(configError = RuntimeException("unreachable"))
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        assertTrue(vm.state.value.loadFailed)
        assertNull(vm.state.value.loaded)
    }

    @Test fun editRulesSetsDirty() = runTest {
        val vm = newVm(FakeApi())
        vm.onIntent(ConfigIntent.Load); runCurrent()
        vm.onIntent(ConfigIntent.EditRules(ConfigRules(readonly = listOf("devices"), valueflags = listOf("-P"))))
        assertTrue(vm.state.value.dirty)
        assertEquals(listOf("-P"), vm.state.value.editing?.valueflags)
    }

    @Test fun editSettingSetsDirty() = runTest {
        val vm = newVm(FakeApi())
        vm.onIntent(ConfigIntent.Load); runCurrent()
        vm.onIntent(ConfigIntent.EditSetting("CORRAL_PORT", "9000"))
        assertTrue(vm.state.value.dirty)
        assertEquals("9000", vm.state.value.settings["CORRAL_PORT"])
    }

    @Test fun savePutsTheDiffAndSurfacesWarningsAndRestart() = runTest {
        val api = FakeApi(putResult = Result.success(PutConfigResponse(restartRequired = true, warnings = listOf("CORRAL_ADB 'adb' is not on PATH"))))
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        val editedRules = vm.state.value.loaded!!.rules.copy(valueflags = listOf("-L"))
        vm.onIntent(ConfigIntent.EditSetting("CORRAL_PORT", "9000"))
        vm.onIntent(ConfigIntent.EditRules(editedRules))
        vm.onIntent(ConfigIntent.Save); runCurrent()

        val (settings, rules) = api.putCalls.single()
        assertEquals(mapOf("CORRAL_PORT" to "9000"), settings)
        assertEquals(editedRules, rules)
        assertEquals(listOf("CORRAL_ADB 'adb' is not on PATH"), vm.state.value.warnings)
        assertTrue(vm.state.value.restartRequired)
        assertFalse(vm.state.value.dirty)
        assertEquals(ConfigEffect.Saved(true), vm.effect.first())
    }

    @Test fun badRequestLandsInErrorsAndKeepsEditing() = runTest {
        val errors = listOf("CORRAL_PORT must be 1024–65535")
        val api = FakeApi(putResult = Result.failure(ConfigApiException(errors)))
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        vm.onIntent(ConfigIntent.EditSetting("CORRAL_PORT", "99200"))
        vm.onIntent(ConfigIntent.Save); runCurrent()

        assertEquals(errors, vm.state.value.errors)
        assertEquals("99200", vm.state.value.settings["CORRAL_PORT"])
        assertTrue(vm.state.value.dirty)
        assertFalse(vm.state.value.saving)
        assertEquals(ConfigEffect.SaveFailed(errors), vm.effect.first())
    }

    @Test fun discardReturnsToLoaded() = runTest {
        val vm = newVm(FakeApi())
        vm.onIntent(ConfigIntent.Load); runCurrent()
        vm.onIntent(ConfigIntent.EditSetting("CORRAL_PORT", "9000"))
        vm.onIntent(ConfigIntent.Discard)
        val loaded = vm.state.value.loaded!!
        assertEquals(loaded.rules, vm.state.value.editing)
        assertEquals(mapOf("CORRAL_PORT" to "8798"), vm.state.value.settings)
        assertFalse(vm.state.value.dirty)
    }

    @Test fun resetToDefaultsSetsSettingsMapWithoutSaving() = runTest {
        val api = FakeApi(
            configResponse = response(
                settings = listOf(
                    ConfigSetting("CORRAL_PORT", "9000", "8798", false),
                    ConfigSetting("CORRAL_DB", "/tmp/x.db", "~/.corral/corral.db", false),
                ),
            ),
        )
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        vm.onIntent(ConfigIntent.ResetToDefaults)
        assertEquals("8798", vm.state.value.settings["CORRAL_PORT"])
        assertEquals("~/.corral/corral.db", vm.state.value.settings["CORRAL_DB"])
        assertTrue(vm.state.value.dirty)
        assertTrue(api.putCalls.isEmpty())
    }

    @Test fun resetToDefaultsPreservesPendingRules() = runTest {
        val vm = newVm(FakeApi())
        vm.onIntent(ConfigIntent.Load); runCurrent()
        val edited = vm.state.value.loaded!!.rules.copy(valueflags = listOf("-L"))
        vm.onIntent(ConfigIntent.EditRules(edited))
        vm.onIntent(ConfigIntent.ResetToDefaults)
        assertEquals(edited, vm.state.value.editing)
        assertTrue(vm.state.value.dirty)
    }

    @Test fun loadPreservesUnsavedEditsWhenDirty() = runTest {
        val api = FakeApi()
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        val original = vm.state.value.loaded
        val editedRules = original!!.rules.copy(valueflags = listOf("-L"))
        vm.onIntent(ConfigIntent.EditSetting("CORRAL_PORT", "9000"))
        vm.onIntent(ConfigIntent.EditRules(editedRules))

        api.configResponse = response(
            settings = listOf(ConfigSetting("CORRAL_PORT", "1234", "8798", false)),
            rules = ConfigRules(readonly = listOf("devices")),
        )
        vm.onIntent(ConfigIntent.Load); runCurrent()

        assertEquals("9000", vm.state.value.settings["CORRAL_PORT"])
        assertEquals(editedRules, vm.state.value.editing)
        assertTrue(vm.state.value.dirty)
        assertEquals(original, vm.state.value.loaded)
    }

    @Test fun reloadAfterCleanLoadRefreshesTheForm() = runTest {
        val api = FakeApi()
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        api.configResponse = response(
            settings = listOf(ConfigSetting("CORRAL_PORT", "1234", "8798", false)),
            rules = ConfigRules(readonly = listOf("devices")),
        )
        vm.onIntent(ConfigIntent.Load); runCurrent()
        assertEquals("1234", vm.state.value.settings["CORRAL_PORT"])
        assertFalse(vm.state.value.dirty)
    }

    @Test fun genericSaveFailureReportsAnEmptyErrorList() = runTest {
        val api = FakeApi(putResult = Result.failure(RuntimeException("connection reset")))
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        vm.onIntent(ConfigIntent.EditSetting("CORRAL_PORT", "9000"))
        vm.onIntent(ConfigIntent.Save); runCurrent()

        assertTrue(vm.state.value.errors.isEmpty())
        assertTrue(vm.state.value.dirty)
        assertEquals("9000", vm.state.value.settings["CORRAL_PORT"])
        assertEquals(ConfigEffect.SaveFailed(emptyList()), vm.effect.first())
    }

    @Test fun emptyReadonlySaveIsRefusedAndEditsKept() = runTest {
        val api = FakeApi()
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        assertTrue(vm.state.value.loaded!!.rules.readonly.isNotEmpty())
        vm.onIntent(ConfigIntent.EditRules(vm.state.value.loaded!!.rules.copy(readonly = emptyList())))
        vm.onIntent(ConfigIntent.Save); runCurrent()

        assertTrue(api.putCalls.isEmpty())
        assertTrue(vm.state.value.clearBlocked)
        assertTrue(vm.state.value.editing!!.readonly.isEmpty())
        assertTrue(vm.state.value.dirty)
    }

    @Test fun emptyValueflagsSaveIsRefused() = runTest {
        val api = FakeApi()
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        assertTrue(vm.state.value.loaded!!.rules.valueflags.isNotEmpty())
        vm.onIntent(ConfigIntent.EditRules(vm.state.value.loaded!!.rules.copy(valueflags = emptyList())))
        vm.onIntent(ConfigIntent.Save); runCurrent()

        assertTrue(api.putCalls.isEmpty())
        assertTrue(vm.state.value.clearBlocked)
        assertTrue(vm.state.value.dirty)
    }

    @Test fun inFlightEditSurvivesSaveSuccess() = runTest {
        val api = FakeApi()
        val gate = CompletableDeferred<Unit>()
        api.putGate = gate
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        vm.onIntent(ConfigIntent.EditSetting("CORRAL_PORT", "9000"))
        vm.onIntent(ConfigIntent.Save); runCurrent()
        assertEquals(1, api.putCalls.size)

        vm.onIntent(ConfigIntent.EditRules(ConfigRules(valueflags = listOf("-L"))))
        vm.onIntent(ConfigIntent.EditSetting("CORRAL_DB", "/tmp/x.db"))
        gate.complete(Unit); runCurrent()

        assertEquals(listOf("-L"), vm.state.value.editing?.valueflags)
        assertEquals("/tmp/x.db", vm.state.value.settings["CORRAL_DB"])
        assertTrue(vm.state.value.dirty)
        assertEquals(1, api.putCalls.size)
    }

    @Test fun resetRulesRestoresEditingFromLoaded() = runTest {
        val vm = newVm(FakeApi())
        vm.onIntent(ConfigIntent.Load); runCurrent()
        vm.onIntent(ConfigIntent.EditRules(ConfigRules(readonly = listOf("devices", "version"))))
        vm.onIntent(ConfigIntent.ResetRules)
        assertEquals(vm.state.value.loaded?.rules, vm.state.value.editing)
    }

    @Test fun identityListsRoundTripUntouchedOnSave() = runTest {
        val api = FakeApi()
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        val loadedRules = vm.state.value.loaded!!.rules
        assertTrue(loadedRules.shell_skip.isNotEmpty())
        vm.onIntent(ConfigIntent.EditRules(loadedRules.copy(valueflags = listOf("-L"))))
        vm.onIntent(ConfigIntent.Save); runCurrent()
        assertEquals(loadedRules.shell_skip, api.putCalls.single().second?.shell_skip)
        assertEquals(loadedRules.init_floor, api.putCalls.single().second?.init_floor)
        assertEquals(ConfigEffect.Saved(false), vm.effect.first())
    }

    @Test fun saveWithoutChangesIsANoOp() = runTest {
        val api = FakeApi()
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        vm.onIntent(ConfigIntent.Save); runCurrent()
        assertTrue(api.putCalls.isEmpty())
        assertTrue(vm.state.value.errors.isEmpty())
        assertEquals(ConfigEffect.Saved(false), vm.effect.first())
    }

    @Test fun checkUpdateFillsState() = runTest {
        val api = FakeApi(updateResponse = available("9.9.9"))
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.CheckUpdate); runCurrent()

        assertEquals(UpdateState.UPDATE_AVAILABLE, vm.state.value.update?.state)
        assertEquals("9.9.9", vm.state.value.update?.latestVersion)
        assertFalse(vm.state.value.updateChecking)
        assertFalse(vm.state.value.updateCheckFailed)
        assertEquals(listOf(true), api.updateForces)   // "Check now" forces a fresh check
    }

    @Test fun installSpawnsTheCliDetachedAndWaitsForTheNewVersion() = runTest {
        val api = FakeApi(updateResponse = available("v9.9.9"))
        val spawned = mutableListOf<Pair<String, String?>>()
        val vm = ConfigViewModel({ api }, backgroundScope) { tag, repo ->
            spawned += tag to repo
            api.updateResponse = UpdateInfo(currentVersion = "9.9.9", state = UpdateState.CURRENT,
                latestVersion = "9.9.9")
            ApplyResult.Completed(emptyList())
        }
        vm.onIntent(ConfigIntent.CheckUpdate); runCurrent()
        vm.onIntent(ConfigIntent.AskInstall)
        assertTrue(vm.state.value.confirmInstall)

        vm.onIntent(ConfigIntent.ConfirmInstall)

        // The spawn is synchronous: it happened before any coroutine turn.
        assertEquals(listOf<Pair<String, String?>>("v9.9.9" to "ciszkin/corral"), spawned)
        assertTrue(vm.state.value.installing)
        advanceTimeBy(1_501); runCurrent()

        assertTrue(vm.state.value.installed)
        assertFalse(vm.state.value.installing)
        assertNull(vm.state.value.installError)
        assertEquals("9.9.9", vm.state.value.update?.currentVersion)
    }

    @Test fun installUpdateSurfacesAFailureHonestly() = runTest {
        val api = FakeApi(updateResponse = available("9.9.9"))
        val vm = ConfigViewModel({ api }, backgroundScope) { _, _ ->
            ApplyResult.Failed("could not find the corral CLI — expected /home/u/bin/corral")
        }
        vm.onIntent(ConfigIntent.CheckUpdate); runCurrent()
        vm.onIntent(ConfigIntent.ConfirmInstall); runCurrent()

        assertFalse(vm.state.value.installed)
        assertFalse(vm.state.value.installing)
        assertTrue(vm.state.value.installError!!.contains("could not find the corral CLI"))
    }

    @Test fun installReleasesInstallingAndShowsErrorWhenTheApplierThrows() = runTest {
        val api = FakeApi(updateResponse = available("9.9.9"))
        val vm = ConfigViewModel({ api }, backgroundScope) { _, _ ->
            throw IOException("could not start the update: Permission denied")
        }
        vm.onIntent(ConfigIntent.CheckUpdate); runCurrent()
        vm.onIntent(ConfigIntent.ConfirmInstall); runCurrent()

        assertFalse(vm.state.value.installing, "a throw must never latch installing")
        assertFalse(vm.state.value.installed)
        assertTrue(vm.state.value.installError!!.contains("Permission denied"), vm.state.value.installError!!)
    }

    @Test fun installTimeoutSaysTheUpdateIsStillRunningInTheBackground() = runTest {
        val api = FakeApi(updateResponse = available("9.9.9"))
        val vm = ConfigViewModel({ api }, backgroundScope) { _, _ -> ApplyResult.Completed(emptyList()) }
        vm.onIntent(ConfigIntent.CheckUpdate); runCurrent()
        vm.onIntent(ConfigIntent.ConfirmInstall)
        advanceTimeBy(300_001); runCurrent()

        assertFalse(vm.state.value.installing)
        assertFalse(vm.state.value.installed)
        assertTrue(vm.state.value.installError!!.contains("background"), vm.state.value.installError!!)
    }

    @Test fun updateNotCheckedIsRetriedWithoutForce() = runTest {
        val api = FakeApi()
        api.updateQueue.addLast(
            UpdateInfo(currentVersion = "1.0.0", state = UpdateState.UNKNOWN, reason = UpdateReason.NOT_CHECKED),
        )
        api.updateResponse = UpdateInfo(currentVersion = "1.0.0", state = UpdateState.CURRENT,
            latestVersion = "1.0.0")
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        assertEquals(listOf(false), api.updateForces)

        advanceTimeBy(1_501); runCurrent()

        assertEquals(listOf(false, false), api.updateForces)
        assertEquals(UpdateState.CURRENT, vm.state.value.update?.state)
        assertFalse(vm.state.value.updateCheckFailed)
    }

    @Test fun aTerminalUnknownIsAnAnswerAndIsNotRetried() = runTest {
        val api = FakeApi(
            updateResponse = UpdateInfo(currentVersion = "1.0.0", state = UpdateState.UNKNOWN,
                reason = UpdateReason.UNREACHABLE),
        )
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()

        assertEquals(listOf(false), api.updateForces)
        assertEquals(UpdateReason.UNREACHABLE, vm.state.value.update?.reason)
    }

    @Test fun aThrowingDaemonIsRetried() = runTest {
        val api = FakeApi()
        api.updateError = IOException("connection refused")
        val vm = newVm(api)
        vm.onIntent(ConfigIntent.Load); runCurrent()
        assertEquals(listOf(false), api.updateForces)

        api.updateError = null
        advanceTimeBy(1_501); runCurrent()

        assertEquals(listOf(false, false), api.updateForces)
        assertEquals(UpdateState.DISABLED, vm.state.value.update?.state)
        assertFalse(vm.state.value.updateCheckFailed)
    }

    @Test fun aFreshCheckClearsTheInstalledCaption() = runTest {
        val api = FakeApi(updateResponse = available("9.9.9"))
        val vm = ConfigViewModel({ api }, backgroundScope) { _, _ ->
            api.updateResponse = UpdateInfo(currentVersion = "9.9.9", state = UpdateState.CURRENT,
                latestVersion = "9.9.9")
            ApplyResult.Completed(emptyList())
        }
        vm.onIntent(ConfigIntent.CheckUpdate); runCurrent()
        vm.onIntent(ConfigIntent.ConfirmInstall)
        advanceTimeBy(1_501); runCurrent()
        assertTrue(vm.state.value.installed)

        api.updateResponse = available("9.9.9")
        vm.onIntent(ConfigIntent.CheckUpdate); runCurrent()

        assertFalse(vm.state.value.installed, "a fresh state must not sit next to the stale caption")
        assertEquals(UpdateState.UPDATE_AVAILABLE, vm.state.value.update?.state)
    }

    private fun available(tag: String) =
        UpdateInfo(currentVersion = "1.0.0", state = UpdateState.UPDATE_AVAILABLE, latestVersion = tag,
            releaseUrl = "https://github.com/ciszkin/corral/releases/tag/$tag")

    private companion object {
        fun response(
            settings: List<ConfigSetting> = listOf(ConfigSetting("CORRAL_PORT", "8798", "8798", false)),
            rules: ConfigRules = ConfigRules(
                readonly = listOf("devices", "version"),
                valueflags = listOf("-P"),
                shell_skip = listOf("bash", "node"),
                init_floor = listOf("systemd"),
            ),
        ) = ConfigResponse(
            settings = settings,
            rules = rules,
            rulesPath = "/home/u/.config/corral/rules",
            envPath = "/home/u/.config/corral/env",
        )
    }
}
