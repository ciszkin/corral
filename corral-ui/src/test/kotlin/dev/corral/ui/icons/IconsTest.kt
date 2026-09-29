package dev.corral.ui.icons

import dev.corral.ui.resources.Res
import dev.corral.ui.resources.ic_activity
import dev.corral.ui.resources.ic_airplay
import dev.corral.ui.resources.ic_camera_off
import dev.corral.ui.resources.ic_check
import dev.corral.ui.resources.ic_chevron_down
import dev.corral.ui.resources.ic_circle_check_big
import dev.corral.ui.resources.ic_circle_minus
import dev.corral.ui.resources.ic_circle_x
import dev.corral.ui.resources.ic_clock
import dev.corral.ui.resources.ic_cpu
import dev.corral.ui.resources.ic_file_text
import dev.corral.ui.resources.ic_globe
import dev.corral.ui.resources.ic_image
import dev.corral.ui.resources.ic_inbox
import dev.corral.ui.resources.ic_info
import dev.corral.ui.resources.ic_list
import dev.corral.ui.resources.ic_loader
import dev.corral.ui.resources.ic_lock_open
import dev.corral.ui.resources.ic_monitor
import dev.corral.ui.resources.ic_moon
import dev.corral.ui.resources.ic_octagon_alert
import dev.corral.ui.resources.ic_plus
import dev.corral.ui.resources.ic_power
import dev.corral.ui.resources.ic_refresh_ccw
import dev.corral.ui.resources.ic_refresh_cw
import dev.corral.ui.resources.ic_save
import dev.corral.ui.resources.ic_server
import dev.corral.ui.resources.ic_settings
import dev.corral.ui.resources.ic_shield
import dev.corral.ui.resources.ic_smartphone
import dev.corral.ui.resources.ic_sun
import dev.corral.ui.resources.ic_terminal
import dev.corral.ui.resources.ic_triangle_alert
import dev.corral.ui.resources.ic_usb
import dev.corral.ui.resources.ic_user
import dev.corral.ui.resources.ic_wifi
import dev.corral.ui.resources.ic_x
import java.nio.file.Path
import kotlin.io.extension
import kotlin.io.readText
import kotlin.io.walkTopDown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.DrawableResource

class IconsTest {
    private val icons: Map<String, DrawableResource> =
        Icons::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == DrawableResource::class.java }
            .associate { it.name.removePrefix("get") to it.invoke(Icons) as DrawableResource }

    private val expected = setOf(
        "Activity", "Airplay", "AlertOctagon", "AlertTriangle", "CameraOff", "Check",
        "CheckCircle", "ChevronDown", "Clock", "Cpu", "FileText", "Globe", "Image",
        "Inbox", "Info", "List", "Loader", "MinusCircle", "Monitor", "Moon", "Plus",
        "Power", "RefreshCcw", "RefreshCw", "Save", "Server", "Settings", "Shield",
        "Smartphone", "Sun", "Terminal", "Unlock", "Usb", "User", "Wifi", "X", "XCircle",
    )

    @Test fun exposesEveryGlyph() {
        assertEquals(expected, icons.keys)
    }

    @Test fun everyGlyphIsItsGeneratedAccessor() {
        val accessors = mapOf(
            Icons.Activity to Res.drawable.ic_activity,
            Icons.Airplay to Res.drawable.ic_airplay,
            Icons.AlertOctagon to Res.drawable.ic_octagon_alert,
            Icons.AlertTriangle to Res.drawable.ic_triangle_alert,
            Icons.CameraOff to Res.drawable.ic_camera_off,
            Icons.Check to Res.drawable.ic_check,
            Icons.CheckCircle to Res.drawable.ic_circle_check_big,
            Icons.ChevronDown to Res.drawable.ic_chevron_down,
            Icons.Clock to Res.drawable.ic_clock,
            Icons.Cpu to Res.drawable.ic_cpu,
            Icons.FileText to Res.drawable.ic_file_text,
            Icons.Globe to Res.drawable.ic_globe,
            Icons.Image to Res.drawable.ic_image,
            Icons.Inbox to Res.drawable.ic_inbox,
            Icons.Info to Res.drawable.ic_info,
            Icons.List to Res.drawable.ic_list,
            Icons.Loader to Res.drawable.ic_loader,
            Icons.MinusCircle to Res.drawable.ic_circle_minus,
            Icons.Monitor to Res.drawable.ic_monitor,
            Icons.Moon to Res.drawable.ic_moon,
            Icons.Plus to Res.drawable.ic_plus,
            Icons.Power to Res.drawable.ic_power,
            Icons.RefreshCcw to Res.drawable.ic_refresh_ccw,
            Icons.RefreshCw to Res.drawable.ic_refresh_cw,
            Icons.Save to Res.drawable.ic_save,
            Icons.Server to Res.drawable.ic_server,
            Icons.Settings to Res.drawable.ic_settings,
            Icons.Shield to Res.drawable.ic_shield,
            Icons.Smartphone to Res.drawable.ic_smartphone,
            Icons.Sun to Res.drawable.ic_sun,
            Icons.Terminal to Res.drawable.ic_terminal,
            Icons.Unlock to Res.drawable.ic_lock_open,
            Icons.Usb to Res.drawable.ic_usb,
            Icons.User to Res.drawable.ic_user,
            Icons.Wifi to Res.drawable.ic_wifi,
            Icons.X to Res.drawable.ic_x,
            Icons.XCircle to Res.drawable.ic_circle_x,
        )
        assertEquals(expected.size, accessors.size)
        for ((res, generated) in accessors) assertTrue(res === generated, "$res is not $generated")
    }

    @Test fun everyVendoredSvgFollowsTheLucideContract() {
        val svgs = Path.of("src/main/composeResources/drawable").toFile().walkTopDown()
            .filter { it.isFile && it.extension == "svg" && it.name.startsWith("ic_") }
            .toList()
        assertEquals(37, svgs.size)
        for (file in svgs) {
            val text = file.readText()
            assertTrue(text.contains("<svg"), file.name)
            assertTrue(text.contains("viewBox=\"0 0 24 24\""), file.name)
            assertTrue(text.contains("stroke-width=\"2\""), file.name)
            assertTrue(text.contains("stroke-linecap=\"round\""), file.name)
        }
    }

    @Test fun packagedSvgIsReadable() = runBlocking {
        assertTrue(Res.readBytes("drawable/ic_smartphone.svg").isNotEmpty())
    }
}
