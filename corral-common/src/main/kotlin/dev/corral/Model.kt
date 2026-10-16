package dev.corral

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class Device(val serial: String, val state: String,
                  val transport: String = transportOf(serial),
                  val model: String? = null, val osVersion: String? = null,
                  val avd: String? = null,
                  val booted: Boolean = transport != "emulator",
                  /** This emulator's AVD is alive but has never registered with adb past the boot
                   *  deadline. Visibility only — [usable] still refuses it. */
                  val wedged: Boolean = false,
                  val wedgeReason: String? = null) {
    companion object {
        fun transportOf(serial: String) = when {
            serial.startsWith("emulator-") -> "emulator"
            serial.contains(":") -> "wifi"
            else -> "usb"
        }
    }
}

@Serializable
data class Lease(val serial: String, val owner: String, val pid: Long = 0,
                 val expiresAt: Long, val pendingAccept: Boolean = false,
                 val offeredAt: Long = 0)

@Serializable data class DeviceStatus(val serial: String, val lease: Lease?, val booted: Boolean = true)

@Serializable data class StatusResponse(val devices: List<DeviceStatus>, val adb: String,
                                         val version: String = "")

/** The four outcomes of a check, carried explicitly so a consumer cannot infer one from another field. */
@Serializable
enum class UpdateState { DISABLED, UNKNOWN, CURRENT, UPDATE_AVAILABLE }

/**
 * The result of one update check. [state] is authoritative; [updateAvailable] and [checksEnabled]
 * are derived defaults that cannot disagree with it (the constructor refuses an inconsistent pair).
 * A failed or unreadable feed is [UpdateState.UNKNOWN] — never expressed as "up to date", which is
 * the lie this type exists to prevent. [latestVersion] is present iff [state] is CURRENT/UPDATE_AVAILABLE.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UpdateInfo(
    @EncodeDefault val currentVersion: String = "",
    @EncodeDefault val state: UpdateState = UpdateState.UNKNOWN,
    @EncodeDefault val latestVersion: String? = null,
    @EncodeDefault val updateAvailable: Boolean = state == UpdateState.UPDATE_AVAILABLE,
    @EncodeDefault val releaseUrl: String? = null,
    @EncodeDefault val checksEnabled: Boolean = state != UpdateState.DISABLED) {
    init {
        val known = state == UpdateState.CURRENT || state == UpdateState.UPDATE_AVAILABLE
        require(known == (latestVersion != null)) {
            "latestVersion must be present iff state is CURRENT or UPDATE_AVAILABLE (state=$state, latestVersion=$latestVersion)"
        }
        require((state == UpdateState.UPDATE_AVAILABLE) == updateAvailable) {
            "updateAvailable must be true iff state is UPDATE_AVAILABLE"
        }
        require((state != UpdateState.DISABLED) == checksEnabled) {
            "checksEnabled must be false iff state is DISABLED"
        }
    }
}

@Serializable data class QueueEntry(val position: Int, val owner: String, val selector: String,
    val waitedSeconds: Long, val deadlineIn: Long)

@Serializable
data class EventFrame(val type: String, val resources: List<String> = emptyList(), val seq: Long = 0)
