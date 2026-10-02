package dev.corral.ui.app

import dev.corral.Device
import dev.corral.EventFrame
import dev.corral.QueueEntry
import dev.corral.StatusResponse
import dev.corral.corralJson
import dev.corral.ui.AvdStatus
import dev.corral.ui.Capabilities
import dev.corral.ui.CorralApi
import dev.corral.ui.data.ConfigResponse
import dev.corral.ui.data.ConfigRules
import dev.corral.ui.data.DoctorResponse
import dev.corral.ui.data.Prefs
import dev.corral.ui.data.PutConfigResponse
import dev.corral.ui.data.UiPrefs
import dev.corral.ui.theme.Language
import dev.corral.ui.theme.ThemeMode
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private class FakeApi : CorralApi {
        var failEvents = false
        var frameThenDrop: String? = null
        var attempts = 0
        var closed = false
        private val frames = MutableSharedFlow<String>(replay = 64, extraBufferCapacity = 64)

        fun send(frame: String) { frames.tryEmit(frame) }

        override suspend fun status(): StatusResponse = error("unused")
        override suspend fun devices(): List<Device> = error("unused")
        override suspend fun queue(): List<QueueEntry> = error("unused")
        override suspend fun screenshot(serial: String): ByteArray = error("unused")
        override suspend fun forceFree(serial: String) = error("unused")
        override suspend fun config(): ConfigResponse = error("unused")
        override suspend fun putConfig(settings: Map<String, String>, rules: ConfigRules?): Result<PutConfigResponse> =
            error("unused")
        override suspend fun doctor(): DoctorResponse = error("unused")
        override suspend fun emulators(): List<AvdStatus> = error("unused")
        override suspend fun emulatorStart(avd: String) = error("unused")
        override suspend fun emulatorStop(avd: String, force: Boolean) = error("unused")
        override fun events(): Flow<String> {
            val drop = frameThenDrop
            return when {
                failEvents -> flow<String> { error("disconnected") }
                drop != null -> flow {
                    attempts++
                    emit(drop)
                    throw RuntimeException("dropped")
                }
                else -> frames
            }
        }
        override fun capabilities(): Capabilities = Capabilities(true)
        override fun close() { closed = true }
    }

    private val hello = EventFrame("hello", listOf("devices", "queue"), 0)

    private fun TestScope.newVm(
        factory: (String, String) -> CorralApi = { _, _ -> FakeApi() },
        prefs: Prefs = Prefs(),
        store: UiPrefs = tmpPrefs(),
        url: String = prefs.url,
        owner: String = prefs.owner,
    ) = AppViewModel(factory, store, prefs, url, owner, backgroundScope) { testScheduler.currentTime }

    private fun tmpPrefs() = UiPrefs(createTempDirectory("appvm").resolve("ui.conf"))

    @Test fun initialStateReflectsStoredPrefs() = runTest {
        val vm = newVm(prefs = Prefs(themeMode = ThemeMode.DARK, language = Language.BELARUSIAN, url = "http://host:1"))
        assertTrue(vm.state.value.useDark)
        assertEquals(Language.BELARUSIAN, vm.state.value.language)
        assertEquals("http://host:1", vm.state.value.prefs.url)
        assertEquals("http://host:1", vm.state.value.url)
    }

    @Test fun helloMarksConnectedAndRecordsSeq() = runTest {
        val vm = newVm()
        vm.onIntent(AppIntent.Frame(hello.copy(seq = 3)))
        assertEquals(Connection.Connected(3), vm.state.value.connection)
        assertEquals(3L, vm.state.value.seq)
    }

    @Test fun unknownFrameTypeIsIgnored() = runTest {
        val vm = newVm()
        vm.onIntent(AppIntent.Frame(hello))
        vm.onIntent(AppIntent.Frame(EventFrame("lease.acquired", seq = 1)))
        assertEquals(Connection.Connected(0), vm.state.value.connection)
        assertEquals(0L, vm.state.value.seq)
    }

    @Test fun lastUpdatedMsTracksFrames() = runTest {
        val vm = newVm()
        advanceTimeBy(5_000)
        vm.onIntent(AppIntent.Frame(hello.copy(seq = 3)))
        assertEquals(5_000L, vm.state.value.lastUpdatedMs)
    }

    @Test fun seqGapEmitsSyntheticRefetchBeforeTheFrame() = runTest {
        val vm = newVm()
        val first = hello.copy(seq = 3)
        val changed = EventFrame("state.changed", listOf("devices"), 9)
        vm.onIntent(AppIntent.Frame(first))
        vm.onIntent(AppIntent.Frame(changed))
        assertEquals(
            listOf(first, EventFrame("state.changed", emptyList(), 9), changed),
            vm.frames.take(3).toList(),
        )
    }

    @Test fun sameSeqReplayIsNotAGap() = runTest {
        val vm = newVm()
        val changed = EventFrame("state.changed", listOf("queue"), 3)
        vm.onIntent(AppIntent.Frame(hello.copy(seq = 3)))
        vm.onIntent(AppIntent.Frame(changed))
        assertEquals(listOf(hello.copy(seq = 3), changed), vm.frames.take(2).toList())
    }

    @Test fun droppedEventsReconnectAfterBackoff() = runTest {
        val api = FakeApi().apply { failEvents = true }
        val vm = newVm(factory = { _, _ -> api })
        vm.start(); runCurrent()
        assertTrue(vm.state.value.connection is Connection.Disconnected)
        api.failEvents = false
        api.send(corralJson.encodeToString(hello))
        advanceTimeBy(1000); runCurrent()
        assertTrue(vm.state.value.connection is Connection.Connected)
    }

    @Test fun frameThenDropResetsBackoffOnEveryFrame() = runTest {
        val api = FakeApi().apply { frameThenDrop = corralJson.encodeToString(hello) }
        val vm = newVm(factory = { _, _ -> api })
        vm.start(); runCurrent()
        assertEquals(1, api.attempts)
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, api.attempts)
        advanceTimeBy(1000); runCurrent()
        assertEquals(3, api.attempts)
    }

    @Test fun connectRebuildsTheClientAndReconnects() = runTest {
        val first = FakeApi()
        val second = FakeApi()
        val requested = mutableListOf<Pair<String, String>>()
        val factory: (String, String) -> CorralApi = { url, owner ->
            requested += url to owner
            if (url == "http://new") second else first
        }
        val vm = newVm(factory, url = "http://old", owner = "owner1")
        vm.start(); runCurrent()
        first.send(corralJson.encodeToString(hello))
        runCurrent()
        assertEquals(Connection.Connected(0), vm.state.value.connection)

        vm.onIntent(AppIntent.Connect("http://new", "owner2")); runCurrent()
        assertEquals("http://new", vm.state.value.url)
        assertEquals("owner2", vm.state.value.prefs.owner)
        assertEquals(listOf("http://old", "http://new"), requested.map { it.first })
        assertEquals(Connection.Connecting, vm.state.value.connection)
        assertTrue(first.closed)
        assertFalse(second.closed)

        first.send(corralJson.encodeToString(hello.copy(seq = 99)))
        runCurrent()
        assertEquals(Connection.Connecting, vm.state.value.connection)

        second.send(corralJson.encodeToString(hello.copy(seq = 5)))
        runCurrent()
        assertEquals(Connection.Connected(5), vm.state.value.connection)
        assertEquals(5L, vm.state.value.seq)
    }

    @Test fun connectResetsSeqSoTheNewStreamIsNotAGap() = runTest {
        val first = FakeApi()
        val second = FakeApi()
        val factory: (String, String) -> CorralApi = { url, _ ->
            if (url == "http://new") second else first
        }
        val vm = newVm(factory, url = "http://old", owner = "owner1")
        vm.start(); runCurrent()
        first.send(corralJson.encodeToString(hello))
        runCurrent()

        vm.onIntent(AppIntent.Connect("http://new", "owner2")); runCurrent()
        second.send(corralJson.encodeToString(EventFrame("state.changed", listOf("devices"), 5)))
        runCurrent()
        assertEquals(Connection.Connected(5), vm.state.value.connection)
        assertEquals(
            listOf(hello, EventFrame("state.changed", listOf("devices"), 5)),
            vm.frames.take(2).toList(),
        )
    }

    @Test fun setThemePersistsAndUpdatesUseDark() = runTest {
        val store = tmpPrefs()
        val vm = newVm(store = store)
        vm.onIntent(AppIntent.SetTheme(ThemeMode.DARK))
        assertTrue(vm.state.value.useDark)
        assertEquals(ThemeMode.DARK, store.load().themeMode)
        vm.onIntent(AppIntent.SetTheme(ThemeMode.LIGHT))
        assertFalse(vm.state.value.useDark)
        assertEquals(ThemeMode.LIGHT, store.load().themeMode)
    }

    @Test fun setLanguagePersists() = runTest {
        val store = tmpPrefs()
        val vm = newVm(store = store)
        vm.onIntent(AppIntent.SetLanguage(Language.BELARUSIAN))
        assertEquals(Language.BELARUSIAN, vm.state.value.language)
        assertEquals(Language.BELARUSIAN, store.load().language)
    }

    @Test fun setScrcpyPersists() = runTest {
        val store = tmpPrefs()
        val vm = newVm(store = store)
        vm.onIntent(AppIntent.SetScrcpy("/opt/scrcpy"))
        assertEquals("/opt/scrcpy", vm.state.value.prefs.scrcpyPath)
        assertEquals("/opt/scrcpy", store.load().scrcpyPath)
    }
}
