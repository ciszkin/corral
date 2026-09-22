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
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.Color as AwtColor
import java.awt.Frame
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

private val BarColor = Color(0xFF2B2B2B)
private val BarTextColor = Color(0xFFEDEDED)

fun main(args: Array<String>) {
    val url = args.toList().zipWithNext().firstOrNull { it.first == "--url" }?.second
        ?: System.getenv("CORRAL_URL")?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1:8798"
    val owner = System.getenv("CORRAL_OWNER")?.takeIf { it.isNotBlank() } ?: "corral-ui"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = UiStore(CorralClient(url, owner), scope)
    store.start()
    val scrcpy = ScrcpyLauncher()
    application {
        var windowVisible by remember { mutableStateOf(true) }
        var trayReady by remember { mutableStateOf(false) }
        var showRequests by remember { mutableStateOf(0) }
        var menuOpen by remember { mutableStateOf(false) }

        // AWT tray icon, driven directly: clicking it (either button) brings the window back.
        if (isTraySupported) {
            DisposableEffect(url) {
                val tray = SystemTray.getSystemTray()
                val icon = TrayIcon(corralTrayImage(22), "Corral — $url")
                icon.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.button != MouseEvent.BUTTON1 && e.button != MouseEvent.BUTTON3) return
                        windowVisible = true
                        showRequests++
                    }
                })
                runCatching { tray.add(icon) }.onSuccess { trayReady = true }
                onDispose { tray.remove(icon) }
            }
        }

        Window(
            onCloseRequest = { if (trayReady) windowVisible = false else exitApplication() },
            visible = windowVisible,
            title = "corral — $url",
            icon = CorralIcon,
        ) {
            // `visible = true` alone leaves the window behind or minimized; restore + focus it.
            LaunchedEffect(showRequests) {
                if (showRequests > 0) {
                    window.extendedState = Frame.NORMAL
                    window.toFront()
                    window.requestFocus()
                }
            }
            MaterialTheme(colors = darkColors()) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        AppMenuBar(
                            open = menuOpen,
                            onOpenChange = { menuOpen = it },
                            onQuit = ::exitApplication,
                        )
                        Box(Modifier.weight(1f)) {
                            MainWindow(store, url, onScrcpy = { scrcpy.launch(it) })
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
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            DropdownMenu(
                expanded = open,
                onDismissRequest = { onOpenChange(false) },
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
            ) {
                DropdownMenuItem(onClick = { onOpenChange(false); onQuit() }) {
                    Text("Quit")
                }
            }
        }
    }
}

/** Placeholder tray mark until the real icon set lands — drawn with Java2D for the AWT TrayIcon. */
private fun corralTrayImage(size: Int): BufferedImage {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g: Graphics2D = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    val s = size.toFloat()
    g.color = AwtColor(0xF2, 0xA0, 0x3D)
    g.fill(RoundRectangle2D.Float(s * 0.06f, s * 0.06f, s * 0.88f, s * 0.88f, s * 0.22f, s * 0.22f))
    g.color = AwtColor(0x2B, 0x2B, 0x2B)
    g.fill(RoundRectangle2D.Float(s * 0.32f, s * 0.20f, s * 0.36f, s * 0.58f, s * 0.06f, s * 0.06f))
    g.color = AwtColor(0xF2, 0xA0, 0x3D)
    g.fillOval((s * 0.44f).toInt(), (s * 0.66f).toInt(), (s * 0.12f).toInt(), (s * 0.12f).toInt())
    g.dispose()
    return img
}

/** Placeholder window mark, mirroring [corralTrayImage]. */
object CorralIcon : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = Color(0xFFF2A03D),
            topLeft = Offset(w * 0.06f, h * 0.06f),
            size = Size(w * 0.88f, h * 0.88f),
            cornerRadius = CornerRadius(w * 0.22f),
        )
        drawRoundRect(
            color = Color(0xFF2B2B2B),
            topLeft = Offset(w * 0.32f, h * 0.20f),
            size = Size(w * 0.36f, h * 0.58f),
            cornerRadius = CornerRadius(w * 0.06f),
        )
        drawCircle(
            color = Color(0xFFF2A03D),
            radius = w * 0.06f,
            center = Offset(w * 0.5f, h * 0.72f),
        )
    }
}
