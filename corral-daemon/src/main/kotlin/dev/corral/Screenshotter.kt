package dev.corral

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** How long a completed capture answers newer requests without spawning again. */
const val SCREENSHOT_WINDOW_MS = 1000L

sealed class CaptureException(message: String) : Exception(message) {
    class UnknownDevice(serial: String) : CaptureException("device $serial is not connected")
    class UnusableDevice(serial: String, state: String) :
        CaptureException("device $serial is $state; screenshots need a usable device")
    class AdbFailure(reason: String) : CaptureException(reason)
}

/**
 * Runs `adb -s <serial> exec-out screencap -p` on demand. Single-flight per serial (concurrent
 * callers share one process) plus a [SCREENSHOT_WINDOW_MS] coalescing window — staleness stays
 * below the operation's own latency, so no caller observes a lie. Read-only: never consults
 * leases (access policy is [ScreenshotPolicy], decided by the callers).
 */
class Screenshotter(
    private val devices: () -> List<Device>,
    private val adbPath: String?,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val state = Mutex()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()
    private var cachedSerial: String? = null
    private var cachedAt = 0L
    private var cachedPng: ByteArray? = null

    suspend fun capture(serial: String): ByteArray {
        val d = devices().firstOrNull { it.serial == serial } ?: throw CaptureException.UnknownDevice(serial)
        if (d.state != "device") throw CaptureException.UnusableDevice(serial, d.state)

        var join: CompletableDeferred<ByteArray>? = null
        var mine: CompletableDeferred<ByteArray>? = null
        state.withLock {
            val fresh = cachedPng?.takeIf { cachedSerial == serial && now() - cachedAt <= SCREENSHOT_WINDOW_MS }
            if (fresh != null) return fresh
            inFlight[serial]?.let { join = it } ?: run { mine = CompletableDeferred(); inFlight[serial] = mine!! }
        }
        join?.let { return it.await() }
        val def = mine!!
        return try {
            val png = execScreencap(serial)
            val at = now()
            state.withLock { cachedSerial = serial; cachedPng = png; cachedAt = at; inFlight.remove(serial) }
            def.complete(png); png
        } catch (e: Throwable) {
            inFlight.remove(serial)
            def.completeExceptionally(e); throw e
        }
    }

    private suspend fun execScreencap(serial: String): ByteArray = withContext(Dispatchers.IO) {
        val p = ProcessBuilder(adbPath ?: "adb", "-s", serial, "exec-out", "screencap", "-p").start()
        val out = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { p.destroy() }
            cont.resumeWith(Result.success(p.inputStream.readBytes()))
        }
        val err = p.errorStream.bufferedReader().readText().lineSequence()
            .firstOrNull { it.isNotBlank() } ?: ""
        if (p.waitFor() != 0 || out.isEmpty())
            throw CaptureException.AdbFailure("screencap failed for $serial${if (err.isBlank()) "" else ": $err"}")
        out
    }
}
