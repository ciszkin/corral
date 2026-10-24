package dev.corral

import java.io.File
import java.lang.ProcessHandle

/** Executable basename as the agent label: `C:\x\claude.exe` → `claude.exe`, `/usr/bin/claude` → `claude`. */
internal fun commOf(command: String): String =
    command.substringAfterLast('/').substringAfterLast('\\')

/**
 * Membership key for the identity lists: lowercased basename with ONE trailing `.exe` stripped.
 *
 * Windows image names keep the extension (`bash.exe`) while the lists are written per program
 * (`bash`), so keying BOTH sides of the comparison means either spelling matches and no entry is
 * a dead entry on one platform. Keying only the comm would leave the `cmd.exe`-style entries dead
 * in the other direction. The identity itself still carries the comm verbatim ([commOf]) — this
 * is a comparison rule, not a rename, so no stored data changes.
 */
internal fun commKey(comm: String): String = comm.lowercase().removeSuffix(".exe")

/** Walk cap: a cyclic or absurd chain gives up (→ user:$USER) instead of spinning. */
private const val MAX_ANCESTRY_DEPTH = 128

/**
 * Walk the process chain from [self]'s parent, returning the first non-shell ancestor as
 * (comm, pid, start), or null at the init floor / when the chain is unreadable (→ user:$USER).
 * Pure over [parentOf] and [infoOf]; the skip/floor sets are injectable so the caller decides
 * whether they come from the rules file or the defaults.
 */
internal fun pickAgentAncestor(
    self: Long,
    parentOf: (Long) -> Long?,
    infoOf: (Long) -> Pair<String, Long>?,   // pid -> (comm, start), or null if unreadable
    shellSkip: Set<String> = DEFAULT_SHELL_SKIP,
    initFloor: Set<String> = DEFAULT_INIT_FLOOR,
): Triple<String, Long, Long>? {
    // Both sides of the membership tests are keyed with commKey: Windows reports `bash.exe`
    // where the lists spell `bash`, and the shipped lists also carry `cmd`/`cmd.exe` pairs.
    // Keying both sides makes either spelling work (and the pairs harmless no-ops).
    val skip = shellSkip.mapTo(HashSet(), ::commKey)
    val floor = initFloor.mapTo(HashSet(), ::commKey)
    var pid = parentOf(self) ?: return null
    var depth = 0
    while (pid > 1) {
        if (++depth > MAX_ANCESTRY_DEPTH) return null
        val cs = infoOf(pid) ?: return null
        // membership matches case-insensitively and ignores one trailing .exe (Windows keeps
        // on-disk case: CMD.EXE is a shell); the identity itself carries the comm verbatim
        val comm = commKey(cs.first)
        if (comm in floor) return null
        if (comm !in skip) return Triple(cs.first, pid, cs.second)
        pid = parentOf(pid) ?: return null
    }
    return null
}

/** `/proc/<pid>/stat` reader (Linux). Returns (comm, starttime) or null when absent. */
internal fun statOf(pid: Long): Pair<String, Long>? = try {
    val text = File("/proc/$pid/stat").readText()
    val open = text.indexOf('(')
    val close = text.lastIndexOf(')')
    if (open < 0 || close < 0) null
    else {
        val comm = text.substring(open + 1, close)
        val start = text.substring(close + 1).trim().split(Regex("\\s+"))
            .getOrNull(19)?.toLongOrNull()
        if (start == null) null else comm to start
    }
} catch (_: Exception) { null }

/**
 * Cross-platform process info (Windows/macOS, used when `/proc` is absent): the command
 * basename and the start instant in epoch millis — the same role as `/proc` field 22.
 */
internal fun processInfoOf(pid: Long): Pair<String, Long>? {
    val h = ProcessHandle.of(pid).orElse(null) ?: return null
    val cmd = h.info().command().orElse(null) ?: return null
    val start = h.info().startInstant().orElse(null)?.toEpochMilli() ?: return null
    return commOf(cmd) to start
}
