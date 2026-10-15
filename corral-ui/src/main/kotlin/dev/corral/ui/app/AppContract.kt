package dev.corral.ui.app

import dev.corral.EventFrame
import dev.corral.ui.architecture.MviIntent
import dev.corral.ui.architecture.MviState
import dev.corral.ui.data.Prefs
import dev.corral.ui.theme.Language
import dev.corral.ui.theme.ThemeMode

sealed interface Connection {
    data class Connected(val seq: Long) : Connection
    data object Connecting : Connection
    data class Disconnected(val sinceMs: Long) : Connection
}

data class AppState(
    val connection: Connection = Connection.Connecting,
    val seq: Long = -1,
    val url: String = "",
    val daemonVersion: String = "",
    val useDark: Boolean = true,
    val language: Language = Language.ENGLISH,
    val prefs: Prefs = Prefs(),
    val lastUpdatedMs: Long = 0,
) : MviState

sealed interface AppIntent : MviIntent {
    data class Connect(val url: String, val owner: String) : AppIntent
    data class SetTheme(val mode: ThemeMode) : AppIntent
    data class SetLanguage(val language: Language) : AppIntent
    data class SetScrcpy(val path: String) : AppIntent
    data object SocketOpened : AppIntent
    data class Frame(val frame: EventFrame) : AppIntent
    data object SocketClosed : AppIntent
}
