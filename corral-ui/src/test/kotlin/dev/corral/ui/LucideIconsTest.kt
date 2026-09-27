package dev.corral.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.addPathNodes
import dev.corral.ui.icons.Lucide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LucideIconsTest {
    private val icons: Map<String, ImageVector> =
        Lucide::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == ImageVector::class.java }
            .associate { it.name.removePrefix("get") to it.invoke(Lucide) as ImageVector }

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

    @Test fun everyGlyphIsStrokeBasedAtTwentyFour() {
        for ((name, icon) in icons) {
            assertEquals(24f, icon.defaultWidth.value, name)
            assertEquals(24f, icon.defaultHeight.value, name)
            assertEquals(24f, icon.viewportWidth, name)
            assertEquals(24f, icon.viewportHeight, name)
            assertTrue(icon.root.size > 0, "$name has no paths")
        }
    }

    @Test fun checkPathDataMatchesItsSource() {
        val check = Lucide.Check
        assertEquals(1, check.root.size)
        val path = check.root[0] as VectorPath
        assertEquals(addPathNodes("M20 6 9 17l-5-5"), path.pathData)
    }

    @Test fun smartphoneRectConvertsToRoundedArcs() {
        assertEquals(
            pathNodes(
                "M 7 2 H 17 A 2 2 0 0 1 19 4 V 20 A 2 2 0 0 1 17 22 H 7 A 2 2 0 0 1 5 20 V 4 A 2 2 0 0 1 7 2 Z",
                "M12 18h.01",
            ),
            dataOf(Lucide.Smartphone),
        )
    }

    @Test fun usbCirclesConvertToArcs() {
        assertEquals(
            pathNodes(
                "M 9 7 A 1 1 0 1 0 11 7 A 1 1 0 1 0 9 7 Z",
                "M 3 20 A 1 1 0 1 0 5 20 A 1 1 0 1 0 3 20 Z",
                "M4.7 19.3 19 5",
                "m21 3-3 1 2 2Z",
                "M9.26 7.68 5 12l2 5",
                "m10 14 5 2 3.5-3.5",
                "m18 12 1-1 1 1-1 1Z",
            ),
            dataOf(Lucide.Usb),
        )
    }

    private fun pathNodes(vararg data: String) = data.map { addPathNodes(it) }

    private fun dataOf(icon: ImageVector) =
        (0 until icon.root.size).map { (icon.root[it] as VectorPath).pathData }
}
