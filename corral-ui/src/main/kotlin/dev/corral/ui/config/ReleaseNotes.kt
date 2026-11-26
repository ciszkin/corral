package dev.corral.ui.config

import dev.corral.UpdateInfo
import dev.corral.UpdateState
import java.awt.Desktop
import java.net.URI

/**
 * The page the update row may open for [state], or null when it may not open one. Only an
 * available update has a page to read, and only a non-blank URL is a page: nothing here builds a
 * URL out of a version string, because a fabricated page is a claim the daemon never made.
 */
internal fun releaseUrlFor(state: UpdateState?, releaseUrl: String?): String? =
    if (state == UpdateState.UPDATE_AVAILABLE) releaseUrl?.takeIf { it.isNotBlank() } else null

/**
 * The update row's release-notes action: the daemon's own `html_url` for an available update,
 * verbatim. A demo never offers it (no feed, no network — I5), and the action is absent for
 * every other state, so the row never points at a page it cannot vouch for.
 */
internal fun releaseNotesUrl(info: UpdateInfo?, demo: Boolean): String? =
    if (demo) null else releaseUrlFor(info?.state, info?.releaseUrl)

/**
 * Opens a release page in the platform's default browser. `java.awt.Desktop` needs a real desktop
 * session, so both the probe and the call are injected: a unit test asserts the exact URI handed to
 * the platform, and a headless or browser-less run answers `false` instead of throwing.
 */
class ReleaseNotesOpener(
    private val supported: () -> Boolean = ::desktopBrowses,
    private val browse: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
) {
    /** Whether this platform can open a page at all; the row keeps its action disabled when it cannot. */
    fun available(): Boolean = runCatching { supported() }.getOrDefault(false)

    /** Opens [url] exactly as given; false when it is blank, not a URL, or the platform refuses. */
    fun open(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!available()) return false
        return runCatching {
            browse(uri)
            true
        }.getOrDefault(false)
    }
}

/** Never throws: a headless CI or a desktop with no browser handler simply cannot browse. */
private fun desktopBrowses(): Boolean = runCatching {
    Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
}.getOrDefault(false)
