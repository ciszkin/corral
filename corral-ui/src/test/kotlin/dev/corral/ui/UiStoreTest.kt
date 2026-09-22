package dev.corral.ui

import dev.corral.DeviceStatus
import dev.corral.EventFrame
import dev.corral.QueueEntry
import dev.corral.StatusResponse
import dev.corral.corralJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UiStoreTest {

    private class FakeApi(
        var statusResponse: StatusResponse = StatusResponse(emptyList(), "/adb"),
        var queueResponse: List<QueueEntry> = emptyList(),
        var caps: Capabilities = Capabilities(true),
    ) : CorralApi {
        var statusCalls = 0
        var queueCalls = 0
        val screenshots = mutableListOf<String>()
        val forced = mutableListOf<String>()
        val denied = mutableSetOf<String>()
        val failing = mutableSetOf<String>()   // any non-policy failure: 404, 502, network
        var failEvents = false
        private val frames = MutableSharedFlow<String>(replay = 64, extraBufferCapacity = 64)

        fun send(frame: String) { frames.tryEmit(frame) }

        override suspend fun status(): StatusResponse { statusCalls++; return statusResponse }
        override suspend fun queue(): List<QueueEntry> { queueCalls++; return queueResponse }
        override suspend fun screenshot(serial: String): ByteArray {
            screenshots += serial
            if (serial in failing) throw RuntimeException("connection reset")
            if (serial in denied) throw ScreenshotDeniedException()
            return byteArrayOf(1, 2)
        }
        override suspend fun forceFree(serial: String) { forced += serial }
        override fun events(): Flow<String> =
            if (failEvents) flow<String> { error("disconnected") } else frames
        override fun capabilities(): Capabilities = caps
    }

    private fun frame(type: String, resources: List<String> = emptyList(), seq: Long) =
        corralJson.encodeToString(EventFrame(type, resources, seq))

    private val hello = frame("hello", listOf("devices", "queue"), 0)

    @Test fun helloTriggersInitialRefetchOfEverything() = runTest {
        val api = FakeApi(
            StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"),
            listOf(QueueEntry(1, "w", "[any]", 0, 600)),
        )
        val store = UiStore(api, backgroundScope)
        store.start(); runCurrent()
        assertEquals(0, api.statusCalls)
        assertEquals(0, api.queueCalls)
        api.send(hello); runCurrent()
        assertEquals(1, api.statusCalls)
        assertEquals(1, api.queueCalls)
        assertEquals(listOf("dev1"), store.devices.value.map { it.serial })
        assertEquals(1, store.queue.value.size)
    }

    @Test fun devicesNudgeFetchesStatusNotQueue() = runTest {
        val api = FakeApi(StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"))
        val store = UiStore(api, backgroundScope)
        store.start(); runCurrent()
        api.send(hello); runCurrent()
        val status = api.statusCalls
        val queue = api.queueCalls
        api.send(frame("state.changed", listOf("devices"), 1)); runCurrent()
        assertEquals(status + 1, api.statusCalls)
        assertEquals(queue, api.queueCalls)
    }

    @Test fun queueNudgeFetchesQueueNotStatus() = runTest {
        val api = FakeApi(queueResponse = listOf(QueueEntry(1, "w", "[any]", 0, 600)))
        val store = UiStore(api, backgroundScope)
        store.start(); runCurrent()
        api.send(hello); runCurrent()
        val status = api.statusCalls
        val queue = api.queueCalls
        api.send(frame("state.changed", listOf("queue"), 1)); runCurrent()
        assertEquals(status, api.statusCalls)
        assertEquals(queue + 1, api.queueCalls)
        assertEquals(1, store.queue.value.size)
    }

    @Test fun unknownResourceRefetchesEverything() = runTest {
        val api = FakeApi()
        val store = UiStore(api, backgroundScope)
        store.start(); runCurrent()
        api.send(hello); runCurrent()
        val status = api.statusCalls
        val queue = api.queueCalls
        api.send(frame("state.changed", listOf("screenshots-v2"), 1)); runCurrent()
        assertEquals(status + 1, api.statusCalls)
        assertEquals(queue + 1, api.queueCalls)
    }

    @Test fun emptyResourcesRefetchesEverything() = runTest {
        val api = FakeApi()
        val store = UiStore(api, backgroundScope)
        store.start(); runCurrent()
        api.send(hello); runCurrent()
        val status = api.statusCalls
        val queue = api.queueCalls
        api.send(frame("state.changed", seq = 1)); runCurrent()
        assertEquals(status + 1, api.statusCalls)
        assertEquals(queue + 1, api.queueCalls)
    }

    @Test fun seqGapAfterReconnectRefetchesEverything() = runTest {
        val api = FakeApi()
        val store = UiStore(api, backgroundScope)
        store.start(); runCurrent()
        api.send(frame("hello", listOf("devices", "queue"), 3)); runCurrent()
        val status = api.statusCalls
        val queue = api.queueCalls
        api.send(frame("state.changed", listOf("devices"), 9)); runCurrent()
        assertEquals(status + 1, api.statusCalls)
        assertEquals(queue + 1, api.queueCalls)
    }

    @Test fun replayedSameSeqFrameDoesNotRefetchEverything() = runTest {
        val api = FakeApi()
        val store = UiStore(api, backgroundScope)
        store.start(); runCurrent()
        api.send(frame("hello", listOf("devices", "queue"), 3)); runCurrent()
        val status = api.statusCalls
        val queue = api.queueCalls
        api.send(frame("state.changed", listOf("queue"), 3)); runCurrent()
        assertEquals(status, api.statusCalls)   // same-seq replay (reconnect) is not a gap
        assertEquals(queue + 1, api.queueCalls)
    }

    @Test fun unknownFrameTypeIsIgnoredNoFetch() = runTest {
        val api = FakeApi()
        val store = UiStore(api, backgroundScope)
        store.start(); runCurrent()
        api.send(hello); runCurrent()
        val status = api.statusCalls
        val queue = api.queueCalls
        api.send(frame("lease.acquired", seq = 1)); runCurrent()
        assertEquals(status, api.statusCalls)
        assertEquals(queue, api.queueCalls)
    }

    @Test fun thumbnailsFetchedOncePerSerial() = runTest {
        val api = FakeApi(StatusResponse(listOf(DeviceStatus("dev1", null), DeviceStatus("dev2", null)), "/adb"))
        val store = UiStore(api, backgroundScope)
        store.start(); runCurrent()
        api.send(hello); runCurrent()
        assertEquals(setOf("dev1", "dev2"), api.screenshots.toSet())
        assertEquals(setOf("dev1", "dev2"), store.thumbnails.value.keys)
        api.send(frame("state.changed", listOf("devices"), 1)); runCurrent()
        assertEquals(2, api.screenshots.size)
    }

    @Test fun screenshotDenialRaisesTheTeachingFlag() = runTest {
        for (devices in listOf(
            listOf(DeviceStatus("denied", null), DeviceStatus("ok", null)),
            listOf(DeviceStatus("ok", null), DeviceStatus("denied", null)),
        )) {
            val api = FakeApi(StatusResponse(devices, "/adb"))
            api.denied += "denied"
            val store = UiStore(api, backgroundScope)
            store.start(); runCurrent()
            api.send(hello); runCurrent()
            assertTrue(store.screenshotDenied.value)
            assertEquals(setOf("denied", "ok"), api.screenshots.toSet())
            assertEquals(setOf("ok"), store.thumbnails.value.keys)
        }
    }

    @Test fun transientScreenshotFailureDoesNotRaiseTheBanner() = runTest {
        val api = FakeApi(StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"))
        api.failing += "dev1"
        val store = UiStore(api, backgroundScope)
        store.start(); runCurrent()
        api.send(hello); runCurrent()
        assertFalse(store.screenshotDenied.value)   // a 404/502 is not a policy lesson
        assertTrue(store.thumbnails.value.isEmpty())

        api.failing -= "dev1"
        api.send(frame("state.changed", listOf("devices"), 1)); runCurrent()
        assertEquals(listOf("dev1", "dev1"), api.screenshots)   // retried on the next nudge
        assertEquals(setOf("dev1"), store.thumbnails.value.keys)
    }

    @Test fun failedThumbnailIsRetriedOnNextNudge() = runTest {
        val api = FakeApi(StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"))
        api.denied += "dev1"
        val store = UiStore(api, backgroundScope)
        store.start(); runCurrent()
        api.send(hello); runCurrent()
        assertTrue(store.screenshotDenied.value)
        assertTrue(store.thumbnails.value.isEmpty())
        assertEquals(listOf("dev1"), api.screenshots)

        api.denied -= "dev1"
        api.send(frame("state.changed", listOf("devices"), 1)); runCurrent()
        assertEquals(listOf("dev1", "dev1"), api.screenshots)
        assertEquals(setOf("dev1"), store.thumbnails.value.keys)
    }

    @Test fun successfulScreenshotClearsTheDenialBanner() = runTest {
        val api = FakeApi(StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"))
        api.denied += "dev1"
        val store = UiStore(api, backgroundScope)
        store.start(); runCurrent()
        api.send(hello); runCurrent()
        assertTrue(store.screenshotDenied.value)

        api.denied -= "dev1"
        api.send(frame("state.changed", listOf("devices"), 1)); runCurrent()
        assertTrue(store.thumbnails.value.containsKey("dev1"))
        assertFalse(store.screenshotDenied.value)
    }

    @Test fun ttlTickerAdvancesWithTime() = runTest {
        val api = FakeApi()
        val store = UiStore(api, backgroundScope) { testScheduler.currentTime }
        store.start(); runCurrent()
        advanceTimeBy(3000); runCurrent()
        assertEquals(3000L, store.now.value)
    }

    @Test fun forceFreeGuardsOnCapabilities() = runTest {
        val denied = FakeApi(caps = Capabilities(false))
        val store = UiStore(denied, backgroundScope)
        store.start(); runCurrent()
        assertFailsWith<IllegalStateException> { store.forceFree("dev1") }
        assertTrue(denied.forced.isEmpty())

        val allowed = FakeApi(caps = Capabilities(true))
        val store2 = UiStore(allowed, backgroundScope)
        store2.start(); runCurrent()
        store2.forceFree("dev1")
        assertEquals(listOf("dev1"), allowed.forced)
    }

    @Test fun droppedEventsReconnectAfterBackoffAndRefetch() = runTest {
        val api = FakeApi(StatusResponse(listOf(DeviceStatus("dev1", null)), "/adb"))
        api.failEvents = true
        val store = UiStore(api, backgroundScope)
        store.start(); runCurrent()
        assertTrue(store.connection.value is Connection.Disconnected)
        api.failEvents = false
        api.send(hello)
        advanceTimeBy(1000); runCurrent()
        assertTrue(store.connection.value is Connection.Connected)
        assertEquals(1, api.statusCalls)
        assertEquals(1, api.queueCalls)
    }
}
