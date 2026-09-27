package dev.corral.ui.di

import dev.corral.ui.CorralApi
import dev.corral.ui.app.AppViewModel
import dev.corral.ui.config.ConfigViewModel
import dev.corral.ui.data.Prefs
import dev.corral.ui.data.UiPrefs
import dev.corral.ui.pool.PoolViewModel
import kotlinx.coroutines.CoroutineScope
import org.koin.dsl.module

object CorralModules {
    fun dataModule(scope: CoroutineScope, prefs: Prefs, prefsStore: UiPrefs) = module {
        single { scope }
        single { prefs }
        single { prefsStore }
    }

    fun vmModule(
        apiFactory: (url: String, owner: String) -> CorralApi,
        initialUrl: String,
        initialOwner: String,
    ) = module {
        single {
            AppViewModel(apiFactory, get(), get(), initialUrl, initialOwner, get(), System::currentTimeMillis)
        }
        single {
            val app = get<AppViewModel>()
            PoolViewModel({ app.currentApi() }, app, get(), System::currentTimeMillis)
        }
        single {
            val app = get<AppViewModel>()
            ConfigViewModel({ app.currentApi() }, get())
        }
    }
}
