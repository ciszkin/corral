package dev.corral

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class EmulatorException(message: String) : Exception(message)

/** The spawn lost a race: an instance for the same AVD is already live. */
class EmulatorAlreadyRunning : Exception()

/** Spawns an emulator and reports whether a spawn for an AVD is still live. */
interface EmulatorLauncher {
    /** Spawns `bin -avd avd` with output in [log]; throws [EmulatorException] when it dies instantly. */
    fun start(bin: String, avd: String, log: Path)
    /** True while a spawn for [avd] is known live; dead tracked processes are reaped. */
    fun isRunning(avd: String): Boolean
    /**
     * Ends the spawn/unit for [avd] directly, for one adb hasn't listed (no serial to kill).
     * Returns a short description of what was ended (`pid <pid>` or `unit <name>`), or null
     * when nothing was running.
     */
    fun stop(avd: String): String?
    /** Last recorded exit status for [avd], or null when running, never ended, or unknown. */
    fun lastExit(avd: String): Int?
    /** Forgets [avd]'s recorded exit status, so a fresh start or a clean stop is not read as an error. */
    fun clearExit(avd: String) {}
    /** Clears failed spawns/units so the user manager is not left degraded. */
    fun reapFailed() {}
    /** Ends every tracked spawn for a graceful daemon stop; systemd units must survive. */
    fun destroyAll() {}
}

private val corralDir: Path get() = Path.of(System.getProperty("user.home"), ".corral")

/** `~/.corral/emulator-<avd>.log`: the start log, shared by both launchers. */
fun emulatorLogPath(avd: String): Path = corralDir.resolve("emulator-$avd.log")

/** The `emulator-NNNN` console serials an emulator prints into its own start log. */
private val emulatorSerialRegex = Regex("""\bemulator-\d+\b""")

/** `<log>.exit`: the direct launcher's last-exit sidecar, beside the log. */
fun emulatorExitPath(log: Path): Path = log.resolveSibling("${log.fileName}.exit")

/** `~/.corral/<unit>.exit`: the systemd launcher's last-exit sidecar, keyed by the space-free unit name. */
fun systemdExitPath(avd: String): Path = unitExitPath(systemdUnitName(avd))

private fun unitExitPath(unit: String): Path = corralDir.resolve("$unit.exit")

private fun writeExitSidecar(path: Path, code: Int) {
    runCatching {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, code.toString())
    }
}

/** Child of this JVM: fine outside systemd, dies with `corral.service`'s cgroup under it. */
class DefaultLauncher : EmulatorLauncher {
    private val processes = ConcurrentHashMap<String, Process>()

    override fun start(bin: String, avd: String, log: Path) {
        val p = try {
            ProcessBuilder(bin, "-avd", avd)
                .redirectOutput(log.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw EmulatorException("cannot start emulator for $avd (set CORRAL_EMULATOR): ${e.message}")
        }
        p.onExit().thenAccept { proc ->
            val code = runCatching { proc.exitValue() }.getOrDefault(1)
            runCatching { Files.writeString(emulatorExitPath(log), code.toString()) }
        }
        if (p.waitFor(EARLY_EXIT_MS, TimeUnit.MILLISECONDS))
            throw EmulatorException("emulator for $avd exited immediately: ${logTail(log) ?: "(no output)"}")
        processes[avd] = p
    }

    override fun isRunning(avd: String): Boolean {
        val p = processes[avd] ?: return false
        if (p.isAlive) return true
        processes.remove(avd)
        return false
    }

    override fun stop(avd: String): String? {
        val p = processes.remove(avd) ?: return null
        if (!p.isAlive) return null
        p.destroy()
        return "pid ${p.pid()}"
    }

    override fun lastExit(avd: String): Int? =
        runCatching { Files.readString(emulatorExitPath(emulatorLogPath(avd))).trim().toIntOrNull() }.getOrNull()

    override fun clearExit(avd: String) {
        runCatching { Files.deleteIfExists(emulatorExitPath(emulatorLogPath(avd))) }
    }

    override fun destroyAll() {
        val tracked = processes.values.toList()
        processes.clear()
        tracked.forEach { runCatching { it.destroy() } }
        tracked.forEach { runCatching { it.waitFor(2, TimeUnit.SECONDS) } }
    }

    private fun logTail(log: Path): String? =
        runCatching { Files.readAllLines(log).lastOrNull { it.isNotBlank() } }.getOrNull()
}

/** One transient user unit per AVD so an emulator outlives a `corral.service` restart. */
class SystemdLauncher(
    private val env: Map<String, String> = System.getenv(),
    private val resetFailed: (String) -> Unit = ::runResetFailed,
    private val listFailed: () -> List<String> = ::readFailedUnits,
    private val systemctlShow: (String) -> String? = ::readUnitState,
) : EmulatorLauncher {
    override fun start(bin: String, avd: String, log: Path) {
        val unit = systemdUnitName(avd)
        val p = try {
            ProcessBuilder(systemdRunCommand(bin, avd, log, env)).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw EmulatorException("cannot run systemd-run for $avd: ${e.message}")
        }
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroy()
            throw EmulatorException("systemd-run for $avd did not finish")
        }
        val out = p.inputStream.bufferedReader().readText()
        if (p.exitValue() != 0) {
            if (out.contains("already exists", ignoreCase = true)) throw EmulatorAlreadyRunning()
            throw EmulatorException("cannot start emulator for $avd: " +
                (out.lineSequence().firstOrNull { it.isNotBlank() } ?: "systemd-run exited ${p.exitValue()}"))
        }
        if (!active(unit, SYSTEMD_EARLY_EXIT_MS))
            throw EmulatorException("emulator for $avd exited immediately: ${logTail(log, unit) ?: "(no output)"}")
    }

    override fun isRunning(avd: String): Boolean = isActive(systemdUnitName(avd))

    override fun stop(avd: String): String? {
        val unit = systemdUnitName(avd)
        if (!isActive(unit)) return null
        runCatching {
            val p = ProcessBuilder("systemctl", "--user", "stop", unit)
                .redirectErrorStream(true).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy()
        }
        return if (isActive(unit)) null else "unit $unit"
    }

    /**
     * Reads the unit's exit: a running unit has none, a recorded [systemdExitPath] sidecar wins, else the
     * unit state is mapped. A failed unit is read once, then the sidecar is written and the unit reset so it
     * does not leave the user manager `degraded` until this AVD is started again.
     */
    override fun lastExit(avd: String): Int? {
        val unit = systemdUnitName(avd)
        val props = showProperties(systemctlShow(unit))
        if (props["ActiveState"] in ACTIVE_STATES) return null
        val sidecar = unitExitPath(unit)
        if (Files.exists(sidecar))
            return runCatching { Files.readString(sidecar).trim().toIntOrNull() }.getOrNull()
        val result = props["Result"] ?: return null
        if (result == "success") return 0
        val code = (parseSystemdExit(props) ?: 1).takeIf { it != 0 } ?: 1
        writeExitSidecar(sidecar, code)
        resetFailed(unit)
        return code
    }

    override fun clearExit(avd: String) {
        val unit = systemdUnitName(avd)
        runCatching { Files.deleteIfExists(unitExitPath(unit)) }
        resetFailed(unit)
    }

    /** Without `--collect` failed units linger; record each then reset it so `is-system-running` recovers. */
    override fun reapFailed() {
        listFailed().forEach { unit ->
            val props = showProperties(systemctlShow(unit))
            val result = props["Result"] ?: return@forEach
            if (result == "success") return@forEach
            val code = (parseSystemdExit(props) ?: 1).takeIf { it != 0 } ?: 1
            writeExitSidecar(unitExitPath(unit), code)
            resetFailed(unit)
        }
    }

    private fun active(unit: String, windowMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            if (isActive(unit)) return true
            Thread.sleep(100)
        }
        return isActive(unit)
    }

    private fun isActive(unit: String): Boolean = runCatching {
        val p = ProcessBuilder("systemctl", "--user", "is-active", "--quiet", unit).start()
        p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private fun logTail(log: Path, unit: String): String? {
        runCatching { Files.readAllLines(log).lastOrNull { it.isNotBlank() } }.getOrNull()?.let { return it }
        return runCatching {
            val p = ProcessBuilder("journalctl", "--user", "-u", unit, "-n", "20").redirectErrorStream(true).start()
            p.waitFor(2, TimeUnit.SECONDS)
            p.inputStream.bufferedReader().readText().lineSequence().lastOrNull { it.isNotBlank() }
        }.getOrNull()
    }
}

/**
 * `systemd-run` argv for [avd]. A user unit inherits the *user manager's* environment, so the emulator's
 * `ANDROID_*` family is forwarded explicitly; Corral's own variables stay out of the unit. The exit status
 * is read back from the unit with `systemctl show` (see [SystemdLauncher.lastExit]), so no `--collect`
 * or `ExecStopPost` sidecar is needed.
 */
fun systemdRunCommand(bin: String, avd: String, log: Path, env: Map<String, String>): List<String> =
    buildList {
        add("systemd-run")
        add("--user")
        add("--unit=${systemdUnitName(avd)}")
        add("--property=StandardOutput=file:$log")
        add("--property=StandardError=file:$log")
        env.filterKeys { it.startsWith("ANDROID_") }.toSortedMap().forEach { (k, v) -> add("--setenv=$k=$v") }
        add("--")
        add(bin)
        add("-avd")
        add(avd)
    }

/** `systemctl show` for the four state properties; null when the unit is unloaded or the command fails. */
private fun readUnitState(unit: String): String? = runCatching {
    val p = ProcessBuilder("systemctl", "--user", "show", unit,
        "-p", "ActiveState", "-p", "Result", "-p", "ExecMainCode", "-p", "ExecMainStatus")
        .redirectErrorStream(true).start()
    if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroy(); return@runCatching null }
    if (p.exitValue() != 0) null else p.inputStream.bufferedReader().readText()
}.getOrNull()

/** `systemctl --user list-units` for failed emulator units; the unit name is the first column. */
private fun readFailedUnits(): List<String> = runCatching {
    val p = ProcessBuilder("systemctl", "--user", "list-units", "corral-emulator-*.service",
        "--state=failed", "--no-legend", "--plain").redirectErrorStream(true).start()
    if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroy(); return@runCatching emptyList() }
    if (p.exitValue() != 0) return@runCatching emptyList()
    p.inputStream.bufferedReader().readText().lineSequence()
        .mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull() }
        .filter { it.startsWith("corral-emulator-") && it.endsWith(".service") }
        .toList()
}.getOrDefault(emptyList())

private fun runResetFailed(unit: String) {
    runCatching {
        val p = ProcessBuilder("systemctl", "--user", "reset-failed", unit)
            .redirectErrorStream(true).start()
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy()
    }
}

/** `systemctl show` key=value lines as a map; empty for a missing unit. */
private fun showProperties(show: String?): Map<String, String> {
    if (show.isNullOrBlank()) return emptyMap()
    return show.lineSequence().mapNotNull { line ->
        val eq = line.indexOf('=')
        if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
    }.toMap()
}

/**
 * Maps `systemctl show` properties to a process exit code. A clean exit unloads the unit (null); a failure
 * lingers with `Result=exit-code`/`signal` (SIGKILL included, as `128 + signal`); other failures map to 1.
 */
private fun parseSystemdExit(props: Map<String, String>): Int? = when (props["Result"]) {
    "success" -> 0
    "exit-code" -> props["ExecMainStatus"]?.toIntOrNull() ?: 1
    "signal" -> props["ExecMainStatus"]?.toIntOrNull()?.let { 128 + it } ?: 1
    null -> null
    else -> 1
}

/** `corral-emulator-<sanitized-avd>-<hash>`: fold unit-unsafe chars, then disambiguate with a stable hash. */
fun systemdUnitName(avd: String): String =
    "corral-emulator-" + avd.replace(Regex("[^A-Za-z0-9_.-]"), "-") + "-" + "%08x".format(avd.hashCode())

/**
 * `CORRAL_EMULATOR_LAUNCHER=systemd|direct` picks explicitly; unset (or anything else) auto-detects
 * systemd only when the daemon itself is the `corral.service` unit. `INVOCATION_ID` can't tell: every
 * process under any unit carries it, so a hand-started dev daemon would wrongly create user units.
 */
fun launcherFor(
    env: Map<String, String> = System.getenv(),
    cgroup: () -> String? = { runCatching { Files.readString(Path.of("/proc/self/cgroup")) }.getOrNull() },
    override: String? = env["CORRAL_EMULATOR_LAUNCHER"],
): EmulatorLauncher = when (override?.trim()?.lowercase()) {
    "systemd" -> SystemdLauncher(env)
    "direct" -> DefaultLauncher()
    else -> if (cgroup()?.contains("corral.service") == true) SystemdLauncher(env) else DefaultLauncher()
}

private val ACTIVE_STATES = setOf("active", "activating", "reloading")

private const val EARLY_EXIT_MS = 700L
private const val SYSTEMD_EARLY_EXIT_MS = 1000L

/**
 * Talks to the SDK emulator binary to list and boot AVDs; [stop] hands the kill to adb because an
 * already-running AVD is only addressable by its `emulator-<port>` serial.
 */
class Emulators(
    private val adbPath: String,
    private val emulatorPath: () -> String?,
    private val launcher: EmulatorLauncher = launcherFor(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val locks = ConcurrentHashMap<String, Any>()
    /** AVD -> when this daemon spawned it; only a daemon-launched AVD can be reported wedged. */
    private val launchedAt = ConcurrentHashMap<String, Long>()

    /**
     * AVD names from `emulator -list-avds`. Null when the binary is absent or the command
     * failed to run, exited non-zero, or timed out; a non-null list (possibly empty) means it
     * ran successfully and reported those AVDs.
     */
    fun list(): List<String>? = runCatching {
        val bin = emulatorPath() ?: return@runCatching null
        val p = ProcessBuilder(bin, "-list-avds").redirectErrorStream(true).start()
        if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroy(); return@runCatching null }
        if (p.exitValue() != 0) null
        else p.inputStream.bufferedReader().readText().lineSequence()
            .map(String::trim).filter(String::isNotEmpty).toList()
    }.getOrNull()

    /** Spawns [avd]; false when an instance is already live (or won the race under systemd). */
    fun start(avd: String): Boolean {
        val bin = emulatorPath() ?: throw EmulatorException("emulator binary not found — set CORRAL_EMULATOR")
        val log = emulatorLogPath(avd)
        log.parent?.let { Files.createDirectories(it) }
        val lock = locks.computeIfAbsent(avd) { Any() }
        synchronized(lock) {
            if (launcher.isRunning(avd)) return false
            runCatching { Files.deleteIfExists(log) }
            runCatching { Files.deleteIfExists(log.resolveSibling("${log.fileName}.1")) }
            launcher.clearExit(avd)
            try {
                launcher.start(bin, avd, log)
                launchedAt[avd] = now()
            } catch (e: EmulatorAlreadyRunning) {
                return false
            }
        }
        return true
    }

    /** True while a spawn for [avd] is live, even before it appears in the adb pool. */
    fun isRunning(avd: String): Boolean = launcher.isRunning(avd)

    /** Ends the spawn/unit for [avd] directly, for one adb has not listed (no serial to kill). */
    fun stopAvd(avd: String): String? {
        launchedAt.remove(avd)
        val terminated = launcher.stop(avd)
        val log = emulatorLogPath(avd)
        runCatching {
            if (Files.exists(log))
                Files.move(log, log.resolveSibling("${log.fileName}.1"), StandardCopyOption.REPLACE_EXISTING)
        }
        launcher.clearExit(avd)
        return terminated
    }

    /** Last non-blank line of [avd]'s start log (a late failure), or null when there is none. */
    fun logTail(avd: String): String? =
        runCatching { Files.readAllLines(emulatorLogPath(avd)).lastOrNull { it.isNotBlank() } }.getOrNull()

    /**
     * Serial(s) (`emulator-NNNN`) [avd]'s start log recorded, independent of adb. The monitor's
     * `adb -s SERIAL emu avd name` enrichment fails on an offline/unbooted emulator, so its pool
     * entry never gains an avd; this launcher-side mapping is what lets an orphaned instance be
     * reconciled by serial. Empty when the log is absent or printed no serial.
     */
    fun logSerials(avd: String): List<String> =
        runCatching { Files.readAllLines(emulatorLogPath(avd)) }.getOrDefault(emptyList())
            .flatMap { line -> emulatorSerialRegex.findAll(line).map { it.value } }
            .distinct()

    /**
     * AVDs *this daemon* launched that are still alive ([isRunning]) and past the boot deadline —
     * the same [MAX_WAIT_S] a queued `--type emulator` claimant already waits for the boot flip —
     * mapped to seconds alive. Whether the wedge is *confirmed* (the serial never reached
     * `device`+`booted`) is the pool's call; see `wedgedAvds` in Api.kt.
     */
    fun launchedPastBootDeadline(): Map<String, Long> {
        launchedAt.keys.removeIf { avd -> !isRunning(avd) }
        return launchedAt
            .filterValues { at -> now() - at >= MAX_WAIT_S * 1000 }
            .mapValues { (_, at) -> (now() - at) / 1000 }
    }

    /** The last failure [avd] ended with, or null while running, after a clean exit, or with no status. */
    fun lastError(avd: String): String? =
        if (isRunning(avd)) null else launcher.lastExit(avd)?.takeIf { it != 0 }?.let { logTail(avd) }

    /** Clears failed spawns/units so a headless box leaves `systemctl --user` not degraded. */
    fun reapFailed() = launcher.reapFailed()

    /** Ends every tracked spawn for a graceful stop; systemd units are left to run. */
    fun shutdown() = launcher.destroyAll()

    fun stop(serial: String) {
        val p = try {
            ProcessBuilder(adbPath, "-s", serial, "emu", "kill").start()
        } catch (e: IOException) {
            throw EmulatorException("cannot run adb for $serial (set CORRAL_ADB): ${e.message}")
        }
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroy()
            throw EmulatorException("emu kill on $serial did not finish — is the emulator console wedged?")
        }
        val err = p.errorStream.bufferedReader().readText().lineSequence()
            .firstOrNull { it.isNotBlank() } ?: ""
        if (p.exitValue() != 0 || err.isNotBlank())
            throw EmulatorException("emu kill failed for $serial${if (err.isBlank()) "" else ": $err"}")
    }

    companion object {
        /** `CORRAL_EMULATOR` > `<sdk>/emulator/emulator[.exe]` next to adb > on [env]'s PATH. */
        fun resolveEmulatorPath(env: Map<String, String> = System.getenv(), adb: String? = null): String? {
            env["CORRAL_EMULATOR"]?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Path.of(it) }.getOrNull() }
                ?.takeIf(::runnable)
                ?.let { return it.toString() }
            // probe both spellings everywhere: extensionless wins on unix, `.exe` on Windows
            adb?.let { a ->
                val emulatorDir = Path.of(a).parent?.parent?.resolve("emulator")
                for (name in EXE_NAMES) {
                    val sibling = emulatorDir?.resolve(name)
                    if (sibling != null && runnable(sibling)) return sibling.toString()
                }
            }
            return pathProbe(env["PATH"])
        }

        private val EXE_NAMES = listOf("emulator", "emulator.exe")

        private fun runnable(p: Path): Boolean = Files.isRegularFile(p) && Files.isExecutable(p)

        private fun pathProbe(path: String?): String? = path
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.filter { it.isNotBlank() }
            ?.flatMap { dir -> EXE_NAMES.map { Path.of(dir, it) } }
            ?.firstOrNull { runnable(it) }
            ?.toString()
    }
}
