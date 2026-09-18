package dev.corral

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The lists the bash wrapper and the CLI must agree on. */
data class GateRules(
    val readonly: Set<String>,
    val valueFlags: Set<String>,
) {
    companion object { val EMPTY = GateRules(emptySet(), emptySet()) }
}

/** Loads, parses and self-heals ~/.config/corral/rules (see `scripts/corral-rules`). */
object RulesFile {
    const val RESOURCE = "/corral-rules"

    /** `CORRAL_RULES` override, else `~/.config/corral/rules` — the gradle-tasks convention. */
    fun defaultPath(): Path =
        System.getenv("CORRAL_RULES")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".config", "corral", "rules")

    /** Comma-separated `key=value`; `#` starts a comment; missing keys are empty (fail closed). */
    fun parse(text: String): GateRules {
        val keys = text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() && it.contains('=') }
            .associate {
                it.substringBefore('=').trim().lowercase() to
                    it.substringAfter('=').split(',').map(String::trim).filter(String::isNotEmpty).toSet()
            }
        return GateRules(keys["readonly"] ?: emptySet(), keys["valueflags"] ?: emptySet())
    }

    fun load(path: Path = defaultPath()): GateRules =
        runCatching { Files.readString(path) }.getOrNull()?.let(::parse) ?: GateRules.EMPTY

    /**
     * Restore the factory default from the jar resource when the file is absent or blank.
     * A present, non-blank file is user intent and is never overwritten. Mirrors SdkShim.
     * @return a status worth logging once, or null when nothing needed doing.
     */
    fun ensure(path: Path = defaultPath()): String? {
        val existing = runCatching { Files.readString(path) }
        if (existing.isSuccess) {
            if (existing.getOrNull()?.isNotBlank() == true) return null   // present user file: never touched
        } else if (Files.exists(path)) {
            return "gate rules at $path are unreadable; re-run install.sh"
        }
        val text = RulesFile::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader()?.use { it.readText() }
            ?: return "gate rules missing at $path and the jar has no default; re-run install.sh"
        var tmp: Path? = null
        val ok = runCatching {
            path.parent?.let { Files.createDirectories(it) }
            tmp = Files.createTempFile(path.parent ?: Path.of("."), "rules", ".tmp")
            Files.writeString(tmp, text)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrElse {
            tmp?.let { t -> runCatching { Files.deleteIfExists(t) } }
            false
        }
        return if (ok) "restored gate rules at $path" else "could not write gate rules at $path; re-run install.sh"
    }
}
