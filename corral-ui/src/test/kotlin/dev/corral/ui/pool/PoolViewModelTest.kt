package dev.corral.ui.pool

import dev.corral.Device
import dev.corral.DeviceStatus
import dev.corral.EventFrame
import dev.corral.Lease
import dev.corral.QueueEntry
import dev.corral.StatusResponse
import dev.corral.ui.AvdStatus
import dev.corral.ui.Capabilities
import dev.corral.ui.CorralApi
import dev.corral.ui.ScreenshotDeniedException
import dev.corral.ui.app.AppIntent
import dev.corral.ui.app.AppViewModel
import dev.corral.ui.components.SelectorPart
import dev.corral.ui.components.selectorParts
import dev.corral.ui.data.ConfigResponse
import dev.corral.ui.data.ConfigRules
import dev.corral.ui.data.DoctorLayer
import dev.corral.ui.data.DoctorResponse
import dev.corral.ui.data.Prefs
import dev.corral.ui.data.PutConfigResponse
import dev.corral.ui.data.UiPrefs
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PoolViewModelTest {

    private class FakeApi(
        var devicesResponse: List<Device> = emptyList(),
        var statusResponse: StatusResponse = StatusResponse(emptyList(), "/adb"),
        var queueResponse: List<QueueEntry> = emptyList(),
        var doctorResponse: DoctorResponse = DoctorResponse("/adb", emptyList()),
        var caps: Capabilities = Capabilities(true),
        var emulatorsResponse: List<AvdStatus> = emptyList(),
    ) : CorralApi {
        var devicesCalls = 0
        var statusCalls = 0
        var queueCalls = 0
        var doctorCalls = 0
        var emulatorsCalls = 0
        val screenshots = mutableListOf<String>()
        val forced = mutableListOf<String>()
        val denied = mutableSetOf<String>()
        val failing = mutableSetOf<String>()
        val started = mutableListOf<String>()
        val stopped = mutableListOf<Pair<String, Boolean>>()
        var forceFail = false
        var startFail = false
        var stopFail = false
        var denialReason: String? = null

        override suspend fun devices(): List<Device> { devicesCalls++; return devicesResponse }
        override suspend fun status(): StatusResponse { statusCalls++; return statusResponse }
        override suspend fun queue(): List<QueueEntry> { queueCalls++; return queueResponse }
        override suspend fun doctor(): DoctorResponse { doctorCalls++; return doctorResponse }
        override suspend fun screenshot(serial: String): ByteArray {
            screenshots += serial
            if (serial in failing) throw RuntimeException("connection reset")
            if (serial in denied) throw ScreenshotDeniedException(denialReason)
            return byteArrayOf(1, 2)
        }
        override suspend fun forceFree(serial: String) {
            if (forceFail) throw RuntimeException("corral: refused")
            forced += serial
        }
        override suspend fun emulators(): List<AvdStatus> { emulatorsCalls++; return emulatorsResponse }
        override suspend fun emulatorStart(avd: String) {
            if (startFail) throw RuntimeException("corral: emulator for $avd exited immediately: boom")
            started += avd
        }
        override suspend fun emulatorStop(avd: String, force: Boolean) {
            if (stopFail) throw RuntimeException("corral: $avd is leased by mcp:claude — free it first, or POST {\"force\":true}")
            stopped += avd to force
        }
        override suspend fun config(): ConfigResponse = error("unused")
        override suspend fun putConfig(settings: Map<String, String>, rules: ConfigRules?): Result<PutConfigResponse> =
            error("unused")
        override fun events(): Flow<String> = flow { error("unused") }
        override fun capabilities(): Capabilities = caps
    }

    private fun TestScope.newPool(api: FakeApi): Pair<PoolViewModel, AppViewModel> {
        val app = AppViewModel(
            { _, _ -> api }, tmpPrefs(), Prefs(), "http://x", "corral-ui", backgroundScope,
        ) { testScheduler.currentTime }
        return PoolViewModel({ api }, app, backgroundScope) { testScheduler.currentTime } to app
    }

    private fun tmpPrefs() = UiPrefs(createTempDirectory("poolvm").resolve("ui.conf"))

    private val hello = EventFrame("hello", listOf("devices", "queue"), 0)

    private fun devicesOf(vararg serials: String) = serials.map { Device(it, "device") }

    @Test fun helloRefetchesEverything() = runTest {
        val api = FakeApi(
            devicesResponse = devicesOf("dev1"),
            statusResponse = StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"),
            queueResponse = listOf(QueueEntry(1, "w", "[any]", 0, 600)),
        )
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        assertEquals(listOf("dev1"), pool.state.value.devices.map { it.d.serial })
        assertEquals(1, pool.state.value.queue.size)
        assertEquals(1, api.devicesCalls)
        assertEquals(1, api.statusCalls)
        assertEquals(1, api.queueCalls)
        assertEquals(1, api.doctorCalls)
    }

    @Test fun devicesNudgeFetchesStatusDevicesAndDoctorNotQueue() = runTest {
        val api = FakeApi(
            devicesResponse = devicesOf("dev1"),
            statusResponse = StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"),
        )
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        val devices = api.devicesCalls
        val status = api.statusCalls
        val queue = api.queueCalls
        val doctor = api.doctorCalls
        app.onIntent(AppIntent.Frame(EventFrame("state.changed", listOf("devices"), 1))); runCurrent()
        assertEquals(devices + 1, api.devicesCalls)
        assertEquals(status + 1, api.statusCalls)
        assertEquals(doctor + 1, api.doctorCalls)
        assertEquals(queue, api.queueCalls)
    }

    @Test fun queueNudgeFetchesQueueOnly() = runTest {
        val api = FakeApi(queueResponse = listOf(QueueEntry(1, "w", "[any]", 0, 600)))
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        val devices = api.devicesCalls
        val status = api.statusCalls
        val queue = api.queueCalls
        val doctor = api.doctorCalls
        app.onIntent(AppIntent.Frame(EventFrame("state.changed", listOf("queue"), 1))); runCurrent()
        assertEquals(devices, api.devicesCalls)
        assertEquals(status, api.statusCalls)
        assertEquals(doctor, api.doctorCalls)
        assertEquals(queue + 1, api.queueCalls)
        assertEquals(1, pool.state.value.queue.size)
    }

    @Test fun unknownResourceRefetchesEverything() = runTest {
        val api = FakeApi()
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        val devices = api.devicesCalls
        val queue = api.queueCalls
        app.onIntent(AppIntent.Frame(EventFrame("state.changed", listOf("screenshots-v2"), 1))); runCurrent()
        assertEquals(devices + 1, api.devicesCalls)
        assertEquals(queue + 1, api.queueCalls)
    }

    @Test fun emptyResourcesRefetchEverything() = runTest {
        val api = FakeApi()
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        val devices = api.devicesCalls
        val queue = api.queueCalls
        app.onIntent(AppIntent.Frame(EventFrame("state.changed", emptyList(), 1))); runCurrent()
        assertEquals(devices + 1, api.devicesCalls)
        assertEquals(queue + 1, api.queueCalls)
    }

    @Test fun unknownFrameTypeIsIgnoredNoFetch() = runTest {
        val api = FakeApi()
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        val devices = api.devicesCalls
        val queue = api.queueCalls
        app.onIntent(AppIntent.Frame(EventFrame("lease.acquired", seq = 1))); runCurrent()
        assertEquals(devices, api.devicesCalls)
        assertEquals(queue, api.queueCalls)
    }

    @Test fun devicesMergeInventoryWithLeases() = runTest {
        val lease = Lease("dev1", "mcp:claude:7f3a", expiresAt = 1_700_000_300)
        val api = FakeApi(
            devicesResponse = listOf(Device("dev1", "device", model = "Pixel 6", osVersion = "13")),
            statusResponse = StatusResponse(listOf(DeviceStatus("dev1", lease)), "/adb"),
        )
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        val view = pool.state.value.devices.single()
        assertEquals("Pixel 6", view.d.model)
        assertEquals("mcp:claude:7f3a", view.lease?.owner)
    }

    @Test fun freeDeviceHasNoLease() = runTest {
        val api = FakeApi(devicesResponse = devicesOf("dev1"))
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        assertNull(pool.state.value.devices.single().lease)
    }

    @Test fun thumbnailsFetchedOncePerSerial() = runTest {
        val api = FakeApi(
            devicesResponse = devicesOf("dev1", "dev2"),
            statusResponse = StatusResponse(listOf(DeviceStatus("dev1", null), DeviceStatus("dev2", null)), "/adb"),
        )
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        assertEquals(setOf("dev1", "dev2"), api.screenshots.toSet())
        assertEquals(setOf("dev1", "dev2"), pool.state.value.thumbnails.keys)
        app.onIntent(AppIntent.Frame(EventFrame("state.changed", listOf("devices"), 1))); runCurrent()
        assertEquals(2, api.screenshots.size)
    }

    @Test fun screenshotDenialRaisesTheTeachingFlag() = runTest {
        for (devices in listOf(
            listOf(DeviceStatus("denied", null), DeviceStatus("ok", null)),
            listOf(DeviceStatus("ok", null), DeviceStatus("denied", null)),
        )) {
            val api = FakeApi(
                devicesResponse = devices.map { Device(it.serial, "device") },
                statusResponse = StatusResponse(devices, "/adb"),
            )
            api.denied += "denied"
            val (pool, app) = newPool(api)
            runCurrent()
            app.onIntent(AppIntent.Frame(hello)); runCurrent()
            assertTrue(pool.state.value.denied)
            assertEquals(setOf("denied", "ok"), api.screenshots.toSet())
            assertEquals(setOf("ok"), pool.state.value.thumbnails.keys)
        }
    }

    @Test fun screenshotDenialCarriesTheDaemonReasonIntoState() = runTest {
        val api = FakeApi(
            devicesResponse = devicesOf("dev1"),
            statusResponse = StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"),
        )
        api.denied += "dev1"
        api.denialReason = "corral: strict screenshots: dev1 is not leased by corral-ui — if corral-ui is an " +
            "observer, add it to CORRAL_SCREENSHOT_OBSERVERS; if it is a usable device, corral claim dev1 first, " +
            "or set CORRAL_SCREENSHOTS=open"
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        assertTrue(pool.state.value.denied)
        assertEquals(api.denialReason, pool.state.value.deniedReason)
    }

    @Test fun clearingTheDenialClearsTheReason() = runTest {
        val api = FakeApi(
            devicesResponse = devicesOf("dev1"),
            statusResponse = StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"),
        )
        api.denied += "dev1"
        api.denialReason = "corral: strict screenshots: dev1 is not leased by corral-ui"
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        assertTrue(pool.state.value.deniedReason != null)

        api.denied -= "dev1"
        app.onIntent(AppIntent.Frame(EventFrame("state.changed", listOf("devices"), 1))); runCurrent()
        assertFalse(pool.state.value.denied)
        assertNull(pool.state.value.deniedReason)
    }

    @Test fun transientScreenshotFailureDoesNotRaiseTheBanner() = runTest {
        val api = FakeApi(
            devicesResponse = devicesOf("dev1"),
            statusResponse = StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"),
        )
        api.failing += "dev1"
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        assertFalse(pool.state.value.denied)
        assertTrue(pool.state.value.thumbnails.isEmpty())

        api.failing -= "dev1"
        app.onIntent(AppIntent.Frame(EventFrame("state.changed", listOf("devices"), 1))); runCurrent()
        assertEquals(listOf("dev1", "dev1"), api.screenshots)
        assertEquals(setOf("dev1"), pool.state.value.thumbnails.keys)
    }

    @Test fun failedThumbnailIsRetriedOnNextNudge() = runTest {
        val api = FakeApi(
            devicesResponse = devicesOf("dev1"),
            statusResponse = StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"),
        )
        api.denied += "dev1"
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        assertTrue(pool.state.value.denied)
        assertTrue(pool.state.value.thumbnails.isEmpty())
        assertEquals(listOf("dev1"), api.screenshots)

        api.denied -= "dev1"
        app.onIntent(AppIntent.Frame(EventFrame("state.changed", listOf("devices"), 1))); runCurrent()
        assertEquals(listOf("dev1", "dev1"), api.screenshots)
        assertEquals(setOf("dev1"), pool.state.value.thumbnails.keys)
    }

    @Test fun successfulScreenshotClearsTheDenialBanner() = runTest {
        val api = FakeApi(
            devicesResponse = devicesOf("dev1"),
            statusResponse = StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"),
        )
        api.denied += "dev1"
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        assertTrue(pool.state.value.denied)

        api.denied -= "dev1"
        app.onIntent(AppIntent.Frame(EventFrame("state.changed", listOf("devices"), 1))); runCurrent()
        assertTrue(pool.state.value.thumbnails.containsKey("dev1"))
        assertFalse(pool.state.value.denied)
    }

    @Test fun forceFreeIsGuardedByCapabilities() = runTest {
        val denied = FakeApi(caps = Capabilities(false))
        val (pool1, _) = newPool(denied)
        runCurrent()
        pool1.onIntent(PoolIntent.AskForceFree("dev1"))
        pool1.onIntent(PoolIntent.ConfirmForceFree); runCurrent()
        assertTrue(denied.forced.isEmpty())
        assertNull(pool1.state.value.confirmFree)

        val allowed = FakeApi(caps = Capabilities(true))
        val (pool2, _) = newPool(allowed)
        runCurrent()
        pool2.onIntent(PoolIntent.AskForceFree("dev1"))
        assertEquals("dev1", pool2.state.value.confirmFree)
        pool2.onIntent(PoolIntent.ConfirmForceFree); runCurrent()
        assertEquals(listOf("dev1"), allowed.forced)
        assertNull(pool2.state.value.confirmFree)
    }

    @Test fun cancelForceFreeClearsTheConfirmation() = runTest {
        val (pool, _) = newPool(FakeApi())
        pool.onIntent(PoolIntent.AskForceFree("dev1"))
        pool.onIntent(PoolIntent.CancelForceFree)
        assertNull(pool.state.value.confirmFree)
    }

    @Test fun scrcpyFailureEmitsTheEffect() = runTest {
        val (pool, _) = newPool(FakeApi())
        pool.scrcpyFailed("dev1", "scrcpy not found (scrcpy)")
        assertEquals(PoolEffect.ScrcpyFailed("dev1", "scrcpy not found (scrcpy)"), pool.effect.first())
    }

    @Test fun failedForceFreeKeepsTheDialogOpenAndEmitsTheEffect() = runTest {
        val api = FakeApi().apply { forceFail = true }
        val (pool, _) = newPool(api)
        runCurrent()
        pool.onIntent(PoolIntent.AskForceFree("dev1"))
        pool.onIntent(PoolIntent.ConfirmForceFree); runCurrent()
        assertEquals("dev1", pool.state.value.confirmFree)
        assertEquals(PoolEffect.ForceFreeFailed("corral: refused"), pool.effect.first())
        assertTrue(api.forced.isEmpty())
    }

    @Test fun forceFreeRetrySucceedsAndClosesTheDialog() = runTest {
        val api = FakeApi().apply { forceFail = true }
        val (pool, _) = newPool(api)
        runCurrent()
        pool.onIntent(PoolIntent.AskForceFree("dev1"))
        pool.onIntent(PoolIntent.ConfirmForceFree); runCurrent()
        pool.effect.first()
        api.forceFail = false
        pool.onIntent(PoolIntent.ConfirmForceFree); runCurrent()
        assertNull(pool.state.value.confirmFree)
        assertEquals(listOf("dev1"), api.forced)
    }

    @Test fun emulatorsListedOnDevicesRefresh() = runTest {
        val api = FakeApi(
            devicesResponse = devicesOf("dev1"),
            statusResponse = StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"),
            emulatorsResponse = listOf(AvdStatus("Pixel6AVD", listOf("emulator-5554"))),
        )
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        assertEquals(listOf(AvdStatus("Pixel6AVD", listOf("emulator-5554"))), pool.state.value.emulators)
        assertTrue(api.emulatorsCalls >= 1)
    }

    @Test fun startStopIntentsCallTheApiAndRefresh() = runTest {
        val api = FakeApi(emulatorsResponse = listOf(AvdStatus("Pixel6AVD", emptyList())))
        val (pool, _) = newPool(api)
        runCurrent()
        pool.onIntent(PoolIntent.RefreshEmulators); runCurrent()
        val emulatorFetches = api.emulatorsCalls
        val deviceFetches = api.devicesCalls

        pool.onIntent(PoolIntent.StartEmulator("Pixel6AVD")); runCurrent()
        assertEquals(listOf("Pixel6AVD"), api.started)
        assertTrue(api.emulatorsCalls > emulatorFetches)
        assertTrue(api.devicesCalls > deviceFetches)

        val afterStart = api.emulatorsCalls
        pool.onIntent(PoolIntent.StopEmulator("Pixel6AVD", force = true)); runCurrent()
        assertEquals(listOf("Pixel6AVD" to true), api.stopped)
        assertTrue(api.emulatorsCalls > afterStart)
    }

    @Test fun stopRefusalSurfacesTheEffect() = runTest {
        val api = FakeApi().apply { stopFail = true }
        val (pool, _) = newPool(api)
        runCurrent()
        pool.onIntent(PoolIntent.StopEmulator("Pixel6AVD", force = false)); runCurrent()
        val effect = pool.effect.first() as PoolEffect.EmulatorActionFailed
        assertTrue(effect.reason.contains("mcp:claude"), effect.reason)
        assertTrue(api.stopped.isEmpty())
    }

    @Test fun startingAnAvdMarksItAndGuardsRepeats() = runTest {
        val api = FakeApi(emulatorsResponse = listOf(AvdStatus("Pixel6AVD", emptyList())))
        val (pool, _) = newPool(api)
        runCurrent()
        pool.onIntent(PoolIntent.StartEmulator("Pixel6AVD"))
        assertEquals(setOf("Pixel6AVD"), pool.state.value.startingEmulators)
        pool.onIntent(PoolIntent.StartEmulator("Pixel6AVD")); runCurrent()
        assertEquals(listOf("Pixel6AVD"), api.started)
        assertEquals(setOf("Pixel6AVD"), pool.state.value.startingEmulators)
    }

    @Test fun aRefreshShowingARunningSerialClearsStarting() = runTest {
        val api = FakeApi(emulatorsResponse = listOf(AvdStatus("Pixel6AVD", emptyList())))
        val (pool, _) = newPool(api)
        runCurrent()
        pool.onIntent(PoolIntent.StartEmulator("Pixel6AVD")); runCurrent()
        assertEquals(setOf("Pixel6AVD"), pool.state.value.startingEmulators)
        api.emulatorsResponse = listOf(AvdStatus("Pixel6AVD", listOf("emulator-5554")))
        pool.onIntent(PoolIntent.RefreshEmulators); runCurrent()
        assertTrue(pool.state.value.startingEmulators.isEmpty())
    }

    @Test fun aFailingStartClearsStartingAndEmitsTheEffect() = runTest {
        val api = FakeApi(emulatorsResponse = listOf(AvdStatus("Pixel6AVD", emptyList()))).apply { startFail = true }
        val (pool, _) = newPool(api)
        runCurrent()
        pool.onIntent(PoolIntent.StartEmulator("Pixel6AVD")); runCurrent()
        assertTrue(pool.state.value.startingEmulators.isEmpty())
        val effect = pool.effect.first() as PoolEffect.EmulatorActionFailed
        assertTrue(effect.reason.contains("exited immediately"), effect.reason)
    }

    @Test fun bootingChipState() = runTest {
        assertTrue(DeviceView(Device("emulator-5554", "device", avd = "Pixel6AVD"), null).booting)
        assertFalse(DeviceView(Device("dev1", "device"), null).booting)
        assertFalse(DeviceView(Device("emulator-5554", "offline"), null).booting)
    }

    @Test fun tickDrivesNow() = runTest {
        val (pool, _) = newPool(FakeApi())
        pool.onIntent(PoolIntent.Tick(3000))
        assertEquals(3000L, pool.state.value.now)
    }

    @Test fun seqGapRefetchesEverything() = runTest {
        val api = FakeApi(
            devicesResponse = devicesOf("dev1"),
            statusResponse = StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"),
        )
        val (_, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(EventFrame("hello", listOf("devices", "queue"), 3))); runCurrent()
        val devices = api.devicesCalls
        val queue = api.queueCalls
        app.onIntent(AppIntent.Frame(EventFrame("state.changed", listOf("devices"), 9))); runCurrent()
        assertTrue(api.devicesCalls > devices)
        assertTrue(api.queueCalls > queue)
    }

    @Test fun sameSeqReplayRefetchesOnlyTheNamedResource() = runTest {
        val api = FakeApi(
            devicesResponse = devicesOf("dev1"),
            statusResponse = StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"),
            queueResponse = listOf(QueueEntry(1, "w", "[any]", 0, 600)),
        )
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(EventFrame("hello", listOf("devices", "queue"), 3))); runCurrent()
        val devices = api.devicesCalls
        val queue = api.queueCalls
        app.onIntent(AppIntent.Frame(EventFrame("state.changed", listOf("queue"), 3))); runCurrent()
        assertEquals(devices, api.devicesCalls)
        assertEquals(queue + 1, api.queueCalls)
        assertEquals(1, pool.state.value.queue.size)
    }

    @Test fun refreshDoctorIntentFetchesDoctor() = runTest {
        val api = FakeApi(doctorResponse = DoctorResponse("/adb", listOf(DoctorLayer("daemon", "ok", "x"))))
        val (pool, _) = newPool(api)
        runCurrent()
        pool.onIntent(PoolIntent.RefreshDoctor); runCurrent()
        assertEquals(1, api.doctorCalls)
        assertEquals("/adb", pool.state.value.doctor?.adb)
    }

    @Test fun doctorFailureIsExposedForTheRailBadge() = runTest {
        val api = FakeApi(doctorResponse = DoctorResponse("/adb", listOf(DoctorLayer("sdk-shim", "fail", "nope"))))
        val (pool, app) = newPool(api)
        runCurrent()
        app.onIntent(AppIntent.Frame(hello)); runCurrent()
        assertTrue(pool.state.value.doctor?.layers?.any { it.status == "fail" } == true)
    }

    @Test fun selectorPartsParseTheDaemonShape() {
        assertEquals(listOf(SelectorPart.Any), selectorParts("[any]"))
        assertEquals(listOf(SelectorPart.Field("serial", "dev1")), selectorParts("[serial=dev1]"))
        assertEquals(listOf(SelectorPart.Field("model", "Pixel_6")), selectorParts("[model=Pixel_6]"))
        assertEquals(listOf(SelectorPart.Field("os", "13")), selectorParts("[os>=13]"))
        assertEquals(
            listOf(SelectorPart.Field("serial", "dev1"), SelectorPart.Field("model", "Pixel_6")),
            selectorParts("[serial=dev1 model=Pixel_6]"),
        )
    }
}
