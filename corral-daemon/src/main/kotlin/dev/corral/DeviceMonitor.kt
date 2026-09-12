package dev.corral

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import kotlin.coroutines.coroutineContext

object TrackParser {
    fun parse(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>(); var i = 0
        while (i + 4 <= text.length) {
            val len = text.substring(i, i + 4).toIntOrNull(16) ?: break
            if (i + 4 + len > text.length) break
            i += 4
            val body = text.substring(i, i + len); i += len
            body.trim().ifEmpty { continue }.lines().forEach { line ->
                val (serial, state) = line.split('\t', limit = 2) + listOf("")
                out += serial to state
            }
        }
        return out
    }
}

/**
 * Keeps a volatile inventory from `adb track-devices` (or a fake file — test seam).
 * Restarts the subprocess with 2s backoff on death. Enriches new serials via getprop.
 * ponytail: getprop is best-effort; missing model is cosmetic, not fatal.
 */
class DeviceMonitor(private val adbPath: String,
                    private val fakeDevicesFile: String? = null,
                    private val scope: CoroutineScope) {
    @Volatile var devices: List<Device> = emptyList(); private set

    fun start() = scope.launch {
        var proc: Process? = null
        coroutineContext[Job]?.invokeOnCompletion { proc?.destroy() }
        while (true) {
            try {
                val stream: InputStream = if (fakeDevicesFile != null) fakeStream() else {
                    proc = ProcessBuilder(adbPath, "track-devices").start()
                    proc!!.inputStream
                }
                stream.use { readLoop(it) }
            } catch (_: Exception) { /* subprocess died or file replaced; loop */ }
            delay(2000)
        }
    }

    private fun fakeStream(): InputStream {
        val f = File(fakeDevicesFile!!)
        val body = if (f.exists()) f.readText().trim().lines()
            .joinToString("") { "${it.replace(' ', '\t')}\n" } else ""
        // Frame the raw text as ONE track-devices message so readLoop can parse it.
        return (body.length.toString(16).padStart(4, '0') + body).byteInputStream()
    }

    private fun readLoop(stream: InputStream) {
        val reader = stream.bufferedReader()
        while (true) {
            val head = reader.read4Hex() ?: break
            val body = reader.readNChars(head) ?: break
            devices = TrackParser.parse(head.toString(16).padStart(4, '0') + body).map { (s, st) ->
                devices.firstOrNull { it.serial == s }?.copy(state = st) ?: Device(s, st)
            }
            devices.filter { it.state == "device" && it.model == null }.forEach { enrich(it.serial) }
        }
    }

    private fun enrich(serial: String) = runCatching {
        val p = ProcessBuilder(adbPath, "-s", serial, "shell",
            "getprop ro.product.model; getprop ro.build.version.release").start()
        val out = p.inputStream.bufferedReader().readText().lines().filter { it.isNotBlank() }
        if (out.size == 2) devices = devices.map {
            if (it.serial == serial) it.copy(model = out[0].trim(), osVersion = out[1].trim()) else it }
    }

    private fun BufferedReader.read4Hex(): Int? {
        val h = readNChars(4) ?: return null
        return h.toIntOrNull(16)
    }
    private fun BufferedReader.readNChars(n: Int): String? {
        val cb = CharArray(n); var read = 0
        while (read < n) { val c = read(cb, read, n - read); if (c == -1) return null; read += c }
        return String(cb)
    }
}
