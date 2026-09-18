package dev.corral

import java.io.File
import java.lang.ProcessHandle

/** Executable basename as the agent label: `C:\x\claude.exe` → `claude.exe`, `/usr/bin/claude` → `claude`. */
internal fun commOf(command: String): String =
    command.substringAfterLast('/').substringAfterLast('\\')

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
    var pid = parentOf(self) ?: return null
    while (pid > 1) {
        val cs = infoOf(pid) ?: return null
        if (cs.first in initFloor) return null
        if (cs.first !in shellSkip) return Triple(cs.first, pid, cs.second)
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
