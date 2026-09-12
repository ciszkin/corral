package dev.corral

import kotlinx.serialization.Serializable

@Serializable
data class Device(val serial: String, val state: String,
                  val transport: String = transportOf(serial),
                  val model: String? = null, val osVersion: String? = null) {
    companion object {
        fun transportOf(serial: String) = when {
            serial.startsWith("emulator-") -> "emulator"
            serial.contains(":") -> "wifi"
            else -> "usb"
        }
    }
}
