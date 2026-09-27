package dev.corral.ui.pool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.corral.ui.components.CorralPill
import dev.corral.ui.components.DeviceCard
import dev.corral.ui.components.DevicesEmptyState
import dev.corral.ui.components.SectionHeader
import dev.corral.ui.icons.Lucide
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.devices_counts
import dev.corral.ui.resources.rail_devices
import dev.corral.ui.theme.Dimens
import dev.corral.ui.theme.LocalExtendedColors
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevicesSection(
    state: PoolState,
    scrcpyFailure: Pair<String, String>?,
    onIntent: (PoolIntent) -> Unit,
    onDismissScrcpy: () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    onScrcpy: (String) -> Unit,
) {
    val colors = LocalExtendedColors.current
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(Lucide.Smartphone, stringResource(Res.string.rail_devices), expanded, onToggle) {
            val free = state.devices.count { it.lease == null }
            CorralPill(
                text = stringResource(Res.string.devices_counts, free, state.devices.size - free),
                container = colors.containerHigh,
                content = colors.onSurfaceVariant,
            )
        }
        if (!expanded) return
        if (state.devices.isEmpty()) {
            DevicesEmptyState()
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
                verticalArrangement = Arrangement.spacedBy(Dimens.space12),
            ) {
                state.devices.forEach { view ->
                    DeviceCard(
                        view = view,
                        bytes = state.thumbnails[view.d.serial],
                        denied = state.denied,
                        now = state.now,
                        canForceFree = state.capabilities.canForceFree,
                        scrcpyReason = scrcpyFailure?.takeIf { it.first == view.d.serial }?.second,
                        onLive = { onScrcpy(view.d.serial) },
                        onForceFree = { onIntent(PoolIntent.AskForceFree(view.d.serial)) },
                        onDismissScrcpy = onDismissScrcpy,
                    )
                }
            }
        }
    }
}
