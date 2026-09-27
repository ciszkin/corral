package dev.corral.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import dev.corral.Lease
import dev.corral.ui.icons.Lucide
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.device_expiring
import dev.corral.ui.theme.Dimens
import dev.corral.ui.theme.LocalExtendedColors
import dev.corral.ui.theme.LocalMonoFont
import java.util.Locale
import org.jetbrains.compose.resources.stringResource

internal fun ttlSeconds(lease: Lease?, nowMs: Long): Long? =
    lease?.let { it.expiresAt - nowMs / 1000 }

internal fun formatDuration(seconds: Long): String =
    "%02d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)

@Composable
fun TTLText(lease: Lease?, now: Long, modifier: Modifier = Modifier) {
    val seconds = ttlSeconds(lease, now) ?: return
    if (seconds <= 0) {
        val colors = LocalExtendedColors.current
        CorralPill(
            text = stringResource(Res.string.device_expiring),
            icon = Lucide.Clock,
            container = colors.warningContainer,
            content = colors.onWarningContainer,
            modifier = modifier,
        )
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Clock, contentDescription = null, modifier = Modifier.size(Dimens.iconXs),
                tint = LocalExtendedColors.current.onSurfaceVariant)
            Spacer(Modifier.width(Dimens.space6))
            Text(formatDuration(seconds), fontFamily = LocalMonoFont.current,
                style = MaterialTheme.typography.caption)
        }
    }
}

@Composable
internal fun CorralPill(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier.clip(RoundedCornerShape(Dimens.radiusPill)).background(container)
            .padding(horizontal = Dimens.space10, vertical = Dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.space12), tint = content)
            Spacer(Modifier.width(Dimens.space5))
        }
        Text(text, style = MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Medium), color = content)
    }
}
