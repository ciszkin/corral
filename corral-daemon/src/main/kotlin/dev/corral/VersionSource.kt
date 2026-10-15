package dev.corral

/**
 * The daemon's own version, read once from the `/corral-version` resource the build stamps into
 * the jar. Reading is a class-init side effect, so a missing or unreadable resource must yield the
 * fallback rather than throw and break the daemon.
 */
object VersionSource {
    val version: String = readVersion()

    private fun readVersion(): String = runCatching {
        VersionSource::class.java.getResourceAsStream("/corral-version")
            ?.bufferedReader()?.use { it.readText() }?.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"
}
