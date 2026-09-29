package dev.corral.ui.pool

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import dev.corral.ui.components.SectionHeader
import dev.corral.ui.data.DoctorLayer
import dev.corral.ui.icons.AppIcon
import dev.corral.ui.icons.Icons
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.doctor_daemon_adb
import dev.corral.ui.resources.doctor_events
import dev.corral.ui.resources.doctor_layer_daemon
import dev.corral.ui.resources.doctor_layer_rules
import dev.corral.ui.resources.doctor_layer_sdk_shim
import dev.corral.ui.resources.doctor_recheck
import dev.corral.ui.resources.doctor_skipped
import dev.corral.ui.resources.doctor_status_fail
import dev.corral.ui.resources.doctor_status_info
import dev.corral.ui.resources.doctor_status_ok
import dev.corral.ui.resources.doctor_status_skipped
import dev.corral.ui.resources.doctor_status_warn
import dev.corral.ui.resources.doctor_title
import dev.corral.ui.theme.Dimens
import dev.corral.ui.theme.LocalExtendedColors
import dev.corral.ui.theme.LocalMonoFont
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun DoctorSection(state: PoolState, seq: Long, onIntent: (PoolIntent) -> Unit, expanded: Boolean, onToggle: () -> Unit) {
    val colors = LocalExtendedColors.current
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(Icons.Activity, stringResource(Res.string.doctor_title), expanded, onToggle) {
            Text(stringResource(Res.string.doctor_events, seq), fontFamily = LocalMonoFont.current,
                style = MaterialTheme.typography.caption, color = colors.onSurfaceVariant)
            Spacer(Modifier.width(Dimens.space8))
            IconButton({ onIntent(PoolIntent.RefreshDoctor) }) {
                AppIcon(Icons.RefreshCw, contentDescription = stringResource(Res.string.doctor_recheck))
            }
        }
        if (!expanded) return
        Column(
            Modifier.fillMaxWidth()
                .background(colors.containerLow, RoundedCornerShape(Dimens.radiusMd))
                .border(Dimens.border, colors.outlineVariant, RoundedCornerShape(Dimens.radiusMd))
                .clip(RoundedCornerShape(Dimens.radiusMd)),
        ) {
            val doctor = state.doctor
            when {
                doctor == null -> DoctorRow(
                    icon = Icons.MinusCircle,
                    tint = colors.muted,
                    name = stringResource(Res.string.doctor_title),
                    detail = stringResource(Res.string.doctor_skipped),
                    status = stringResource(Res.string.doctor_status_skipped),
                    statusTint = colors.muted,
                )
                else -> {
                    doctor.layers.forEachIndexed { index, layer ->
                        LayerRow(layer)
                        if (index != doctor.layers.lastIndex || doctor.adb.isNotBlank()) {
                            Divider(color = colors.outlineVariant)
                        }
                    }
                    if (doctor.adb.isNotBlank()) {
                        DoctorRow(
                            icon = Icons.Info,
                            tint = colors.tertiary,
                            name = stringResource(Res.string.doctor_daemon_adb),
                            detail = doctor.adb,
                            status = stringResource(Res.string.doctor_status_info),
                            statusTint = colors.tertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerRow(layer: DoctorLayer) {
    val colors = LocalExtendedColors.current
    val (icon, tint, word) = when (layer.status) {
        "ok" -> Triple(Icons.CheckCircle, colors.positive, stringResource(Res.string.doctor_status_ok))
        "fail" -> Triple(Icons.AlertTriangle, MaterialTheme.colors.error, stringResource(Res.string.doctor_status_fail))
        "skipped" -> Triple(Icons.MinusCircle, colors.muted, stringResource(Res.string.doctor_status_skipped))
        "warn" -> Triple(Icons.AlertTriangle, colors.warning, stringResource(Res.string.doctor_status_warn))
        else -> Triple(Icons.Info, colors.tertiary, layer.status.uppercase())
    }
    val name = when (layer.layer) {
        "daemon" -> stringResource(Res.string.doctor_layer_daemon)
        "sdk-shim" -> stringResource(Res.string.doctor_layer_sdk_shim)
        "rules" -> stringResource(Res.string.doctor_layer_rules)
        else -> layer.layer
    }
    DoctorRow(icon, tint, name, layer.detail, word, tint)
}

@Composable
private fun DoctorRow(
    icon: DrawableResource,
    tint: Color,
    name: String,
    detail: String,
    status: String,
    statusTint: Color,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.space16, vertical = Dimens.space10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconLg), tint = tint)
        Spacer(Modifier.width(Dimens.space12))
        Text(name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.body2,
            modifier = Modifier.width(Dimens.doctorNameWidth))
        Text(detail, fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.caption,
            color = LocalExtendedColors.current.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Dimens.space12))
        Text(status, style = MaterialTheme.typography.caption, fontWeight = FontWeight.Bold, color = statusTint)
    }
}
