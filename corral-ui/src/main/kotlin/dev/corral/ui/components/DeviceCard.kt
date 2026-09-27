package dev.corral.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.corral.Lease
import dev.corral.ui.icons.Lucide
import dev.corral.ui.pool.DeviceView
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.action_force_free
import dev.corral.ui.resources.action_live_view
import dev.corral.ui.resources.device_android
import dev.corral.ui.resources.device_avd
import dev.corral.ui.resources.device_free
import dev.corral.ui.resources.device_offering
import dev.corral.ui.resources.device_offline
import dev.corral.ui.resources.device_unauthorized
import dev.corral.ui.resources.device_usable
import dev.corral.ui.resources.device_waiting_accept
import dev.corral.ui.resources.scrcpy_died
import dev.corral.ui.resources.scrcpy_missing
import dev.corral.ui.resources.thumbnail_denied
import dev.corral.ui.resources.transport_emulator
import dev.corral.ui.resources.transport_usb
import dev.corral.ui.resources.transport_wifi
import dev.corral.ui.theme.Dimens
import dev.corral.ui.theme.LocalExtendedColors
import dev.corral.ui.theme.LocalMonoFont
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun DeviceCard(
    view: DeviceView,
    bytes: ByteArray?,
    denied: Boolean,
    now: Long,
    canForceFree: Boolean,
    scrcpyReason: String?,
    onLive: () -> Unit,
    onForceFree: () -> Unit,
    onDismissScrcpy: () -> Unit,
) {
    val colors = LocalExtendedColors.current
    val nonUsable = view.d.state != "device"
    val offline = view.d.state == "offline"
    val accent = if (view.d.state == "unauthorized") colors.warning else colors.muted
    Column(
        Modifier.width(Dimens.cardMinWidth)
            .alpha(if (nonUsable) 0.9f else 1f)
            .background(colors.containerLow, RoundedCornerShape(Dimens.radiusMd))
            .drawBehind {
                drawRoundRect(
                    color = colors.outlineVariant,
                    cornerRadius = CornerRadius(Dimens.radiusMd.toPx()),
                    style = if (offline) {
                        Stroke(Dimens.border.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    } else {
                        Stroke(Dimens.border.toPx())
                    },
                )
                if (nonUsable) {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(0f, Dimens.space10.toPx()),
                        size = Size(Dimens.space3.toPx(), size.height - Dimens.space20.toPx()),
                        cornerRadius = CornerRadius(Dimens.space2.toPx()),
                    )
                }
            }
            .padding(Dimens.space16),
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(view.d.serial, fontFamily = LocalMonoFont.current, fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(Dimens.space8))
            TransportIcon(view.d.transport)
            Spacer(Modifier.weight(1f))
            StateChip(view.d.state)
        }
        scrcpyReason?.let { reason ->
            InlineError(
                text = stringResource(if (reason.contains("not found")) Res.string.scrcpy_missing else Res.string.scrcpy_died),
                onDismiss = onDismissScrcpy,
            )
        }
        Row {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.space6)) {
                HolderRow(view.lease)
                if (view.lease?.pendingAccept == true) {
                    Text(stringResource(Res.string.device_waiting_accept),
                        style = MaterialTheme.typography.caption, color = colors.onSurfaceVariant)
                } else {
                    TTLText(view.lease, now)
                }
                ModelRow(view.d.model, view.d.osVersion, view.d.avd)
            }
            Spacer(Modifier.width(Dimens.space12))
            Thumbnail(bytes, denied)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onLive) {
                Icon(Lucide.Airplay, contentDescription = stringResource(Res.string.action_live_view))
            }
            if (canForceFree && view.lease != null) {
                IconButton(onForceFree) {
                    Icon(Lucide.Unlock, contentDescription = stringResource(Res.string.action_force_free),
                        tint = MaterialTheme.colors.error)
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun InlineError(text: String, onDismiss: () -> Unit) {
    val colors = LocalExtendedColors.current
    Row(
        Modifier.fillMaxWidth().background(colors.errorContainer, RoundedCornerShape(Dimens.radiusSm)).padding(Dimens.space8),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Lucide.XCircle, contentDescription = null, modifier = Modifier.size(Dimens.iconXs),
            tint = colors.onErrorContainer)
        Spacer(Modifier.width(Dimens.space8))
        Text(text, style = MaterialTheme.typography.caption, color = colors.onErrorContainer,
            modifier = Modifier.weight(1f))
        IconButton(onDismiss, modifier = Modifier.size(Dimens.space28)) {
            Icon(Lucide.X, contentDescription = null, modifier = Modifier.size(Dimens.iconSm),
                tint = colors.onErrorContainer)
        }
    }
}

@Composable
private fun HolderRow(lease: Lease?) {
    val colors = LocalExtendedColors.current
    when {
        lease == null -> CorralPill(
            text = stringResource(Res.string.device_free),
            icon = Lucide.CheckCircle,
            container = colors.positiveContainer,
            content = colors.onPositiveContainer,
        )
        lease.pendingAccept -> CorralPill(
            text = stringResource(Res.string.device_offering, lease.owner),
            icon = Lucide.Loader,
            container = colors.tertiaryContainer,
            content = colors.onTertiaryContainer,
        )
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.User, contentDescription = null, modifier = Modifier.size(Dimens.iconXs),
                tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(Dimens.space6))
            Text(lease.owner, fontFamily = LocalMonoFont.current,
                style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ModelRow(model: String?, osVersion: String?, avd: String?) {
    val android = osVersion?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.device_android, it) }
    val avdLabel = avd?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.device_avd, it) }
    val parts = buildList {
        model?.takeIf { it.isNotBlank() }?.let { add(it) }
        android?.let { add(it) }
        avdLabel?.let { add(it) }
    }
    if (parts.isNotEmpty()) {
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.caption,
            color = LocalExtendedColors.current.onSurfaceVariant)
    }
}

@Composable
private fun TransportIcon(transport: String) {
    val icon = when (transport) {
        "wifi" -> Lucide.Wifi
        "emulator" -> Lucide.Monitor
        else -> Lucide.Usb
    }
    val label: StringResource = when (transport) {
        "wifi" -> Res.string.transport_wifi
        "emulator" -> Res.string.transport_emulator
        else -> Res.string.transport_usb
    }
    Icon(icon, contentDescription = stringResource(label), modifier = Modifier.size(Dimens.iconSm),
        tint = LocalExtendedColors.current.onSurfaceVariant)
}

@Composable
private fun StateChip(state: String) {
    val colors = LocalExtendedColors.current
    when (state) {
        "offline" -> CorralPill(stringResource(Res.string.device_offline), colors.mutedContainer,
            colors.onMutedContainer, icon = Lucide.MinusCircle)
        "unauthorized" -> CorralPill(stringResource(Res.string.device_unauthorized), colors.warningContainer,
            colors.onWarningContainer, icon = Lucide.AlertTriangle)
        "device" -> CorralPill(stringResource(Res.string.device_usable), colors.positiveContainer,
            colors.onPositiveContainer, icon = Lucide.CheckCircle)
        else -> CorralPill(state, colors.mutedContainer, colors.onMutedContainer)
    }
}

@Composable
private fun Thumbnail(bytes: ByteArray?, denied: Boolean) {
    val colors = LocalExtendedColors.current
    val bitmap = remember(bytes) { bytes?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() } }
    Box(
        Modifier.width(Dimens.thumbnailWidth).height(Dimens.thumbnailHeight)
            .background(colors.surfaceLowest, RoundedCornerShape(Dimens.radiusSm))
            .drawBehind {
                drawRoundRect(
                    color = colors.outlineVariant,
                    cornerRadius = CornerRadius(Dimens.radiusSm.toPx()),
                    style = Stroke(Dimens.border.toPx()),
                )
            }
            .clip(RoundedCornerShape(Dimens.radiusSm)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bytes == null && denied -> ThumbPlaceholder(Lucide.CameraOff,
                stringResource(Res.string.thumbnail_denied), colors.warningContainer, colors.onWarningContainer)
            bytes == null -> Box(Modifier.fillMaxSize().background(colors.containerHigh))
            bitmap == null -> ThumbPlaceholder(Lucide.Image, null, colors.containerHigh, colors.onSurfaceVariant)
            else -> Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun ThumbPlaceholder(icon: ImageVector, label: String?, container: Color, content: Color) {
    Column(
        Modifier.fillMaxSize().background(container).padding(Dimens.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconLg), tint = content)
        if (label != null) {
            Spacer(Modifier.height(Dimens.space6))
            Text(label, style = MaterialTheme.typography.overline, color = content, textAlign = TextAlign.Center)
        }
    }
}
