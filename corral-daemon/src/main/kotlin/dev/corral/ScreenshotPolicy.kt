package dev.corral

/**
 * Who may look at a device's screen. Strict (default): the current lease holder, or an owner
 * listed in `CORRAL_SCREENSHOT_OBSERVERS`. That list is empty unless configured — the desktop
 * UI opts in by setting it to its own identity. Open: anyone local. Observer names match
 * case-sensitively; the mode value is case-insensitive. The owner identity is cooperative
 * (self-declared), so this is a rail against casual snooping, not authentication — see the
 * design doc's security-model section.
 */
class ScreenshotPolicy(
    mode: String? = System.getenv("CORRAL_SCREENSHOTS"),
    observers: String? = System.getenv("CORRAL_SCREENSHOT_OBSERVERS"),
) {
    private val strict = (mode ?: "strict").trim().lowercase() != "open"   // unknown/absent -> strict (fail closed)
    private val observerSet = (observers ?: "").split(',')
        .map(String::trim).filter(String::isNotEmpty).toSet()

    /** @return null when allowed, else the self-teaching denial text. */
    fun denies(serial: String, lease: Lease?, owner: String): String? {
        if (!strict) return null
        if (owner in observerSet) return null
        return when (val holder = lease?.owner) {
            owner -> null
            null -> "corral: strict screenshots: $serial is not leased by $owner — if it is a usable " +
                "device, corral claim $serial first, or set CORRAL_SCREENSHOTS=open"
            else -> "corral: strict screenshots: $serial is held by $holder — corral claim $serial, " +
                "or ask the holder; set CORRAL_SCREENSHOTS=open to disable this policy"
        }
    }
}
