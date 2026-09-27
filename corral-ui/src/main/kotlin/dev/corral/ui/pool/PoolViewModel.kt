package dev.corral.ui.pool

import dev.corral.EventFrame
import dev.corral.ui.CorralApi
import dev.corral.ui.ScreenshotDeniedException
import dev.corral.ui.app.AppViewModel
import dev.corral.ui.architecture.BaseMviViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PoolViewModel(
    private val api: () -> CorralApi?,
    app: AppViewModel,
    vmScope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : BaseMviViewModel<PoolIntent, PoolState, PoolEffect>(vmScope) {

    private val thumbAttempted = mutableSetOf<String>()
    private val thumbLock = Mutex()

    init {
        reduceState {
            copy(now = clock(), capabilities = api()?.capabilities() ?: capabilities)
        }
        vmScope.launch {
            app.frames.collect { frame ->
                try {
                    onFrame(frame)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun initialState() = PoolState()

    override fun onIntent(intent: PoolIntent) {
        when (intent) {
            PoolIntent.RefreshDevices -> fetch { refetchDevices() }
            PoolIntent.RefreshQueue -> fetch { refetchQueue() }
            PoolIntent.RefreshDoctor -> fetch { refetchDoctor() }
            is PoolIntent.FetchThumbnail -> vmScope.launch { ensureThumbnail(intent.serial) }
            is PoolIntent.AskForceFree -> reduceState { copy(confirmFree = intent.serial) }
            PoolIntent.ConfirmForceFree -> confirmForceFree()
            PoolIntent.CancelForceFree -> reduceState { copy(confirmFree = null) }
            is PoolIntent.Tick -> reduceState { copy(now = intent.nowMs) }
        }
    }

    fun scrcpyFailed(serial: String, reason: String) {
        sendEffect(PoolEffect.ScrcpyFailed(serial, reason))
    }

    private fun fetch(block: suspend () -> Unit) {
        vmScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun onFrame(f: EventFrame) {
        when (f.type) {
            "hello" -> refetchAll()
            "state.changed" -> when {
                f.resources.isEmpty() -> refetchAll()
                f.resources.any { it != "devices" && it != "queue" } -> refetchAll()
                else -> {
                    if ("devices" in f.resources) refetchDevices()
                    if ("queue" in f.resources) refetchQueue()
                }
            }
        }
    }

    private suspend fun refetchAll() {
        refetchDevices()
        refetchQueue()
    }

    private suspend fun refetchDevices() {
        val client = api() ?: return
        val leases = client.status().devices.associate { it.serial to it.lease }
        val devices = client.devices()
        reduceState {
            copy(
                devices = devices.map { DeviceView(it, leases[it.serial]) },
                capabilities = client.capabilities(),
            )
        }
        devices.forEach { ensureThumbnail(it.serial) }
        refetchDoctor()
    }

    private suspend fun refetchQueue() {
        val client = api() ?: return
        val queue = client.queue()
        reduceState { copy(queue = queue) }
    }

    private suspend fun refetchDoctor() {
        val client = api() ?: return
        runCatching { client.doctor() }.getOrNull()?.let { d -> reduceState { copy(doctor = d) } }
    }

    private fun confirmForceFree() {
        val serial = state.value.confirmFree ?: return
        if (!state.value.capabilities.canForceFree) {
            reduceState { copy(confirmFree = null) }
            return
        }
        vmScope.launch {
            val client = api() ?: return@launch
            try {
                client.forceFree(serial)
                reduceState { copy(confirmFree = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendEffect(PoolEffect.ForceFreeFailed(e.message.orEmpty()))
            }
        }
    }

    private suspend fun ensureThumbnail(serial: String) {
        if (!thumbLock.withLock { thumbAttempted.add(serial) }) return
        vmScope.launch {
            try {
                val png = api()?.screenshot(serial) ?: return@launch
                reduceState { copy(thumbnails = thumbnails + (serial to png)) }
                if (state.value.devices.all { it.d.serial in state.value.thumbnails }) {
                    reduceState { copy(denied = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScreenshotDeniedException) {
                thumbLock.withLock { thumbAttempted.remove(serial) }
                reduceState { copy(denied = true) }
            } catch (_: Exception) {
                thumbLock.withLock { thumbAttempted.remove(serial) }
            }
        }
    }
}
