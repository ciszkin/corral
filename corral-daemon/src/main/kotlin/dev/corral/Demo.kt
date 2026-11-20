package dev.corral

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/*
 * `corral demo` - a supervised, isolated demo daemon (plan §5) and its narrative (plan §8). This
 * file owns the supervisor, the demo home and the two daemon-side demo helpers; the pool/screenshot/
 * emulator simulation is DemoWorld and the scripted beats are DemoNarrative.
 *
 * The supervisor never touches a device, a gate or the real home. It creates a home under the JVM's
 * temp directory, spawns the same jar with `CORRAL_DEMO=1` and a `-Duser.home` pointing at that
 * home, drives the narrative over loopback HTTP, and removes the home when the demo ends.
 * Everything it writes carries the marker below, so `--clean` can never remove a directory it did
 * not create (I10).
 */

/** The plan's default. A real daemon owns 8798 and the wrapper suite owns 8799; the demo scans up. */
internal const val DEFAULT_DEMO_PORT = 8799

/** How far past the requested port the supervisor looks for a free one (plan §5). */
internal const val DEMO_PORT_SCAN = 20

internal const val DEMO_MARKER = ".corral-demo"
internal const val DEMO_HOME_PREFIX = "corral-demo-"

/** Exit codes (plan §6): 2 usage, 3 no port / cannot create home, 4 refused as unsafe. */
internal const val DEMO_EXIT_USAGE = 2
internal const val DEMO_EXIT_SETUP = 3
internal const val DEMO_EXIT_REFUSED = 4

/**
 * The demo's update switch: fixed off whatever the config file or the inherited environment says
 * (I5). Injected into the daemon's [UpdateCheck] so a `daemon.env` that says `on` is still inert.
 */
internal fun demoUpdateOff(): DaemonConfig =
    DaemonConfig(fileValues = emptyMap(), inherited = mapOf("CORRAL_UPDATE_CHECK" to "off"))

/**
 * The demo daemon stops when the supervisor that spawned it is gone. A killed supervisor runs no
 * shutdown hook anywhere, so this is the only thing that keeps a crashed demo from leaving a JVM
 * serving a loopback port (I10). Absent `CORRAL_DEMO_SUPERVISOR` (a hand-started demo daemon) is a
 * no-op.
 */
internal fun watchSupervisor(scope: CoroutineScope, env: Map<String, String> = System.getenv()) {
    val pid = env["CORRAL_DEMO_SUPERVISOR"]?.toLongOrNull() ?: return
    scope.launch {
        while (true) {
            delay(500)
            if (!ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
                System.err.println("corral: demo supervisor $pid is gone - stopping this demo daemon")
                exitProcess(0)
            }
        }
    }
}

/** What a demo home records about itself. Parsed back by `--clean` and by `--home`; never trusted otherwise. */
internal data class DemoMarker(
    val supervisor: Long,
    val daemon: Long,
    val port: Int,
    val createdAt: Long,
) {
    fun render(version: String): String = buildString {
        appendLine("# corral demo home — a simulation. Remove it with: corral demo --clean")
        appendLine("marker=corral-demo")
        appendLine("version=$version")
        appendLine("supervisor=$supervisor")
        appendLine("daemon=$daemon")
        appendLine("port=$port")
        appendLine("created=${Instant.ofEpochMilli(createdAt)}")
    }

    companion object {
        /** Null for anything that is not a demo marker — an absent or foreign file is never a guess. */
        fun parse(text: String): DemoMarker? {
            val kv = text.lineSequence().map(String::trim)
                .filter { it.contains('=') && !it.startsWith("#") }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            if (kv["marker"] != "corral-demo") return null
            return DemoMarker(
                supervisor = kv["supervisor"]?.toLongOrNull() ?: 0,
                daemon = kv["daemon"]?.toLongOrNull() ?: 0,
                port = kv["port"]?.toIntOrNull() ?: 0,
                createdAt = kv["created"]?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0)
        }
    }
}

/** A demo home reports liveness by the two pids it recorded; a dead pid is a removable home. */
private fun DemoMarker.isRunning(): Boolean =
    listOf(supervisor, daemon).any { it != 0L && ProcessHandle.of(it).map { h -> h.isAlive }.orElse(false) }

/** Where demo homes live: the JVM's temp directory, so nothing lands beside the real install. */
internal fun demoHomesRoot(): Path = Path.of(System.getProperty("java.io.tmpdir"))

/**
 * Whether a loopback port can be bound right now — the demo must never take one that is in use.
 * `SO_REUSEADDR` must be OFF for this probe: on Windows it lets a second socket bind a port that is
 * already serving, which would make the probe say "free" for a port the demo cannot have.
 */
internal fun loopbackFree(port: Int): Boolean = runCatching {
    val socket = ServerSocket()
    socket.reuseAddress = false
    socket.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1)
    socket.close()
    true
}.getOrDefault(false)

/** The first free port at or after [requested], inside the plan's +[DEMO_PORT_SCAN] window. */
internal fun freeDemoPort(requested: Int, isFree: (Int) -> Boolean = ::loopbackFree): Int? =
    (requested..(requested + DEMO_PORT_SCAN)).firstOrNull(isFree)

/**
 * The child daemon's environment (plan §5's table). The six inherited variables that could point
 * the demo at anything real are removed rather than overridden: `CORRAL_ADB`, `CORRAL_EMULATOR`,
 * `CORRAL_REPO`, a fake inventory, the Android SDK selectors, and systemd's `INVOCATION_ID`.
 */
internal fun demoEnv(home: Path, port: Int, supervisor: Long): Map<String, String> = mapOf(
    "CORRAL_DEMO" to "1",
    "CORRAL_DEMO_SUPERVISOR" to supervisor.toString(),
    "CORRAL_PORT" to port.toString(),
    "CORRAL_DB" to home.resolve("corral.db").toString(),
    "CORRAL_RULES" to home.resolve("rules").toString(),
    "CORRAL_ENV_FILE" to home.resolve("daemon.env").toString(),
    "CORRAL_UPDATE_CHECK" to "off",
    "CORRAL_SCREENSHOT_OBSERVERS" to "corral-ui",
    "CORRAL_LEASE_TTL_SECONDS" to "60",
    "CORRAL_EMULATOR_LAUNCHER" to "direct",
)

/** Variables an inherited shell must not be able to hand the demo daemon. */
internal val DEMO_UNSET = listOf(
    "CORRAL_ADB", "CORRAL_EMULATOR", "CORRAL_REPO", "CORRAL_FAKE_DEVICES", "CORRAL_DEMO_WORLD",
    "ANDROID_SERIAL", "ANDROID_HOME", "ANDROID_SDK_ROOT", "INVOCATION_ID",
)

/**
 * Demo mode's write barrier (I9), applied by `PUT /api/config` before anything is written. A demo
 * must not be walked into I1 (a real adb/emulator path), I3 (a DB outside the demo home) or I5 (an
 * update check) by its own config route. Turning the update check *off* stays allowed: it is already
 * off, so the write is a no-op rather than a hole.
 */
internal fun demoSettingRefusal(key: String, raw: String): String? = when (key) {
    "CORRAL_ADB" -> "CORRAL_ADB is refused in demo mode: the demo pool is in-JVM and never runs adb"
    "CORRAL_EMULATOR" -> "CORRAL_EMULATOR is refused in demo mode: the demo never starts an emulator binary"
    "CORRAL_DB" -> "CORRAL_DB is refused in demo mode: the demo keeps its own database in the demo home"
    "CORRAL_UPDATE_CHECK" ->
        if (raw.trim().lowercase() == "on") "CORRAL_UPDATE_CHECK=on is refused in demo mode: a demo makes no network call"
        else null
    else -> null
}

/** The jar the demo daemon is spawned from: the running one, or `CORRAL_JAR` when that is a jar. */
internal fun daemonJar(): Path? {
    val self = runCatching { CorralCli::class.java.protectionDomain?.codeSource?.location }.getOrNull()
        ?.let { runCatching { Path.of(it.toURI()) }.getOrNull() }
    if (self != null && Files.isRegularFile(self) && self.toString().endsWith(".jar", ignoreCase = true)) return self
    val fromEnv = System.getenv("CORRAL_JAR")?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }
    return fromEnv?.takeIf { Files.isRegularFile(it) }
}

/**
 * `java -Duser.home=<home> -Djava.io.tmpdir=<home>/tmp -jar <jar>`: the same jar as a second,
 * isolated process. Both homes point into the demo home, so even the JVM's scratch files (the
 * sqlite-jdbc native library, for one) are removed with it. `CORRAL_DEMO` comes from the child's
 * environment and nowhere else — there is no `-Dcorral.demo` twin, so the flag has exactly one
 * source.
 */
internal fun demoCommand(java: Path, jar: Path, home: Path): List<String> = listOf(
    java.toString(), "-Duser.home=$home", "-Djava.io.tmpdir=${home.resolve("tmp")}",
    "-jar", jar.toString())

/**
 * Write the demo home's contents that do not depend on the child: the rules copy and a `daemon.env`
 * that says what the demo is. The marker is written once, in [runDemo], after the child's pid is
 * known — a home is never marked before there is something to point at. Nothing here reads or
 * writes the real home (I3).
 */
private fun prepareHome(home: Path) {
    Files.createDirectories(home)
    Files.createDirectories(home.resolve("tmp"))   // the child's java.io.tmpdir: everything dies with the home
    val rules = home.resolve("rules")
    if (!Files.exists(rules)) {
        // the same factory default the daemon ships — never the operator's own file
        val body = DemoWorld::class.java.getResourceAsStream("/corral-rules")?.use { it.readBytes() }
        if (body != null) runCatching { Files.write(rules, body) }
    }
    Files.writeString(home.resolve("daemon.env"),
        "# corral demo — this file belongs to a demo home; `corral demo --clean` removes it\n" +
            "CORRAL_UPDATE_CHECK=off\n")
}

/** The daemon.log tail, so a failed start explains itself instead of printing "failed". */
private fun logTail(home: Path, lines: Int = 10): String = runCatching {
    Files.readAllLines(home.resolve("daemon.log")).takeLast(lines).joinToString("\n")
}.getOrDefault("")

/**
 * Remove a demo home: retried, and never left un-marked (review F1).
 *
 * On Windows the child's `java.io.tmpdir` is `<home>/tmp`, so sqlite-jdbc's extracted native
 * library is a *mapped image* the filesystem refuses to unlink the instant the process object
 * exits. That is a filesystem fact, not a shutdown one — so this waits a moment and tries again
 * rather than spending more of the child's stop budget. And the marker is the home's provenance:
 * it is deleted **last**, only once nothing else remains, because a residue that loses its marker
 * is one `corral demo --clean` can never collect (I10).
 *
 * [delete] is the seam the tests use to make a single file refuse to go; it is
 * `Files.deleteIfExists` everywhere else.
 */
internal fun removeTree(dir: Path, attempts: Int = 3, pauseMs: Long = 350,
                        delete: (Path) -> Unit = { Files.deleteIfExists(it) }): Boolean {
    if (!Files.exists(dir)) return true
    val marker = dir.resolve(DEMO_MARKER)
    repeat(attempts) { attempt ->
        runCatching {
            Files.walk(dir).use { walk ->
                walk.sorted(Comparator.reverseOrder())
                    .filter { it != dir && it.fileName.toString() != DEMO_MARKER }
                    .forEach { runCatching { delete(it) } }
            }
        }
        // Anything but the marker still inside means the tree did not go: the marker stays. A
        // listing that fails is read as "something is left", never as "empty, safe to unmark".
        val residue = runCatching { Files.list(dir).use { s -> s.anyMatch { it != marker } } }.getOrDefault(true)
        if (!residue) {
            runCatching { delete(marker) }
            runCatching { delete(dir) }
            if (!Files.exists(dir)) return true
        }
        if (attempt < attempts - 1) runCatching { Thread.sleep(pauseMs) }
    }
    return !Files.exists(dir)
}

/**
 * Remove a home at a lifecycle boundary. [removeTree] retries, so this normally says nothing; a
 * residue that survives the retries is named, because silence is what made F1 invisible.
 */
private fun removeHome(home: Path): Boolean {
    if (removeTree(home)) return true
    System.err.println("demo: could not remove $home - its marker stays, so `corral demo --clean` can collect it")
    return false
}

/** One demo device in the banner: the serial, the transport, the DEMO-labelled model and readiness. */
internal fun demoDeviceLine(d: Device): String {
    val ready = when {
        d.state != "device" -> d.state
        !d.booted -> "booting (not claimable yet)"
        else -> "ready"
    }
    val avd = d.avd?.let { " [$it]" } ?: ""
    return "  ${d.serial.padEnd(18)} ${d.transport.padEnd(9)} ${(d.model ?: "?").padEnd(24)}$avd  $ready"
}

/**
 * `--speed X` (plan §6): 0.5–4, default 1, and it scales the beat delays only — a lease TTL, an
 * emulator's boot and the FIFO queue are the daemon's, not the script's. Null means "not a speed",
 * which the caller turns into exit 2 rather than silently clamping: a typo must not produce a demo
 * that looks right and runs at the wrong pace.
 */
internal fun parseDemoSpeed(raw: String): Double? =
    raw.trim().toDoubleOrNull()?.takeIf { it >= 0.5 && it <= 4.0 }

/**
 * The demo's own stdout, pinned to UTF-8. The summary line carries the plan's `·`, and a captured
 * log on a host whose default charset is not UTF-8 would otherwise keep a `?` where the separator
 * was. Tests pass their own stream and it is returned untouched.
 */
internal fun demoOutput(default: PrintStream = System.out): PrintStream =
    if (default !== System.out) default
    else PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8)

/** Everything a cycle needs, so `--loop` can run the same one again with a fresh world. */
private class DemoOptions(
    val port: Int,
    val home: Path?,
    val keep: Boolean,
    val loop: Boolean,
    /** false = `--no-script`: start the world, print where to look, and wait. */
    val script: Boolean,
    val plain: Boolean,
    val speed: Double,
)

/** The one shutdown hook a demo registers, however many `--loop` cycles it runs. */
private class DemoHandle {
    @Volatile var child: Process? = null
    @Volatile var home: Path? = null
    @Volatile var keep = false
    val stopping = AtomicBoolean(false)

    fun shutdown() {
        stopping.set(true)
        val c = child ?: return
        runCatching { c.destroy() }
        runCatching { if (!c.waitFor(2, TimeUnit.SECONDS)) c.destroyForcibly() }
        val h = home
        if (!keep && h != null && removeHome(h)) System.err.println("demo: removed $h")
    }
}

private fun usage(out: PrintStream): Int {
    out.println("usage: corral demo [--port P] [--speed X] [--loop] [--no-script] [--keep] [--home DIR] [--plain]")
    out.println("                  | --clean | --help")
    out.println("  --port P      first port to try (default $DEFAULT_DEMO_PORT; scans +$DEMO_PORT_SCAN for a free one)")
    out.println("  --speed X     scale the beat delays by 1/X (0.5-4, default 1); a lease, a boot and a TTL stay real")
    out.println("  --loop        run the script again with a fresh world each cycle (kiosk, GIF capture)")
    out.println("  --no-script   start the world, print where to look, and wait (no narrative)")
    out.println("  --plain       no ANSI colour or cursor movement, for a clean recording")
    out.println("  --keep        leave the demo home in place when the demo stops")
    out.println("  --home DIR    use DIR as the demo home (refused if it exists without a demo marker, or is a running demo's)")
    out.println("  --clean       remove demo homes that carry a marker and are not running; names what it leaves")
    return DEMO_EXIT_USAGE
}

private fun help(out: PrintStream) {
    out.println("corral demo - a simulated pool. No phones, no adb, no SDK, no network; nothing is installed.")
    out.println("It starts a second, isolated daemon on a free loopback port, then plays a ~30 s script:")
    out.println("a claim, a refusal, a queued ticket that is served when the holder leaves, and an emulator")
    out.println("that boots behind a waiting ticket. Nothing it shows is a real device; nothing is written")
    out.println("outside the demo home it prints and removes.")
    usage(out)
}

/**
 * `corral demo --clean`: removes only directories that carry a demo marker, are under the demo
 * homes root, and whose supervisor and daemon are both gone. Anything else is left and named.
 */
internal fun cleanDemoHomes(out: PrintStream, root: Path = demoHomesRoot()): Int {
    if (!Files.isDirectory(root)) {
        out.println("demo: no demo homes under $root")
        return 0
    }
    val candidates = runCatching {
        Files.list(root).use { s -> s.filter { Files.isDirectory(it) }.sorted().toList() }
    }.getOrDefault(emptyList())
    var removed = 0
    for (dir in candidates) {
        if (!dir.fileName.toString().startsWith(DEMO_HOME_PREFIX)) continue
        val marker = runCatching { Files.readString(dir.resolve(DEMO_MARKER)) }.getOrNull()
            ?.let(DemoMarker::parse)
        when {
            marker == null -> out.println("demo: left alone $dir (no demo marker - not ours to remove)")
            marker.isRunning() -> out.println("demo: left alone $dir (still running: daemon ${marker.daemon})")
            removeTree(dir) -> { out.println("demo: removed $dir"); removed++ }
            else -> out.println("demo: left alone $dir (could not remove every file)")
        }
    }
    out.println("demo: $removed home(s) removed")
    return 0
}

/**
 * The `corral demo` supervisor (plan §5, §6). Returns the process exit code: 0 ok, 2 usage,
 * 3 no free port / cannot create the home, 4 refused as unsafe.
 *
 * One command, one or many cycles: `--loop` repeats [runCycle], each time with a fresh home and
 * therefore a fresh world — a kiosk cannot inherit the previous run's leases or a booted emulator.
 */
internal fun runDemo(args: List<String>, out: PrintStream = System.out): Int {
    var requested = DEFAULT_DEMO_PORT
    var homeArg: Path? = null
    var keep = false
    var clean = false
    var loop = false
    var noScript = false
    var plain = false
    var speed = 1.0
    var i = 0
    while (i < args.size) {
        val a = args[i]
        val next = { args.getOrNull(i + 1) }
        when {
            a == "--port" || a.startsWith("--port=") -> {
                val raw = if (a == "--port") {
                    val v = next() ?: run {
                        System.err.println("corral: --port needs a value")
                        return usage(out)
                    }
                    i++; v
                } else a.substringAfter('=')
                requested = parsePort(raw) ?: run {
                    System.err.println("corral: --port must be 1024–65535 (got '$raw')")
                    return DEMO_EXIT_USAGE
                }
            }
            a == "--home" || a.startsWith("--home=") -> {
                val raw = if (a == "--home") {
                    val v = next() ?: run {
                        System.err.println("corral: --home needs a directory")
                        return usage(out)
                    }
                    i++; v
                } else a.substringAfter('=')
                homeArg = runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull() ?: run {
                    System.err.println("corral: --home '$raw' is not a usable path")
                    return DEMO_EXIT_REFUSED
                }
            }
            a == "--speed" || a.startsWith("--speed=") -> {
                val raw = if (a == "--speed") {
                    val v = next() ?: run {
                        System.err.println("corral: --speed needs a value")
                        return usage(out)
                    }
                    i++; v
                } else a.substringAfter('=')
                speed = parseDemoSpeed(raw) ?: run {
                    System.err.println("corral: --speed must be a number in 0.5-4 (got '$raw')")
                    return DEMO_EXIT_USAGE
                }
            }
            a == "--keep" -> keep = true
            a == "--clean" -> clean = true
            a == "--loop" -> loop = true
            a == "--no-script" -> noScript = true
            a == "--plain" -> plain = true
            a == "--help" || a == "-h" -> { help(out); return 0 }
            else -> { System.err.println("corral: unknown demo argument '$a'"); return usage(out) }
        }
        i++
    }
    if (clean) return cleanDemoHomes(out)

    val realHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
    val realCorral = realHome.resolve(".corral")
    val realConfig = realHome.resolve(".config").resolve("corral")
    val home = homeArg ?: demoHomesRoot().resolve(DEMO_HOME_PREFIX + UUID.randomUUID().toString().take(8))
    if (home == realHome || home.startsWith(realCorral) || home.startsWith(realConfig)) {
        System.err.println("corral: --home $home is inside the real Corral home - a demo must never write there")
        return DEMO_EXIT_REFUSED
    }
    if (homeArg != null && Files.exists(home)) {
        val existing = runCatching { Files.readString(home.resolve(DEMO_MARKER)) }.getOrNull()?.let(DemoMarker::parse)
        if (existing == null) {
            System.err.println("corral: --home $home exists and carries no demo marker - refusing to reuse or delete it")
            return DEMO_EXIT_REFUSED
        }
        // A running demo's home is not a scratch directory: reusing it would rewrite the marker under
        // the daemon that is serving from it, and a failed start would then delete a live home (I10).
        if (existing.isRunning()) {
            System.err.println(
                "corral: --home $home belongs to a running demo (supervisor ${existing.supervisor}, " +
                    "daemon ${existing.daemon}, port ${existing.port}) - stop it or pick another directory")
            return DEMO_EXIT_REFUSED
        }
    }
    // A kiosk cycle owns its home: it is created, marked, served from and removed. A fixed --home
    // would be reused while its database still carries the previous run's leases.
    if (loop && homeArg != null) {
        System.err.println("corral: --loop starts a fresh world in a fresh home every cycle; --home fixes one directory, so they cannot be combined")
        return DEMO_EXIT_USAGE
    }

    val jar = daemonJar() ?: run {
        System.err.println("corral: cannot find the daemon jar to spawn - run the installed `corral` or set CORRAL_JAR")
        return DEMO_EXIT_SETUP
    }
    val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java")
    if (!Files.isExecutable(java)) {
        System.err.println("corral: no java at $java - cannot start the demo daemon")
        return DEMO_EXIT_SETUP
    }

    val options = DemoOptions(requested, homeArg, keep, loop, script = !noScript, plain = plain, speed = speed)
    val handle = DemoHandle()
    Runtime.getRuntime().addShutdownHook(Thread { handle.shutdown() })

    var cycleNo = 0
    while (true) {
        cycleNo++
        val code = runCycle(options, cycleNo, java, jar, handle, out)
        if (code != 0 || !options.loop) return code
    }
}

/**
 * One demo: a home, a free port, a daemon, then either the narrative or an idle pool. Returns the
 * exit code for this cycle (0 when the script finished or a stop was clean).
 */
private fun runCycle(options: DemoOptions, cycleNo: Int, java: Path, jar: Path,
                     handle: DemoHandle, out: PrintStream): Int {
    val home = options.home
        ?: demoHomesRoot().resolve(DEMO_HOME_PREFIX + UUID.randomUUID().toString().take(8))
    val port = freeDemoPort(options.port) ?: run {
        System.err.println("corral: no free loopback port in ${options.port}..${options.port + DEMO_PORT_SCAN}")
        return DEMO_EXIT_SETUP
    }

    runCatching { prepareHome(home) }.onFailure {
        System.err.println("corral: cannot create the demo home $home: ${it.message}")
        return DEMO_EXIT_SETUP
    }

    out.println("corral demo - a simulated pool. No phones, no adb, no SDK, no network; nothing is installed.")
    if (options.loop) out.println("demo: cycle $cycleNo - a fresh world")
    out.println("demo: home $home")
    if (port != options.port) out.println("demo: port ${options.port} is busy; using $port")
    out.println("demo: port $port")

    val supervisor = ProcessHandle.current().pid()
    val builder = ProcessBuilder(demoCommand(java, jar, home))
        .directory(home.toFile())
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("daemon.log").toFile()))
    demoEnv(home, port, supervisor).forEach { (k, v) -> builder.environment()[k] = v }
    DEMO_UNSET.forEach { builder.environment().remove(it) }
    val started = System.currentTimeMillis()
    val child = runCatching { builder.start() }.getOrElse {
        System.err.println("corral: cannot start the demo daemon: ${it.message}")
        if (!options.keep) removeTree(home)
        return DEMO_EXIT_SETUP
    }
    runCatching {
        Files.writeString(home.resolve(DEMO_MARKER),
            DemoMarker(supervisor, child.pid(), port, started).render(VersionSource.version))
    }
    handle.child = child
    handle.home = home
    handle.keep = options.keep

    val url = "http://127.0.0.1:$port"
    val devices = awaitDevices(url, child) ?: run {
        System.err.println("corral: the demo daemon did not answer on $url - its log said:")
        logTail(home).lineSequence().forEach { System.err.println("  $it") }
        runCatching { child.destroyForcibly() }
        if (!options.keep) removeHome(home)
        return DEMO_EXIT_SETUP
    }
    out.println("demo: daemon up in ${System.currentTimeMillis() - started} ms (pid ${child.pid()})")
    out.println("demo: log ${home.resolve("daemon.log")}")

    // --no-script: the world, where to look, and a wait. The pool table is the whole first screen.
    if (!options.script) {
        val emulatorDown = devices.count { !it.booted && it.transport == "emulator" }
        out.println("DEMO pool - ${devices.size} simulated device(s), $emulatorDown waiting on a boot:")
        devices.forEach { out.println(demoDeviceLine(it)) }
        out.println("demo: CLI  CORRAL_URL=$url corral devices")
        out.println("demo: console  corral-ui --url $url")
        out.println("demo: press Ctrl-C to stop" +
            if (options.keep) " (--keep: the home is left in place)" else " and remove this home")
        while (child.isAlive) Thread.sleep(200)
        return afterChildExit(child, home, options.keep, handle)
    }

    // A recording (or a log) must read as text: --plain, NO_COLOR, or a redirected stdout all mean
    // the same thing, and only a real terminal gets colour.
    val plain = options.plain || System.getenv("NO_COLOR") != null || System.console() == null
    val narrative = DemoNarrative(HttpDemoTransport(url), DemoConsole(out, plain), url, options.speed)
    runCatching { narrative.run() }.onFailure {
        System.err.println("corral: the demo script stopped at ${it.message ?: "an unknown failure"}")
        System.err.println("demo: the daemon's log is ${home.resolve("daemon.log")}")
        logTail(home, 8).lineSequence().forEach { line -> System.err.println("  $line") }
        stopChild(child, home, options.keep)
        handle.child = null; handle.home = null
        return 1
    }
    // The script is the demo: it ends, and so does the daemon. An idle pool is `--no-script`.
    stopChild(child, home, options.keep)
    handle.child = null; handle.home = null
    return 0
}

/** Stop a cycle's daemon and remove its home unless `--keep`; never leaves a JVM behind. */
private fun stopChild(child: Process, home: Path, keep: Boolean) {
    runCatching { child.destroy() }
    runCatching { if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly() }
    if (!keep) removeHome(home)
}

/** The exit code after an idle (`--no-script` / `--loop`) daemon went away on its own. */
private fun afterChildExit(child: Process, home: Path, keep: Boolean, handle: DemoHandle): Int {
    handle.child = null
    if (handle.stopping.get()) return 0
    System.err.println("corral: the demo daemon exited (code ${runCatching { child.exitValue() }.getOrDefault(-1)})" +
        " - see ${home.resolve("daemon.log")}")
    if (!keep) removeHome(home)
    handle.home = null
    return 1
}

/** Poll `/api/devices` until the demo daemon answers (bounded 15 s) — never a fixed sleep. */
private fun awaitDevices(url: String, child: Process): List<Device>? {
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
    val deadline = System.currentTimeMillis() + 15_000
    while (System.currentTimeMillis() < deadline) {
        if (!child.isAlive) return null
        // Only the child this supervisor spawned may answer. A port that was free when probed can be
        // taken by a demo daemon that was still booting — CI run 44772818392: a killed `--loop`
        // cycle's daemon on 8908 answered this probe and then exited, and beat 1 died on the socket.
        // The pid in /api/status is what tells the two apart; anything else keeps waiting.
        val status = runCatching {
            client.send(HttpRequest.newBuilder(URI.create("$url/api/status"))
                .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString())
        }.getOrNull()
        val ours = status?.takeIf { it.statusCode() == 200 }?.let { response ->
            runCatching { corralJson.decodeFromString<StatusResponse>(response.body()) }.getOrNull()
        }?.pid == child.pid()
        if (ours) {
            val body = runCatching {
                client.send(HttpRequest.newBuilder(URI.create("$url/api/devices"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString())
            }.getOrNull()
            if (body != null && body.statusCode() == 200) {
                runCatching { corralJson.decodeFromString<List<Device>>(body.body()) }.getOrNull()?.let { return it }
            }
        }
        Thread.sleep(150)
    }
    return null
}
