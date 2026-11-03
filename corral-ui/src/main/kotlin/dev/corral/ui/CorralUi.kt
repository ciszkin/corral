package dev.corral.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
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
import dev.corral.ui.theme.CorralTheme
import dev.corral.ui.theme.Dimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlin.system.exitProcess
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.painterResource
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

fun main(args: Array<String>) {
    val argUrl = args.toList().zipWithNext().firstOrNull { it.first == "--url" }?.second
    val envUrl = System.getenv("CORRAL_URL")?.takeIf { it.isNotBlank() }
    val envOwner = System.getenv("CORRAL_OWNER")?.takeIf { it.isNotBlank() }
    val prefsStore = UiPrefs()
    val stored = prefsStore.load()
    pinEnglishLocale()
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
    val scrcpy = ScrcpyLauncher(savedPath = { app.state.value.prefs.scrcpyPath })

    // The window libraries can die at class-init on exotic hosts (e.g. Skiko's theme read on
    // Windows under JDK 25) — turn that into a teaching exit instead of a raw stack.
    try {
    application {
        var windowVisible by remember { mutableStateOf(true) }
        var trayReady by remember { mutableStateOf(false) }
        var showRequests by remember { mutableStateOf(0) }
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
            CorralTheme(state.useDark) {
                AppScreen(
                    app, pool, config, listState,
                    onQuit = {
                        saveGeometry(prefsStore, app.state.value.prefs, awtWindow, offsetX, offsetY)
                        exitApplication()
                    },
                    scrcpy = { serial -> scrcpy.launch(serial) },
                )
            }
        }
    }
    } catch (e: Throwable) {
        System.err.println(
            "corral: the console could not start (${e.javaClass.simpleName}" +
                (e.cause?.let { ": ${it.javaClass.simpleName}" } ?: "") + ") — a known cause is " +
                "Skiko failing on very new JDKs; try running under JDK 17–21 and report the stack below")
        e.printStackTrace()
        exitProcess(1)
    }
}

internal fun pinEnglishLocale() {
    Locale.setDefault(Locale.ENGLISH)
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


