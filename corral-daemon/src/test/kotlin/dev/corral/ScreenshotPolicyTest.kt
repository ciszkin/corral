package dev.corral

import kotlin.test.Test
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
