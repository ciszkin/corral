package dev.corral

import java.nio.file.Files
import java.nio.file.Path

/** A user-scoped daemon can bind only 1024–65535. */
fun parsePort(raw: String): Int? = raw.toIntOrNull()?.takeIf { it in 1024..65535 }

/** A bare command name (no separator) is allowed; a path-shaped value must be absolute and executable. */
fun adbValueUsable(value: String): Boolean =
    (!value.contains('/') && !value.contains('\\')) ||
        (runCatching { Path.of(value).isAbsolute && Files.isExecutable(Path.of(value)) }.getOrDefault(false))

/** An absolute path whose nearest existing ancestor is a writable directory. */
fun dbPathUsable(value: String): Boolean {
    val p = runCatching { Path.of(value) }.getOrNull() ?: return false
    if (!p.isAbsolute || Files.isDirectory(p)) return false
    var anc = p.parent ?: return false
    while (!Files.exists(anc)) anc = anc.parent ?: return false
    return Files.isDirectory(anc) && Files.isWritable(anc)
}
