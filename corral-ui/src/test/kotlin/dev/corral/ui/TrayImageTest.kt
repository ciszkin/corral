package dev.corral.ui

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrayImageTest {

    @Test fun trayImageKeepsTransparencyAndScalesToTheTraySize() {
        val mark = ImageBitmap(4, 4)
        val paint = Paint().apply { color = Color(0xFFFF0000) }
        Canvas(mark).drawRect(1f, 1f, 3f, 3f, paint)

        val out = trayImageFor(mark, 8)

        assertEquals(BufferedImage.TYPE_INT_ARGB, out.type)
        assertEquals(8, out.width)
        assertEquals(8, out.height)
        assertEquals(0, out.getRGB(0, 0) ushr 24)
        assertEquals(0, out.getRGB(7, 0) ushr 24)
        assertTrue(out.getRGB(4, 4) ushr 24 > 0)
    }
}
