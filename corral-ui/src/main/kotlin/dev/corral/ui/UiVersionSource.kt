package dev.corral.ui

/**
 * The console's OWN version, read once from the `/corral-version` resource the UI build stamps
 * into the UI jar. Named distinctly from the daemon's `dev.corral.VersionSource` so the two are
 * obvious in a stack trace — the two jars are installed and updated separately and can disagree.
 */
object UiVersionSource {
    val version: String = readVersion()

    private fun readVersion(): String = runCatching {
        UiVersionSource::class.java.getResourceAsStream("/corral-version")
            ?.bufferedReader()?.use { it.readText() }?.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"
}
