package dev.corral.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.corral.UpdateInfo
import dev.corral.UpdateReason
import dev.corral.UpdateState
import dev.corral.ui.UiVersionSource
import dev.corral.ui.app.AppIntent
import dev.corral.ui.app.AppState
import dev.corral.ui.components.CorralPill
import dev.corral.ui.components.SectionHeader
import dev.corral.ui.data.ConfigResponse
import dev.corral.ui.data.ConfigSetting
import dev.corral.ui.data.Prefs
import dev.corral.ui.icons.AppIcon
import dev.corral.ui.icons.Icons
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.config_loading
import dev.corral.ui.resources.rail_settings
import dev.corral.ui.resources.rules_discard
import dev.corral.ui.resources.rules_dirty
import dev.corral.ui.resources.rules_save
import dev.corral.ui.resources.settings_adb_desc
import dev.corral.ui.resources.settings_client
import dev.corral.ui.resources.settings_daemon
import dev.corral.ui.resources.settings_db_desc
import dev.corral.ui.resources.settings_default
import dev.corral.ui.resources.settings_default_empty
import dev.corral.ui.resources.settings_emulator_desc
import dev.corral.ui.resources.settings_gradle_tasks_desc
import dev.corral.ui.resources.settings_launcher_desc
import dev.corral.ui.resources.settings_load_error
import dev.corral.ui.resources.settings_observers_desc
import dev.corral.ui.resources.settings_owner_desc
import dev.corral.ui.resources.settings_paths
import dev.corral.ui.resources.settings_port_desc
import dev.corral.ui.resources.settings_reset_defaults
import dev.corral.ui.resources.settings_restart_hint
import dev.corral.ui.resources.settings_saved
import dev.corral.ui.resources.settings_screenshots_desc
import dev.corral.ui.resources.settings_screenshots_open
import dev.corral.ui.resources.settings_screenshots_strict
import dev.corral.ui.resources.settings_scrcpy_desc
import dev.corral.ui.resources.settings_theme
import dev.corral.ui.resources.settings_theme_desc
import dev.corral.ui.resources.settings_url
import dev.corral.ui.resources.settings_url_desc
import dev.corral.ui.resources.settings_version
import dev.corral.ui.resources.settings_version_unknown
import dev.corral.ui.resources.theme_dark
import dev.corral.ui.resources.theme_light
import dev.corral.ui.resources.theme_system
import dev.corral.ui.resources.update_check_failed
import dev.corral.ui.resources.update_check_now
import dev.corral.ui.resources.update_current
import dev.corral.ui.resources.update_install
import dev.corral.ui.resources.update_install_failed
import dev.corral.ui.resources.update_installed
import dev.corral.ui.resources.update_installing
import dev.corral.ui.resources.update_latest
import dev.corral.ui.resources.update_section
import dev.corral.ui.resources.update_source
import dev.corral.ui.resources.update_source_desc
import dev.corral.ui.resources.update_state_available
import dev.corral.ui.resources.update_state_cause_unknown
import dev.corral.ui.resources.update_state_current
import dev.corral.ui.resources.update_state_disabled
import dev.corral.ui.resources.update_state_http
import dev.corral.ui.resources.update_state_not_checked
import dev.corral.ui.resources.update_state_unknown
import dev.corral.ui.resources.update_state_unparseable
import dev.corral.ui.resources.update_state_unreachable
import dev.corral.ui.theme.Dimens
import dev.corral.ui.theme.LocalExtendedColors
import dev.corral.ui.theme.LocalMonoFont
import dev.corral.ui.theme.ThemeMode
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The version row's value: the honest version, or an explicit placeholder when the jar reported none. */
internal fun versionText(version: String, unknown: String): String = version.ifBlank { unknown }

/** The update-source row's value: the daemon's repo, or a placeholder for a daemon that predates it. */
internal fun updateRepoText(loaded: ConfigResponse, unknown: String): String =
    loaded.updateRepo.ifBlank { unknown }

@Composable
fun SettingsSection(
    state: ConfigState,
    app: AppState,
    onConfigIntent: (ConfigIntent) -> Unit,
    onAppIntent: (AppIntent) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    var url by remember(app.url) { mutableStateOf(app.url) }
    var owner by remember(app.prefs.owner) { mutableStateOf(app.prefs.owner) }
    var scrcpy by remember(app.prefs.scrcpyPath) { mutableStateOf(app.prefs.scrcpyPath) }
    val clientDirty = url != app.url || owner != app.prefs.owner || scrcpy != app.prefs.scrcpyPath
    val dirty = clientDirty || state.dirty

    Column(Modifier.fillMaxWidth()) {
        SectionHeader(Icons.Settings, stringResource(Res.string.rail_settings), expanded, onToggle)
        if (expanded) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < Dimens.layoutBreakpoint
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space16)) {
                        ClientGroup(url, owner, scrcpy, app, onAppIntent, { url = it }, { owner = it }, { scrcpy = it })
                        DaemonGroup(state, app.daemonVersion, onConfigIntent)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space16)) {
                        ClientGroup(url, owner, scrcpy, app, onAppIntent, { url = it }, { owner = it }, { scrcpy = it },
                            Modifier.weight(1f))
                        DaemonGroup(state, app.daemonVersion, onConfigIntent, Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.size(Dimens.space12))
            UpdateGroup(state, onConfigIntent)
            Spacer(Modifier.size(Dimens.space12))
        }
        SettingsFooter(
            dirty = dirty,
            saving = state.saving,
            onDiscard = {
                url = app.url
                owner = app.prefs.owner
                scrcpy = app.prefs.scrcpyPath
                onConfigIntent(ConfigIntent.Discard)
            },
            onReset = {
                val defaults = Prefs()
                url = defaults.url
                owner = defaults.owner
                scrcpy = defaults.scrcpyPath
                onAppIntent(AppIntent.SetTheme(ThemeMode.SYSTEM))
                onConfigIntent(ConfigIntent.ResetToDefaults)
            },
            onSave = {
                if (url != app.url || owner != app.prefs.owner) onAppIntent(AppIntent.Connect(url, owner))
                if (scrcpy != app.prefs.scrcpyPath) onAppIntent(AppIntent.SetScrcpy(scrcpy))
                onConfigIntent(ConfigIntent.Save)
            },
        )
    }
}

@Composable
private fun ClientGroup(
    url: String,
    owner: String,
    scrcpy: String,
    app: AppState,
    onAppIntent: (AppIntent) -> Unit,
    onUrl: (String) -> Unit,
    onOwner: (String) -> Unit,
    onScrcpy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionGroup(stringResource(Res.string.settings_client), Icons.Monitor, modifier) {
        FieldBlock(stringResource(Res.string.settings_version)) {
            Text(versionText(UiVersionSource.version, stringResource(Res.string.settings_version_unknown)),
                fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.body2)
        }
        SettingField(url, stringResource(Res.string.settings_url),
            desc = stringResource(Res.string.settings_url_desc),
            default = stringResource(Res.string.settings_default, Prefs().url), onChange = onUrl)
        SettingField(owner, "CORRAL_OWNER",
            desc = stringResource(Res.string.settings_owner_desc),
            default = stringResource(Res.string.settings_default, Prefs().owner), onChange = onOwner)
        SettingField(scrcpy, "CORRAL_SCRCPY",
            desc = stringResource(Res.string.settings_scrcpy_desc),
            default = stringResource(Res.string.settings_default, stringResource(Res.string.settings_default_empty)),
            onChange = onScrcpy)
        FieldBlock(stringResource(Res.string.settings_theme), stringResource(Res.string.settings_theme_desc)) {
            Segmented(
                options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                selected = app.prefs.themeMode,
                onSelect = { mode -> onAppIntent(AppIntent.SetTheme(mode)) },
                icon = { mode ->
                    when (mode) {
                        ThemeMode.SYSTEM -> Icons.Monitor
                        ThemeMode.LIGHT -> Icons.Sun
                        ThemeMode.DARK -> Icons.Moon
                    }
                },
            ) { mode ->
                when (mode) {
                    ThemeMode.SYSTEM -> stringResource(Res.string.theme_system)
                    ThemeMode.LIGHT -> stringResource(Res.string.theme_light)
                    ThemeMode.DARK -> stringResource(Res.string.theme_dark)
                }
            }
        }
    }
}

@Composable
private fun DaemonGroup(
    state: ConfigState,
    daemonVersion: String,
    onConfigIntent: (ConfigIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalExtendedColors.current
    val loaded = state.loaded
    SectionGroup(stringResource(Res.string.settings_daemon), Icons.Server, modifier) {
        FieldBlock(stringResource(Res.string.settings_version)) {
            Text(versionText(daemonVersion, stringResource(Res.string.settings_version_unknown)),
                fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.body2)
        }
        if (loaded == null) {
            val message = if (state.loadFailed) Res.string.settings_load_error else Res.string.config_loading
            Text(stringResource(message), style = MaterialTheme.typography.body2,
                color = colors.onSurfaceVariant)
            return@SectionGroup
        }
        if (state.restartRequired) {
            Row(
                Modifier.clip(RoundedCornerShape(Dimens.radiusSm)).background(colors.warningContainer).padding(Dimens.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(Icons.Power, contentDescription = null, modifier = Modifier.size(Dimens.iconXs),
                    tint = colors.onWarningContainer)
                Spacer(Modifier.width(Dimens.space6))
                Text(stringResource(Res.string.settings_restart_hint), style = MaterialTheme.typography.caption,
                    color = colors.onWarningContainer)
            }
        }
        val settings = loaded.settings
        ConfigWarnings(state.warnings.filter { !it.isRulesMessage() })
        ConfigErrors(state.errors.filter { error ->
            !error.isRulesMessage() && settings.none { error.contains(it.key) }
        })
        settings.forEach { setting ->
            SettingRow(setting, state.settings[setting.key] ?: setting.value,
                state.errors.firstOrNull { it.contains(setting.key) && !it.isRulesMessage() }, onConfigIntent)
        }
        FieldBlock(
            stringResource(Res.string.update_source),
            stringResource(Res.string.update_source_desc),
        ) {
            Text(
                updateRepoText(loaded, stringResource(Res.string.settings_version_unknown)),
                fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.body2,
            )
        }
    }
}

@Composable
private fun UpdateGroup(
    state: ConfigState,
    onConfigIntent: (ConfigIntent) -> Unit,
) {
    val colors = LocalExtendedColors.current
    val info = state.update
    val available = info?.state == UpdateState.UPDATE_AVAILABLE
    val busy = state.updateChecking || state.installing
    SectionGroup(stringResource(Res.string.update_section), Icons.RefreshCw) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space16)) {
            FieldBlock(stringResource(Res.string.update_current)) {
                Text(
                    versionText(info?.currentVersion.orEmpty(), stringResource(Res.string.settings_version_unknown)),
                    fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.body2,
                )
            }
            FieldBlock(stringResource(Res.string.update_latest)) {
                Text(
                    info?.latestVersion?.removePrefix("v")?.removePrefix("V")?.takeIf { it.isNotBlank() }
                        ?.let { "v$it" } ?: stringResource(Res.string.settings_version_unknown),
                    fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.body2,
                )
            }
        }
        Text(
            updateStateText(info),
            style = MaterialTheme.typography.body2,
            color = if (available) colors.positive else colors.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
            TextButton(onClick = { onConfigIntent(ConfigIntent.CheckUpdate) }, enabled = !busy) {
                Text(stringResource(Res.string.update_check_now))
            }
            // The one destructive action here: error colours, never wired into ordinary Save.
            Button(
                onClick = { onConfigIntent(ConfigIntent.AskInstall) },
                enabled = available && !busy,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.error,
                    contentColor = MaterialTheme.colors.onError,
                ),
            ) { Text(stringResource(Res.string.update_install)) }
        }
        when {
            state.installing -> Text(
                stringResource(Res.string.update_installing),
                style = MaterialTheme.typography.caption, color = colors.onSurfaceVariant,
            )
            state.installed -> Text(
                stringResource(Res.string.update_installed),
                style = MaterialTheme.typography.caption, color = colors.positive,
            )
            state.installError != null -> Text(
                stringResource(Res.string.update_install_failed, state.installError),
                style = MaterialTheme.typography.caption, color = MaterialTheme.colors.error,
            )
            state.updateCheckFailed -> Text(
                stringResource(Res.string.update_check_failed),
                style = MaterialTheme.typography.caption, color = MaterialTheme.colors.error,
            )
        }
    }
}

@Composable
private fun updateStateText(info: UpdateInfo?): String = when (info?.state) {
    null -> stringResource(Res.string.update_state_unknown)
    UpdateState.CURRENT -> stringResource(Res.string.update_state_current)
    UpdateState.UPDATE_AVAILABLE -> stringResource(Res.string.update_state_available)
    UpdateState.DISABLED -> stringResource(Res.string.update_state_disabled)
    UpdateState.UNKNOWN -> when (info.reason) {
        UpdateReason.NOT_CHECKED -> stringResource(Res.string.update_state_not_checked)
        UpdateReason.UNREACHABLE -> stringResource(Res.string.update_state_unreachable)
        UpdateReason.HTTP_ERROR -> stringResource(Res.string.update_state_http, info.httpStatus ?: 0)
        UpdateReason.UNPARSEABLE -> stringResource(Res.string.update_state_unparseable)
        null -> stringResource(Res.string.update_state_cause_unknown)
    }
}

@Composable
private fun SettingRow(
    setting: ConfigSetting,
    value: String,
    error: String?,
    onConfigIntent: (ConfigIntent) -> Unit,
) {
    val default = setting.default.ifBlank { stringResource(Res.string.settings_default_empty) }
    val desc = descriptionOf(setting.key)?.let { stringResource(it) } ?: ""
    if (setting.key == "CORRAL_SCREENSHOTS") {
        FieldBlock(setting.key, desc, stringResource(Res.string.settings_default, default), error) {
            Segmented(
                options = listOf("strict", "open"),
                selected = value,
                onSelect = { choice -> onConfigIntent(ConfigIntent.EditSetting(setting.key, choice)) },
            ) { choice ->
                stringResource(
                    if (choice == "strict") Res.string.settings_screenshots_strict
                    else Res.string.settings_screenshots_open,
                )
            }
        }
    } else {
        SettingField(value, setting.key, desc = desc,
            default = stringResource(Res.string.settings_default, default), error = error) {
            onConfigIntent(ConfigIntent.EditSetting(setting.key, it))
        }
    }
}

@Composable
private fun SettingField(
    value: String,
    label: String,
    desc: String,
    default: String,
    error: String? = null,
    onChange: (String) -> Unit,
) {
    FieldBlock(label, desc, default, error) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FieldBlock(
    label: String,
    desc: String? = null,
    default: String? = null,
    error: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalExtendedColors.current
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        Text(label, style = MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
            color = colors.onSurfaceVariant)
        content()
        if (error != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(Icons.XCircle, contentDescription = null, modifier = Modifier.size(Dimens.iconXs),
                    tint = MaterialTheme.colors.error)
                Spacer(Modifier.width(Dimens.space6))
                Text(error, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.error)
            }
        }
        if (!desc.isNullOrEmpty() || default != null) {
            Text(
                buildString {
                    append(desc.orEmpty())
                    if (default != null) {
                        append(' ')
                        append(default)
                    }
                },
                style = MaterialTheme.typography.caption,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    icon: (T) -> DrawableResource? = { null },
    label: @Composable (T) -> String,
) {
    val colors = LocalExtendedColors.current
    Row(
        Modifier.clip(RoundedCornerShape(Dimens.radiusPill))
            .background(colors.containerLow)
            .border(Dimens.border, colors.outlineVariant, RoundedCornerShape(Dimens.radiusPill))
            .padding(Dimens.space2),
    ) {
        options.forEach { option ->
            val on = option == selected
            Row(
                Modifier.clip(RoundedCornerShape(Dimens.radiusPill))
                    .background(if (on) colors.secondaryContainer else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = Dimens.space16, vertical = Dimens.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon(option)?.let { glyph ->
                    AppIcon(glyph, contentDescription = null, modifier = Modifier.size(Dimens.iconSm),
                        tint = if (on) colors.onSecondaryContainer else colors.onSurfaceVariant)
                    Spacer(Modifier.width(Dimens.space8))
                }
                Text(label(option), style = MaterialTheme.typography.body2,
                    color = if (on) colors.onSecondaryContainer else colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionGroup(title: String, icon: DrawableResource, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = LocalExtendedColors.current
    Column(
        modifier.fillMaxWidth()
            .background(colors.containerLow, RoundedCornerShape(Dimens.radiusSm))
            .border(Dimens.border, colors.outlineVariant, RoundedCornerShape(Dimens.radiusSm))
            .padding(Dimens.space16),
        verticalArrangement = Arrangement.spacedBy(Dimens.space16),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconMd), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(Dimens.space8))
            Text(title, style = MaterialTheme.typography.subtitle1)
        }
        content()
    }
}

@Composable
private fun SettingsFooter(
    dirty: Boolean,
    saving: Boolean,
    onDiscard: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = LocalExtendedColors.current
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colors.surface, RoundedCornerShape(Dimens.radiusSm))
            .border(Dimens.border, colors.outlineVariant, RoundedCornerShape(Dimens.radiusSm))
            .padding(horizontal = Dimens.space16, vertical = Dimens.space10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.settings_paths), style = MaterialTheme.typography.caption,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Dimens.space8))
            if (dirty) {
                CorralPill(stringResource(Res.string.rules_dirty), colors.warningContainer,
                    colors.onWarningContainer, icon = Icons.Clock)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(Icons.CheckCircle, contentDescription = null, modifier = Modifier.size(Dimens.iconSm),
                        tint = colors.positive)
                    Spacer(Modifier.width(Dimens.space6))
                    Text(stringResource(Res.string.settings_saved), style = MaterialTheme.typography.caption,
                        color = colors.positive)
                }
            }
        }
        Spacer(Modifier.width(Dimens.space12))
        TextButton(onClick = onDiscard) {
            AppIcon(Icons.X, contentDescription = null, modifier = Modifier.size(Dimens.iconMd))
            Spacer(Modifier.width(Dimens.space8))
            Text(stringResource(Res.string.rules_discard), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onReset) {
            AppIcon(Icons.RefreshCcw, contentDescription = null, modifier = Modifier.size(Dimens.iconMd))
            Spacer(Modifier.width(Dimens.space8))
            Text(stringResource(Res.string.settings_reset_defaults), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        Button(onClick = onSave, enabled = dirty && !saving) {
            AppIcon(Icons.Save, contentDescription = null, modifier = Modifier.size(Dimens.iconMd))
            Spacer(Modifier.width(Dimens.space8))
            Text(stringResource(Res.string.rules_save), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun descriptionOf(key: String): StringResource? = when (key) {
    "CORRAL_PORT" -> Res.string.settings_port_desc
    "CORRAL_SCREENSHOTS" -> Res.string.settings_screenshots_desc
    "CORRAL_SCREENSHOT_OBSERVERS" -> Res.string.settings_observers_desc
    "CORRAL_ADB" -> Res.string.settings_adb_desc
    "CORRAL_EMULATOR" -> Res.string.settings_emulator_desc
    "CORRAL_EMULATOR_LAUNCHER" -> Res.string.settings_launcher_desc
    "CORRAL_DB" -> Res.string.settings_db_desc
    "CORRAL_GRADLE_TASKS" -> Res.string.settings_gradle_tasks_desc
    else -> null
}
