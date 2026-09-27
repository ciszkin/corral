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

    val monitor = DeviceMonitor(adb, System.getenv("CORRAL_FAKE_DEVICES"), scope)
    val leases = LeaseManager({ monitor.devices }, db)
    leases.recoverPersisted()
    monitor.onChange = { leases.dispatchFreeDevices() }

    monitor.start()

    // Self-heal the SDK adb shim: an SDK update replaces platform-tools/adb (and the shim
    // with it); the daemon restores the wrapper, keeping the newer binary as adb.real.
    val adbPath = Path.of(adb)
    val wrapperSource = Path.of(System.getProperty("user.home"), "bin", "adb")
    var lastShimStatus: String? = null
    fun selfHealSdkShim() {
        val status = runCatching { SdkShim.ensure(adbPath, wrapperSource) }.getOrNull() ?: return
        if (status != lastShimStatus) { println("corral: $status"); lastShimStatus = status }
    }
    selfHealSdkShim()

    var lastRulesStatus: String? = null
    fun selfHealRules() {
        val status = runCatching { RulesFile.ensure() }.getOrNull() ?: return
        if (status != lastRulesStatus) { println("corral: $status"); lastRulesStatus = status }
    }
    selfHealRules()

    scope.launch { while (true) { delay(10_000); try { leases.sweep() } catch (_: Exception) {}; selfHealSdkShim(); selfHealRules() } } // expiry reaper + queue dispatch; inventory changes dispatch via monitor.onChange

    val events = Events(
        { monitor.devices.map { DeviceStatus(it.serial, leases.snapshot[it.serial]) } },
        { leases.queueSnapshot() },
        scope)
    val env = AppEnv({ monitor.devices }, leases, adb, Screenshotter({ monitor.devices }, adb), screenshotPolicy(config), events, config)
    events.start()
    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        guardLocalHost()
        corralModule(env)
        installMcp(env)
    }.start(wait = true)
}
