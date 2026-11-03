package dev.corral.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import dev.corral.UpdateInfo
import dev.corral.UpdateState
import dev.corral.ui.config.ConfigEffect
import dev.corral.ui.config.ConfigIntent
import dev.corral.ui.config.ConfigViewModel
import dev.corral.ui.config.RulesSection
import dev.corral.ui.config.SettingsSection
import dev.corral.ui.components.CorralPill
import dev.corral.ui.icons.AppIcon
import dev.corral.ui.icons.Icons
import dev.corral.ui.pool.DevicesSection
import dev.corral.ui.pool.DoctorSection
import dev.corral.ui.pool.EmulatorsSection
import dev.corral.ui.pool.PoolEffect
import dev.corral.ui.pool.PoolIntent
import dev.corral.ui.pool.PoolState
import dev.corral.ui.pool.PoolViewModel
import dev.corral.ui.pool.QueueSection
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.banner_screenshots_denied
import dev.corral.ui.resources.cancel
import dev.corral.ui.resources.confirm
import dev.corral.ui.resources.emulator_action_failed
import dev.corral.ui.resources.force_free_body
import dev.corral.ui.resources.force_free_failed
import dev.corral.ui.resources.force_free_nobody
import dev.corral.ui.resources.force_free_title
import dev.corral.ui.resources.menu_quit
import dev.corral.ui.resources.quit_body
import dev.corral.ui.resources.quit_confirm
import dev.corral.ui.resources.quit_title
import dev.corral.ui.resources.rail_devices
import dev.corral.ui.resources.rail_doctor
import dev.corral.ui.resources.rail_emulators
import dev.corral.ui.resources.rail_queue
import dev.corral.ui.resources.rail_rules
import dev.corral.ui.resources.rail_settings
import dev.corral.ui.resources.retry
import dev.corral.ui.resources.status_connected
import dev.corral.ui.resources.status_connecting
import dev.corral.ui.resources.status_disconnected
import dev.corral.ui.resources.status_stale_updated
import dev.corral.ui.resources.status_stale_watermark
import dev.corral.ui.resources.settings_save_failed
import dev.corral.ui.resources.theme_dark
import dev.corral.ui.resources.theme_light
import dev.corral.ui.resources.theme_system
import dev.corral.ui.resources.update_confirm_body
import dev.corral.ui.resources.update_confirm_title
import dev.corral.ui.resources.update_pill
import dev.corral.ui.theme.Dimens
import dev.corral.ui.theme.LocalExtendedColors
import dev.corral.ui.theme.ThemeMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal enum class AppSection(val label: StringResource, val icon: DrawableResource) {
    Devices(Res.string.rail_devices, Icons.Smartphone),
    Emulators(Res.string.rail_emulators, Icons.Cpu),
    Queue(Res.string.rail_queue, Icons.List),
    Doctor(Res.string.rail_doctor, Icons.Activity),
    Rules(Res.string.rail_rules, Icons.Shield),
    Settings(Res.string.rail_settings, Icons.Settings),
}

internal val AppSections: List<AppSection> = AppSection.entries

/**
 * The localized banner plus the daemon's own denial text, so the operator can read the identity that was
 * sent. The reason goes on its own line: the localized string already carries dash clauses, so joining with
 * another dash reads as one run-on sentence.
 */
internal fun deniedBannerText(base: String, reason: String?): String =
    if (reason.isNullOrBlank()) base else "$base\n$reason"

/**
 * The status-bar caption: the connection line plus the daemon's version when it is known. A blank
 * version is omitted; an untagged build reports `0.0.0-dev` and is shown as-is, because a real
 * placeholder beats a hidden version — the daemon jar governs device behaviour, so a stale number
 * would be worse than none.
 */
internal fun statusBarText(status: String, url: String, version: String): String =
    if (version.isBlank()) "$status — $url" else "$status — $url · v$version"

/**
 * The latest version to show in the status-bar pill, or null when no pill belongs there. Only an
 * UPDATE_AVAILABLE state renders one, so it disappears the moment the state moves on.
 */
internal fun updatePillVersion(update: UpdateInfo?): String? =
    if (update?.state == UpdateState.UPDATE_AVAILABLE) {
        update.latestVersion?.removePrefix("v")?.removePrefix("V")?.takeIf { it.isNotBlank() }
    } else {
        null
    }

@Composable
fun AppScreen(
    app: AppViewModel,
    pool: PoolViewModel,
    config: ConfigViewModel,
    listState: LazyListState,
    onQuit: () -> Unit,
    scrcpy: suspend (String) -> Unit,
) {
    val state by app.state.collectAsState()
    val poolState by pool.state.collectAsState()
    val configState by config.state.collectAsState()
    val scope = rememberCoroutineScope()
    val current by remember {
        derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, AppSections.lastIndex) }
    }
    var lastSectionHeightPx by remember { mutableStateOf(0) }
    var scrcpyFailure by remember { mutableStateOf<Pair<String, String>?>(null) }
    var forceFreeError by remember { mutableStateOf<String?>(null) }
    var emulatorError by remember { mutableStateOf<String?>(null) }
    var configSaveFailed by remember { mutableStateOf(false) }
    var confirmQuit by remember { mutableStateOf(false) }
    val expandedSections = remember { mutableStateMapOf<AppSection, Boolean>() }

    LaunchedEffect(Unit) {
        while (true) {
            pool.onIntent(PoolIntent.Tick(System.currentTimeMillis()))
            delay(1000)
        }
    }
    LaunchedEffect(Unit) {
        config.effect.collect { effect ->
            when (effect) {
                is ConfigEffect.Saved -> configSaveFailed = false
                is ConfigEffect.SaveFailed -> configSaveFailed = effect.errors.isEmpty()
            }
        }
    }
    LaunchedEffect(configState.dirty) {
        if (!configState.dirty) configSaveFailed = false
    }
    LaunchedEffect(Unit) {
        pool.effect.collect { effect ->
            when (effect) {
                is PoolEffect.ScrcpyFailed -> scrcpyFailure = effect.serial to effect.reason
                is PoolEffect.ForceFreeFailed -> forceFreeError = effect.reason
                is PoolEffect.EmulatorActionFailed -> emulatorError = effect.reason
            }
        }
    }
    LaunchedEffect(poolState.confirmFree) { forceFreeError = null }
    LaunchedEffect(poolState.emulators) { emulatorError = null }
    LaunchedEffect(state.url, state.connection is Connection.Connected) {
        if (state.connection is Connection.Connected) config.onIntent(ConfigIntent.Load)
    }

    val stale = state.connection is Connection.Disconnected
    val doctorFailed = poolState.doctor?.layers?.any { it.status == "fail" } == true
    val updatedAt = remember(state.lastUpdatedMs) {
        Instant.ofEpochMilli(state.lastUpdatedMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colors.surface)) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(IntrinsicSize.Max)
                    .fillMaxHeight()
                    .background(MaterialTheme.colors.surface)
                    .padding(Dimens.space8),
            ) {
                AppSections.forEachIndexed { index, section ->
                    RailItem(
                        icon = section.icon,
                        label = section.label,
                        active = index == current,
                        badge = section == AppSection.Doctor && doctorFailed,
                    ) {
                        expandedSections[section] = true
                        scope.launch { listState.animateScrollToItem(index) }
                    }
                }
                Spacer(Modifier.weight(1f))
                Divider(
                    color = LocalExtendedColors.current.outlineVariant,
                    thickness = Dimens.border,
                    modifier = Modifier.padding(vertical = Dimens.space8),
                )
                RailItem(
                    icon = Icons.Power,
                    label = Res.string.menu_quit,
                    active = false,
                    badge = false,
                ) { confirmQuit = true }
            }
            Column(Modifier.weight(1f)) {
                StatusBar(state, configState.update) { mode -> app.onIntent(AppIntent.SetTheme(mode)) }
                if (poolState.denied) {
                    val colors = LocalExtendedColors.current
                    Banner(
                        icon = Icons.CameraOff,
                        text = deniedBannerText(
                            stringResource(Res.string.banner_screenshots_denied), poolState.deniedReason),
                        container = colors.warningContainer,
                        content = colors.onWarningContainer,
                    )
                }
                if (configSaveFailed) {
                    val colors = LocalExtendedColors.current
                    Banner(
                        icon = Icons.XCircle,
                        text = stringResource(Res.string.settings_save_failed),
                        container = colors.errorContainer,
                        content = colors.onErrorContainer,
                    )
                }
                emulatorError?.let { reason ->
                    val colors = LocalExtendedColors.current
                    Banner(
                        icon = Icons.XCircle,
                        text = reason.ifBlank { stringResource(Res.string.emulator_action_failed) },
                        container = colors.errorContainer,
                        content = colors.onErrorContainer,
                    )
                }
                if (stale) {
                    val colors = LocalExtendedColors.current
                    Banner(
                        icon = Icons.Clock,
                        text = stringResource(Res.string.status_stale_updated, updatedAt),
                        container = colors.warningContainer,
                        content = colors.onWarningContainer,
                    )
                }
                val viewportPx by remember {
                    derivedStateOf { listState.layoutInfo.viewportSize.height }
                }
                val spacerPx = (viewportPx - lastSectionHeightPx).coerceAtLeast(0)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().alpha(if (stale) 0.6f else 1f),
                    contentPadding = PaddingValues(Dimens.space24),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space24),
                ) {
                    items(AppSections, key = { it.name }) { section ->
                        val expanded = expandedSections[section] ?: true
                        val toggle = { expandedSections[section] = !expanded }
                        when (section) {
                            AppSection.Devices -> DevicesSection(
                                state = poolState,
                                scrcpyFailure = scrcpyFailure,
                                onIntent = pool::onIntent,
                                onDismissScrcpy = { scrcpyFailure = null },
                                expanded = expanded,
                                onToggle = toggle,
                            ) { serial ->
                                scope.launch {
                                    runCatching { scrcpy(serial) }.onFailure { e ->
                                        pool.scrcpyFailed(serial, e.message.orEmpty())
                                    }
                                }
                            }
                            AppSection.Queue -> QueueSection(poolState, expanded, toggle)
                            AppSection.Doctor -> DoctorSection(poolState, state.seq, pool::onIntent, expanded, toggle)
                            AppSection.Emulators -> EmulatorsSection(poolState, pool::onIntent, expanded, toggle)
                            AppSection.Rules -> RulesSection(configState, config::onIntent, expanded, toggle)
                            AppSection.Settings -> Box(
                                Modifier.onSizeChanged { lastSectionHeightPx = it.height },
                            ) {
                                SettingsSection(
                                    configState, state, config::onIntent, app::onIntent, expanded, toggle,
                                )
                            }
                        }
                    }
                    if (spacerPx > 0) {
                        item {
                            Spacer(Modifier.height(with(LocalDensity.current) { spacerPx.toDp() }))
                        }
                    }
                }
            }
        }
        if (stale) {
            Text(
                stringResource(Res.string.status_stale_watermark),
                fontSize = Dimens.watermarkFontSize,
                color = MaterialTheme.colors.onSurface,
                modifier = Modifier.align(Alignment.Center).graphicsLayer(rotationZ = -8f).alpha(0.06f),
            )
        }
    }
    poolState.confirmFree?.let { serial ->
        ForceFreeDialog(
            serial = serial,
            state = poolState,
            error = forceFreeError,
            onConfirm = { pool.onIntent(PoolIntent.ConfirmForceFree) },
            onRetry = {
                forceFreeError = null
                pool.onIntent(PoolIntent.ConfirmForceFree)
            },
            onCancel = {
                forceFreeError = null
                pool.onIntent(PoolIntent.CancelForceFree)
            },
        )
    }
    if (configState.confirmInstall) {
        InstallDialog(
            version = configState.update?.latestVersion?.removePrefix("v")?.removePrefix("V").orEmpty(),
            busy = configState.installing,
            onConfirm = { config.onIntent(ConfigIntent.ConfirmInstall) },
            onCancel = { config.onIntent(ConfigIntent.CancelInstall) },
        )
    }
    if (confirmQuit) {
        QuitDialog(
            onConfirm = {
                confirmQuit = false
                onQuit()
            },
            onCancel = { confirmQuit = false },
        )
    }
}

@Composable
private fun InstallDialog(version: String, busy: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.update_confirm_title, version)) },
        text = { Text(stringResource(Res.string.update_confirm_body)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy,
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

@Composable
private fun QuitDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.quit_title)) },
        text = { Text(stringResource(Res.string.quit_body)) },
        confirmButton = { Button(onClick = onConfirm) { Text(stringResource(Res.string.quit_confirm)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun ForceFreeDialog(
    serial: String,
    state: PoolState,
    error: String?,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalExtendedColors.current
    val holder = state.devices.firstOrNull { it.d.serial == serial }?.lease?.owner
        ?: stringResource(Res.string.force_free_nobody)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.force_free_title, serial)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space12)) {
                Text(stringResource(Res.string.force_free_body, holder))
                if (error != null) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(colors.errorContainer, RoundedCornerShape(Dimens.radiusSm))
                            .padding(Dimens.space8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(Icons.XCircle, contentDescription = null, modifier = Modifier.size(Dimens.iconXs),
                            tint = colors.onErrorContainer)
                        Spacer(Modifier.width(Dimens.space8))
                        Text(stringResource(Res.string.force_free_failed),
                            style = MaterialTheme.typography.caption, color = colors.onErrorContainer,
                            modifier = Modifier.weight(1f))
                        TextButton(onRetry) { Text(stringResource(Res.string.retry)) }
                    }
                }
            }
        },
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

@Composable
private fun RailItem(
    icon: DrawableResource,
    label: StringResource,
    active: Boolean,
    badge: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalExtendedColors.current
    val background = if (active) colors.primaryContainer else Color.Transparent
    val tint = if (active) colors.onPrimaryContainer else MaterialTheme.colors.onSurface
    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = Dimens.space4)
            .background(background, RoundedCornerShape(Dimens.radiusSm))
            .clickable(onClick = onClick)
            .padding(Dimens.space8),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            AppIcon(icon, contentDescription = null, tint = tint)
            if (badge) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(Dimens.space8)
                        .background(MaterialTheme.colors.error, RoundedCornerShape(Dimens.radiusPill)),
                )
            }
        }
        Text(
            stringResource(label),
            style = MaterialTheme.typography.caption,
            color = tint,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun StatusBar(state: AppState, update: UpdateInfo?, onTheme: (ThemeMode) -> Unit) {
    val colors = LocalExtendedColors.current
    val status = when (val connection = state.connection) {
        is Connection.Connected -> stringResource(Res.string.status_connected, connection.seq)
        Connection.Connecting -> stringResource(Res.string.status_connecting)
        is Connection.Disconnected -> stringResource(Res.string.status_disconnected)
    }
    Row(
        Modifier.fillMaxWidth()
            .background(colors.containerHigh)
            .padding(horizontal = Dimens.space12, vertical = Dimens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            statusBarText(status, state.url, state.daemonVersion),
            style = MaterialTheme.typography.caption,
            modifier = Modifier.weight(1f),
        )
        UpdatePill(update)
        Row(
            Modifier.clip(RoundedCornerShape(Dimens.radiusPill))
                .background(colors.containerLow)
                .border(Dimens.border, colors.outlineVariant, RoundedCornerShape(Dimens.radiusPill))
                .padding(Dimens.space2),
        ) {
            ThemeOption(Icons.Monitor, Res.string.theme_system, state.prefs.themeMode == ThemeMode.SYSTEM) {
                onTheme(ThemeMode.SYSTEM)
            }
            ThemeOption(Icons.Sun, Res.string.theme_light, state.prefs.themeMode == ThemeMode.LIGHT) {
                onTheme(ThemeMode.LIGHT)
            }
            ThemeOption(Icons.Moon, Res.string.theme_dark, state.prefs.themeMode == ThemeMode.DARK) {
                onTheme(ThemeMode.DARK)
            }
        }
    }
}

@Composable
private fun UpdatePill(update: UpdateInfo?) {
    val version = updatePillVersion(update) ?: return
    val colors = LocalExtendedColors.current
    CorralPill(
        text = stringResource(Res.string.update_pill, version),
        container = colors.tertiaryContainer,
        content = colors.onTertiaryContainer,
        icon = Icons.RefreshCw,
        modifier = Modifier.padding(end = Dimens.space8),
    )
}

@Composable
private fun ThemeOption(icon: DrawableResource, label: StringResource, active: Boolean, onClick: () -> Unit) {
    val colors = LocalExtendedColors.current
    Box(
        Modifier.size(Dimens.space40, Dimens.space32)
            .clip(RoundedCornerShape(Dimens.radiusPill))
            .background(if (active) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon, contentDescription = stringResource(label), modifier = Modifier.size(Dimens.iconLg),
            tint = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant)
    }
}

@Composable
private fun Banner(icon: DrawableResource, text: String, container: Color, content: Color) {
    Row(
        Modifier.fillMaxWidth().background(container).padding(horizontal = Dimens.space24, vertical = Dimens.space9),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconLg), tint = content)
        Spacer(Modifier.width(Dimens.space8))
        Text(text, style = MaterialTheme.typography.body2, color = content, modifier = Modifier.weight(1f))
    }
}
