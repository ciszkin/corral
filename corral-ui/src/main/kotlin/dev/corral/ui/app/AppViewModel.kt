package dev.corral.ui.app

import dev.corral.EventFrame
import dev.corral.corralJson
import dev.corral.ui.CorralApi
import dev.corral.ui.architecture.BaseMviViewModel
import dev.corral.ui.data.Prefs
import dev.corral.ui.data.UiPrefs
import dev.corral.ui.theme.ThemeMode
import javax.swing.UIManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString

class AppViewModel(
    private val apiFactory: (url: String, owner: String) -> CorralApi,
    private val prefsStore: UiPrefs,
    initial: Prefs,
    private val initialUrl: String,
    private val initialOwner: String,
    vmScope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : BaseMviViewModel<AppIntent, AppState, Nothing>(vmScope) {

    private val _frames = MutableSharedFlow<EventFrame>(
        replay = 64,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<EventFrame> = _frames.asSharedFlow()

    private var lastSeq = -1L
    private var loopJob: Job? = null
    @Volatile
    private var api: CorralApi? = null

    init {
        reduceState {
            copy(
                url = initialUrl,
                useDark = resolveUseDark(initial.themeMode),
                language = initial.language,
                prefs = initial,
                lastUpdatedMs = clock(),
            )
        }
    }

    override fun initialState() = AppState()

    fun start() {
        launchLoop(initialUrl, initialOwner)
    }

    fun currentApi(): CorralApi? = api

    override fun onIntent(intent: AppIntent) {
        when (intent) {
            is AppIntent.Connect -> {
                val p = persist { it.copy(url = intent.url, owner = intent.owner) }
                lastSeq = -1
                reduceState { copy(prefs = p, url = intent.url) }
                launchLoop(intent.url, intent.owner)
            }
            is AppIntent.SetTheme -> {
                val p = persist { it.copy(themeMode = intent.mode) }
                reduceState { copy(prefs = p, useDark = resolveUseDark(intent.mode)) }
            }
            is AppIntent.SetLanguage -> {
                val p = persist { it.copy(language = intent.language) }
                reduceState { copy(prefs = p, language = intent.language) }
            }
            is AppIntent.SetScrcpy -> persist { it.copy(scrcpyPath = intent.path) }
            AppIntent.SocketOpened -> reduceState { copy(connection = Connection.Connecting) }
            is AppIntent.Frame -> onFrame(intent.frame)
            AppIntent.SocketClosed -> reduceState { copy(connection = Connection.Disconnected(clock())) }
        }
    }

    private fun launchLoop(url: String, owner: String) {
        loopJob?.cancel()
        runCatching { api?.close() }
        val client = apiFactory(url, owner)
        api = client
        loopJob = vmScope.launch { socketLoop(client) }
    }

    private fun persist(transform: (Prefs) -> Prefs): Prefs {
        val p = transform(state.value.prefs)
        runCatching { prefsStore.save(p) }
        reduceState { copy(prefs = p) }
        return p
    }

    private suspend fun socketLoop(api: CorralApi) {
        var backoffMs = 1000L
        while (true) {
            onIntent(AppIntent.SocketOpened)
            try {
                api.events().collect {
                    onIntent(AppIntent.Frame(corralJson.decodeFromString(it)))
                    backoffMs = 1000L
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            onIntent(AppIntent.SocketClosed)
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(5000L)
        }
    }

    private fun onFrame(f: EventFrame) {
        when (f.type) {
            "hello" -> {
                lastSeq = f.seq
                reduceState { copy(connection = Connection.Connected(f.seq), seq = f.seq, lastUpdatedMs = clock()) }
            }
            "state.changed" -> {
                if (gap(f.seq)) _frames.tryEmit(EventFrame("state.changed", emptyList(), f.seq))
                lastSeq = f.seq
                reduceState { copy(connection = Connection.Connected(f.seq), seq = f.seq, lastUpdatedMs = clock()) }
            }
            else -> Unit
        }
        _frames.tryEmit(f)
    }

    private fun gap(seq: Long) = lastSeq >= 0 && seq > lastSeq + 1
}

internal fun resolveUseDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    // best-effort sniff: fall back to light where the LAF cannot be read (exotic hosts)
    ThemeMode.SYSTEM -> runCatching {
        UIManager.getLookAndFeel()?.javaClass?.name?.contains("Dark") == true
    }.getOrDefault(false)
}
