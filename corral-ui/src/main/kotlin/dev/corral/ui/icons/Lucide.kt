package dev.corral.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object Lucide {
    val Activity: ImageVector by lazy {
        ImageVector.Builder("Activity", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Airplay: ImageVector by lazy {
        ImageVector.Builder("Airplay", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M5 17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-1"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m12 15 5 6H7Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val AlertOctagon: ImageVector by lazy {
        ImageVector.Builder("AlertOctagon", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M12 16h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M12 8v4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M15.312 2a2 2 0 0 1 1.414.586l4.688 4.688A2 2 0 0 1 22 8.688v6.624a2 2 0 0 1-.586 1.414l-4.688 4.688a2 2 0 0 1-1.414.586H8.688a2 2 0 0 1-1.414-.586l-4.688-4.688A2 2 0 0 1 2 15.312V8.688a2 2 0 0 1 .586-1.414l4.688-4.688A2 2 0 0 1 8.688 2z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val AlertTriangle: ImageVector by lazy {
        ImageVector.Builder("AlertTriangle", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M12 9v4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M12 17h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val CameraOff: ImageVector by lazy {
        ImageVector.Builder("CameraOff", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M14.564 14.558a3 3 0 1 1-4.122-4.121"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m2 2 20 20"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M20 20H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h1.997a2 2 0 0 0 .819-.175"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M9.695 4.024A2 2 0 0 1 10.004 4h3.993a2 2 0 0 1 1.76 1.05l.486.9A2 2 0 0 0 18.003 7H20a2 2 0 0 1 2 2v7.344"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Check: ImageVector by lazy {
        ImageVector.Builder("Check", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M20 6 9 17l-5-5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val CheckCircle: ImageVector by lazy {
        ImageVector.Builder("CheckCircle", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M21.801 10A10 10 0 1 1 17 3.335"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m9 11 3 3L22 4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val ChevronDown: ImageVector by lazy {
        ImageVector.Builder("ChevronDown", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("m6 9 6 6 6-6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Clock: ImageVector by lazy {
        ImageVector.Builder("Clock", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M 2 12 A 10 10 0 1 0 22 12 A 10 10 0 1 0 2 12 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M12 6v6l4 2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Cpu: ImageVector by lazy {
        ImageVector.Builder("Cpu", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M12 20v2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M12 2v2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M17 20v2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M17 2v2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M2 12h2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M2 17h2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M2 7h2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M20 12h2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M20 17h2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M20 7h2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M7 20v2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M7 2v2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M 6 4 H 18 A 2 2 0 0 1 20 6 V 18 A 2 2 0 0 1 18 20 H 6 A 2 2 0 0 1 4 18 V 6 A 2 2 0 0 1 6 4 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M 9 8 H 15 A 1 1 0 0 1 16 9 V 15 A 1 1 0 0 1 15 16 H 9 A 1 1 0 0 1 8 15 V 9 A 1 1 0 0 1 9 8 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val FileText: ImageVector by lazy {
        ImageVector.Builder("FileText", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M14 2v5a1 1 0 0 0 1 1h5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M10 9H8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M16 13H8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M16 17H8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Globe: ImageVector by lazy {
        ImageVector.Builder("Globe", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M 2 12 A 10 10 0 1 0 22 12 A 10 10 0 1 0 2 12 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M2 12h20"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Image: ImageVector by lazy {
        ImageVector.Builder("Image", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M 5 3 H 19 A 2 2 0 0 1 21 5 V 19 A 2 2 0 0 1 19 21 H 5 A 2 2 0 0 1 3 19 V 5 A 2 2 0 0 1 5 3 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M 7 9 A 2 2 0 1 0 11 9 A 2 2 0 1 0 7 9 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Inbox: ImageVector by lazy {
        ImageVector.Builder("Inbox", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M 22 12 L 16 12 L 14 15 L 10 15 L 8 12 L 2 12"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Info: ImageVector by lazy {
        ImageVector.Builder("Info", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M 2 12 A 10 10 0 1 0 22 12 A 10 10 0 1 0 2 12 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M12 16v-4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M12 8h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val List: ImageVector by lazy {
        ImageVector.Builder("List", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M3 5h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M3 12h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M3 19h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M8 5h13"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M8 12h13"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M8 19h13"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Loader: ImageVector by lazy {
        ImageVector.Builder("Loader", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M12 2v4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m16.2 7.8 2.9-2.9"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M18 12h4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m16.2 16.2 2.9 2.9"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M12 18v4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m4.9 19.1 2.9-2.9"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M2 12h4"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m4.9 4.9 2.9 2.9"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val MinusCircle: ImageVector by lazy {
        ImageVector.Builder("MinusCircle", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M 2 12 A 10 10 0 1 0 22 12 A 10 10 0 1 0 2 12 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M8 12h8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Monitor: ImageVector by lazy {
        ImageVector.Builder("Monitor", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M 4 3 H 20 A 2 2 0 0 1 22 5 V 15 A 2 2 0 0 1 20 17 H 4 A 2 2 0 0 1 2 15 V 5 A 2 2 0 0 1 4 3 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M 8 21 L 16 21"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M 12 17 L 12 21"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Moon: ImageVector by lazy {
        ImageVector.Builder("Moon", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Plus: ImageVector by lazy {
        ImageVector.Builder("Plus", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M5 12h14"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M12 5v14"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Power: ImageVector by lazy {
        ImageVector.Builder("Power", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M12 2v10"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M18.4 6.6a9 9 0 1 1-12.77.04"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val RefreshCcw: ImageVector by lazy {
        ImageVector.Builder("RefreshCcw", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M3 3v5h5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M16 16h5v5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val RefreshCw: ImageVector by lazy {
        ImageVector.Builder("RefreshCw", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M21 3v5h-5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M8 16H3v5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Save: ImageVector by lazy {
        ImageVector.Builder("Save", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M15.2 3a2 2 0 0 1 1.4.6l3.8 3.8a2 2 0 0 1 .6 1.4V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M17 21v-7a1 1 0 0 0-1-1H8a1 1 0 0 0-1 1v7"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M7 3v4a1 1 0 0 0 1 1h7"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Server: ImageVector by lazy {
        ImageVector.Builder("Server", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M 4 2 H 20 A 2 2 0 0 1 22 4 V 8 A 2 2 0 0 1 20 10 H 4 A 2 2 0 0 1 2 8 V 4 A 2 2 0 0 1 4 2 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M 4 14 H 20 A 2 2 0 0 1 22 16 V 20 A 2 2 0 0 1 20 22 H 4 A 2 2 0 0 1 2 20 V 16 A 2 2 0 0 1 4 14 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M 6 6 L 6.01 6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M 6 18 L 6.01 18"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Settings: ImageVector by lazy {
        ImageVector.Builder("Settings", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M 9 12 A 3 3 0 1 0 15 12 A 3 3 0 1 0 9 12 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Shield: ImageVector by lazy {
        ImageVector.Builder("Shield", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Smartphone: ImageVector by lazy {
        ImageVector.Builder("Smartphone", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M 7 2 H 17 A 2 2 0 0 1 19 4 V 20 A 2 2 0 0 1 17 22 H 7 A 2 2 0 0 1 5 20 V 4 A 2 2 0 0 1 7 2 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M12 18h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Sun: ImageVector by lazy {
        ImageVector.Builder("Sun", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M 8 12 A 4 4 0 1 0 16 12 A 4 4 0 1 0 8 12 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M12 2v2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M12 20v2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m4.93 4.93 1.41 1.41"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m17.66 17.66 1.41 1.41"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M2 12h2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M20 12h2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m6.34 17.66-1.41 1.41"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m19.07 4.93-1.41 1.41"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Terminal: ImageVector by lazy {
        ImageVector.Builder("Terminal", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M12 19h8"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m4 17 6-6-6-6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Unlock: ImageVector by lazy {
        ImageVector.Builder("Unlock", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M 5 11 H 19 A 2 2 0 0 1 21 13 V 20 A 2 2 0 0 1 19 22 H 5 A 2 2 0 0 1 3 20 V 13 A 2 2 0 0 1 5 11 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M7 11V7a5 5 0 0 1 9.9-1"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Usb: ImageVector by lazy {
        ImageVector.Builder("Usb", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M 9 7 A 1 1 0 1 0 11 7 A 1 1 0 1 0 9 7 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M 3 20 A 1 1 0 1 0 5 20 A 1 1 0 1 0 3 20 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M4.7 19.3 19 5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m21 3-3 1 2 2Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M9.26 7.68 5 12l2 5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m10 14 5 2 3.5-3.5"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m18 12 1-1 1 1-1 1Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val User: ImageVector by lazy {
        ImageVector.Builder("User", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M 8 7 A 4 4 0 1 0 16 7 A 4 4 0 1 0 8 7 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val Wifi: ImageVector by lazy {
        ImageVector.Builder("Wifi", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M12 20h.01"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M2 8.82a15 15 0 0 1 20 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M5 12.859a10 10 0 0 1 14 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("M8.5 16.429a5 5 0 0 1 7 0"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val X: ImageVector by lazy {
        ImageVector.Builder("X", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M18 6 6 18"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m6 6 12 12"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }

    val XCircle: ImageVector by lazy {
        ImageVector.Builder("XCircle", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes("M 2 12 A 10 10 0 1 0 22 12 A 10 10 0 1 0 2 12 Z"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m15 9-6 6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
            addPath(
                pathData = addPathNodes("m9 9 6 6"),
                stroke = SolidColor(Color(0xFF000000)),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }.build()
    }
}
