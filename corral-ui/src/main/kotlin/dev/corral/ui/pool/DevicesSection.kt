package dev.corral.ui.pool

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.corral.ui.components.CorralPill
import dev.corral.ui.components.DeviceCard
import dev.corral.ui.components.DevicesEmptyState
import dev.corral.ui.components.SectionHeader
import dev.corral.ui.icons.AppIcon
import dev.corral.ui.icons.Icons
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.carousel_next
import dev.corral.ui.resources.carousel_prev
import dev.corral.ui.resources.devices_booting
import dev.corral.ui.resources.devices_free
import dev.corral.ui.resources.devices_held
import dev.corral.ui.resources.devices_offline
import dev.corral.ui.resources.rail_devices
import dev.corral.ui.theme.Dimens
import dev.corral.ui.theme.LocalExtendedColors
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
        SectionHeader(Icons.Smartphone, stringResource(Res.string.rail_devices), expanded, onToggle) {
            val free = state.devices.count { it.usable && it.lease == null }
            val held = state.devices.count { it.lease != null }
            val offline = state.devices.count { it.d.state == "offline" }
            val booting = state.devices.count { it.booting }
            CorralPill(
                text = listOfNotNull(
                    pluralStringResource(Res.plurals.devices_free, free, free),
                    pluralStringResource(Res.plurals.devices_held, held, held),
                    pluralStringResource(Res.plurals.devices_offline, offline, offline).takeIf { offline > 0 },
                    pluralStringResource(Res.plurals.devices_booting, booting, booting).takeIf { booting > 0 },
                ).joinToString(" · "),
                container = colors.containerHigh,
                content = colors.onSurfaceVariant,
            )
        }
        if (!expanded) return
        if (state.devices.isEmpty()) {
            DevicesEmptyState()
        } else {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val viewport by remember { derivedStateOf { listState.layoutInfo.viewportSize.width } }
            val canLeft = listState.canScrollBackward
            val canRight = listState.canScrollForward
            Box(Modifier.fillMaxWidth()) {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
                    contentPadding = PaddingValues(bottom = Dimens.space20),
                ) {
                    items(state.devices, key = { it.d.serial }) { view ->
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
                if (canLeft) {
                    ChevronScrim(Alignment.CenterStart, Brush.horizontalGradient(listOf(MaterialTheme.colors.surface, Color.Transparent)))
                    CarouselChevron(
                        icon = Icons.ChevronDown,
                        contentDescription = stringResource(Res.string.carousel_prev),
                        rotation = 90f,
                        modifier = Modifier.align(Alignment.CenterStart)
                            .padding(start = Dimens.carouselChevronInset),
                    ) { scope.launch { listState.animateScrollBy(-viewport.toFloat()) } }
                }
                if (canRight) {
                    ChevronScrim(Alignment.CenterEnd, Brush.horizontalGradient(listOf(Color.Transparent, MaterialTheme.colors.surface)))
                    CarouselChevron(
                        icon = Icons.ChevronDown,
                        contentDescription = stringResource(Res.string.carousel_next),
                        rotation = -90f,
                        modifier = Modifier.align(Alignment.CenterEnd)
                            .padding(end = Dimens.carouselChevronInset),
                    ) { scope.launch { listState.animateScrollBy(viewport.toFloat()) } }
                }
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BoxScope.ChevronScrim(alignment: Alignment, brush: Brush) {
    Box(
        Modifier.align(alignment)
            .height(Dimens.cardHeight)
            .width(Dimens.carouselScrimWidth)
            .background(brush),
    )
}

@Composable
private fun CarouselChevron(
    icon: DrawableResource,
    contentDescription: String,
    rotation: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalExtendedColors.current
    Box(
        modifier.size(Dimens.carouselChevronSize)
            .background(colors.containerHigh, CircleShape)
            .border(Dimens.border, colors.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(Dimens.iconMd).rotate(rotation),
            tint = colors.onSurfaceVariant,
        )
    }
}
