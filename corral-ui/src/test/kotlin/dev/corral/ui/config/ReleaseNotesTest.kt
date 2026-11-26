package dev.corral.ui.config

import dev.corral.UpdateInfo
import dev.corral.UpdateReason
import dev.corral.UpdateState
import java.net.URI
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Row 101: the update row's "Release notes" action. The console may only open the page the daemon
 * reported — never one assembled from a version string, never for a state that has no update, and
 * never in a demo (no feed, no network, I5).
 */
class ReleaseNotesTest {
    private val reported = "https://github.com/ciszkin/corral/releases/tag/v0.2.0"

    private fun available(releaseUrl: String?, latestVersion: String = "v0.2.0") = UpdateInfo(
        currentVersion = "0.1.0",
        state = UpdateState.UPDATE_AVAILABLE,
        latestVersion = latestVersion,
        releaseUrl = releaseUrl,
    )

    @Test fun anAvailableUpdateOffersThePageTheDaemonReported() {
        assertEquals(reported, releaseNotesUrl(available(reported), demo = false))
    }

    /** The action opens the daemon's URL verbatim: nothing is derived from current/latest version. */
    @Test fun thePageIsTheDaemonsUrlNotOneDerivedFromTheVersion() {
        val daemonUrl = "https://github.com/acme/corral/releases/tag/corral-42"
        val info = available(daemonUrl, latestVersion = "v9.9.9")
        val url = releaseNotesUrl(info, demo = false)
        assertEquals(daemonUrl, url)
        assertFalse(url!!.contains("9.9.9"), "version must not leak into the page: $url")
        assertFalse(url.contains("0.1.0"), "current version must not leak into the page: $url")
    }

    @Test fun onlyAnAvailableUpdateOffersAPage() {
        assertNull(
            releaseNotesUrl(
                UpdateInfo(currentVersion = "0.1.0", state = UpdateState.CURRENT,
                    latestVersion = "v0.1.0", releaseUrl = reported),
                demo = false,
            ),
            "up to date has no page to read",
        )
        assertNull(
            releaseNotesUrl(
                UpdateInfo(currentVersion = "0.1.0", state = UpdateState.UNKNOWN,
                    reason = UpdateReason.UNREACHABLE, releaseUrl = reported),
                demo = false,
            ),
            "an unread feed's stray URL is not an update",
        )
        assertNull(releaseNotesUrl(null, demo = false), "no update info offers nothing")
    }

    /**
     * The wire type refuses an UPDATE_AVAILABLE without a page, so this gate exists to keep the row
     * total: a blank URL from any source produces no action instead of a dead link.
     */
    @Test fun aBlankUrlIsNoAction() {
        assertNull(releaseUrlFor(UpdateState.UPDATE_AVAILABLE, null))
        assertNull(releaseUrlFor(UpdateState.UPDATE_AVAILABLE, ""))
        assertNull(releaseUrlFor(UpdateState.UPDATE_AVAILABLE, "   "))
        assertEquals(reported, releaseUrlFor(UpdateState.UPDATE_AVAILABLE, reported))
        assertNull(releaseUrlFor(UpdateState.CURRENT, reported))
    }

    @Test fun aDemoNeverOffersThePage() {
        assertNull(releaseNotesUrl(available(reported), demo = true))
    }

    @Test fun theOpenerGetsExactlyTheDaemonsUrl() {
        val seen = mutableListOf<URI>()
        val opener = ReleaseNotesOpener(supported = { true }, browse = { seen += it })
        assertTrue(opener.open(reported))
        assertEquals(listOf(URI(reported)), seen)
    }

    @Test fun aBlankOrUnusableUrlNeverReachesThePlatform() {
        val seen = mutableListOf<URI>()
        val opener = ReleaseNotesOpener(supported = { true }, browse = { seen += it })
        assertFalse(opener.open(""))
        assertFalse(opener.open("   "))
        assertFalse(opener.open("not a url"))
        assertTrue(seen.isEmpty(), "nothing should have been handed to the browser: $seen")
    }

    @Test fun aPlatformWithoutABrowserOpensNothingAndSaysSo() {
        val seen = mutableListOf<URI>()
        val opener = ReleaseNotesOpener(supported = { false }, browse = { seen += it })
        assertFalse(opener.available())
        assertFalse(opener.open(reported))
        assertTrue(seen.isEmpty(), seen.toString())
    }

    @Test fun aRefusedOrThrowingLaunchIsFalseInsteadOfACrash() {
        val refused = ReleaseNotesOpener(supported = { true }, browse = { throw IllegalStateException("no browser") })
        assertFalse(refused.open(reported))
        val headless = ReleaseNotesOpener(supported = { error("headless") }, browse = { error("must not browse") })
        assertFalse(headless.available())
        assertFalse(headless.open(reported))
    }

    /** The action's label travels in both bundles (BE is a draft) and is a single line. */
    @Test fun theLabelExistsInBothLanguagesOnOneLine() {
        listOf(
            Path.of("src/main/composeResources/values/strings.xml"),
            Path.of("src/main/composeResources/values-be/strings.xml"),
        ).forEach { file ->
            val text = stringValue(file, "update_release_notes")
            assertTrue(text.isNotBlank(), "$file: blank label")
            assertFalse(text.contains('\n'), "$file: label must be one line: $text")
        }
    }

    private fun stringValue(path: Path, name: String): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile())
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .first { it.attributes.getNamedItem("name").nodeValue == name }
            .textContent
    }
}
