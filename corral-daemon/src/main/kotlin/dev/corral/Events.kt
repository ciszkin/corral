package dev.corral

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Coarse change nudges over the same shapes REST serves: a tick diffs the devices+lease view
 * and the queue (stable fields only — waitedSeconds/deadlineIn are clock-derived and would
 * fire every tick) and bumps a monotonic [seq]. Envelope contract: additive-only — clients
 * ignore unknown types and treat unknown resources as refetch-all; a seq gap after reconnect
 * means refetch everything.
 */
class Events(
    private val devicesView: () -> List<DeviceStatus>,
    private val queueView: suspend () -> List<QueueEntry>,
    private val scope: CoroutineScope,
    private val tickMs: Long = 500,
) {
    private val json = Json { encodeDefaults = true }
    private val _flow = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<String> = _flow
    private val counter = AtomicLong(0)
    val seq: Long get() = counter.get()

    fun helloFrame(): String = json.encodeToString(EventFrame("hello", listOf("devices", "queue"), seq))

    fun start() {
        scope.launch {
            var primed = false
            var lastDevices = listOf<DeviceStatus>()
            var lastQueue = listOf<Triple<Int, String, String>>()
            var lastError: String? = null
            while (isActive) {
                try {
                    val dv = devicesView()
                    val qv = queueView().map { Triple(it.position, it.owner, it.selector) }
                    val changed = mutableListOf<String>()
                    if (dv != lastDevices) { changed += "devices"; lastDevices = dv }
                    if (qv != lastQueue) { changed += "queue"; lastQueue = qv }
                    if (primed && changed.isNotEmpty())
                        _flow.emit(json.encodeToString(EventFrame("state.changed", changed, counter.incrementAndGet())))
                    primed = true
                    lastError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.toString() != lastError) { println("corral: events tick failed: $e"); lastError = e.toString() }
                }
                delay(tickMs)
            }
        }
    }
}
