package dev.corral

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EventsTest {
    private var devices = listOf(DeviceStatus("dev1", null))
    private var queue = listOf<QueueEntry>()
    private val nudges = Channel<String>(Channel.UNLIMITED)

    private fun events(tb: TestScope) =
        Events({ devices }, { queue }, tb.backgroundScope, tickMs = 100).also { e ->
            e.start()
            e.flow.onEach { nudges.send(it) }.launchIn(tb.backgroundScope)
        }

    private fun TestScope.tick(n: Int = 1) {
        repeat(n) { advanceTimeBy(100); runCurrent() }
    }

    @Test fun claimNudgesDevicesOnce() = runTest {
        events(this)
        runCurrent()                                    // baseline tick, nothing to report yet
        devices = listOf(DeviceStatus("dev1", Lease("dev1", "a", 0, 99)))
        tick()
        val frame = nudges.receive()
        assertTrue(frame.contains("state.changed") && frame.contains("\"devices\""))
        assertTrue(nudges.isEmpty)
    }

    @Test fun queueChangeNudgesQueue() = runTest {
        events(this)
        runCurrent()
        queue = listOf(QueueEntry(1, "w1", "[any]", 0, 600))
        tick()
        val frame = nudges.receive()
        assertTrue(frame.contains("\"queue\"") && frame.contains("state.changed"))
        assertTrue(!frame.contains("\"devices\""))
        assertTrue(nudges.isEmpty)
    }

    @Test fun queueElapsedFieldsNeverNudge() = runTest {
        events(this)
        runCurrent()
        queue = listOf(QueueEntry(1, "w1", "[any]", 0, 600))
        tick()
        nudges.receive()                                // the enqueue nudge
        queue = listOf(QueueEntry(1, "w1", "[any]", waitedSeconds = 45, deadlineIn = 555))
        tick(10)
        assertTrue(nudges.isEmpty)                      // <- the regression this test exists for
    }

    @Test fun noChangeIsSilent() = runTest {
        events(this)
        runCurrent()
        tick(20)
        assertTrue(nudges.isEmpty)
    }

    @Test fun seqIsMonotonicAndHelloCarriesIt() = runTest {
        val e = events(this)
        runCurrent()
        assertTrue(e.helloFrame().contains("\"type\":\"hello\"") && e.helloFrame().contains("\"seq\":0"))
        devices = listOf(DeviceStatus("dev1", Lease("dev1", "a", 0, 99)))
        tick()
        assertTrue(nudges.receive().contains("\"seq\":1"))
        assertEquals(1, e.seq)
    }

    @Test fun bootFlipFiresDevicesNudge() = runTest {
        devices = listOf(DeviceStatus("emulator-5554", null, booted = false))
        events(this)
        runCurrent()
        devices = listOf(DeviceStatus("emulator-5554", null, booted = true))
        tick()
        val frame = nudges.receive()
        assertTrue(frame.contains("state.changed") && frame.contains("\"devices\""))
        assertTrue(nudges.isEmpty)
    }
}
