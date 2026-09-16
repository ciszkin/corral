package dev.corral

import kotlinx.serialization.Serializable

/** Which device a claimant wants; all present fields AND together, empty = any usable device. */
@Serializable
data class Selector(val serial: String? = null, val model: String? = null,
    val minOs: String? = null, val transport: String? = null, val avd: String? = null) {

    val isEmpty: Boolean
        get() = serial == null && model == null && minOs == null && transport == null && avd == null

    fun matches(d: Device): Boolean = d.usable()
        && (serial == null || serial == d.serial)
        && (model == null || model == d.model)
        && (transport == null || transport == d.transport)
        && (avd == null || avd == d.avd)
        && (minOs == null || (d.osVersion != null && osAtLeast(d.osVersion, minOs)))

    override fun toString() = listOfNotNull(
        serial?.let { "serial=$it" }, model?.let { "model=$it" }, minOs?.let { "os>=$it" },
        transport?.let { "type=$it" }, avd?.let { "avd=$it" }
    ).takeIf { it.isNotEmpty() }?.joinToString(" ", "[", "]") ?: "[any]"
}

/** Numeric per-component compare: "13" > "4.4", "13.1" > "13"; non-numeric components count as 0. */
fun osAtLeast(actual: String, min: String): Boolean {
    val a = actual.split('.').map { it.toIntOrNull() ?: 0 }
    val b = min.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return true
}