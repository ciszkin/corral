package dev.corral.ui

import dev.corral.DeviceStatus
import dev.corral.EventFrame
import dev.corral.QueueEntry
import dev.corral.corralJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString

sealed interface Connection {
    data class Connected(val seq: Long) : Connection
    data object Connecting : Connection
    data class Disconnected(val sinceMs: Long) : Connection
}

/**
 * The UI's whole world: devices+leases, queue, connection, capabilities, thumbnails. Honors the
 * daemon's envelope contract literally — refetch on (re)connect, refetch the named resources,
 * unknown resource -> all, seq gap -> all, unknown types ignored. Reconnects with 1->5 s backoff;
 * thumbnails are raw bytes (decoding is rendering, so this class stays Compose-free).
 */
class UiStore(
    private val api: CorralApi,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _devices = MutableStateFlow(emptyList<DeviceStatus>())
    val devices: StateFlow<List<DeviceStatus>> = _devices.asStateFlow()
    private val _queue = MutableStateFlow(emptyList<QueueEntry>())
    val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()
    private val _connection = MutableStateFlow<Connection>(Connection.Connecting)
    val connection: StateFlow<Connection> = _connection.asStateFlow()
    private val _capabilities = MutableStateFlow(Capabilities(canForceFree = false))
    val capabilities: StateFlow<Capabilities> = _capabilities.asStateFlow()
    private val _thumbnails = MutableStateFlow(emptyMap<String, ByteArray>())
    val thumbnails: StateFlow<Map<String, ByteArray>> = _thumbnails.asStateFlow()
    private val _screenshotDenied = MutableStateFlow(false)
    val screenshotDenied: StateFlow<Boolean> = _screenshotDenied.asStateFlow()
    private val _now = MutableStateFlow(clock())
    val now: StateFlow<Long> = _now.asStateFlow()

    private var lastSeq = -1L
    private val thumbAttempted = mutableSetOf<String>()
    private val thumbLock = Mutex()

    fun start() {
        runCatching { api.capabilities() }.getOrNull()?.let { _capabilities.value = it }
        scope.launch { while (isActive) { _now.value = clock(); delay(1000) } }
        scope.launch { eventLoop() }
    }

    suspend fun forceFree(serial: String) {
        check(_capabilities.value.canForceFree) { "force-free is not permitted for this client" }
        api.forceFree(serial)
    }

    private suspend fun eventLoop() {
        var backoffMs = 1000L
        while (true) {
            _connection.value = Connection.Connecting
            try {
                api.events().collect { frame ->
                    onFrame(corralJson.decodeFromString<EventFrame>(frame))
                    backoffMs = 1000L
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // fall through to reconnect
            }
            _connection.value = Connection.Disconnected(clock())
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(5000L)
        }
    }

    private suspend fun onFrame(f: EventFrame) {
        when (f.type) {
            "hello" -> {
                lastSeq = f.seq
                _connection.value = Connection.Connected(f.seq)
                refetchAll()   // every (re)connect starts from fresh server state
            }
            "state.changed" -> {
                when {
                    gap(f.seq) -> refetchAll()
                    f.resources.isEmpty() -> refetchAll()
                    f.resources.any { it != "devices" && it != "queue" } -> refetchAll()
                    else -> {
                        if ("devices" in f.resources) refetchDevices()
                        if ("queue" in f.resources) refetchQueue()
                    }
                }
                lastSeq = f.seq
                _connection.value = Connection.Connected(f.seq)
            }
            else -> Unit   // unknown types are ignored — the envelope contract
        }
    }

    private fun gap(seq: Long) = lastSeq >= 0 && seq > lastSeq + 1

    private suspend fun refetchAll() { refetchDevices(); refetchQueue() }

    private suspend fun refetchDevices() {
        _devices.value = api.status().devices
        _devices.value.forEach { refreshThumbnail(it.serial) }
    }

    private suspend fun refreshThumbnail(serial: String) {
        if (!thumbLock.withLock { thumbAttempted.add(serial) }) return
        scope.launch {
            try {
                val png = api.screenshot(serial)
                _thumbnails.update { it + (serial to png) }
                // order-independent: clear only once every current device is covered
                if (_devices.value.all { d -> d.serial in _thumbnails.value }) _screenshotDenied.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScreenshotDeniedException) {
                thumbLock.withLock { thumbAttempted.remove(serial) }   // retry once the daemon's policy changes
                _screenshotDenied.value = true   // strict policy — the banner teaches the way out
            } catch (_: Exception) {
                thumbLock.withLock { thumbAttempted.remove(serial) }   // transient failure: allow a later nudge to retry
            }
        }
    }

    private suspend fun refetchQueue() { _queue.value = api.queue() }
}
