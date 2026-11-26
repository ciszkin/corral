package dev.corral.ui.config

import dev.corral.UpdateInfo
import dev.corral.UpdateReason
import dev.corral.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Row 99: the update row's state is a pill, and an available release is coloured by how stale the
 * installed build is — patch yellow, minor orange, major red. These tests pin the classifier only;
 * the colour tokens are chosen in the composable, so no Compose window is needed here.
 */
class UpdateToneTest {
    private fun available(current: String, latest: String?) =
        UpdateInfo(
            currentVersion = current,
            state = UpdateState.UPDATE_AVAILABLE,
            latestVersion = latest,
            releaseUrl = "https://github.com/ciszkin/corral/releases/tag/$latest",
        )

    /** A tag may carry the `v` the release workflow publishes; "0.1.9" -> "v0.1.10" is one patch. */
    @Test fun aPatchDifferenceIsTheMildestTone() {
        assertEquals(UpdateTone.PATCH, stalenessTone("0.1.9", "v0.1.10"))
        assertEquals(UpdateTone.PATCH, stalenessTone("v0.1.9", "0.1.10"))
        assertEquals(UpdateTone.PATCH, stalenessTone("1.4.0", "1.4.3"))
    }

    @Test fun aMinorDifferenceIsAMinorTone() {
        assertEquals(UpdateTone.MINOR, stalenessTone("0.1.9", "0.2.0"))
        assertEquals(UpdateTone.MINOR, stalenessTone("1.9.9", "1.10.0"))
    }

    @Test fun aMajorDifferenceIsTheLoudestTone() {
        assertEquals(UpdateTone.MAJOR, stalenessTone("0.1.9", "1.0.0"))
        assertEquals(UpdateTone.MAJOR, stalenessTone("0.9.9", "1.0.0"))
    }

    /**
     * SemVer precedence is numeric per component: "0.10.0" is newer than "0.9.0" even though
     * string order says the opposite, so a string comparison would report no update at all here.
     */
    @Test fun theComparisonIsNumericNotLexicographic() {
        assertEquals(UpdateTone.MINOR, stalenessTone("0.9.0", "0.10.0"))
        assertEquals(UpdateTone.PATCH, stalenessTone("0.9.9", "0.9.10"))
        assertEquals(UpdateTone.PLAIN, stalenessTone("0.10.0", "0.9.0"))
    }

    @Test fun equalVersionsAndBuildsAheadOfTheFeedHaveNoStaleness() {
        assertEquals(UpdateTone.PLAIN, stalenessTone("0.1.9", "v0.1.9"))
        assertEquals(UpdateTone.PLAIN, stalenessTone("0.2.0", "0.1.9"))
        assertEquals(UpdateTone.PLAIN, stalenessTone("1.0.0", "0.9.9"))
        assertEquals(UpdateTone.PLAIN, stalenessTone("0.1.9+build7", "0.1.9"))
    }

    @Test fun currentIsThePositiveTone() {
        val info = UpdateInfo(currentVersion = "0.1.9", state = UpdateState.CURRENT, latestVersion = "0.1.9")
        assertEquals(UpdateTone.POSITIVE, updateTone(info, demo = false))
    }

    @Test fun anAvailableReleaseIsColouredFromTheInfo() {
        assertEquals(UpdateTone.PATCH, updateTone(available("0.1.9", "v0.1.10"), demo = false))
        assertEquals(UpdateTone.MINOR, updateTone(available("0.1.9", "0.2.0"), demo = false))
        assertEquals(UpdateTone.MAJOR, updateTone(available("0.1.9", "2.0.0"), demo = false))
    }

    /** DISABLED and every UNKNOWN cause keep their own sentence, uncoloured — they are not staleness. */
    @Test fun disabledAndUnknownReasonsStayPlain() {
        assertEquals(UpdateTone.PLAIN, updateTone(UpdateInfo(currentVersion = "0.1.9", state = UpdateState.DISABLED), false))
        assertEquals(UpdateTone.PLAIN, updateTone(UpdateInfo(currentVersion = "0.1.9", state = UpdateState.UNKNOWN,
            reason = UpdateReason.NOT_CHECKED), false))
        assertEquals(UpdateTone.PLAIN, updateTone(UpdateInfo(currentVersion = "0.1.9", state = UpdateState.UNKNOWN,
            reason = UpdateReason.UNREACHABLE), false))
        assertEquals(UpdateTone.PLAIN, updateTone(UpdateInfo(currentVersion = "0.1.9", state = UpdateState.UNKNOWN,
            reason = UpdateReason.HTTP_ERROR, httpStatus = 503), false))
        assertEquals(UpdateTone.PLAIN, updateTone(UpdateInfo(currentVersion = "0.1.9", state = UpdateState.UNKNOWN,
            reason = UpdateReason.UNPARSEABLE), false))
        assertEquals(UpdateTone.PLAIN, updateTone(null, demo = false))
    }

    /** A demo has nothing to install (I5), so it never colours the row even when an update exists. */
    @Test fun aDemoNeverColoursTheRow() {
        assertEquals(UpdateTone.PLAIN, updateTone(available("0.1.9", "1.0.0"), demo = true))
        assertEquals(UpdateTone.PLAIN, updateTone(
            UpdateInfo(currentVersion = "0.1.9", state = UpdateState.CURRENT, latestVersion = "0.1.9"), demo = true))
    }

    /**
     * A feed tag the console cannot read must not throw and must not invent a colour: pre-release
     * and non-version strings all fall back to the plain tone.
     */
    @Test fun preReleaseAndOddTagsFallBackToPlain() {
        val odd = listOf("nightly", "", " ", "dev", "1.2", "1.2.3.4", "v", "latest", "0.1.9.0", "-1.2.3")
        for (tag in odd) {
            assertEquals(UpdateTone.PLAIN, stalenessTone("0.1.9", tag), "latest=$tag")
            assertEquals(UpdateTone.PLAIN, stalenessTone(tag, "0.1.9"), "current=$tag")
        }
        assertEquals(UpdateTone.PLAIN, stalenessTone("0.1.9", null))
        assertEquals(UpdateTone.PLAIN, stalenessTone("", "1.0.0"))
    }

    @Test fun preReleaseSuffixesAreComparedByTheirNumericCore() {
        assertEquals(UpdateTone.PATCH, stalenessTone("v0.1.8-1", "0.1.9"))
        assertEquals(UpdateTone.MINOR, stalenessTone("0.1.9-rc1", "0.2.0"))
        assertEquals(UpdateTone.PLAIN, stalenessTone("0.1.9", "0.1.9-rc1"))
    }
}
