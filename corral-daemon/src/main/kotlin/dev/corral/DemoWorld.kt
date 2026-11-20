package dev.corral

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** One device of the demo world. Field names are the real DTO's (`osVersion`, not `os`). */
@Serializable
internal data class WorldDevice(
    val serial: String,
    val state: String = "device",
    val model: String? = null,
    val osVersion: String? = null,
    val avd: String? = null,
    /** Null keeps [Device]'s own default: everything but an emulator is booted. */
    val booted: Boolean? = null,
)

/** An AVD the demo can list and boot; [bootSeconds] is the demo's boot-gate delay. */
@Serializable internal data class WorldAvd(
    val name: String,
    val bootSeconds: Long = 6,
    /**
     * The instance this AVD boots as. Only needed when no device entry owns the AVD: an emulator
     * that has not booted cannot name its AVD in reality (`emu avd name` needs an online device),
     * so the shipped world declares the pairing here and lets `avd start` create the instance.
     */
    val serial: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
)

@Serializable
internal data class DemoWorldFile(
    val devices: List<WorldDevice> = emptyList(),
    val avds: List<WorldAvd> = emptyList(),
    /** Serial -> jar resource holding that device's screen (absent falls back to [DEFAULT_SCREEN]). */
    val screens: Map<String, String> = emptyMap(),
)

/**
 * The whole device side of a demo daemon, in-JVM: the pool, the screenshot bytes and the emulator
 * host. Nothing here opens a process, a socket or a real device path (I1) — that is exactly why the
 * demo cannot reuse `DeviceMonitor`/`Screenshotter`/`Emulators`, which all shell out.
 *
 * The clock is injectable so a boot flip is a value, not a sleep; the pump in [start] only turns a
 * due flip into an inventory nudge, the way [DeviceMonitor] nudges the queue after every frame.
 */
internal class DemoWorld(
    private val file: DemoWorldFile,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Classpath loader, injectable so a unit test needs no jar fixture. */
    private val resource: (String) -> ByteArray? = { name ->
        DemoWorld::class.java.getResourceAsStream(name)?.use { it.readBytes() }
    },
) : DeviceSource, ScreenshotSource, EmulatorHost {
    private val lock = Any()
    @Volatile private var current: List<Device> = file.devices.map(::deviceOf)
    /** AVD -> boot deadline, set by [start]; a due entry flips its device to booted. */
    private val bootAt = ConcurrentHashMap<String, Long>()
    /** AVDs this demo started — "live" without a process to ask. */
    private val launched = ConcurrentHashMap.newKeySet<String>()
    private val shots = ConcurrentHashMap<String, ByteArray>()

    override var onChange: (suspend () -> Unit)? = null

    override val devices: List<Device> get() { refresh(); return current }

    /**
     * Flips every instance whose boot deadline has passed, so an emulator only becomes claimable the
     * way a real one does. True when the pool changed.
     */
    fun refresh(): Boolean = synchronized(lock) {
        val due = bootAt.entries.filter { it.value <= now() }.map { it.key }
        if (due.isEmpty()) return false
        due.forEach(bootAt::remove)
        var changed = false
        current = current.map { d ->
            if (!d.booted && d.avd != null && d.avd in due) { changed = true; d.copy(booted = true) } else d
        }
        changed
    }

    override fun start() {
        scope.launch {
            while (true) {
                delay(100)
                if (refresh()) onChange?.let { runCatching { it() } }
            }
        }
    }

    // --- ScreenshotSource -----------------------------------------------------------------------
    override suspend fun capture(serial: String): ByteArray {
        val d = devices.firstOrNull { it.serial == serial } ?: throw CaptureException.UnknownDevice(serial)
        if (d.state != "device") throw CaptureException.UnusableDevice(serial, d.state)
        val asset = file.screens[serial] ?: DEFAULT_SCREEN
        return shots.computeIfAbsent(asset) {
            resource(it) ?: throw CaptureException.AdbFailure("the demo world carries no screen at $it")
        }
    }

    // --- EmulatorHost ---------------------------------------------------------------------------
    override fun list(): List<String>? = file.avds.map { it.name }

    override fun start(avd: String): Boolean {
        // Two shapes of template, both from the world file: a device entry that already owns the AVD
        // (a `stop`bed instance can be started again and gets the same serial back), or the AVD's
        // own declaration when no device has booted far enough to name it yet.
        val meta = file.avds.firstOrNull { it.name == avd }
        val template = file.devices.firstOrNull { it.avd == avd }
        if (template == null && meta?.serial == null) {
            throw EmulatorException("AVD $avd is not in the demo world - edit the demo world file to add it")
        }
        if (isRunning(avd)) return false
        launched += avd
        val seconds = meta?.bootSeconds ?: DEFAULT_BOOT_SECONDS
        synchronized(lock) {
            // a start shows the instance unbooted again, exactly like a real cold boot
            val fresh = if (template != null) {
                deviceOf(template).copy(booted = false)
            } else {
                val m = meta!!
                Device(serial = m.serial!!, state = "device", transport = Device.transportOf(m.serial),
                    model = m.model, osVersion = m.osVersion, avd = avd, booted = false)
            }
            val serial = fresh.serial
            current = if (current.none { it.serial == serial }) current + fresh
            else current.map { if (it.serial == serial) fresh else it }
            bootAt[avd] = now() + seconds * 1000
        }
        return true
    }

    override fun isRunning(avd: String): Boolean = avd in launched

    override fun stop(serial: String) {
        synchronized(lock) {
            val d = current.firstOrNull { it.serial == serial }
                ?: throw EmulatorException("no demo device has serial $serial")
            if (d.transport != "emulator")
                throw EmulatorException("$serial is not an emulator in the demo world")
            current = current.filterNot { it.serial == serial }
            d.avd?.let { launched -= it; bootAt.remove(it) }
        }
    }

    override fun stopAvd(avd: String): String? {
        var stopped = false
        synchronized(lock) {
            if (current.any { it.avd == avd }) stopped = true
            current = current.filterNot { it.avd == avd }
            launched -= avd
            bootAt.remove(avd)
        }
        return if (stopped) "demo $avd" else null
    }

    override fun logSerials(avd: String): List<String> = current.filter { it.avd == avd }.map { it.serial }

    /** No wedge beat in the demo: an empty map keeps `wedgeReason` off every demo device. */
    override fun launchedPastBootDeadline(): Map<String, Long> = emptyMap()

    override fun lastError(avd: String): String? = null

    /** The demo pool never suppresses a serial it killed: a stop removes the entry outright. */
    override fun liveDevices(devices: List<Device>): List<Device> = devices

    override fun reapFailed() {}

    override fun shutdown() {}

    private fun deviceOf(w: WorldDevice) = Device(
        serial = w.serial, state = w.state, transport = Device.transportOf(w.serial),
        model = w.model, osVersion = w.osVersion, avd = w.avd,
        booted = w.booted ?: (Device.transportOf(w.serial) != "emulator"))

    companion object {
        /** The placeholder screen shipped in the jar; S3 adds one per device through `screens`. */
        const val DEFAULT_SCREEN = "/demo/screen.png"
        private const val DEFAULT_BOOT_SECONDS = 6L

        /**
         * Load the demo world: `CORRAL_DEMO_WORLD` (a test/demo override) or the jar's `demo/world.json`.
         * A malformed world fails loudly — a demo that silently invents a pool would be worse than none.
         */
        fun load(scope: CoroutineScope, env: Map<String, String> = System.getenv()): DemoWorld {
            val override = env["CORRAL_DEMO_WORLD"]?.takeIf { it.isNotBlank() }
            val text = if (override != null) {
                runCatching { Files.readString(Path.of(override)) }.getOrElse {
                    throw IllegalStateException("CORRAL_DEMO_WORLD '$override' is unreadable: ${it.message}")
                }
            } else {
                DemoWorld::class.java.getResourceAsStream("/demo/world.json")?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("the demo world is missing from the jar (/demo/world.json)")
            }
            val file = runCatching { corralJson.decodeFromString<DemoWorldFile>(text) }.getOrElse {
                throw IllegalStateException("the demo world is not valid JSON: ${it.message}")
            }
            return DemoWorld(file, scope)
        }
    }
}
