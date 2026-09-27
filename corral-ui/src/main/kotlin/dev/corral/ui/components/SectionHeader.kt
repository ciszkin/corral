package dev.corral.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import dev.corral.ui.icons.Lucide
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.section_collapse
import dev.corral.ui.resources.section_expand
import dev.corral.ui.theme.Dimens
import dev.corral.ui.theme.LocalExtendedColors
import org.jetbrains.compose.resources.stringResource

@Composable
fun SectionHeader(
    icon: ImageVector,
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(bottom = Dimens.space16)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconXl),
                tint = MaterialTheme.colors.primary)
            Spacer(Modifier.width(Dimens.space12))
            Text(title, style = MaterialTheme.typography.h6)
            Spacer(Modifier.weight(1f))
            trailing()
            Spacer(Modifier.width(Dimens.space8))
            Box(
                Modifier.size(Dimens.iconXxl).clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.ChevronDown,
                    contentDescription = stringResource(
                        if (expanded) Res.string.section_collapse else Res.string.section_expand, title,
                    ),
                    modifier = Modifier.size(Dimens.iconLg).rotate(if (expanded) 180f else 0f),
                    tint = MaterialTheme.colors.primary)
            }
        }
        Spacer(Modifier.height(Dimens.space12))
        Divider(color = LocalExtendedColors.current.outlineVariant)
    }
}
