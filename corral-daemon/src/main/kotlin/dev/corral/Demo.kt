package dev.corral

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/*
 * `corral demo` - a supervised, isolated demo daemon (plan §5). This file owns the supervisor and
 * the two daemon-side demo helpers; the pool/screenshot/emulator simulation itself is DemoWorld.
 *
 * The supervisor never touches a device, a gate or the real home. It creates a home under the JVM's
 * temp directory, spawns the same jar with `CORRAL_DEMO=1` and a `-Duser.home` pointing at that
 * home, and removes the home on a graceful stop. Everything it writes carries the marker below, so
 * `--clean` can never remove a directory it did not create (I10).
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

private fun removeTree(dir: Path): Boolean {
    runCatching {
        Files.walk(dir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
    return !Files.exists(dir)
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

private fun usage(out: PrintStream): Int {
    out.println("usage: corral demo [--port P] [--keep] [--home DIR] | --clean | --help")
    out.println("  --port P    first port to try (default $DEFAULT_DEMO_PORT; scans +$DEMO_PORT_SCAN for a free one)")
    out.println("  --keep      leave the demo home in place when the demo stops")
    out.println("  --home DIR  use DIR as the demo home (refused if it exists without a demo marker, or is a running demo's)")
    out.println("  --clean     remove demo homes that carry a marker and are not running; names what it leaves")
    return DEMO_EXIT_USAGE
}

private fun help(out: PrintStream) {
    out.println("corral demo - a simulated pool. No phones, no adb, no SDK, no network; nothing is installed.")
    out.println("It starts a second, isolated daemon on a free loopback port and prints where to look.")
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
 */
internal fun runDemo(args: List<String>, out: PrintStream = System.out): Int {
    var requested = DEFAULT_DEMO_PORT
    var homeArg: Path? = null
    var keep = false
    var clean = false
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
            a == "--keep" -> keep = true
            a == "--clean" -> clean = true
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

    val jar = daemonJar() ?: run {
        System.err.println("corral: cannot find the daemon jar to spawn - run the installed `corral` or set CORRAL_JAR")
        return DEMO_EXIT_SETUP
    }
    val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows) "java.exe" else "java")
    if (!Files.isExecutable(java)) {
        System.err.println("corral: no java at $java - cannot start the demo daemon")
        return DEMO_EXIT_SETUP
    }
    val port = freeDemoPort(requested) ?: run {
        System.err.println("corral: no free loopback port in $requested..${requested + DEMO_PORT_SCAN}")
        return DEMO_EXIT_SETUP
    }

    runCatching { prepareHome(home) }.onFailure {
        System.err.println("corral: cannot create the demo home $home: ${it.message}")
        return DEMO_EXIT_SETUP
    }

    out.println("corral demo - a simulated pool. No phones, no adb, no SDK, no network; nothing is installed.")
    out.println("demo: home $home")
    if (port != requested) out.println("demo: port $requested is busy; using $port")
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
        if (!keep) removeTree(home)
        return DEMO_EXIT_SETUP
    }
    runCatching {
        Files.writeString(home.resolve(DEMO_MARKER),
            DemoMarker(supervisor, child.pid(), port, started).render(VersionSource.version))
    }

    val stopping = AtomicBoolean(false)
    Runtime.getRuntime().addShutdownHook(Thread {
        stopping.set(true)
        runCatching { child.destroy() }
        runCatching { if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly() }
        if (!keep && removeTree(home)) System.err.println("demo: removed $home")
    })

    val url = "http://127.0.0.1:$port"
    val devices = awaitDevices(url, child) ?: run {
        System.err.println("corral: the demo daemon did not answer on $url - its log said:")
        logTail(home).lineSequence().forEach { System.err.println("  $it") }
        runCatching { child.destroyForcibly() }
        if (!keep) removeTree(home)
        return DEMO_EXIT_SETUP
    }
    out.println("demo: daemon up in ${System.currentTimeMillis() - started} ms (pid ${child.pid()})")
    val emulatorDown = devices.count { !it.booted && it.transport == "emulator" }
    out.println("DEMO pool - ${devices.size} simulated device(s), $emulatorDown waiting on a boot:")
    devices.forEach { out.println(demoDeviceLine(it)) }
    out.println("demo: log ${home.resolve("daemon.log")}")
    out.println("demo: CLI  CORRAL_URL=$url corral devices")
    out.println("demo: console  corral-ui --url $url")
    out.println("demo: press Ctrl-C to stop" + if (keep) " (--keep: the home is left in place)" else " and remove this home")

    while (child.isAlive) Thread.sleep(200)
    if (!stopping.get()) {
        System.err.println("corral: the demo daemon exited (code ${runCatching { child.exitValue() }.getOrDefault(-1)}) - see ${home.resolve("daemon.log")}")
        if (!keep) removeTree(home)
        return 1
    }
    return 0
}

/** Poll `/api/devices` until the demo daemon answers (bounded 15 s) — never a fixed sleep. */
private fun awaitDevices(url: String, child: Process): List<Device>? {
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
    val deadline = System.currentTimeMillis() + 15_000
    while (System.currentTimeMillis() < deadline) {
        if (!child.isAlive) return null
        val body = runCatching {
            client.send(HttpRequest.newBuilder(URI.create("$url/api/devices"))
                .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString())
        }.getOrNull()
        if (body != null && body.statusCode() == 200) {
            runCatching { corralJson.decodeFromString<List<Device>>(body.body()) }.getOrNull()?.let { return it }
        }
        Thread.sleep(150)
    }
    return null
}
