package dev.corral

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Self-heal for the SDK adb shim: an SDK update replaces platform-tools/adb with a fresh
 *  real binary, silently removing the wrapper (and with it, enforcement of absolute-path
 *  adb calls). The daemon detects that and restores the shim, stashing the newer binary as
 *  adb.real - so the updated adb is never lost and enforcement never silently disappears. */
/** The shim for a reported adb path: `…/adb.real` → its sibling `…/adb`; any other path passes
 *  through verbatim. String-based so a Windows-style path maps correctly on any host. */
internal fun shimPathOf(reported: String): String =
    if (reported.substringAfterLast('/').substringAfterLast('\\').endsWith(".real")) reported.removeSuffix(".real")
    else reported

object SdkShim {
    private const val MARKER = "corral adb wrapper"

    /** Resolve either spelling of the SDK adb pair to the real binary. A bare path counts as
     *  Corral's shim only when its `.real` sibling exists or it carries the marker; any other
     *  adb binary stays unmanaged. */
    private fun realPathOf(adb: Path): Path? {
        if (!adb.isAbsolute) return null
        val name = adb.fileName?.toString() ?: return null
        if (name.endsWith(".real")) return adb
        val real = adb.resolveSibling("$name.real")
        val shimText = runCatching { Files.readString(adb) }.getOrNull()
        return if (Files.exists(real) || shimText?.contains(MARKER) == true) real else null
    }

    private fun shimOf(realAdb: Path): Path =
        realAdb.resolveSibling(realAdb.fileName.toString().removeSuffix(".real"))

    /** @return a status message worth logging, or null when the shim is fine or unmanaged. */
    fun ensure(adb: Path, wrapperSource: Path?): String? {
        val realAdb = realPathOf(adb) ?: return null
        val shim = shimOf(realAdb)
        val shimText = runCatching { Files.readString(shim) }.getOrNull()
        if (shimText?.contains(MARKER) == true) {
            return if (Files.exists(realAdb)) null
            else "SDK adb shim at $shim is the wrapper but $realAdb is missing; re-run install.sh"
        }
        val wrapperText = wrapperSource?.let { src -> runCatching { Files.readString(src) }.getOrNull() }
            ?.takeIf { it.contains(MARKER) }
            ?: return "SDK adb shim at $shim is not the corral wrapper (re-run install.sh to repair)"
        val repaired = runCatching {
            if (shimText != null) Files.move(shim, realAdb, StandardCopyOption.REPLACE_EXISTING)
            val tmp = Files.createTempFile(shim.parent, "adb", ".shim")
            Files.writeString(tmp, wrapperText)
            tmp.toFile().setExecutable(true)
            Files.move(tmp, shim, StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
        return if (repaired) "repaired SDK adb shim at $shim (real binary kept as $realAdb)"
        else "SDK adb shim repair failed at $shim; re-run install.sh"
    }

    /** Read-only health check for /api/doctor — never repairs (that is [ensure]'s job on the 10 s tick). */
    fun probe(adb: Path): Pair<String, String> {
        val realAdb = realPathOf(adb)
        if (realAdb == null) {
            // unmanaged path: legitimate when it exists (a bare PATH adb); a configured absolute
            // path that does not exist means discovery is broken — doctor must say so on every surface
            return if (adb.isAbsolute && !Files.exists(adb))
                "fail" to "$adb does not exist — the daemon's device discovery is broken (fix CORRAL_ADB)"
            else "skipped" to "unmanaged adb path (${shimPathOf(adb.toString())})"
        }
        val shim = shimOf(realAdb)
        val shimText = runCatching { Files.readString(shim) }.getOrNull()
            ?: return "fail" to "$shim missing (re-run install.sh)"
        if (!shimText.contains(MARKER)) return "fail" to "$shim is not the corral wrapper (re-run install.sh)"
        if (!Files.isExecutable(realAdb))
            return "fail" to "shim ok but $realAdb is missing or not executable (re-run install.sh)"
        return "ok" to "$shim -> adb.real"
    }
}