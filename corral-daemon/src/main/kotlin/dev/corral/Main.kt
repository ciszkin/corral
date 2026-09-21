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

fun main() {
    val port = System.getenv("CORRAL_PORT")?.toInt() ?: 8798
    val dbPath = System.getenv("CORRAL_DB")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".corral", "corral.db") // one portable state dir on every OS
    dbPath.parent?.let { Files.createDirectories(it) }
    // Refuse a second daemon on the same DB: each would keep its own in-memory leases over one
    // SQLite file and double-lease. The OS drops the lock on crash, so a dead daemon never blocks.
    // Keep the returned reference for the process lifetime — an unreferenced FileLock can be
    // collected, which would release it.
    val dbLock = DbLock.acquire(dbPath) ?: run {
        System.err.println("corral: another daemon already holds $dbPath — refusing to start (two daemons must not share one CORRAL_DB)")
        exitProcess(1)
    }
    val adb = System.getenv("CORRAL_ADB") ?: "adb"
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
    val env = AppEnv({ monitor.devices }, leases, adb, Screenshotter({ monitor.devices }, adb), ScreenshotPolicy(), events)
    events.start()
    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        guardLocalHost()
        corralModule(env)
        installMcp(env)
    }.start(wait = true)
}
