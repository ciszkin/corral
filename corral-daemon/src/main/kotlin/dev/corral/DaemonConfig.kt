package dev.corral

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable

/** KEY=VALUE lines with # comments; unknown keys ride along untouched (other tools' lines survive). */
object EnvFile {
    fun read(path: Path): Map<String, String> = runCatching {
        Files.readAllLines(path).mapNotNull { line ->
            val l = line.substringBefore('#').trim()
            if (!l.contains('=')) null
            else l.substringBefore('=').trim() to l.substringAfter('=').trim().unquote()
        }.toMap()
    }.getOrDefault(emptyMap())

    /** systemd strips one matching pair of surrounding quotes; match that so EnvironmentFile values agree. */
    private fun String.unquote(): String =
        if (length >= 2 && ((first() == '"' && last() == '"') || (first() == '\'' && last() == '\'')))
            substring(1, length - 1)
        else this

    /** Upsert [values]; every other line (comments, unknown keys) stays verbatim. Atomic. */
    fun write(path: Path, values: Map<String, String>) {
        val existing = if (Files.exists(path)) Files.readAllLines(path) else emptyList()
        val out = existing.filter { line ->
            line.substringBefore('#').substringBefore('=').trim() !in values.keys
        }.toMutableList()
        values.forEach { (k, v) -> out.add("$k=$v") }
        val dir = path.parent ?: Path.of(".")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "daemon.env", ".tmp")
        try {
            Files.write(tmp, out)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }
}

@Serializable
data class ConfigKey(val key: String, val default: String, val restartRequired: Boolean = true)

@Serializable
data class ConfigSetting(val key: String, val value: String, val default: String, val restartRequired: Boolean)

/** Daemon knobs resolved as file > inherited env > default — the file is deliberate, edited config. */
class DaemonConfig(
    private val fileValues: Map<String, String> = EnvFile.read(defaultPath()),
    private val inherited: Map<String, String> = System.getenv(),
) {
    /** Whether a file-sourced [value] is usable for [key]; unknown keys are always usable. */
    fun usable(key: String, value: String): Boolean = when (key) {
        "CORRAL_PORT" -> parsePort(value) != null
        "CORRAL_DB" -> dbPathUsable(value)
        "CORRAL_ADB" -> adbValueUsable(value)
        "CORRAL_EMULATOR" -> adbValueUsable(value)
        "CORRAL_EMULATOR_LAUNCHER" -> value.trim().lowercase() in setOf("", "systemd", "direct")
        "CORRAL_SCREENSHOTS" -> value.trim().lowercase() in setOf("strict", "open")
        else -> true
    }

    fun get(key: String, useFile: Boolean = true): String {
        val def = KEYS.firstOrNull { it.key == key }?.default ?: return inherited[key] ?: ""
        fun blankOk(v: String?) = v?.takeIf { it.isNotBlank() || def.isBlank() }
        val file = if (useFile) blankOk(fileValues[key])?.takeIf { usable(key, it) } else null
        return file ?: blankOk(inherited[key]) ?: def
    }

    /** The built-in default for a registry key ("" for unknown keys). */
    fun defaultValue(key: String): String = KEYS.firstOrNull { it.key == key }?.default ?: ""

    /** The raw value from the config file, or null when the key is absent. */
    fun fileValue(key: String): String? = fileValues[key]

    fun settings(): List<ConfigSetting> = KEYS.map {
        ConfigSetting(it.key, get(it.key), it.default, it.restartRequired)
    }

    companion object {
        /** CORRAL_ENV_FILE override — the CORRAL_RULES convention; suites redirect it to stay hermetic. */
        fun defaultPath(): Path =
            System.getenv("CORRAL_ENV_FILE")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".config", "corral", "daemon.env")

        val KEYS = listOf(
            ConfigKey("CORRAL_PORT", "8798"),
            ConfigKey("CORRAL_SCREENSHOTS", "strict"),
            ConfigKey("CORRAL_SCREENSHOT_OBSERVERS", ""),
            ConfigKey("CORRAL_ADB", "adb"),
            ConfigKey("CORRAL_EMULATOR", ""),
            ConfigKey("CORRAL_EMULATOR_LAUNCHER", ""),
            ConfigKey("CORRAL_DB", Path.of(System.getProperty("user.home"), ".corral", "corral.db").toString()),
            ConfigKey("CORRAL_GRADLE_TASKS", ""),
        )
    }
}
