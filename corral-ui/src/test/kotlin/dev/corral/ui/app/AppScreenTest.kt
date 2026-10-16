package dev.corral.ui.app

import dev.corral.UpdateInfo
import dev.corral.UpdateReason
import dev.corral.UpdateState
import dev.corral.ui.CorralClient
import dev.corral.ui.UiVersionSource
import dev.corral.ui.config.ConfigViewModel
import dev.corral.ui.config.versionText
import dev.corral.ui.data.Prefs
import dev.corral.ui.data.UiPrefs
import dev.corral.ui.di.CorralModules
import dev.corral.ui.pool.PoolViewModel
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppScreenTest {

    @Test fun railHasTheSixSectionsInOrder() {
        assertEquals(
            listOf(
                AppSection.Devices, AppSection.Emulators, AppSection.Queue,
                AppSection.Doctor, AppSection.Rules, AppSection.Settings,
            ),
            AppSections,
        )
        AppSections.forEach {
            assertNotNull(it.icon)
            assertNotNull(it.label)
        }
    }

    // The real English banner: it already carries a dash clause, so the reason must go on its own line.
    private val deniedBanner = "screenshots denied — is this client named in the daemon's CORRAL_SCREENSHOT_OBSERVERS?"

    @Test fun deniedBannerPutsTheDaemonReasonOnItsOwnLine() {
        val reason = "corral: strict screenshots: dev1 is not leased by wrong-operator — " +
            "if wrong-operator is an observer, add it to CORRAL_SCREENSHOT_OBSERVERS; if it is a usable " +
            "device, corral claim dev1 first, or set CORRAL_SCREENSHOTS=open"
        assertEquals(
            "$deniedBanner\n$reason",
            deniedBannerText(deniedBanner, reason),
        )
    }

    @Test fun deniedBannerFallsBackToTheLocalizedStringAlone() {
        assertEquals(deniedBanner, deniedBannerText(deniedBanner, null))
        assertEquals(deniedBanner, deniedBannerText(deniedBanner, "  "))
    }

    @Test fun statusBarShowsTheDaemonVersionWhenKnown() {
        assertEquals(
            "connected (seq 0) — http://127.0.0.1:8798 · v1.2.3",
            statusBarText("connected (seq 0)", "http://127.0.0.1:8798", "1.2.3"),
        )
    }

    @Test fun statusBarOmitsTheVersionWhenUnknown() {
        assertEquals(
            "connected (seq 0) — http://127.0.0.1:8798",
            statusBarText("connected (seq 0)", "http://127.0.0.1:8798", ""),
        )
        assertEquals(
            "disconnected — view is stale — http://127.0.0.1:8798",
            statusBarText("disconnected — view is stale", "http://127.0.0.1:8798", "   "),
        )
    }

    @Test fun settingsRowsReadTheirOwnVersionSources() {
        val unknown = "unknown"
        assertEquals("1.2.3", versionText(AppState(daemonVersion = "1.2.3").daemonVersion, unknown))
        assertEquals(unknown, versionText(AppState().daemonVersion, unknown))
        assertEquals(UiVersionSource.version, versionText(UiVersionSource.version, unknown))
    }

    @Test fun updatePillOnlyRendersWhenAnUpdateIsAvailable() {
        assertNull(updatePillVersion(null))
        assertNull(updatePillVersion(UpdateInfo(currentVersion = "1.0.0", state = UpdateState.DISABLED)))
        assertNull(updatePillVersion(UpdateInfo(currentVersion = "1.0.0", state = UpdateState.UNKNOWN,
            reason = UpdateReason.NOT_CHECKED)))
        assertNull(updatePillVersion(UpdateInfo(currentVersion = "1.0.0", state = UpdateState.CURRENT, latestVersion = "1.0.0")))
        assertEquals(
            "9.9.9",
            updatePillVersion(UpdateInfo(currentVersion = "1.0.0", state = UpdateState.UPDATE_AVAILABLE,
                latestVersion = "v9.9.9", releaseUrl = "https://example/9.9.9")),
        )
    }

    @Test fun koinWiresTheViewModel() {
        val prefsStore = UiPrefs(createTempDirectory("koin").resolve("ui.conf"))
        val koin = startKoin {
            modules(
                CorralModules.dataModule(CoroutineScope(SupervisorJob()), Prefs(), prefsStore),
                CorralModules.vmModule(
                    { url, owner -> CorralClient(url, owner) }, "http://127.0.0.1:8798", "corral-ui",
                ),
            )
        }.koin
        try {
            assertNotNull(koin.get<AppViewModel>())
            assertNotNull(koin.get<PoolViewModel>())
            assertNotNull(koin.get<ConfigViewModel>())
        } finally {
            stopKoin()
        }
    }
}
