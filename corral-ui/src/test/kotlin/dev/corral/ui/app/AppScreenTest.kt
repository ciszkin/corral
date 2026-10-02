package dev.corral.ui.app

import dev.corral.ui.CorralClient
import dev.corral.ui.config.ConfigViewModel
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
