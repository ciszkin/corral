package dev.corral.ui.pool

import dev.corral.Device
import dev.corral.Lease
import dev.corral.QueueEntry
import dev.corral.ui.Capabilities
import dev.corral.ui.architecture.MviEffect
import dev.corral.ui.architecture.MviIntent
import dev.corral.ui.architecture.MviState
import dev.corral.ui.data.DoctorResponse

data class DeviceView(val d: Device, val lease: Lease?)

data class PoolState(
    val devices: List<DeviceView> = emptyList(),
    val queue: List<QueueEntry> = emptyList(),
    val doctor: DoctorResponse? = null,
    val thumbnails: Map<String, ByteArray?> = emptyMap(),
    val denied: Boolean = false,
    val confirmFree: String? = null,
    val capabilities: Capabilities = Capabilities(false),
    val now: Long = 0,
) : MviState

sealed interface PoolIntent : MviIntent {
    data object RefreshDevices : PoolIntent
    data object RefreshQueue : PoolIntent
    data object RefreshDoctor : PoolIntent
    data class FetchThumbnail(val serial: String) : PoolIntent
    data class AskForceFree(val serial: String) : PoolIntent
    data object ConfirmForceFree : PoolIntent
    data object CancelForceFree : PoolIntent
    data class Tick(val nowMs: Long) : PoolIntent
}

sealed interface PoolEffect : MviEffect {
    data class ScrcpyFailed(val serial: String, val reason: String) : PoolEffect
    data class ForceFreeFailed(val reason: String) : PoolEffect
}
