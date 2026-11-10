package dev.corral.ui.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateLaunchTest {
    @Test fun readsTheRepoFromAReleasePageUrl() {
        assertEquals("ciszkin/corral",
            repoFromReleaseUrl("https://github.com/ciszkin/corral/releases/tag/v0.1.1"))
    }

    @Test fun toleratesATrailingSlash() {
        assertEquals("acme/corral",
            repoFromReleaseUrl("https://github.com/acme/corral/releases/"))
    }

    @Test fun nullAndOddUrlsYieldNullSoTheSpawnOmitsTheFlag() {
        assertNull(repoFromReleaseUrl(null))
        assertNull(repoFromReleaseUrl(""))
        assertNull(repoFromReleaseUrl("not a url"))
        assertNull(repoFromReleaseUrl("https://github.com/ciszkin"))
        assertNull(repoFromReleaseUrl("https://gitlab.com/ciszkin/corral/releases/tag/v0.1.1"))
    }
}
