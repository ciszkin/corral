package dev.corral.ui.theme

import androidx.compose.material.Colors
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import dev.corral.ui.resources.JetBrainsMono_Regular
import dev.corral.ui.resources.Res
import org.jetbrains.compose.resources.Font

data class ExtendedColors(
    val surface: Color,
    val surfaceLowest: Color,
    val containerLow: Color,
    val containerHigh: Color,
    val containerHighest: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val positive: Color,
    val positiveContainer: Color,
    val onPositiveContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val muted: Color,
    val mutedContainer: Color,
    val onMutedContainer: Color,
    val frame: Color,
    val frameText: Color,
    val scrim: Color,
)

val LocalExtendedColors = staticCompositionLocalOf { corralLightExtended }
val LocalMonoFont = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

@Composable
fun CorralTheme(useDark: Boolean, content: @Composable () -> Unit) {
    val colors = if (useDark) corralDarkColors else corralLightColors
    val extended = if (useDark) corralDarkExtended else corralLightExtended
    val monoFont = Font(Res.font.JetBrainsMono_Regular)
    val mono = remember(monoFont) { FontFamily(monoFont) }
    MaterialTheme(colors = colors) {
        CompositionLocalProvider(
            LocalContentColor provides colors.onSurface,
            LocalExtendedColors provides extended,
            LocalMonoFont provides mono,
            content = content,
        )
    }
}

private val corralDarkColors: Colors = darkColors(
    primary = Color(0xFFF59E0B),
    primaryVariant = Color(0xFF4A2D06),
    secondary = Color(0xFF33281C),
    secondaryVariant = Color(0xFFA9C8F0),
    background = Color(0xFF1D1B17),
    surface = Color(0xFF27241F),
    error = Color(0xFFF2B8B5),
    onPrimary = Color(0xFF3D2400),
    onSecondary = Color(0xFFF6E2D0),
    onBackground = Color(0xFFE7E2DA),
    onSurface = Color(0xFFE7E2DA),
    onError = Color(0xFF3D2400),
)

private val corralLightColors: Colors = lightColors(
    primary = Color(0xFFB45309),
    primaryVariant = Color(0xFFFBE3CD),
    secondary = Color(0xFFF6E2D0),
    secondaryVariant = Color(0xFF445F88),
    background = Color(0xFFFBF8F3),
    surface = Color(0xFFEFEBE3),
    error = Color(0xFFB3261E),
    onPrimary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFF301A0B),
    onBackground = Color(0xFF1D1B16),
    onSurface = Color(0xFF1D1B16),
    onError = Color(0xFFFFFFFF),
)

private val corralDarkExtended = ExtendedColors(
    surface = Color(0xFF1D1B17),
    surfaceLowest = Color(0xFF0C0B09),
    containerLow = Color(0xFF181613),
    containerHigh = Color(0xFF322E28),
    containerHighest = Color(0xFF3C3831),
    onSurfaceVariant = Color(0xFFC7BFB2),
    outline = Color(0xFF9D9488),
    outlineVariant = Color(0xFF4C463D),
    primaryContainer = Color(0xFF4A2D06),
    onPrimaryContainer = Color(0xFFFBE3CD),
    secondaryContainer = Color(0xFF33281C),
    onSecondaryContainer = Color(0xFFF6E2D0),
    tertiary = Color(0xFFA9C8F0),
    tertiaryContainer = Color(0xFF2C4666),
    onTertiaryContainer = Color(0xFFD8E4F8),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    positive = Color(0xFF86D98F),
    positiveContainer = Color(0xFF1D4527),
    onPositiveContainer = Color(0xFFB8EFC0),
    warning = Color(0xFFE8C36A),
    warningContainer = Color(0xFF4E3B08),
    onWarningContainer = Color(0xFFFAE7B0),
    muted = Color(0xFFBDB3A6),
    mutedContainer = Color(0xFF4A443B),
    onMutedContainer = Color(0xFFDED6C9),
    frame = Color(0xFF000000),
    frameText = Color(0xFF8A8A8A),
    scrim = Color(0x800C0B09),
)

private val corralLightExtended = ExtendedColors(
    surface = Color(0xFFFBF8F3),
    surfaceLowest = Color(0xFFFFFFFF),
    containerLow = Color(0xFFF6F2EB),
    containerHigh = Color(0xFFE8E4DC),
    containerHighest = Color(0xFFE0DBD1),
    onSurfaceVariant = Color(0xFF5A5248),
    outline = Color(0xFF817668),
    outlineVariant = Color(0xFFD7CFC2),
    primaryContainer = Color(0xFFFBE3CD),
    onPrimaryContainer = Color(0xFF3F1A00),
    secondaryContainer = Color(0xFFF6E2D0),
    onSecondaryContainer = Color(0xFF301A0B),
    tertiary = Color(0xFF445F88),
    tertiaryContainer = Color(0xFFD8E4F8),
    onTertiaryContainer = Color(0xFF10233F),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    positive = Color(0xFF1F6E33),
    positiveContainer = Color(0xFFDCEFD9),
    onPositiveContainer = Color(0xFF0B3D16),
    warning = Color(0xFF7A5A00),
    warningContainer = Color(0xFFF7E0A5),
    onWarningContainer = Color(0xFF3E2C00),
    muted = Color(0xFF6B6257),
    mutedContainer = Color(0xFFE9E2D8),
    onMutedContainer = Color(0xFF4A4238),
    frame = Color(0xFFC9C2B4),
    frameText = Color(0xFF6B6257),
    scrim = Color(0x80FBF8F3),
)
