package dev.corral

import kotlinx.serialization.Serializable

@Serializable
data class Device(val serial: String, val state: String,
                  val transport: String = transportOf(serial),
                  val model: String? = null, val osVersion: String? = null,
                  val avd: String? = null) {
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

@Serializable data class DeviceStatus(val serial: String, val lease: Lease?)

@Serializable data class StatusResponse(val devices: List<DeviceStatus>, val adb: String)

@Serializable data class QueueEntry(val position: Int, val owner: String, val selector: String,
    val waitedSeconds: Long, val deadlineIn: Long)

@Serializable
data class EventFrame(val type: String, val resources: List<String> = emptyList(), val seq: Long = 0)
