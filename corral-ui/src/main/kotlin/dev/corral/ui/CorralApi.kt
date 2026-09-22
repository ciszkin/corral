package dev.corral.ui

import dev.corral.QueueEntry
import dev.corral.StatusResponse
import kotlinx.coroutines.flow.Flow

/** What the UI may do — v1 is the single-operator world; the future swaps the provider for /api/whoami. */
data class Capabilities(val canForceFree: Boolean)

/** The daemon's 403 on screenshots: this owner is not in CORRAL_SCREENSHOT_OBSERVERS. Everything else is transient. */
class ScreenshotDeniedException(cause: Throwable? = null) :
    Exception("screenshots denied by daemon policy", cause)

/** The daemon surface the UI consumes. Fake it in tests; `CorralClient` is the only real impl. */
interface CorralApi {
    suspend fun status(): StatusResponse
    suspend fun queue(): List<QueueEntry>
    suspend fun screenshot(serial: String): ByteArray
    suspend fun forceFree(serial: String)
    /** One connection's frames; completes/throws on disconnect — reconnection is the store's job. */
    fun events(): Flow<String>
    fun capabilities(): Capabilities
}
