package dev.corral.ui.config

import dev.corral.currentConsoleArgv
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** The console jar the launcher would run next: CORRAL_UI_JAR, else the installed default. */
internal fun consoleJarPath(
    home: Path = Path.of(System.getProperty("user.home")),
    env: (String) -> String? = System::getenv,
): Path = env("CORRAL_UI_JAR")?.takeIf { it.isNotBlank() }?.let(Path::of)
    ?: home.resolve(".corral").resolve("corral-ui-all.jar")

/**
 * The `corral-version` stamped into [jar], or null when the file is missing, is not a zip, or has
 * no such entry — an unreadable jar is no claim about what is installed.
 */
internal fun jarVersion(jar: Path): String? = runCatching {
    if (!Files.isRegularFile(jar)) return@runCatching null
    ZipFile(jar.toFile()).use { zip ->
        zip.getEntry("corral-version")?.let { entry ->
            zip.getInputStream(entry).bufferedReader().use { it.readText() }.trim().takeIf { it.isNotBlank() }
        }
    }
}.getOrNull()

/** An untagged build reports this; it is never a reason to replace a window. */
internal const val DEV_VERSION = "0.0.0-dev"

/**
 * Relaunch only when the jar on disk is exactly the release just installed and this process is
 * still the old code. A dev tree, an unrelated daemon restart, or a jar that is not the target
 * leaves the window alone for the version-mismatch hint.
 */
internal fun shouldRelaunch(runningVersion: String, target: String, onDiskVersion: String?): Boolean {
    val wanted = target.trim().removePrefix("v").removePrefix("V")
    val onDisk = onDiskVersion?.trim()?.removePrefix("v")?.removePrefix("V")
    val running = runningVersion.trim()
    if (wanted.isBlank() || onDisk.isNullOrBlank()) return false
    if (running == DEV_VERSION) return false
    return onDisk == wanted && running != wanted
}

/** Derives this console's argv and starts it detached; false when it cannot be derived or started. */
internal fun relaunchCurrentConsole(): Boolean =
    currentConsoleArgv()?.let(UpdateLaunch::relaunch) ?: false
