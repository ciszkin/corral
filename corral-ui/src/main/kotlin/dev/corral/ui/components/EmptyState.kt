package dev.corral.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.corral.ui.icons.AppIcon
import dev.corral.ui.icons.Icons
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.devices_empty
import dev.corral.ui.resources.devices_empty_title
import dev.corral.ui.resources.queue_empty
import dev.corral.ui.theme.Dimens
import dev.corral.ui.theme.LocalExtendedColors
import org.jetbrains.compose.resources.stringResource

@Composable
fun DevicesEmptyState(modifier: Modifier = Modifier) {
    val colors = LocalExtendedColors.current
    Column(
        modifier.fillMaxWidth().padding(vertical = Dimens.space28),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        AppIcon(Icons.Inbox, contentDescription = null, modifier = Modifier.size(Dimens.space40), tint = colors.onSurfaceVariant)
        Text(stringResource(Res.string.devices_empty_title), style = MaterialTheme.typography.subtitle1)
        Text(
            stringResource(Res.string.devices_empty),
            style = MaterialTheme.typography.body2,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun QueueEmptyState(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Dimens.space16, vertical = Dimens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(Icons.Inbox, contentDescription = null, modifier = Modifier.size(Dimens.iconSm),
            tint = LocalExtendedColors.current.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.space8))
        Text(stringResource(Res.string.queue_empty), style = MaterialTheme.typography.caption,
            color = LocalExtendedColors.current.onSurfaceVariant)
    }
}
