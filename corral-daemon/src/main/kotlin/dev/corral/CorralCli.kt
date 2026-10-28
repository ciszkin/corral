package dev.corral

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.lang.ProcessHandle
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarFile
import kotlin.system.exitProcess

/** Percent-encodes a path segment; spaces must be `%20`, not the form-encoding `+`. */
internal fun pathSegment(s: String): String =
    URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

/** Decodes a `/api/devices` body leniently, so an older CLI tolerates fields a newer daemon adds. */
internal fun decodeDeviceList(body: String): List<Device> = corralJson.decodeFromString(body)

/** Decodes a `/api/doctor` body leniently, so an older CLI tolerates fields a newer daemon adds. */
internal fun decodeDoctorResponse(body: String): DoctorResponse = corralJson.decodeFromString(body)

/**
 * The CLI's emulators problems, taken from the daemon's own doctor layer — never re-derived from the
 * pool. A wedge before adb ever lists the AVD has no pool serial to flag, so the pool-only reading
 * printed nothing while /api/doctor failed; one source of truth keeps the two surfaces agreeing.
 */
internal fun emulatorProblems(doctor: DoctorResponse?): List<String> =
    doctor?.layers.orEmpty()
        .filter { it.layer == "emulators" && it.status == "fail" }
        .map { it.detail }

/** The empty-pool hint, only for a reachable daemon that lists no device — never for an unreachable one. */
internal fun emptyPoolHint(devices: List<Device>?): String? =
    EMPTY_POOL_HINT.takeIf { devices != null && devices.isEmpty() }

/**
 * The doctor's unreachable-daemon hint. POSIX hosts run the user unit; Windows has no systemd and
 * runs the per-user `CorralDaemon` Scheduled Task — a `systemctl` suggestion there is a dead end.
 * [windows] is injectable so both branches are testable on either host.
 */
internal fun daemonDownHint(windows: Boolean = isWindows): String =
    if (windows) "is the CorralDaemon Scheduled Task running? schtasks /query /tn CorralDaemon"
    else "is it running? systemctl --user status corral"

/** The doctor's one update line. Switches on the explicit state — never says "current" when unknown. */
internal fun updateLine(info: UpdateInfo?): String = when (info?.state) {
    null -> "update: unknown (could not read /api/update)"
    UpdateState.DISABLED -> "update: disabled (CORRAL_UPDATE_CHECK=off)"
    // UNKNOWN has several causes; name the one the daemon actually observed, never a guess
    UpdateState.UNKNOWN -> when (info.reason) {
        UpdateReason.NOT_CHECKED -> "update: unknown (no update check has run yet)"
        UpdateReason.UNREACHABLE -> "update: unknown (could not reach the release feed)"
        UpdateReason.HTTP_ERROR -> "update: unknown (release feed answered HTTP ${info.httpStatus})"
        UpdateReason.UNPARSEABLE -> "update: unknown (release feed's tag is not a version)"
        null -> "update: unknown (cause not recorded)"
    }
    UpdateState.UPDATE_AVAILABLE -> {
        // tags arrive with or without a leading v; render one v either way
        val latest = info.latestVersion.orEmpty().removePrefix("v").removePrefix("V")
        "update: v${info.currentVersion} -> v$latest available (${info.releaseUrl})"
    }
    UpdateState.CURRENT -> "update: current"
}

/**
 * `corral update`'s summary: the console's own version on the first line, then the daemon's honest
 * update line. Naming the version makes the answer checkable even when no release is known.
 */
internal fun updateSummary(info: UpdateInfo?): String {
    val current = info?.currentVersion?.takeIf { it.isNotBlank() }
    return if (current == null) updateLine(info) else "corral v$current\n${updateLine(info)}"
}

/**
 * The version stamped into the daemon jar that is actually installed at `~/.corral`, read from its
 * `/corral-version` resource. `corral update --apply` reports THIS, not the release feed's tag: only
 * the bytes on disk are a claim the install performed, and an unreadable jar yields null (no claim).
 */
internal fun installedDaemonVersion(home: Path): String? = runCatching {
    val jar = home.resolve(".corral/corral-daemon-all.jar")
    if (!Files.isRegularFile(jar)) return@runCatching null
    JarFile(jar.toFile()).use { jf ->
        jf.getJarEntry("corral-version")?.let { entry ->
            jf.getInputStream(entry).bufferedReader().use { it.readText() }.trim().takeIf { it.isNotBlank() }
        }
    }
}.getOrNull()

/** True on Windows, where a `.bat`/`.cmd` shim is not a PE image and needs `cmd.exe /c`. */
internal val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

/**
 * Search for a bare program the way Windows does: the current directory first, then System32,
 * Windows, and the rest of PATH (all of PATH here; the two system dirs are entries on it). Java's
 * CreateProcess never applies PATHEXT, so a bare `adb` whose real file is `adb.bat` is simply "not
 * found"; every directory is probed direct-name then by each PATHEXT suffix. Returns the first
 * existing candidate, or the program unchanged when it already has a path. [cwd] and [env] are
 * injected so the resolution order is testable without touching the process's real CWD or PATH.
 */
internal fun resolvePathProgram(
    program: String,
    env: Map<String, String> = System.getenv(),
    cwd: String = ".",
): String? {
    if (program.isEmpty() || program.contains('/') || program.contains('\\')) return program
    val exts = (env["PATHEXT"] ?: ".COM;.EXE;.BAT;.CMD").split(';').filter { it.isNotBlank() }
    val dirs = (listOf(cwd) + (env["PATH"] ?: "").split(File.pathSeparator)).filter { it.isNotBlank() }
    for (dir in dirs) {
        val base = dir.trim('"')
        val direct = File(base, program)
        if (direct.isFile) return direct.path
        for (ext in exts) {
            val f = File(base, program + ext.lowercase())
            if (f.isFile) return f.path
        }
    }
    return null
}

/**
 * The argv to hand to ProcessBuilder. Windows cannot CreateProcess a `.bat`/`.cmd` (no shebang,
 * no PE header), so a program that resolves to one is launched via `cmd.exe /c`.
 *
 * `cmd.exe /c` takes ONE command string, not an argv. Each argument containing whitespace (or an
 * empty one) is wrapped in its own quotes, so a batch shim's `%1` is the whole argument, not its
 * first word; the quoted program plus those args are then wrapped in one outer pair. cmd strips
 * exactly that outer pair (its /c rule 2), leaving `"<program>" <arg...>` — the program stays one
 * token, space-bearing arguments keep their boundaries, and space-free args stay unquoted. Java
 * leaves a start-and-end-quoted element untouched, so our quoting reaches cmd intact.
 *
 * Ceiling: arguments are otherwise passed through verbatim. Embedded double quotes inside an
 * argument and cmd percent-expansion (`%VAR%`) inside the .bat are NOT handled — cmd may mis-split
 * or expand them. cmd metacharacters are also not re-escaped (same-user interpretation, reviewed).
 * Linux and directly-executable targets are returned untouched, so behaviour there is unchanged.
 *
 * [onResolved] fires once with whether the Windows resolver found the program, so the caller can tell
 * a "not found" launch failure from a "found but refused" one without resolving a second time. It is
 * never called on a non-Windows host, where the resolver does not run.
 */
internal fun commandArgv(
    argv: List<String>,
    windows: Boolean = isWindows,
    resolve: (String) -> String? = { resolvePathProgram(it) },
    onResolved: (Boolean) -> Unit = {},
): List<String> {
    if (!windows || argv.isEmpty()) return argv
    val found = resolve(argv.first())
    onResolved(found != null)
    val program = found ?: argv.first()
    if (!program.endsWith(".bat", true) && !program.endsWith(".cmd", true))
        return listOf(program) + argv.drop(1)
    val args = argv.drop(1).joinToString(" ") {
        if (it.isEmpty() || it.any(Character::isWhitespace)) "\"$it\"" else it
    }
    val command = "\"$program\"" + if (args.isEmpty()) "" else " $args"
    return listOf("cmd.exe", "/c", "\"$command\"")
}

/**
 * The JDK's launch-failure prefix — `Cannot run program "adb": ` — is stripped before we print any
 * part of the OS error. Reprinting it inside our own message made a launch failure read exactly like
 * the daemon-unreachable line it replaced, so a log reader could not tell them apart without the exit
 * code. The rest of the detail (the CreateProcess error code) is kept: it is the useful part.
 */
private val jdkCannotRunPrefix = Regex("""^Cannot run program "[^"]*"(: )?""")

private fun launchDetail(e: Throwable): String =
    (e.message ?: e::class.simpleName ?: "unknown error")
        .replaceFirst(jdkCannotRunPrefix, "")
        .trim()
        .ifEmpty { "unknown error" }

/**
 * The launch-failure line, worded for what actually went wrong. [resolved] is the Windows resolver's
 * verdict: `false` = nothing on PATH/PATHEXT matched (on Windows almost always `%USERPROFILE%\bin`
 * being off PATH, where corral's shims live), `true` = the program was found but CreateProcess still
 * refused it, `null` = the resolver never ran (non-Windows), so we name neither.
 */
internal fun launchFailure(program: String?, resolved: Boolean?, detail: String): String = when (resolved) {
    false -> "corral: cannot find ${program} on PATH — is %USERPROFILE%\\bin on it? corral's shims live there"
    true -> "corral: found ${program} but could not start it ($detail)"
    null -> "corral: could not start ${program} ($detail) — check it is installed and on PATH"
}

/**
 * Stop [proc] and everything it started. Only a signal sent to this CLI's pid alone needs it: Ctrl-C
 * and GNU `timeout` signal the whole process group, so the child already sees those. A supervisor's
 * `kill <pid>` or `pkill -f 'corral lease'` does not reach the child, and without this the lease
 * would go back (it is tied to this process) while the command keeps driving the device — the next
 * waiter would start on a device still in use (review 45 §4).
 *
 * TERM for the tree, a short grace so a command can clean up, then KILL what is left. Bounded, so a
 * shutdown hook cannot outlive a supervisor's own kill deadline. Returns true when the child was
 * still running (a normal exit, where the command already ended, does nothing).
 */
internal fun stopChildTree(proc: Process, graceMillis: Long = 2000): Boolean {
    if (!proc.isAlive) return false
    val tree = proc.toHandle().descendants().toList() + proc.toHandle()
    tree.forEach { runCatching { it.destroy() } }
    val termDeadline = System.nanoTime() + graceMillis * 1_000_000
    while (System.nanoTime() < termDeadline && tree.any { it.isAlive }) Thread.sleep(20)
    val left = tree.filter { it.isAlive }
    left.forEach { runCatching { it.destroyForcibly() } }
    val killDeadline = System.nanoTime() + graceMillis * 1_000_000
    while (System.nanoTime() < killDeadline && left.any { it.isAlive }) Thread.sleep(20)
    return true
}

/**
 * Run the wrapped child, then hand the lease back no matter how it ends: a start failure and a
 * non-zero exit both fall through the finally. A start failure is an IOException (program missing)
 * or an IllegalArgumentException — a JVM started with `-Djdk.lang.Process.allowAmbiguousCommands=false`
 * rejects our start-and-end-quoted-element command in ProcessImpl.start before CreateProcess, which
 * is still a launch failure, so it gets the same teaching message and 127. Both are reported as
 * such, so a program that cannot be started never reads as an unreachable daemon. [release] is
 * owner-scoped (see release), so a foreign lease is never touched.
 *
 * A signal that ends this process runs the shutdown hook, not the finally: it stops the child's
 * tree FIRST and only then releases, so the device is never handed over while the command still
 * runs. On the normal path the child is already reaped, the hook is removed, and the release is
 * exactly what it was before. SIGKILL runs no hook at all, so [onStart] gets the started child to
 * register its pid with the daemon (row 42, review 46 §2); a failure there never fails the command.
 */
internal fun runLeased(
    cmd: List<String>,
    resolved: Boolean?,
    build: () -> ProcessBuilder,
    onStart: (Process) -> Unit = {},
    release: () -> Unit,
): Int {
    val child = AtomicReference<Process?>(null)
    val released = AtomicBoolean(false)
    val releaseOnce = { if (released.compareAndSet(false, true)) release() }
    val stopHook = Thread({
        child.get()?.let { runCatching { stopChildTree(it) } }
        releaseOnce()
    }, "corral-stop-child")
    runCatching { Runtime.getRuntime().addShutdownHook(stopHook) }   // a signal mid-startup: no hook, the finally still releases
    return try {
        build().inheritIO().start().also { child.set(it); runCatching { onStart(it) } }.waitFor()
    } catch (e: IOException) {
        System.err.println(launchFailure(cmd.firstOrNull(), resolved, launchDetail(e)))
        127
    } catch (e: IllegalArgumentException) {
        System.err.println(launchFailure(cmd.firstOrNull(), resolved, launchDetail(e)))
        127
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(stopHook) }   // fails while already shutting down; the hook then releases
        releaseOnce()
    }
}

/**
 * `corral lease`'s own flags, split from the command. Everything after `--` is the command, so a
 * command flag can never be mistaken for a lease flag; a flag-shaped token BEFORE it that lease does
 * not know is an error, never a program to run (pass 44 §6).
 */
internal data class LeaseCliArgs(val all: Boolean, val timeoutSeconds: Long?, val cmd: List<String>)

/** null = a usage error: unknown flag, missing/negative/not-a-number timeout, or `--all` with `--timeout`. */
internal fun parseLeaseArgs(args: List<String>): LeaseCliArgs? {
    var all = false
    var timeout: Long? = null
    var i = 0
    while (i < args.size && args[i].startsWith("-")) {
        when {
            args[i] == "--" -> { i++; break }
            args[i] == "--all" -> { all = true; i++ }
            args[i] == "--timeout" -> {
                timeout = args.getOrNull(i + 1)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
                i += 2
            }
            args[i].startsWith("--timeout=") -> {
                timeout = args[i].substringAfter("=").toLongOrNull()?.takeIf { it >= 0 } ?: return null
                i++
            }
            else -> return null
        }
    }
    if (all && timeout != null) return null   // `--all` holds everything it can; queueing is one device
    return LeaseCliArgs(all, timeout, args.drop(i))
}

/**
 * The one-line busy answer for `claim` and `lease`: what is connected, booting or leased, who holds
 * what, and the CLI form that queues and KEEPS the device — never the server's raw JSON (pass 44 §6;
 * pass 9 fixed claim the same way). Lenient on an unexpected body: a server that answers something
 * else must not crash the CLI with a parse trace.
 */
internal fun busyLine(selector: Selector, body: String): String {
    val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return "corral: no free device matches $selector — corral status for holders"
    val matching = runCatching { obj["matching"]!!.jsonPrimitive.content.toInt() }.getOrNull()
    val booting = runCatching { obj["booting"]!!.jsonPrimitive.content.toInt() }.getOrNull() ?: 0
    val holders = runCatching {
        obj["busy"]!!.jsonArray.joinToString(", ") {
            it.jsonObject.let { o ->
                "${o["serial"]!!.jsonPrimitive.content} by ${o["owner"]!!.jsonPrimitive.content}"
            }
        }
    }.getOrNull().orEmpty()
    return when {
        matching == 0 -> "corral: no connected device matches $selector"
        booting > 0 -> "corral: no free device matches $selector ($booting emulator(s) booting) — " +
            "corral lease --timeout S --type emulator -- <cmd> queues until boot"
        else -> {
            val who = if (holders.isEmpty()) "" else ": $holders"
            "corral: no free device matches $selector (${matching ?: "some"} connected, all leased$who) — " +
                "corral lease --timeout S -- <cmd> queues and holds, corral status for holders"
        }
    }
}

/**
 * corral CLI — all logic in the jar (portable), launched by 3-line shims.
 * claim: JVM exits right after printing -> no pid recorded -> TTL-bound lease (MVP `adb-lease claim` semantics).
 * lease: JVM outlives the child -> pid recorded -> instant crash recovery (MVP `withdev` semantics).
 */
object CorralCli {
    private val url = System.getenv("CORRAL_URL")?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1:8798"
    // Resolved once per process so claim/free in one invocation use the same owner.
    private val owner: String by lazy { resolveOwner() }
    private val rules: GateRules by lazy { RulesFile.load() }

    /**
     * Lease owner identity — the declared corral identifier wins, else process ancestry:
     * CORRAL_OWNER > proc:<comm>:<pid>:<starttime> (universal) > user:$USER
     */
    private fun resolveOwner(): String {
        System.getenv("CORRAL_OWNER")?.takeIf { it.isNotBlank() }?.let { return it }
        processOwner()?.let { return it }
        return "user:" + (System.getenv("USER") ?: System.getProperty("user.name"))
    }

    // CORRAL_ADB first (the only SDK pointer the daemon unit sets; install.sh computed and
    // trusts it) — it points at the real binary (adb.real), the shim is its sibling "adb".
    // Then the same detection order as install.sh: ANDROID_HOME, ANDROID_SDK_ROOT, default path.
    private fun findSdkAdb(corralAdb: String? = System.getenv("CORRAL_ADB")): String? {
        val fromCorral = corralAdb?.let(::shimPathOf)
        // both spellings per source: extensionless on unix, adb.exe on Windows
        fun sdkCandidates(root: String) = listOf("$root/platform-tools/adb", "$root/platform-tools/adb.exe")
        val candidates = buildList {
            fromCorral?.let { add(it) }
            System.getenv("ANDROID_HOME")?.let { addAll(sdkCandidates(it)) }
            System.getenv("ANDROID_SDK_ROOT")?.let { addAll(sdkCandidates(it)) }
            // Windows installs the SDK under %LOCALAPPDATA%\Android\Sdk, not the user-home
            // default; the candidate simply does not exist on Linux, so behaviour is unchanged.
            System.getenv("LOCALAPPDATA")?.let { addAll(sdkCandidates("$it/Android/Sdk")) }
            addAll(sdkCandidates(System.getProperty("user.home") + "/Android/Sdk"))
        }
        return candidates.firstOrNull { File(it).exists() } ?: candidates.firstOrNull()
    }

    /** The real binary the wrapper must exec — never the corral shim, or we would recurse. */
    private fun findRealAdb(): String? {
        val sdk = findSdkAdb()
        val candidates = listOfNotNull(
            System.getenv("CORRAL_REAL_ADB")?.takeIf { it.isNotBlank() },
            System.getenv("CORRAL_ADB")?.takeIf { it.isNotBlank() }) +
            listOfNotNull(sdk?.let { if (File("$it.real").exists()) "$it.real" else null }) +
            listOfNotNull(sdk)
        return candidates.firstOrNull { File(it).canExecute() && !isCorralShim(it) }
    }

    private fun isCorralShim(path: String) =
        runCatching { File(path).inputStream().use { it.readNBytes(512).decodeToString().contains("corral adb wrapper") } }
            .getOrDefault(false)

    /**
     * Prepend the configured adb's directory to the wrapped child's PATH, so a bare `adb` resolves
     * even when the session that launched the CLI has no platform-tools on PATH (Windows smoke:
     * `corral lease -- adb ...` died with CreateProcess error=2). PATH key casing varies on Windows.
     */
    private fun ProcessBuilder.withAdbOnPath() = apply {
        val dir = findRealAdb()?.let { File(it).parent } ?: return@apply
        val env = environment()
        val key = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        env[key] = env[key]?.takeIf { it.isNotBlank() }?.let { "$dir${File.pathSeparator}$it" } ?: dir
    }

    private fun execRealAdb(argv: List<String>): Nothing {
        val adb = findRealAdb() ?: run {
            System.err.println("corral: real adb not found; set CORRAL_ADB or CORRAL_REAL_ADB"); exitProcess(127) }
        val code = try {
            ProcessBuilder(commandArgv(listOf(adb) + argv)).inheritIO().start().waitFor()
        } catch (e: IOException) {
            System.err.println("corral: failed to exec $adb (${e.message})"); exitProcess(127)
        }
        exitProcess(code)
    }

    private fun denyText(serial: String?, reason: String): String =
        "corral: " + (if (serial != null) "lease check failed for $serial: $reason" else reason) +
            "\nLease it first:\n  corral lease -- <cmd>\n  ser=\$(corral claim) && ANDROID_SERIAL=\$ser <cmd>\nstatus: corral status"

    // Walk up from the JVM's parent (like the wrapper starts at $PPID); the first
    // non-shell ancestor is the agent. Read /proc/<pid>/stat directly so comm and
    // starttime match the bash wrapper byte for byte.
    private fun processOwner(): String? = agentAncestor()?.let { "proc:${it.first}:${it.second}:${it.third}" }

    /** The agent's pid from the ancestry walk (see processOwner) — the holder that outlives this CLI. */
    private fun agentPid(): Long = agentAncestor()?.second ?: 0

    /**
     * The pid to record on a lease as a liveness hint, or 0 for TTL-bound.
     * Session id is the identity; a pid is only a valid liveness signal when the process
     * actually spans the work. A declared `CORRAL_OWNER` is a durable identity — the process
     * is not the session (Air starts a fresh process per prompt) — so pinning the lease to
     * the pid would break the hold; send 0 and lean on the TTL. Only `proc:` owners (process
     * ancestry, no declared identifier) get pid liveness, because there the process is the session.
     */
    private fun holderPid(): Long =
        if (owner.startsWith("proc:")) agentPid() else 0

    /** pid -> parent pid, cross-platform (ProcessHandle). */
    private fun parentPid(pid: Long): Long? =
        ProcessHandle.of(pid).orElse(null)?.parent()?.orElse(null)?.pid()

    /** (comm, start) from /proc when present, else from ProcessHandle — Linux keeps byte-parity. */
    private fun commAndStart(pid: Long): Pair<String, Long>? =
        statOf(pid) ?: processInfoOf(pid)

    /** First non-shell ancestor as (comm, pid, starttime), or null if none (→ user:$USER). */
    private fun agentAncestor(): Triple<String, Long, Long>? =
        pickAgentAncestor(ProcessHandle.current().pid(), ::parentPid, ::commAndStart,
            shellSkip = rules.shellSkip.ifEmpty { DEFAULT_SHELL_SKIP },
            initFloor = rules.initFloor.ifEmpty { DEFAULT_INIT_FLOOR })

    private val http = HttpClient.newHttpClient()

    private fun get(path: String) = http.send(
        HttpRequest.newBuilder(URI.create("$url$path")).timeout(Duration.ofSeconds(5)).GET().build(),
        HttpResponse.BodyHandlers.ofString()).let(::ok).body()

    /** The daemon's cached update check for the plumbing verbs; null when unreachable or non-200. */
    private fun fetchUpdate(): UpdateInfo? = runCatching {
        val r = http.send(HttpRequest.newBuilder(URI.create("$url/api/update"))
            .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() == 200) corralJson.decodeFromString<UpdateInfo>(r.body()) else null
    }.getOrNull()

    /** Fetch a device PNG and exit; on non-200 print the denial body and exit 1. */
    private fun screenshot(serial: String, out: String): Nothing {
        val resp = http.send(
            HttpRequest.newBuilder(URI.create("$url/api/devices/${URLEncoder.encode(serial, StandardCharsets.UTF_8)}/screenshot" +
                "?owner=${URLEncoder.encode(owner, StandardCharsets.UTF_8)}"))
                .timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() != 200) {
            System.err.println(String(resp.body(), StandardCharsets.UTF_8)
                .ifBlank { "corral: screenshot failed (HTTP ${resp.statusCode()})" })
            exitProcess(1)
        }
        try {
            Files.write(Path.of(out), resp.body())
        } catch (e: IOException) {
            System.err.println("corral: cannot write $out (${e.message})"); exitProcess(1)
        }
        println("$out (${resp.body().size} bytes)")
        exitProcess(0)
    }

    private fun send(method: String, path: String, body: String, timeoutSeconds: Long = 0) = http.send(
        HttpRequest.newBuilder(URI.create("$url$path"))
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .let { b -> if (timeoutSeconds > 0) b.timeout(Duration.ofSeconds(timeoutSeconds)) else b }
            .build(),
        HttpResponse.BodyHandlers.ofString()).let(::ok).body()

    private fun trySend(method: String, path: String, body: String) = http.send(
        HttpRequest.newBuilder(URI.create("$url$path"))
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").build(),
        HttpResponse.BodyHandlers.ofString())

    private fun ok(r: HttpResponse<String>) = r.also {
        if (it.statusCode() >= 400) { System.err.println(it.body()); exitProcess(1) }
    }

    private fun release(serial: String) {
        runCatching {
            val r = http.send(HttpRequest.newBuilder(URI.create("$url/api/leases/$serial"))
                .method("DELETE", HttpRequest.BodyPublishers.ofString(Json.encodeToString(FreeRequest(owner = owner))))
                .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString())
            if (r.statusCode() >= 400) System.err.println(r.body())
        }
    }

    /**
     * Tell the daemon which pids are still driving [serial] — this CLI and the command it just
     * started. A SIGKILLed CLI runs no shutdown hook, so without this the daemon would prune the
     * lease on the CLI's dead pid and hand a still-busy device to the next ticket (review 46 §2).
     * Owner-scoped by the renew, bounded, and best-effort: an unreachable daemon leaves the old
     * pid/TTL machinery in charge and never changes what the command returns.
     */
    private fun registerPids(serial: String, pids: List<Long>) {
        runCatching {
            http.send(HttpRequest.newBuilder(URI.create("$url/api/leases/$serial/renew"))
                .timeout(Duration.ofSeconds(2))
                .method("POST", HttpRequest.BodyPublishers.ofString(
                    Json.encodeToString(RenewRequest(owner = owner, pids = pids))))
                .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString())
        }
    }

    /** Claim one free device; retries on a raced busy (409 {"busy":...}), null when none free. */
    private fun claimFree(owner: String, pid: Long, selector: Selector = Selector()): String? {
        repeat(5) {
            val r = trySend("POST", "/api/leases",
                Json.encodeToString(ClaimRequest(selector = selector, owner = owner, pid = pid, ttlSeconds = 1800)))
            if (r.statusCode() == 200)
                return Json.parseToJsonElement(r.body()).jsonObject["serial"]!!.jsonPrimitive.content
            if (r.body().contains("\"busy\"")) return@repeat // raced; try again
            return null // 409 no-free-device (or other) -> stop
        }
        return null
    }

    /** Claim one device now; on 409 print a plain message (not the raw busy JSON) and exit 1. */
    private fun claimOne(selector: Selector, pid: Long): String {
        val r = trySend("POST", "/api/leases",
            Json.encodeToString(ClaimRequest(selector = selector, owner = owner, pid = pid, ttlSeconds = 1800)))
        if (r.statusCode() == 200)
            return Json.parseToJsonElement(r.body()).jsonObject["serial"]!!.jsonPrimitive.content
        System.err.println(busyLine(selector, r.body()))
        exitProcess(1)
    }

    private fun String.str(field: String) =
        Json.parseToJsonElement(this).jsonObject[field]!!.jsonPrimitive.content

    /** Matching emulators still booting, read straight from the daemon's device list. */
    private fun bootingMatches(selector: Selector): Int = runCatching {
        decodeDeviceList(get("/api/devices"))
            .count { it.transport == "emulator" && !it.booted && selector.matchesFields(it) }
    }.getOrDefault(0)

    /** A ticket that hit its deadline: say so, naming the selector and — for emulators — a booting match. */
    private fun waitTimedOut(selector: Selector, waitSeconds: Long): Nothing {
        val booting = bootingMatches(selector)
        val tail = if (booting > 0) "; $booting matching emulator(s) still booting" else ""
        System.err.println("corral: gave up after ${waitSeconds}s waiting for $selector$tail")
        exitProcess(1)
    }

    /**
     * Enter the FIFO ticket queue, block until a matching device is offered (accepting it here), and
     * return its serial. From the acceptance on it is a normal owner-scoped lease: [holderPid] is the
     * liveness signal, and it lives until [release] or that pid dies. The shutdown hook always cancels
     * an UNSERVED ticket; with [releaseOnExit] it also hands a served lease back when this process
     * exits — `wait`'s documented contract. A queueing `lease` passes false: it releases in
     * [runHolding]'s finally, and a killed CLI is covered by the pid/TTL machinery like a plain claim.
     */
    private fun awaitServed(sel: Selector, waitSeconds: Long, holderPid: Long,
                            releaseOnExit: Boolean): String {
        // Own pid: killing this CLI frees the ticket at once (the daemon skips dead pids).
        val ticket = send("POST", "/api/leases",
            Json.encodeToString(ClaimRequest(selector = sel, owner = owner,
                pid = ProcessHandle.current().pid(), ttlSeconds = 1800, waitSeconds = waitSeconds))).str("ticket")
        val served = AtomicReference<String?>(null)
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching {
                val s = served.get()
                if (s != null) { if (releaseOnExit) release(s) }
                else http.send(HttpRequest.newBuilder(URI.create("$url/api/leases/tickets/$ticket"))
                    .timeout(Duration.ofSeconds(2))
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(
                        Json.encodeToString(TicketCancelRequest(owner = owner))))
                    .header("Content-Type", "application/json")
                    .build(), HttpResponse.BodyHandlers.ofString())
            }
        })
        while (true) {
            val r = trySend("POST", "/api/leases/tickets/$ticket/poll",
                Json.encodeToString(TicketPollRequest(owner = owner, pid = holderPid)))
            when (r.statusCode()) {
                200 -> { val serial = r.body().str("serial"); served.set(serial); return serial }
                202 -> Thread.sleep(runCatching { r.body().str("retryAfterMs").toLong() }.getOrDefault(1000L))
                else -> waitTimedOut(sel, waitSeconds)
            }
        }
    }

    /**
     * Run [cmd] holding [serial]: `ANDROID_SERIAL` and `CORRAL_OWNER` exported, the configured adb's
     * directory on PATH, and the lease returned however the command ends. This CLI process outlives
     * the child, so the pid recorded on the lease keeps it held for exactly the command's lifetime —
     * and for a CLI that is SIGKILLed instead of signalled, the child's own pid is registered with
     * the daemon as soon as it starts (review 46 §2).
     */
    private fun runHolding(serial: String, cmd: List<String>): Int {
        var resolved: Boolean? = null
        val launchArgv = commandArgv(cmd, onResolved = { resolved = it })
        return runLeased(cmd, resolved, {
            ProcessBuilder(launchArgv).apply {
                environment()["ANDROID_SERIAL"] = serial
                environment()["CORRAL_OWNER"] = owner
            }.withAdbOnPath()
        }, onStart = { child -> registerPids(serial, listOf(ProcessHandle.current().pid(), child.pid())) }) {
            release(serial)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (e: IOException) {
            System.err.println("corral: daemon unreachable at $url (${e.message})")
            exitProcess(1)
        }
    }

    private fun run(args: Array<String>) {
        val pid = ProcessHandle.current().pid()
        when (args.firstOrNull() ?: "help") {
            // Version is answered from the jar's own resource, with no daemon and no device.
            "--version", "-V" -> { println(VersionSource.version); exitProcess(0) }
            "devices" -> println(get("/api/devices"))
            "status" -> println(get("/api/status"))
            "owner" -> println(owner)
            "screenshot" -> {
                var serial = ""
                var out = ""
                val rest = args.drop(1)
                var i = 0
                while (i < rest.size) {
                    when {
                        rest[i] == "--out" -> out = rest.getOrNull(++i) ?: usage(2)
                        rest[i].startsWith("--out=") -> out = rest[i].substringAfter("=")
                        serial.isEmpty() -> serial = rest[i]
                        else -> usage(2)
                    }
                    i++
                }
                if (serial.isEmpty()) usage(2)
                screenshot(serial, out.ifEmpty { "$serial.png" })
            }
            "events" -> {
                val target = URI.create(url.replaceFirst("http", "ws") + "/ws/events")
                var backoff = 1000L
                while (true) {
                    val closed = CountDownLatch(1)
                    try {
                        http.newWebSocketBuilder().buildAsync(target, object : WebSocket.Listener {
                            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean) =
                                CompletableFuture.completedFuture(webSocket).also { webSocket.request(1); println(data) }
                            override fun onError(webSocket: WebSocket, error: Throwable) { closed.countDown() }
                            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*> {
                                closed.countDown(); return CompletableFuture.completedFuture(null)
                            }
                        }).join()
                        closed.await(); backoff = 1000L
                    } catch (_: Exception) { /* fall through to reconnect */ }
                    System.err.println("corral: events disconnected; retrying in ${backoff / 1000}s")
                    Thread.sleep(backoff); backoff = (backoff * 2).coerceAtMost(5000L)
                }
            }
            "doctor" -> {
                var problems = 0
                val pool: List<Device>? = try {
                    val r = http.send(HttpRequest.newBuilder(URI.create("$url/api/devices")).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                    if (r.statusCode() == 200) decodeDeviceList(r.body()) else null
                } catch (_: Exception) { null }
                if (pool != null) println("daemon: ok ($url, ${pool.size} device(s))")
                else { println("daemon: FAIL - unreachable at $url (${daemonDownHint()})"); problems++ }
                // an empty pool is not a failure, but silence reads as "nothing is plugged in"
                emptyPoolHint(pool)?.let { println("devices: hint - $it") }
                // a wedged emulator is stuck, not booting: name it and its remedy (Windows smoke H2).
                // Read the daemon's own doctor layer, not the pool: a wedge before adb lists the AVD
                // has no pool serial to flag, so /api/doctor is the only place that knows about it.
                val doctor = runCatching { decodeDoctorResponse(get("/api/doctor")) }.getOrNull()
                emulatorProblems(doctor).forEach {
                    println("emulators: FAIL - $it"); problems++
                }

                // prefer the daemon's own configured adb (its unit env may differ from this shell's);
                // the unit points CORRAL_ADB at the real binary (adb.real), shimPathOf maps it to the
                // shim. No fallback chain here: a stale/moved SDK must FAIL, not silently pass this
                // shell's tree — the daemon wouldn't be using that one.
                val daemonAdb = runCatching {
                    Json.parseToJsonElement(get("/api/status")).jsonObject["adb"]
                        ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && ('/' in it || '\\' in it) }
                }.getOrNull()
                val shellAdb = findSdkAdb()
                val sdkAdb = daemonAdb?.let(::shimPathOf) ?: shellAdb
                if (daemonAdb != null && sdkAdb != shellAdb)
                    println("sdk adb shim: checking the daemon's adb ($sdkAdb), not this shell's")
                val whose = if (daemonAdb != null) " (the daemon's configured adb)" else ""
                when {
                    sdkAdb == null -> println("sdk adb shim: skipped (no SDK platform-tools/adb found; set ANDROID_HOME)")
                    else -> {
                        // one vocabulary with /api/doctor: the probe classifies unmanaged paths as
                        // skipped and managed ones as ok/fail (the Windows smoke caught the two disagreeing)
                        val (status, detail) = SdkShim.probe(Path.of(sdkAdb))
                        println("sdk adb shim: $status - $detail$whose")
                        if (status == "fail") problems++
                    }
                }
                val rulesPath = RulesFile.defaultPath()
                val parsed = RulesFile.load(rulesPath)
                when {
                    !rulesPath.toFile().exists() -> { println("rules: FAIL - $rulesPath missing"); problems++ }
                    parsed.readonly.isEmpty() || parsed.valueFlags.isEmpty() -> {
                        println("rules: FAIL - $rulesPath is missing readonly or valueflags"); problems++ }
                    else -> println("rules: ok ($rulesPath)")
                }
                // informational: an available update is not a failure, and an unknown must not read as current
                val info = runCatching {
                    val r = http.send(HttpRequest.newBuilder(URI.create("$url/api/update"))
                        .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString())
                    if (r.statusCode() == 200) corralJson.decodeFromString<UpdateInfo>(r.body()) else null
                }.getOrNull()
                println(updateLine(info))
                if (problems > 0) exitProcess(1)
            }
            "update" -> {
                val apply = args.drop(1).any { it == "--apply" }
                val info = fetchUpdate()
                println(updateSummary(info))
                // Without --apply this downloads nothing and runs nothing — the explicit-apply rule.
                if (!apply) { exitProcess(0) }
                val tag = info?.latestVersion
                if (info?.state != UpdateState.UPDATE_AVAILABLE || tag.isNullOrBlank()) {
                    System.err.println("corral: no update available to install — refusing to download or change anything")
                    exitProcess(1)
                }
                when (val result = UpdateApplier.forCli(tag).run()) {
                    is ApplyResult.Completed -> {
                        // The feed's tag is not a claim we verified; read the version back off disk.
                        val installed = installedDaemonVersion(Path.of(System.getProperty("user.home")))
                        println(if (installed != null) "update: installed v$installed" else "update: installed")
                        exitProcess(0)
                    }
                    is ApplyResult.Refused -> { System.err.println("corral: ${result.reason}"); exitProcess(1) }
                    is ApplyResult.Failed -> { System.err.println("corral: ${result.reason}"); exitProcess(1) }
                }
            }
            "check" -> {
                val checkOwner: String
                val serials: List<String>
                if (args.getOrNull(1) == "--owner") {
                    checkOwner = args.getOrNull(2) ?: usage(2)
                    serials = args.drop(3)
                } else {
                    checkOwner = owner
                    serials = args.drop(1)
                }
                val targets = if (serials.isEmpty())
                    Json.parseToJsonElement(get("/api/devices")).jsonArray
                        .filter { it.jsonObject["state"]!!.jsonPrimitive.content == "device" }
                        .map { it.jsonObject["serial"]!!.jsonPrimitive.content }
                else serials
                val denied = targets.mapNotNull { s ->
                    val obj = Json.parseToJsonElement(get("/api/check?serial=${URLEncoder.encode(s, StandardCharsets.UTF_8)}&owner=${URLEncoder.encode(checkOwner, StandardCharsets.UTF_8)}")).jsonObject
                    if (obj["allowed"]!!.jsonPrimitive.content == "true") null
                    else "  $s: ${obj["reason"]!!.jsonPrimitive.content}"
                }
                if (denied.isEmpty()) println("ok: ${targets.size} device(s) leased by $checkOwner")
                else {
                    System.err.println("corral: devices not leased by $checkOwner:")
                    denied.forEach(System.err::println)
                    exitProcess(1)
                }
            }
            "claim" -> {
                val (sel, rest) = parseSelector(args.drop(1).toTypedArray())
                when {
                    rest.firstOrNull() == "--all" -> {
                        var claimed = 0
                        while (true) {
                            val s = claimFree(owner, holderPid(), selector = sel) ?: break
                            println(s); claimed++
                        }
                        if (claimed == 0) { System.err.println("corral: no free device"); exitProcess(1) }
                    }
                    else -> {
                        val selector = rest.firstOrNull()?.let { sel.copy(serial = it) } ?: sel
                        println(claimOne(selector, holderPid()))
                    }
                }
            }
            "free" -> {
                val target = args.getOrNull(1)
                // A bare `corral free` frees the caller's own leases, exactly like `free --all`;
                // `free <serial>` keeps its owner-scoped DELETE. Neither touches a foreign lease.
                if (target == "--all" || target == null) {
                    val owned = Json.parseToJsonElement(get("/api/status")).jsonObject["devices"]!!.jsonArray
                        .filter { (it.jsonObject["lease"] as? JsonObject)?.get("owner")?.jsonPrimitive?.content == owner }
                        .map { it.jsonObject["serial"]!!.jsonPrimitive.content }
                    if (owned.isEmpty()) println("no leases owned by $owner")
                    else {
                        owned.forEach { send("DELETE", "/api/leases/$it", Json.encodeToString(FreeRequest(owner = owner))) }
                        println("freed: ${owned.joinToString(", ")}")
                    }
                } else {
                    send("DELETE", "/api/leases/$target", Json.encodeToString(FreeRequest(owner = owner)))
                    println("freed $target")
                }
            }
            "renew" -> {
                val serial = args.getOrNull(1) ?: usage(2)
                send("POST", "/api/leases/$serial/renew", Json.encodeToString(RenewRequest(owner = owner)))
                println("renewed $serial")
            }
            "lease" -> {
                val (sel, rest0) = parseSelector(args.drop(1).toTypedArray())
                val leaseArgs = parseLeaseArgs(rest0) ?: usage(2)
                val cmd = leaseArgs.cmd
                if (cmd.isEmpty()) usage(2)
                val isGradle = cmd.firstOrNull()?.let { File(it).name } in setOf("gradle", "gradlew")
                val requested = leaseArgs.timeoutSeconds
                // The daemon caps a ticket at MAX_WAIT_S; say so instead of letting "601" read as honest.
                if (requested != null && requested > MAX_WAIT_S)
                    System.err.println("corral: --timeout $requested clamped to ${MAX_WAIT_S}s (max $MAX_WAIT_S)")
                val queueSeconds = requested?.takeIf { it > 0 }?.coerceAtMost(MAX_WAIT_S)
                when {
                    leaseArgs.all -> {
                        val claimed = mutableListOf<String>()
                        while (true) {
                            val s = claimFree(owner, pid, selector = sel) ?: break
                            claimed += s
                        }
                        if (claimed.isEmpty()) { System.err.println("corral: no free device"); exitProcess(1) }
                        val gradleFlag = if (isGradle) listOf("-PcorralOwner=$owner") else emptyList()
                        val argv = cmd + gradleFlag
                        var resolved: Boolean? = null
                        val launchArgv = commandArgv(argv, onResolved = { resolved = it })
                        val rc = runLeased(argv, resolved, {
                            ProcessBuilder(launchArgv).apply {
                                environment()["CORRAL_OWNER"] = owner
                            }.withAdbOnPath()
                        }, onStart = { child ->
                            claimed.forEach { registerPids(it, listOf(pid, child.pid())) }
                        }) { claimed.forEach { release(it) } }
                        exitProcess(rc)
                    }
                    // Row 35: wait in the FIFO queue, then run HOLDING the served device. The lease is
                    // accepted under this CLI's live pid and released only when the command exits, so
                    // the next ticket cannot take it while the command runs (the `wait` inversion).
                    queueSeconds != null -> {
                        val serial = awaitServed(sel, queueSeconds, pid, releaseOnExit = false)
                        exitProcess(runHolding(serial, cmd))
                    }
                    else -> {
                        val r = trySend("POST", "/api/leases",
                            Json.encodeToString(ClaimRequest(selector = sel, owner = owner, pid = pid, ttlSeconds = 1800)))
                        if (r.statusCode() != 200) {
                            System.err.println(busyLine(sel, r.body()))
                            exitProcess(1)
                        }
                        val serial = Json.parseToJsonElement(r.body()).jsonObject["serial"]!!.jsonPrimitive.content
                        exitProcess(runHolding(serial, cmd))
                    }
                }
            }
            "wait" -> {
                val (sel, rest) = parseSelector(args.drop(1).toTypedArray())
                val i = rest.indexOf("--timeout")
                val requested = if (i >= 0) rest.getOrNull(i + 1)?.toLongOrNull() ?: usage(2) else 600L
                // Same input handling as `lease`: a negative value is a usage error, and the daemon's
                // MAX_WAIT_S cap is stated at the moment it bites instead of silently shortening the wait.
                if (requested < 0) usage(2)
                val waitSeconds = if (requested > MAX_WAIT_S) {
                    System.err.println("corral: --timeout $requested clamped to ${MAX_WAIT_S}s (max $MAX_WAIT_S)")
                    MAX_WAIT_S
                } else requested
                if (waitSeconds == 0L) { println(claimOne(sel, holderPid())); return }
                // A served lease outlives this process only as long as the process does: hand it back on
                // exit (normal or signal-driven; a SIGKILL leaves the TTL/pid-prune machinery in charge).
                // Use `lease --timeout` when the device must stay held to run a command.
                println(awaitServed(sel, waitSeconds, holderPid(), releaseOnExit = true))
            }
            "gate" -> {
                if (rules.readonly.isEmpty() || rules.valueFlags.isEmpty()) {
                    System.err.println("corral: gate rules missing or incomplete at ${RulesFile.defaultPath()} — failing closed. Re-run install.sh or start the daemon to self-heal.")
                    exitProcess(1)
                }
                var gateOwner = owner
                var rest = args.drop(1)
                if (rest.firstOrNull() == "--owner") { gateOwner = rest.getOrNull(1) ?: usage(2); rest = rest.drop(2) }
                if (rest.firstOrNull() == "--") rest = rest.drop(1)
                when (val d = Gate.classify(rest, System.getenv("ANDROID_SERIAL"), rules)) {
                    is GateDecision.Pass -> {}
                    is GateDecision.Deny -> { System.err.println(denyText(null, d.reason)); exitProcess(1) }
                    is GateDecision.Check -> {
                        val obj = Json.parseToJsonElement(get(
                            "/api/check?serial=${URLEncoder.encode(d.serial, StandardCharsets.UTF_8)}&owner=${URLEncoder.encode(gateOwner, StandardCharsets.UTF_8)}")).jsonObject
                        val allowed = (obj["allowed"] as? JsonPrimitive)?.contentOrNull == "true"
                        if (!allowed) {
                            val reason = (obj["reason"] as? JsonPrimitive)?.contentOrNull ?: "unexpected check response"
                            System.err.println(denyText(d.serial, reason)); exitProcess(1)
                        }
                    }
                }
                execRealAdb(rest)
            }
            "queue" -> println(get("/api/queue"))
            "avd" -> {
                when (args.getOrNull(1) ?: "list") {
                    "list" -> {
                        val avds = Json.parseToJsonElement(get("/api/emulators")).jsonArray
                        if (avds.isEmpty()) println("no AVDs")
                        else avds.forEach { o ->
                            val obj = o.jsonObject
                            val name = obj["avd"]!!.jsonPrimitive.content
                            val serials = obj["runningSerials"]!!.jsonArray.map { it.jsonPrimitive.content }
                            println((listOf(name) + serials).joinToString(" "))
                        }
                    }
                    "start" -> {
                        val avd = args.getOrNull(2) ?: usage(2)
                        println(send("POST", "/api/emulators/${pathSegment(avd)}/start", "{}"))
                    }
                    "stop" -> {
                        val avd = args.getOrNull(2) ?: usage(2)
                        val force = args.drop(3).any { it == "--force" }
                        println(send("POST", "/api/emulators/${pathSegment(avd)}/stop",
                            Json.encodeToString(StopBody(force))))
                    }
                    else -> usage(2)
                }
            }
            "help" -> usage(0)
            else -> usage(2)
        }
    }

    /** Pulls --serial/--model/--os/--type/--avd out of args; returns (selector, remaining positional args). */
    private fun parseSelector(args: Array<String>): Pair<Selector, List<String>> {
        var sel = Selector(); val rest = mutableListOf<String>(); var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--serial" -> sel = sel.copy(serial = args.getOrNull(++i) ?: usage(2))
                "--model" -> sel = sel.copy(model = args.getOrNull(++i) ?: usage(2))
                "--os" -> sel = sel.copy(minOs = args.getOrNull(++i) ?: usage(2))
                "--type" -> sel = sel.copy(transport = args.getOrNull(++i) ?: usage(2))
                "--avd" -> sel = sel.copy(avd = args.getOrNull(++i) ?: usage(2))
                "--" -> { rest.addAll(args.drop(i)); i = args.size }
                else -> rest += args[i]
            }
            i++
        }
        return sel to rest
    }

    private fun usage(code: Int): Nothing {
        println("usage: corral devices|status|queue|owner|doctor|check [--owner <o>] [<serial>...]" +
            "|update [--apply]" +
            "|claim [--all|<serial>] [selector]|free [--all|<serial>]|renew <serial>" +
            "|wait [--timeout S (max 600)] [selector]|lease [--all | --timeout S (max 600)] [selector] -- <cmd>" +
            "|gate [--owner o] -- <adb args>" +
            "|screenshot <serial> [--out F]|events" +
            "|avd [list|start A|stop A [--force]]" +
            "\n  selector: --serial S --model M --os V(min) --type usb|wifi|emulator --avd A")
        exitProcess(code)
    }
}
