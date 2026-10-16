package dev.corral

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every test here drives the injected [UpdateCheck] feed — none opens a socket. */
class UpdateCheckTest {
    private fun checker(
        body: String?,
        version: String = "1.0.0",
        enabled: Boolean = true,
        calls: IntArray? = null,
    ) = UpdateCheck(
        feed = { calls?.let { it[0]++ }; body },
        config = { DaemonConfig(fileValues = mapOf(
            "CORRAL_UPDATE_CHECK" to if (enabled) "on" else "off"), inherited = emptyMap()) },
        version = version)

    @Test fun newerTagMeansUpdateAvailable() {
        val info = checker("""{"tag_name":"v1.1.0","html_url":"https://example/1.1.0"}""").checkNow()
        assertEquals(UpdateState.UPDATE_AVAILABLE, info.state)
        assertTrue(info.updateAvailable)
        assertEquals("1.0.0", info.currentVersion)
        assertEquals("v1.1.0", info.latestVersion)
        assertEquals("https://example/1.1.0", info.releaseUrl)
        assertTrue(info.checksEnabled)
    }

    @Test fun sameTagMeansNoUpdate() {
        val info = checker("""{"tag_name":"v1.0.0","html_url":"https://example/1.0.0"}""").checkNow()
        assertEquals(UpdateState.CURRENT, info.state)
        assertFalse(info.updateAvailable)
        assertEquals("v1.0.0", info.latestVersion)
    }

    @Test fun olderTagMeansNoUpdate() {
        assertEquals(UpdateState.CURRENT,
            checker("""{"tag_name":"v0.9.0","html_url":"https://example/0.9.0"}""").checkNow().state)
    }

    @Test fun numericOrderingBeatsStringOrdering() {
        assertEquals(true, updateVerdict("0.9.0", "v0.10.0"))     // 0.10.0 > 0.9.0 numerically
        assertEquals(false, updateVerdict("0.10.0", "v0.9.0"))
        assertEquals(true, updateVerdict("1.0.0", "1.0.10"))
    }

    @Test fun preReleaseIsOlderThanItsRelease() {
        assertEquals(true, updateVerdict("0.1.0-rc1", "v0.1.0"))    // release beats its own rc
        assertEquals(false, updateVerdict("0.1.0", "v0.1.0-rc1"))   // rc is not newer than the release
        assertEquals(true, updateVerdict("0.1.0-rc1", "v0.1.0-rc2"))
    }

    @Test fun feedReturnsNullMeansUnknownNotCurrent() {
        val unknown = checker(null).checkNow()
        assertEquals(UpdateState.UNKNOWN, unknown.state)
        assertFalse(unknown.updateAvailable)
        assertNull(unknown.latestVersion)
        assertTrue(unknown.checksEnabled)

        val current = checker("""{"tag_name":"v1.0.0","html_url":"u"}""").checkNow()
        assertEquals(UpdateState.CURRENT, current.state)
        assertFalse(current.updateAvailable)
        assertNotEquals(current, unknown)
    }

    @Test fun malformedOrUnexpectedJsonIsUnknownNeverAThrow() {
        val bad = listOf(
            "{not json",
            "[1,2,3]",
            "\"just a string\"",
            "42",
            "{}",
            """{"html_url":"u"}""",               // missing tag_name
            """{"tag_name":"v9.9.9"}""",          // missing html_url
            """{"tag_name":9,"html_url":"u"}""",  // tag_name is not a string
            "",
            null)
        for (body in bad) {
            val info = checker(body).checkNow()
            assertEquals(UpdateState.UNKNOWN, info.state, "body=$body")
            assertFalse(info.updateAvailable, "body=$body")
            assertNull(info.latestVersion, "body=$body")
        }
    }

    @Test fun unparseableTagsAreUnknownNotCurrent() {
        // exactly three numeric segments required: a short/long/non-numeric tag is incomparable -> unknown
        assertEquals(UpdateState.UNKNOWN, checker(tag("nightly")).checkNow().state)
        assertEquals(UpdateState.UNKNOWN, checker(tag("1.2.3.4")).checkNow().state)
        assertEquals(UpdateState.UNKNOWN, checker(tag("1.2")).checkNow().state)
        assertEquals(UpdateState.UPDATE_AVAILABLE, checker(tag("v1.2.3")).checkNow().state)
        assertEquals(UpdateState.UPDATE_AVAILABLE, checker(tag("1.2.3-rc1")).checkNow().state)
    }

    private fun tag(name: String) = """{"tag_name":"$name","html_url":"https://example/$name"}"""

    @Test fun disabledCheckerReportsDisabledAndNeverCallsTheFeed() {
        val calls = intArrayOf(0)
        val info = checker("""{"tag_name":"v9.9.9","html_url":"u"}""", enabled = false, calls = calls).checkNow()
        assertEquals(UpdateState.DISABLED, info.state)
        assertFalse(info.checksEnabled)
        assertFalse(info.updateAvailable)
        assertNull(info.latestVersion)
        assertEquals(0, calls[0])
        assertEquals(UpdateState.DISABLED, checker(null, enabled = false, calls = calls).current().state)
        assertEquals(0, calls[0])
    }

    @Test fun testToggleIsReadLiveEachCycle() {
        val file = createTempDirectory("update").resolve("daemon.env")
        EnvFile.write(file, mapOf("CORRAL_UPDATE_CHECK" to "off"))
        var calls = 0
        val check = UpdateCheck(
            feed = { calls++; """{"tag_name":"v2.0.0","html_url":"https://example/2.0.0"}""" },
            config = { DaemonConfig(EnvFile.read(file), emptyMap()) },
            version = "1.0.0")

        assertEquals(UpdateState.DISABLED, check.checkNow().state)   // off -> disabled, feed untouched
        assertEquals(0, calls)

        EnvFile.write(file, mapOf("CORRAL_UPDATE_CHECK" to "on"))
        val on = check.checkNow()                     // flipped file, no restart: next cycle consults the feed
        assertEquals(UpdateState.UPDATE_AVAILABLE, on.state)
        assertEquals(1, calls)
    }

    /** Defect 1: a cache recorded while disabled must never mask a now-enabled switch. */
    @Test fun enablingTheSwitchIsLiveWithoutForce() {
        val file = createTempDirectory("update").resolve("daemon.env")
        EnvFile.write(file, mapOf("CORRAL_UPDATE_CHECK" to "off"))
        var calls = 0
        val check = UpdateCheck(
            feed = { calls++; """{"tag_name":"v2.0.0","html_url":"https://example/2.0.0"}""" },
            config = { DaemonConfig(EnvFile.read(file), emptyMap()) },
            version = "1.0.0")

        assertEquals(UpdateState.DISABLED, check.checkNow().state)   // boot cycle caches disabled
        assertEquals(0, calls)

        EnvFile.write(file, mapOf("CORRAL_UPDATE_CHECK" to "on"))
        val now = check.current()                     // no ?force, no new check cycle
        assertNotEquals(UpdateState.DISABLED, now.state)
        assertEquals(UpdateState.UNKNOWN, now.state)  // enabled but not yet re-checked
        assertTrue(now.checksEnabled)
        assertEquals(0, calls)
    }

    @Test fun stateIsSelfValidating() {
        // latestVersion present iff CURRENT/UPDATE_AVAILABLE
        assertFailsWith<IllegalArgumentException> { UpdateInfo("1.0.0", UpdateState.UPDATE_AVAILABLE) }
        assertFailsWith<IllegalArgumentException> { UpdateInfo("1.0.0", UpdateState.CURRENT) }
        assertFailsWith<IllegalArgumentException> { UpdateInfo("1.0.0", UpdateState.UNKNOWN, latestVersion = "x") }
        assertFailsWith<IllegalArgumentException> { UpdateInfo("1.0.0", UpdateState.DISABLED, latestVersion = "x") }
        // flags cannot disagree with the state
        assertFailsWith<IllegalArgumentException> {
            UpdateInfo("1.0.0", UpdateState.CURRENT, "1.0.0", updateAvailable = true)
        }
        assertFailsWith<IllegalArgumentException> {
            UpdateInfo("1.0.0", UpdateState.DISABLED, checksEnabled = true)
        }
        // the four consistent constructions pass
        assertEquals(UpdateState.DISABLED, UpdateInfo("1.0.0", UpdateState.DISABLED).state)
        assertEquals(UpdateState.UNKNOWN, UpdateInfo("1.0.0", UpdateState.UNKNOWN).state)
        assertFalse(UpdateInfo("1.0.0", UpdateState.CURRENT, "1.0.0").updateAvailable)
        assertTrue(UpdateInfo("1.0.0", UpdateState.UPDATE_AVAILABLE, "2.0.0").updateAvailable)
    }

    @Test fun doctorLineDistinguishesEachState() {
        val current = UpdateInfo("1.0.0", UpdateState.CURRENT, "1.0.0", releaseUrl = "u")
        val available = UpdateInfo("1.0.0", UpdateState.UPDATE_AVAILABLE, "2.0.0",
            releaseUrl = "https://example/2.0.0")
        val unknown = UpdateInfo("1.0.0", UpdateState.UNKNOWN)
        val disabled = UpdateInfo("1.0.0", UpdateState.DISABLED)

        assertEquals("update: current", updateLine(current))
        assertEquals("update: v1.0.0 -> v2.0.0 available (https://example/2.0.0)", updateLine(available))
        // a v-prefixed tag (GitHub's normal form) must not render as "vv2.0.0"
        assertEquals("update: v1.0.0 -> v2.0.0 available (https://example/v2.0.0)",
            updateLine(UpdateInfo("1.0.0", UpdateState.UPDATE_AVAILABLE, "v2.0.0",
                releaseUrl = "https://example/v2.0.0")))
        assertEquals("update: unknown (could not reach the release feed)", updateLine(unknown))
        assertEquals("update: disabled (CORRAL_UPDATE_CHECK=off)", updateLine(disabled))
        assertEquals("update: unknown (could not read /api/update)", updateLine(null))
        assertEquals(5, setOf(updateLine(current), updateLine(available), updateLine(unknown),
            updateLine(disabled), updateLine(null)).size)
    }
}
