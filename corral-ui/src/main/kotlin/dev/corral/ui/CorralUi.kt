package dev.corral.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberWindowState
import dev.corral.ui.app.AppScreen
import dev.corral.ui.app.AppViewModel
import dev.corral.ui.config.ConfigViewModel
import dev.corral.ui.data.Prefs
import dev.corral.ui.data.UiPrefs
import dev.corral.ui.di.CorralModules
import dev.corral.ui.pool.PoolViewModel
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.corral_icon_128
import dev.corral.ui.resources.corral_icon_48
import dev.corral.ui.resources.menu_quit
import dev.corral.ui.theme.CorralTheme
import dev.corral.ui.theme.Dimens
import dev.corral.ui.theme.locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.startKoin
import java.awt.Dimension
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window as AwtWindow
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.Locale

private val BarColor = Color(0xFF2B2B2B)
private val BarTextColor = Color(0xFFEDEDED)

fun main(args: Array<String>) {
    val argUrl = args.toList().zipWithNext().firstOrNull { it.first == "--url" }?.second
    val envUrl = System.getenv("CORRAL_URL")?.takeIf { it.isNotBlank() }
    val envOwner = System.getenv("CORRAL_OWNER")?.takeIf { it.isNotBlank() }
    val prefsStore = UiPrefs()
    val stored = prefsStore.load()
    Locale.setDefault(stored.language.locale())
    val url = argUrl ?: envUrl ?: stored.url
    val owner = envOwner ?: stored.owner
    val sessionType = System.getenv("XDG_SESSION_TYPE")
    val currentDesktop = System.getenv("XDG_CURRENT_DESKTOP")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val koin = startKoin {
        modules(
            CorralModules.dataModule(scope, stored, prefsStore),
            CorralModules.vmModule({ baseUrl, ownerName -> CorralClient(baseUrl, ownerName) }, url, owner),
        )
    }.koin
    val app = koin.get<AppViewModel>()
    val pool = koin.get<PoolViewModel>()
    val config = koin.get<ConfigViewModel>()
    app.start()
    val scrcpy = ScrcpyLauncher()

    application {
        var windowVisible by remember { mutableStateOf(true) }
        var trayReady by remember { mutableStateOf(false) }
        var showRequests by remember { mutableStateOf(0) }
        var menuOpen by remember { mutableStateOf(false) }
        val state by app.state.collectAsState()
        var awtWindow by remember { mutableStateOf<AwtWindow?>(null) }
        var offsetX by remember { mutableStateOf(0) }
        var offsetY by remember { mutableStateOf(0) }
        val windowState = rememberWindowState()
        val listState = rememberLazyListState()

        // AWT tray icon, driven directly: clicking it (either button) brings the window back.
        val systemTray = remember { runCatching { SystemTray.getSystemTray() }.getOrNull() }
        val parkEnabled = trayParkEnabled(isTraySupported, sessionType, systemTray != null, currentDesktop)
        if (parkEnabled && systemTray != null) {
            val mark = imageResource(Res.drawable.corral_icon_128)
            val trayImage = remember(mark) { trayImageFor(mark, trayIconSize()) }
            DisposableEffect(state.url) {
                val icon = TrayIcon(trayImage, "Corral — ${state.url}")
                icon.isImageAutoSize = true
                icon.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.button != MouseEvent.BUTTON1 && e.button != MouseEvent.BUTTON3) return
                        windowVisible = true
                        showRequests++
                    }
                })
                runCatching { systemTray.add(icon) }.onSuccess { trayReady = true }
                onDispose { systemTray.remove(icon) }
            }
        }

        Window(
            onCloseRequest = {
                saveGeometry(prefsStore, app.state.value.prefs, awtWindow, offsetX, offsetY)
                if (trayReady) windowVisible = false else exitApplication()
            },
            visible = windowVisible,
            state = windowState,
            title = "corral — ${state.url}",
            icon = painterResource(Res.drawable.corral_icon_48),
        ) {
            SideEffect { awtWindow = window }
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(Dimens.minWindowWidthPx, Dimens.minWindowHeightPx)
                if (stored.windowW > 0 && stored.windowH > 0) {
                    window.setSize(stored.windowW.coerceAtLeast(Dimens.minWindowWidthPx), stored.windowH.coerceAtLeast(Dimens.minWindowHeightPx))
                }
                if (stored.windowX >= 0 && stored.windowY >= 0) {
                    window.setLocation(stored.windowX, stored.windowY)
                    delay(200)
                    offsetX = window.x - stored.windowX
                    offsetY = window.y - stored.windowY
                }
            }
            // `visible = true` alone leaves the window behind or minimized; restore + focus it.
            LaunchedEffect(showRequests) {
                if (showRequests > 0) {
                    window.extendedState = Frame.NORMAL
                    window.toFront()
                    window.requestFocus()
                }
            }
            val language = state.language
            Locale.setDefault(language.locale())
            key(language) {
                CorralTheme(state.useDark) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            AppMenuBar(
                                open = menuOpen,
                                onOpenChange = { menuOpen = it },
                                onQuit = {
                                    saveGeometry(prefsStore, app.state.value.prefs, awtWindow, offsetX, offsetY)
                                    exitApplication()
                                },
                            )
                            Box(Modifier.weight(1f)) {
                                AppScreen(app, pool, config, listState, scrcpy = { serial -> scrcpy.launch(serial) })
                            }
                        }
                        // Clicking anywhere outside the menu closes it. The DropdownMenu itself is a
                        // separate popup window, so this scrim only covers the app window.
                        if (menuOpen) {
                            Box(
                                Modifier.fillMaxSize().clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { menuOpen = false },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun saveGeometry(
    store: UiPrefs,
    prefs: Prefs,
    window: AwtWindow?,
    offsetX: Int,
    offsetY: Int,
) {
    if (window == null) return
    val updated = prefs.copy(
        windowX = window.x - offsetX,
        windowY = window.y - offsetY,
        windowW = window.width,
        windowH = window.height,
    )
    runCatching { store.save(updated) }
}

internal fun trayParkEnabled(
    isTraySupported: Boolean,
    sessionType: String?,
    hasSystemTray: Boolean,
    currentDesktop: String?,
): Boolean = isTraySupported &&
    hasSystemTray &&
    !sessionType.equals("wayland", ignoreCase = true) &&
    currentDesktop?.contains("GNOME", ignoreCase = true) != true

internal fun trayIconSize(): Int =
    runCatching { SystemTray.getSystemTray().trayIconSize.width }.getOrDefault(22)

internal fun trayImageFor(mark: ImageBitmap, size: Int): BufferedImage {
    val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.drawImage(mark.toAwtImage(), 0, 0, size, size, null)
    g.dispose()
    return out
}

/** A Compose menu bar: the native Swing MenuBar ignores the Material theme and looked out of place. */
@Composable
private fun AppMenuBar(open: Boolean, onOpenChange: (Boolean) -> Unit, onQuit: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(BarColor)) {
        Box {
            Text(
                "Corral",
                color = BarTextColor,
                modifier = Modifier
                    .clickable { onOpenChange(!open) }
                    .padding(horizontal = Dimens.space12, vertical = Dimens.space6),
            )
            DropdownMenu(
                expanded = open,
                onDismissRequest = { onOpenChange(false) },
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
            ) {
                DropdownMenuItem(onClick = { onOpenChange(false); onQuit() }) {
                    Text(stringResource(Res.string.menu_quit))
                }
            }
        }
    }
}


