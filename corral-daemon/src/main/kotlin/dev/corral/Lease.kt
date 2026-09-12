package dev.corral

import kotlinx.serialization.Serializable

@Serializable
data class Lease(val serial: String, val owner: String, val pid: Long = 0,
                 val expiresAt: Long, val pendingAccept: Boolean = false)
