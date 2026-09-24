package dev.corral

import kotlin.test.Test
import kotlin.test.assertEquals

class GateTest {
    private val rules = RulesFile.parse(
        "readonly=devices,start-server,kill-server,track-devices,version,help\nvalueflags=-H,-P,-L,-p,-t\n")

    private fun c(vararg args: String, env: String? = null) = Gate.classify(args.toList(), env, rules)

    @Test fun readonlyPassesUnpinned() = assertEquals(GateDecision.Pass, c("devices"))
    @Test fun bareAdbPasses() = assertEquals(GateDecision.Pass, c())
    @Test fun globalFlagAlonePasses() = assertEquals(GateDecision.Pass, c("-v"))
    @Test fun valueFlagConsumesItsValue() = assertEquals(GateDecision.Pass, c("-P", "5037", "devices"))
    @Test fun nonDashValueFlagDoesNotSwallowTheSubcommand() {
        val rules = GateRules(setOf("devices"), setOf("shell"), emptySet(), emptySet())
        assertEquals(GateDecision.Deny("no device pinned (no -s, no ANDROID_SERIAL)"),
            Gate.classify(listOf("shell", "ls"), null, rules))
    }
    @Test fun inlineSerialChecks() =
        assertEquals(GateDecision.Check("dev1", "shell"), c("-sdev1", "shell", "ls"))
    @Test fun splitSerialChecks() =
        assertEquals(GateDecision.Check("dev1", "shell"), c("-s", "dev1", "shell", "ls"))
    @Test fun envSerialChecks() =
        assertEquals(GateDecision.Check("dev1", "shell"), c("shell", "ls", env = "dev1"))
    @Test fun serialConsumesTheNextArg() = assertEquals(GateDecision.Check("devices", ""), c("-s", "devices"))
    @Test fun emptySerialFallsThroughToReadonly() = assertEquals(GateDecision.Pass, c("-s", "", "devices"))
    @Test fun danglingSerialPasses() = assertEquals(GateDecision.Pass, c("-s"))
    @Test fun deviceCommandWithoutPinDenies() =
        assertEquals(GateDecision.Deny("no device pinned (no -s, no ANDROID_SERIAL)"), c("shell", "ls"))
    @Test fun valueFlagSelectorStillDenies() =
        assertEquals(GateDecision.Deny("no device pinned (no -s, no ANDROID_SERIAL)"), c("-t", "3", "shell", "ls"))
    @Test fun emptyPositionalIsNotASubcommand() = assertEquals(GateDecision.Pass, c(""))
    @Test fun emptyPositionalThenReadonlyPasses() = assertEquals(GateDecision.Pass, c("", "devices"))
    @Test fun emptyPositionalBetweenSerialAndSubIsSkipped() =
        assertEquals(GateDecision.Check("dev1", "shell"), c("-sdev1", "", "shell"))
    @Test fun emptyEnvSerialIsNotAPin() =
        assertEquals(GateDecision.Deny("no device pinned (no -s, no ANDROID_SERIAL)"), c("shell", "ls", env = ""))
}
