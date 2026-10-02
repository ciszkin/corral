package dev.corral.ui.pool

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.corral.ui.AvdStatus
import dev.corral.ui.components.CorralPill
import dev.corral.ui.components.SectionHeader
import dev.corral.ui.icons.AppIcon
import dev.corral.ui.icons.Icons
import dev.corral.ui.lastError
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.cancel
import dev.corral.ui.resources.confirm
import dev.corral.ui.resources.device_booting
import dev.corral.ui.resources.device_free
import dev.corral.ui.resources.emulator_force_stop_body
import dev.corral.ui.resources.emulator_force_stop_title
import dev.corral.ui.resources.emulator_last_error
import dev.corral.ui.resources.emulator_not_running
import dev.corral.ui.resources.emulator_running
import dev.corral.ui.resources.emulator_start
import dev.corral.ui.resources.emulator_starting
import dev.corral.ui.resources.emulator_stop
import dev.corral.ui.resources.emulators_avds
import dev.corral.ui.resources.emulators_empty
import dev.corral.ui.resources.emulators_empty_title
import dev.corral.ui.resources.emulators_running
import dev.corral.ui.resources.force_free_nobody
import dev.corral.ui.resources.rail_emulators
import dev.corral.ui.theme.Dimens
import dev.corral.ui.theme.LocalExtendedColors
import dev.corral.ui.theme.LocalMonoFont
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmulatorsSection(state: PoolState, onIntent: (PoolIntent) -> Unit, expanded: Boolean, onToggle: () -> Unit) {
    val colors = LocalExtendedColors.current
    var confirmStop by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(Icons.Cpu, stringResource(Res.string.rail_emulators), expanded, onToggle) {
            val running = state.emulators.count { it.runningSerials.isNotEmpty() }
            CorralPill(
                text = listOf(
                    pluralStringResource(Res.plurals.emulators_avds, state.emulators.size, state.emulators.size),
                    pluralStringResource(Res.plurals.emulators_running, running, running),
                ).joinToString(" · "),
                container = colors.containerHigh,
                content = colors.onSurfaceVariant,
            )
        }
        if (!expanded) return
        if (state.emulators.isEmpty()) {
            EmulatorsEmptyState()
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
                verticalArrangement = Arrangement.spacedBy(Dimens.space12),
            ) {
                state.emulators.forEach { status ->
                    EmulatorCard(
                        state = state,
                        status = status,
                        onStart = { onIntent(PoolIntent.StartEmulator(status.avd)) },
                        onStop = { force ->
                            val leased = isLeased(state, status)
                            when {
                                force -> onIntent(PoolIntent.StopEmulator(status.avd, force = true))
                                leased -> confirmStop = status.avd
                                else -> onIntent(PoolIntent.StopEmulator(status.avd, force = false))
                            }
                        },
                    )
                }
            }
        }
        confirmStop?.let { avd -> state.emulators.firstOrNull { it.avd == avd } }?.let { status ->
            EmulatorStopDialog(
                avd = status.avd,
                holder = holderOf(state, status) ?: stringResource(Res.string.force_free_nobody),
                onConfirm = {
                    onIntent(PoolIntent.StopEmulator(status.avd, force = true))
                    confirmStop = null
                },
                onCancel = { confirmStop = null },
            )
        }
    }
}

private fun isLeased(state: PoolState, status: AvdStatus): Boolean =
    holderOf(state, status) != null

private fun holderOf(state: PoolState, status: AvdStatus): String? =
    status.runningSerials.firstNotNullOfOrNull { serial ->
        state.devices.firstOrNull { it.d.serial == serial }?.lease?.owner
    }

@Composable
private fun EmulatorCard(
    state: PoolState,
    status: AvdStatus,
    onStart: () -> Unit,
    onStop: (force: Boolean) -> Unit,
) {
    val colors = LocalExtendedColors.current
    val running = status.runningSerials.isNotEmpty()
    val starting = status.avd in state.startingEmulators
    val views = status.runningSerials.mapNotNull { serial -> state.devices.firstOrNull { it.d.serial == serial } }
    val booting = views.any { it.booting }
    val booted = views.any { it.usable }
    val owner = holderOf(state, status)
    val error = status.lastError
    Column(
        Modifier.width(Dimens.cardMinWidth)
            .background(colors.containerLow, RoundedCornerShape(Dimens.radiusMd))
            .border(Dimens.border, colors.outlineVariant, RoundedCornerShape(Dimens.radiusMd))
            .padding(Dimens.space16),
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Icons.Cpu, contentDescription = null, modifier = Modifier.size(Dimens.iconSm),
                tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(Dimens.space8))
            Text(status.avd, fontFamily = LocalMonoFont.current, fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (running) status.runningSerials.joinToString(", ") else stringResource(Res.string.emulator_not_running),
            fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.caption,
            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (!running && error != null) {
            Text(
                stringResource(Res.string.emulator_last_error, error),
                fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.caption,
                color = colors.warning, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (running) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                when {
                    booting -> CorralPill(stringResource(Res.string.device_booting), colors.warningContainer,
                        colors.onWarningContainer, icon = Icons.Loader)
                    booted -> CorralPill(stringResource(Res.string.emulator_running), colors.positiveContainer,
                        colors.onPositiveContainer, icon = Icons.CheckCircle)
                }
                when {
                    owner != null -> CorralPill(owner, colors.containerHigh, colors.onSurfaceVariant,
                        icon = Icons.User)
                    booted -> CorralPill(stringResource(Res.string.device_free), colors.positiveContainer,
                        colors.onPositiveContainer, icon = Icons.CheckCircle)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
            Button(onClick = onStart, enabled = !running && !starting) {
                Text(stringResource(
                    when {
                        starting -> Res.string.emulator_starting
                        running -> Res.string.emulator_running
                        else -> Res.string.emulator_start
                    },
                ))
            }
            Button(onClick = { onStop(false) }, enabled = running) {
                Text(stringResource(Res.string.emulator_stop))
            }
        }
    }
}

@Composable
private fun EmulatorsEmptyState() {
    val colors = LocalExtendedColors.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = Dimens.space28),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        AppIcon(Icons.Cpu, contentDescription = null, modifier = Modifier.size(Dimens.space40),
            tint = colors.onSurfaceVariant)
        Text(stringResource(Res.string.emulators_empty_title), style = MaterialTheme.typography.subtitle1)
        Text(stringResource(Res.string.emulators_empty), style = MaterialTheme.typography.body2,
            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EmulatorStopDialog(
    avd: String,
    holder: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.emulator_force_stop_title, avd)) },
        text = { Text(stringResource(Res.string.emulator_force_stop_body, holder)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.error,
                    contentColor = MaterialTheme.colors.onError,
                ),
            ) { Text(stringResource(Res.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel)) }
        },
    )
}
