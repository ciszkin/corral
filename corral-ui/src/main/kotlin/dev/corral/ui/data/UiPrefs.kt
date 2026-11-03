package dev.corral.ui.data

import dev.corral.ui.theme.ThemeMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Prefs(
    val url: String = "http://127.0.0.1:8798",
    val owner: String = "corral-ui",
    val scrcpyPath: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val windowX: Int = -1,
    val windowY: Int = -1,
    val windowW: Int = 1100,
    val windowH: Int = 700,
)

class UiPrefs(
    private val path: Path = Path.of(System.getProperty("user.home"), ".config", "corral", "ui.conf"),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): Prefs = runCatching {
        json.decodeFromString(Prefs.serializer(), Files.readString(path))
    }.getOrDefault(Prefs())

    fun save(p: Prefs) {
        val dir = path.parent ?: Path.of(".")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "ui.conf", ".tmp")
        try {
            Files.writeString(tmp, json.encodeToString(Prefs.serializer(), p))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }
}
