package dev.corral

/** The gate verdict for an adb argument vector (without the leading `adb`). */
sealed interface GateDecision {
    data object Pass : GateDecision
    data class Check(val serial: String, val sub: String) : GateDecision
    data class Deny(val reason: String) : GateDecision
}

object Gate {
    /**
     *  - a non-empty pin (`-s S`, `-sS`, `ANDROID_SERIAL`) -> Check
     *  - no pin and a read-only subcommand, no subcommand, or only global flags -> Pass
     *  - no pin and a device-touching subcommand -> Deny
     * Mirrors `scripts/adb-wrapper` exactly, including `-s ""` falling through to the
     * read-only check; the agreement test in `test_wrapper.sh` holds the two together.
     */
    fun classify(argv: List<String>, envSerial: String?, rules: GateRules): GateDecision {
        var serial = envSerial ?: ""          // bash: serial=${ANDROID_SERIAL:-}
        var sub: String? = null
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            when {
                a == "-s" -> { serial = argv.getOrNull(i + 1) ?: ""; i++ }   // bash: serial=${2:-}; shift 2 (or 1 when it is the last arg)
                a.startsWith("-s") && a.length > 2 -> serial = a.substring(2)
                a in rules.valueFlags -> i++
                a.startsWith("-") -> {}
                sub.isNullOrEmpty() -> sub = a
            }
            i++
        }
        return when {
            serial.isNotEmpty() -> GateDecision.Check(serial, sub ?: "")
            sub.isNullOrEmpty() || sub in rules.readonly -> GateDecision.Pass
            else -> GateDecision.Deny("no device pinned (no -s, no ANDROID_SERIAL)")
        }
    }
}
