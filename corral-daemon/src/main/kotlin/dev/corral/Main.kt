package dev.corral

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

internal fun resolvePort(config: DaemonConfig): Int {
    val raw = config.get("CORRAL_PORT")
    parsePort(raw)?.let { return it }
    val chosen = parsePort(config.get("CORRAL_PORT", useFile = false)) ?: 8798
    System.err.println("corral: CORRAL_PORT '$raw' is unusable; using $chosen")
    return chosen
}

internal fun resolveDbPath(config: DaemonConfig): Path {
    val raw = config.get("CORRAL_DB")
    if (dbPathUsable(raw)) return Path.of(raw)
    val fallback = config.get("CORRAL_DB", useFile = false)
    val chosen = if (dbPathUsable(fallback)) fallback else config.defaultValue("CORRAL_DB")
    System.err.println("corral: CORRAL_DB '$raw' is unusable; using '$chosen'")
    return Path.of(chosen)
}

internal fun screenshotPolicy(config: DaemonConfig): ScreenshotPolicy =
    ScreenshotPolicy(config.get("CORRAL_SCREENSHOTS"), config.get("CORRAL_SCREENSHOT_OBSERVERS"))

internal fun resolveAdb(config: DaemonConfig): String {
    val raw = config.get("CORRAL_ADB")
    if (adbValueUsable(raw)) return raw
    val fileValue = config.fileValue("CORRAL_ADB")?.takeIf { it.isNotBlank() }
    if (fileValue == null || adbValueUsable(fileValue)) {
        // an env-provided bad value is preserved so /api/doctor can surface it, but say so
        System.err.println("corral: CORRAL_ADB '$raw' is unusable (from the environment)")
        return raw
    }
    val fallback = config.get("CORRAL_ADB", useFile = false)
    val chosen = if (adbValueUsable(fallback)) fallback else config.defaultValue("CORRAL_ADB")
    System.err.println("corral: CORRAL_ADB '$raw' is unusable; using '$chosen'")
    return chosen
}

/**
 * `CORRAL_DEMO=1` — the server-side demo flag, read once right after config resolution and then
 * held in a value: every gate below is decided from it, so a `PUT` can only ever be refused (I9).
 *
 * The flag is **environment-only by design**: `CORRAL_DEMO` is not a `daemon.env` key (`get` falls
 * through to the inherited environment for any unregistered key), it is not a registered
 * `DaemonConfig` key, and it has no `-D` twin — so the process's own environment, set by the
 * supervisor, is the one and only source, and `PUT /api/config` can neither set nor clear it.
 */
internal fun demoMode(config: DaemonConfig): Boolean =
    config.get("CORRAL_DEMO").trim().lowercase() in setOf("1", "true")

/** A file value the API wrote that the daemon had to reject: say so, then the effective value shows in GET. */
private fun warnRejectedFileValue(config: DaemonConfig, key: String) {
    val file = config.fileValue(key)?.takeIf { it.isNotBlank() } ?: return
    if (!config.usable(key, file))
        System.err.println("corral: $key '$file' in ${DaemonConfig.defaultPath()} is unusable; using '${config.get(key)}'")
}

fun main() {
    val config = DaemonConfig()
    warnRejectedFileValue(config, "CORRAL_PORT")
    warnRejectedFileValue(config, "CORRAL_DB")
    warnRejectedFileValue(config, "CORRAL_ADB")
    warnRejectedFileValue(config, "CORRAL_SCREENSHOTS")
    warnRejectedFileValue(config, "CORRAL_EMULATOR_LAUNCHER")
    // Demo mode is decided once, here, and every host-write gate below reads this value.
    val demo = demoMode(config)
    val port = resolvePort(config)
    val dbPath = resolveDbPath(config)
    dbPath.parent?.let { Files.createDirectories(it) }
    // Refuse a second daemon on the same DB: each would keep its own in-memory leases over one
    // SQLite file and double-lease. The OS drops the lock on crash, so a dead daemon never blocks.
    // Keep the returned reference for the process lifetime — an unreferenced FileLock can be
    // collected, which would release it.
    val dbLock = DbLock.acquire(dbPath) ?: run {
        System.err.println("corral: another daemon already holds $dbPath — refusing to start (two daemons must not share one CORRAL_DB)")
        exitProcess(1)
    }
    val adb = resolveAdb(config)
    val db = Db(dbPath)
    val scope = CoroutineScope(Dispatchers.IO)
    // The demo's world is the whole device side: pool, screenshots and emulator host, in-JVM (I1).
    // Loading it before anything else means a broken world fails before a single write.
    val world = if (demo) DemoWorld.load(scope) else null
    val emulators: EmulatorHost = world ?: Emulators(adb, { Emulators.resolveEmulatorPath(adb = adb) },
        launcherFor(override = config.get("CORRAL_EMULATOR_LAUNCHER")))
    // A graceful stop ends direct children; a SIGKILL can still orphan them.
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { emulators.shutdown() } })

    val monitor: DeviceSource = world ?: DeviceMonitor(adb, System.getenv("CORRAL_FAKE_DEVICES"), scope)
    // The one pool every consumer sees: adb's inventory minus serials this daemon just killed. Filter
    // here, exactly once per read — `liveDevices` forgets a serial adb no longer lists, so handing it
    // an already-filtered list would drop the suppression and resurrect the stopped emulator.
    val pool: () -> List<Device> = { emulators.liveDevices(monitor.devices) }
    val leases = LeaseManager(pool, db)
    leases.recoverPersisted()
    monitor.onChange = { leases.dispatchFreeDevices() }

    monitor.start()

    // Self-heal the SDK adb shim: an SDK update replaces platform-tools/adb (and the shim
    // with it); the daemon restores the wrapper, keeping the newer binary as adb.real.
    // Both heals are gated from the flag read above rather than at the call sites, so the 10 s tick
    // below cannot reintroduce the write: the demo touches no shim (I2) and no rules file (I3).
    val adbPath = Path.of(adb)
    val wrapperSource = Path.of(System.getProperty("user.home"), "bin", "adb")
    var lastShimStatus: String? = null
    fun selfHealSdkShim() {
        if (demo) return
        val status = runCatching { SdkShim.ensure(adbPath, wrapperSource) }.getOrNull() ?: return
        if (status != lastShimStatus) { println("corral: $status"); lastShimStatus = status }
    }
    selfHealSdkShim()

    var lastRulesStatus: String? = null
    fun selfHealRules() {
        if (demo) return
        val status = runCatching { RulesFile.ensure() }.getOrNull() ?: return
        if (status != lastRulesStatus) { println("corral: $status"); lastRulesStatus = status }
    }
    selfHealRules()

    scope.launch { while (true) { delay(10_000); try { leases.sweep() } catch (_: Exception) {}; runCatching { emulators.reapFailed() }; selfHealSdkShim(); selfHealRules() } } // expiry reaper + queue dispatch; inventory changes dispatch via monitor.onChange

    // The one outbound call: once shortly after boot, then daily. Off Dispatchers.IO and wrapped so a
    // missing network never delays or fails startup, and the loop can never die on a thrown check.
    // In demo mode the switch is fixed off in the supplier AND the loop is never started (I5): a
    // file or inherited CORRAL_UPDATE_CHECK=on cannot re-arm it.
    // The real daemon must re-read the file every cycle, exactly like UpdateCheck's default
    // supplier: the `config` built at the top of main() is a constructor snapshot of
    // `daemon.env`, so handing it over would freeze the kill-switch until a restart — `PUT
    // /api/config` writes the file, and only a fresh instance sees that write.
    val updateCheck = UpdateCheck(config = { if (demo) demoUpdateOff() else DaemonConfig() })
    if (!demo) scope.launch {
        delay(5_000)
        while (true) {
            runCatching { updateCheck.checkNow() }
            delay(24 * 60 * 60 * 1000L)
        }
    }
    if (demo) watchSupervisor(scope)

    val events = Events(
        { pool().map { DeviceStatus(it.serial, leases.snapshot[it.serial], it.booted) } },
        { leases.queueSnapshot() },
        scope, demo = demo)
    val env = AppEnv(pool, leases, adb, world ?: Screenshotter(pool, adb), screenshotPolicy(config), events,
        config, emulators, updateCheck, demo)
    events.start()
    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        guardLocalHost()
        corralModule(env)
        installMcp(env)
    }.start(wait = true)
}
