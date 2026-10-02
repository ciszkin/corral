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
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import dev.corral.QueueEntry
import dev.corral.ui.components.CorralPill
import dev.corral.ui.components.QueueEmptyState
import dev.corral.ui.components.SectionHeader
import dev.corral.ui.components.SelectorText
import dev.corral.ui.icons.AppIcon
import dev.corral.ui.icons.Icons
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.duration_min
import dev.corral.ui.resources.duration_min_sec
import dev.corral.ui.resources.queue_deadline
import dev.corral.ui.resources.queue_expired
import dev.corral.ui.resources.queue_position
import dev.corral.ui.resources.queue_waiting
import dev.corral.ui.resources.queue_waited
import dev.corral.ui.resources.rail_queue
import dev.corral.ui.theme.Dimens
import dev.corral.ui.theme.LocalExtendedColors
import dev.corral.ui.theme.LocalMonoFont
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun QueueSection(state: PoolState, expanded: Boolean, onToggle: () -> Unit) {
    val colors = LocalExtendedColors.current
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(Icons.List, stringResource(Res.string.rail_queue), expanded, onToggle) {
            CorralPill(
                text = pluralStringResource(Res.plurals.queue_waiting, state.queue.size, state.queue.size),
                container = colors.containerHigh,
                content = colors.onSurfaceVariant,
            )
        }
        if (!expanded) return
        Column(
            Modifier.fillMaxWidth()
                .background(colors.containerLow, RoundedCornerShape(Dimens.radiusMd))
                .border(Dimens.border, colors.outlineVariant, RoundedCornerShape(Dimens.radiusMd))
                .clip(RoundedCornerShape(Dimens.radiusMd)),
        ) {
            if (state.queue.isEmpty()) {
                QueueEmptyState()
            } else {
                state.queue.forEachIndexed { index, entry ->
                    QueueRow(entry)
                    if (index != state.queue.lastIndex) Divider(color = colors.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun QueueRow(entry: QueueEntry) {
    val colors = LocalExtendedColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.space16, vertical = Dimens.space10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.queue_position, entry.position), fontFamily = LocalMonoFont.current,
            color = colors.onSurfaceVariant, modifier = Modifier.width(Dimens.space30))
        Row(Modifier.width(Dimens.queueOwnerWidth), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Icons.User, contentDescription = null, modifier = Modifier.size(Dimens.iconXs),
                tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(Dimens.space6))
            Text(entry.owner, fontFamily = LocalMonoFont.current,
                style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Dimens.space12))
        SelectorText(entry.selector, Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Icons.Clock, contentDescription = null, modifier = Modifier.size(Dimens.iconXs),
                tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(Dimens.space6))
            val waited = stringResource(Res.string.duration_min_sec, entry.waitedSeconds / 60, entry.waitedSeconds % 60)
            Text(stringResource(Res.string.queue_waited, waited),
                fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.caption,
                color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(Dimens.space16))
        if (entry.deadlineIn <= 0) {
            CorralPill(stringResource(Res.string.queue_expired), colors.warningContainer,
                colors.onWarningContainer, icon = Icons.Clock)
        } else {
            Text(stringResource(Res.string.queue_deadline, stringResource(Res.string.duration_min, entry.deadlineIn / 60)),
                fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.caption,
                color = colors.onSurfaceVariant)
        }
    }
}
