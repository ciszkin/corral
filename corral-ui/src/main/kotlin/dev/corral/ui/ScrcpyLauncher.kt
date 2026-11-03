package dev.corral.ui

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spawns scrcpy for a live view. Strictly read-only observation — no lease needed, by the
 * design: `--no-control` also drops drag-drop install/push, `--no-clipboard-autosync` closes
 * the clipboard channel and `--no-audio` keeps it visual. An observer must never be able to
 * interact with a device an agent holds.
 */
class ScrcpyLauncher(
    private val env: Map<String, String> = System.getenv(),
    private val savedPath: () -> String = { "" },
) {
    /** Suspends through the early-exit window on Dispatchers.IO — safe to call from the UI thread. */
    suspend fun launch(serial: String): Process = withContext(Dispatchers.IO) {
        val bin = scrcpyBinary(savedPath(), env)
        val process = try {
            ProcessBuilder(bin, "-s", serial, "--no-control", "--no-clipboard-autosync", "--no-audio")
                .inheritIO().start()
        } catch (e: IOException) {
            throw IllegalStateException(
                "scrcpy not found ($bin) — install it, set CORRAL_SCRCPY in Settings, " +
                    "or point CORRAL_SCRCPY at the binary", e)
        }
        // A scrcpy that starts and dies at once — denied by the adb gate, device offline, server
        // push rejected — would otherwise look like success: inheritIO sends its complaint to the
        // UI's stdout, which a desktop launch buries in the journal.
        if (process.waitFor(EARLY_EXIT_MS, TimeUnit.MILLISECONDS) && process.exitValue() != 0) {
            throw IllegalStateException(
                "scrcpy exited immediately (code ${process.exitValue()}) — " +
                    "run `$bin -s $serial` manually to see why")
        }
        process
    }

    private companion object {
        const val EARLY_EXIT_MS = 700L
    }
}

/** Saved preference first, then the console's environment, then the bare binary on PATH. */
internal fun scrcpyBinary(savedPath: String, env: Map<String, String>): String =
    savedPath.takeIf { it.isNotBlank() }
        ?: env["CORRAL_SCRCPY"]?.takeIf { it.isNotBlank() }
        ?: "scrcpy"
