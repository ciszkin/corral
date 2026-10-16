package dev.corral.ui

import dev.corral.Device
import dev.corral.QueueEntry
import dev.corral.StatusResponse
import dev.corral.UpdateInfo
import dev.corral.UpdateState
import dev.corral.ui.data.ConfigResponse
import dev.corral.ui.data.ConfigRules
import dev.corral.ui.data.DoctorResponse
import dev.corral.ui.data.PutConfigResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Client mirror of the daemon's `/api/emulators` row. `error` is the last log line, set only when the AVD is stopped. */
@Serializable
data class AvdStatus(val avd: String, val runningSerials: List<String>, val error: String? = null)

/** The daemon's last error line, or null when absent or blank. */
val AvdStatus.lastError: String? get() = error?.takeIf { it.isNotBlank() }

/** What the UI may do — v1 is the single-operator world; the future swaps the provider for /api/whoami. */
data class Capabilities(val canForceFree: Boolean)

/**
 * The daemon's 403 on screenshots: this owner is not in CORRAL_SCREENSHOT_OBSERVERS. Everything else is
 * transient. [reason] is the daemon's own denial text (naming the identity it saw), when the body was readable.
 */
class ScreenshotDeniedException(val reason: String? = null, cause: Throwable? = null) :
    Exception("screenshots denied by daemon policy", cause)

/** The daemon's 400 on a config PUT, carrying the per-key validation messages. */
class ConfigApiException(val errors: List<String>, cause: Throwable? = null) :
    Exception(errors.joinToString("; "), cause)

/** The daemon surface the UI consumes. Fake it in tests; `CorralClient` is the only real impl. */
interface CorralApi {
    suspend fun status(): StatusResponse
    suspend fun devices(): List<Device>
    suspend fun queue(): List<QueueEntry>
    suspend fun screenshot(serial: String): ByteArray
    suspend fun forceFree(serial: String)
    suspend fun config(): ConfigResponse
    suspend fun putConfig(settings: Map<String, String>, rules: ConfigRules?): Result<PutConfigResponse>
    /** The daemon's cached update check; [force] runs a fresh check instead of the cached one. */
    suspend fun update(force: Boolean = false): UpdateInfo =
        UpdateInfo(currentVersion = "", state = UpdateState.DISABLED)
    suspend fun doctor(): DoctorResponse
    suspend fun emulators(): List<AvdStatus>
    suspend fun emulatorStart(avd: String)
    suspend fun emulatorStop(avd: String, force: Boolean)
    /** One connection's frames; completes/throws on disconnect — reconnection is the store's job. */
    fun events(): Flow<String>
    fun capabilities(): Capabilities
    fun close() {}
}
