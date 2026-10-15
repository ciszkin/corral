package dev.corral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenshotPolicyTest {
    private val lease = Lease("dev1", "agent-a", 0, expiresAt = 9_999)

    @Test fun holderPassesInStrictMode() = assertNull(ScreenshotPolicy("strict").denies("dev1", lease, "agent-a"))
    @Test fun observerPassesForAnyDevice() = assertNull(ScreenshotPolicy("strict", "corral-ui, human").denies("dev1", lease, "corral-ui"))
    @Test fun strangerIsDeniedWithRemedy() {
        val why = ScreenshotPolicy("strict").denies("dev1", lease, "agent-b")
        assertTrue(why!!.contains("agent-a") && why.contains("corral claim dev1") && why.contains("CORRAL_SCREENSHOTS=open"))
    }
    @Test fun unleasedDeniedInStrictMode() {
        val why = ScreenshotPolicy(null).denies("dev1", null, "agent-b")   // default env = strict
        assertTrue(why!!.contains("not leased") && why.contains("corral claim dev1"))
    }

    // Finding I4: a free-device denial must name the identity it received AND point at the observer list,
    // or a UI operator cannot tell which name the daemon saw or how to let it in.
    @Test fun freeDeviceDenialNamesTheSentOwnerAndTheObserverList() {
        val why = ScreenshotPolicy("strict", "corral-ui").denies("dev1", null, "wrong-name")
        assertTrue(why!!.contains("not leased by wrong-name"), why)
        assertTrue(why.contains("CORRAL_SCREENSHOT_OBSERVERS"), why)
    }

    @Test fun holderBranchAdviceIsUnchanged() {
        assertEquals(
            "corral: strict screenshots: dev1 is held by agent-a — corral claim dev1, " +
                "or ask the holder; set CORRAL_SCREENSHOTS=open to disable this policy",
            ScreenshotPolicy("strict").denies("dev1", lease, "agent-b"),
        )
    }

    @Test fun observerIsAllowedForAFreeDevice() =
        assertNull(ScreenshotPolicy("strict", "corral-ui").denies("dev1", null, "corral-ui"))

    @Test fun openModeAllowsAFreeDevice() = assertNull(ScreenshotPolicy("open").denies("dev1", null, "anyone"))

    @Test fun openModeAllowsAnyone() = assertNull(ScreenshotPolicy("open").denies("dev1", lease, "anyone"))
    @Test fun unknownModeFailsClosed() = assertTrue(ScreenshotPolicy("yes").denies("dev1", lease, "anyone") != null)
    @Test fun observerListTrimsAndIgnoresBlanks() = assertNull(ScreenshotPolicy("strict", " corral-ui , ,x ").denies("dev1", lease, "corral-ui"))
    @Test fun noBuiltInObserverWhenUnconfigured() =
        assertTrue(ScreenshotPolicy("strict", null).denies("dev1", lease, "corral-ui") != null)

    @Test fun policyHonorsAFileObserverWhenTheEnvVarIsAbsent() {
        val config = DaemonConfig(
            fileValues = mapOf("CORRAL_SCREENSHOT_OBSERVERS" to "corral-ui"),
            inherited = emptyMap(),
        )
        assertNull(screenshotPolicy(config).denies("dev1", lease, "corral-ui"))
    }
}
