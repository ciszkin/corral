package dev.corral.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.corral.ui.components.CorralPill
import dev.corral.ui.components.SectionHeader
import dev.corral.ui.data.ConfigResponse
import dev.corral.ui.data.ConfigRules
import dev.corral.ui.icons.AppIcon
import dev.corral.ui.icons.Icons
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.config_loading
import dev.corral.ui.resources.config_warnings
import dev.corral.ui.resources.rail_rules
import dev.corral.ui.resources.rules_add
import dev.corral.ui.resources.rules_add_flag
import dev.corral.ui.resources.rules_boundary_warning
import dev.corral.ui.resources.rules_cannot_clear
import dev.corral.ui.resources.rules_discard
import dev.corral.ui.resources.rules_dirty
import dev.corral.ui.resources.rules_device_touching
import dev.corral.ui.resources.rules_entries
import dev.corral.ui.resources.rules_file_only
import dev.corral.ui.resources.rules_init_floor
import dev.corral.ui.resources.rules_init_floor_warning
import dev.corral.ui.resources.rules_pin_guard
import dev.corral.ui.resources.rules_readonly
import dev.corral.ui.resources.rules_reset
import dev.corral.ui.resources.rules_safe_defaults
import dev.corral.ui.resources.rules_save
import dev.corral.ui.resources.rules_saved
import dev.corral.ui.resources.rules_shell_skip
import dev.corral.ui.resources.rules_valueflags
import dev.corral.ui.resources.rules_valueflags_hint
import dev.corral.ui.resources.rules_valueflag_invalid
import dev.corral.ui.theme.Dimens
import dev.corral.ui.theme.LocalExtendedColors
import dev.corral.ui.theme.LocalMonoFont
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val SafeReadonly = listOf("devices", "start-server", "kill-server", "track-devices", "version", "help")
private val KnownValueFlags = listOf("-H", "-P", "-L", "-p", "-t")

private class RulesEditState {
    val openCards = mutableStateMapOf<String, Boolean>()
    var valueFlagDraft by mutableStateOf("")
    var valueFlagError by mutableStateOf<StringResource?>(null)
}

@Composable
fun RulesSection(state: ConfigState, onIntent: (ConfigIntent) -> Unit, expanded: Boolean, onToggle: () -> Unit) {
    val loaded = state.loaded
    val editState = remember { RulesEditState() }
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(Icons.Shield, stringResource(Res.string.rail_rules), expanded, onToggle) {
            if (loaded != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(Icons.FileText, contentDescription = null, modifier = Modifier.size(Dimens.iconXs),
                        tint = LocalExtendedColors.current.onSurfaceVariant)
                    Spacer(Modifier.width(Dimens.space6))
                    Text(loaded.rulesPath, fontFamily = LocalMonoFont.current,
                        style = MaterialTheme.typography.caption,
                        color = LocalExtendedColors.current.onSurfaceVariant)
                }
            }
        }
        if (expanded) {
            if (loaded == null) {
                Text(stringResource(Res.string.config_loading), style = MaterialTheme.typography.body2,
                    color = LocalExtendedColors.current.onSurfaceVariant)
            } else {
                val rules = state.editing ?: loaded.rules
                ReadonlyCard(rules, loaded, onIntent, editState)
                ValueFlagsCard(rules, onIntent, editState)
                IdentityCard(stringResource(Res.string.rules_shell_skip), "shell_skip", rules.shell_skip,
                    loaded.rulesPath, null, editState)
                IdentityCard(stringResource(Res.string.rules_init_floor), "init_floor", rules.init_floor,
                    loaded.rulesPath, stringResource(Res.string.rules_init_floor_warning), editState)
                ConfigWarnings(state.warnings.filter { it.isRulesMessage() })
                ConfigErrors(state.errors.filter { it.isRulesMessage() })
                if (state.clearBlocked) ConfigError(stringResource(Res.string.rules_cannot_clear))
            }
        }
        if (loaded != null) RulesFooter(state, loaded.rulesPath, onIntent)
    }
}

@Composable
private fun ReadonlyCard(rules: ConfigRules, loaded: ConfigResponse, onIntent: (ConfigIntent) -> Unit, editState: RulesEditState) {
    RuleCard(stringResource(Res.string.rules_readonly), "readonly", rules.readonly.size, editState, initiallyOpen = true) {
        GroupLabel(stringResource(Res.string.rules_safe_defaults))
        ChipFlow(SafeReadonly) { cmd ->
            ToggleChip(cmd, selected = cmd in rules.readonly,
                enabled = !(cmd in rules.readonly && rules.readonly.size == 1)) {
                onIntent(ConfigIntent.EditRules(rules.withReadonly(cmd, cmd !in rules.readonly)))
            }
        }
        val present = loaded.rules.readonly.filter { it !in SafeReadonly }
        if (present.isNotEmpty()) {
            GroupLabel(stringResource(Res.string.rules_device_touching), Icons.AlertTriangle)
            ChipFlow(present) { cmd -> ToggleChip(cmd, selected = true, enabled = false, onClick = {}) }
            FileOnlyNotice(loaded.rulesPath)
            WarningBanner(stringResource(Res.string.rules_boundary_warning))
        }
    }
}

@Composable
private fun ValueFlagsCard(rules: ConfigRules, onIntent: (ConfigIntent) -> Unit, editState: RulesEditState) {
    val colors = LocalExtendedColors.current
    RuleCard(stringResource(Res.string.rules_valueflags), "valueflags", rules.valueflags.size, editState) {
        GroupLabel(stringResource(Res.string.rules_valueflags))
        ChipFlow((KnownValueFlags + rules.valueflags).distinct()) { flag ->
            ToggleChip(flag, selected = flag in rules.valueflags,
                enabled = !(flag in rules.valueflags && rules.valueflags.size == 1)) {
                if (flag !in rules.valueflags && flag.startsWith("-s")) {
                    editState.valueFlagError = Res.string.rules_pin_guard
                } else {
                    onIntent(ConfigIntent.EditRules(rules.withValueFlag(flag, flag !in rules.valueflags)))
                }
            }
        }
        Text(stringResource(Res.string.rules_valueflags_hint), style = MaterialTheme.typography.caption,
            color = colors.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = editState.valueFlagDraft,
                onValueChange = { editState.valueFlagDraft = it; editState.valueFlagError = null },
                label = { Text(stringResource(Res.string.rules_add_flag)) },
                singleLine = true,
                modifier = Modifier.width(Dimens.valueFlagFieldWidth),
            )
            Spacer(Modifier.width(Dimens.space8))
            TextButton(onClick = {
                val flag = editState.valueFlagDraft.trim()
                editState.valueFlagError = when {
                    flag.startsWith("-s") -> Res.string.rules_pin_guard
                    flag.isBlank() -> null
                    !flag.startsWith("-") || flag.any { it.isWhitespace() || it in ",#=" } ->
                        Res.string.rules_valueflag_invalid
                    else -> {
                        onIntent(ConfigIntent.EditRules(rules.withValueFlag(flag, true)))
                        editState.valueFlagDraft = ""
                        null
                    }
                }
            }) { Text(stringResource(Res.string.rules_add)) }
        }
        editState.valueFlagError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(Icons.XCircle, contentDescription = null, modifier = Modifier.size(Dimens.iconXs),
                    tint = MaterialTheme.colors.error)
                Spacer(Modifier.width(Dimens.space6))
                Text(stringResource(error), style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.error)
            }
        }
    }
}

@Composable
private fun IdentityCard(
    title: String,
    key: String,
    entries: List<String>,
    path: String,
    warning: String?,
    editState: RulesEditState,
) {
    RuleCard(title, key, entries.size, editState) {
        ChipFlow(entries) { entry -> ReadOnlyChip(entry) }
        if (warning != null) WarningBanner(warning)
        FileOnlyNotice(path)
    }
}

@Composable
private fun RulesFooter(state: ConfigState, path: String, onIntent: (ConfigIntent) -> Unit) {
    val colors = LocalExtendedColors.current
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colors.surface, RoundedCornerShape(Dimens.radiusSm))
            .border(Dimens.border, colors.outlineVariant, RoundedCornerShape(Dimens.radiusSm))
            .padding(horizontal = Dimens.space16, vertical = Dimens.space10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(Icons.FileText, contentDescription = null, modifier = Modifier.size(Dimens.iconSm), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.space6))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(path, fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.caption,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Dimens.space8))
            if (state.dirty) {
                CorralPill(stringResource(Res.string.rules_dirty), colors.warningContainer,
                    colors.onWarningContainer, icon = Icons.Clock)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(Icons.CheckCircle, contentDescription = null, modifier = Modifier.size(Dimens.iconSm),
                        tint = colors.positive)
                    Spacer(Modifier.width(Dimens.space6))
                    Text(stringResource(Res.string.rules_saved), style = MaterialTheme.typography.caption,
                        color = colors.positive)
                }
            }
        }
        Spacer(Modifier.width(Dimens.space12))
        TextButton(onClick = { onIntent(ConfigIntent.Discard) }) {
            AppIcon(Icons.X, contentDescription = null, modifier = Modifier.size(Dimens.iconMd))
            Spacer(Modifier.width(Dimens.space8))
            Text(stringResource(Res.string.rules_discard), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = { onIntent(ConfigIntent.ResetRules) }) {
            AppIcon(Icons.RefreshCcw, contentDescription = null, modifier = Modifier.size(Dimens.iconMd))
            Spacer(Modifier.width(Dimens.space8))
            Text(stringResource(Res.string.rules_reset), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        Button(onClick = { onIntent(ConfigIntent.Save) }, enabled = state.dirty && !state.saving) {
            AppIcon(Icons.Save, contentDescription = null, modifier = Modifier.size(Dimens.iconMd))
            Spacer(Modifier.width(Dimens.space8))
            Text(stringResource(Res.string.rules_save), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RuleCard(
    title: String,
    key: String,
    count: Int,
    editState: RulesEditState,
    initiallyOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = LocalExtendedColors.current
    val open = editState.openCards[key] ?: initiallyOpen
    Column(
        Modifier.fillMaxWidth()
            .padding(bottom = Dimens.space12)
            .clip(RoundedCornerShape(Dimens.radiusSm))
            .background(colors.containerLow)
            .border(Dimens.border, colors.outlineVariant, RoundedCornerShape(Dimens.radiusSm)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { editState.openCards[key] = !open }
                .padding(horizontal = Dimens.space16, vertical = Dimens.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(Icons.ChevronDown, contentDescription = null,
                modifier = Modifier.size(Dimens.iconLg).rotate(if (open) 180f else 0f), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(Dimens.space12))
            Text(title, style = MaterialTheme.typography.subtitle1)
            Spacer(Modifier.width(Dimens.space8))
            Text(key, fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.primary)
            Spacer(Modifier.weight(1f))
            CorralPill(stringResource(Res.string.rules_entries, count), colors.containerHigh, colors.onSurfaceVariant)
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().padding(start = Dimens.space16, end = Dimens.space16, bottom = Dimens.space16),
                verticalArrangement = Arrangement.spacedBy(Dimens.space12),
            ) {
                content()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(entries: List<String>, chip: @Composable (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        entries.forEach { chip(it) }
    }
}

@Composable
private fun ToggleChip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalExtendedColors.current
    val container = if (selected) colors.secondaryContainer else colors.containerHigh
    val content = when {
        !enabled -> colors.muted
        selected -> colors.onSecondaryContainer
        else -> MaterialTheme.colors.onSurface
    }
    Row(
        Modifier.clip(RoundedCornerShape(Dimens.radiusPill))
            .background(container)
            .border(Dimens.border, if (selected) Color.Transparent else colors.outlineVariant, RoundedCornerShape(Dimens.radiusPill))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.space12, vertical = Dimens.space6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            AppIcon(Icons.Check, contentDescription = null, modifier = Modifier.size(Dimens.iconXs), tint = content)
            Spacer(Modifier.width(Dimens.space6))
        }
        Text(text, fontFamily = LocalMonoFont.current, style = MaterialTheme.typography.caption, color = content)
    }
}

@Composable
private fun ReadOnlyChip(text: String) {
    val colors = LocalExtendedColors.current
    Text(
        text,
        fontFamily = LocalMonoFont.current,
        style = MaterialTheme.typography.caption,
        modifier = Modifier.clip(RoundedCornerShape(Dimens.radiusPill))
            .background(colors.containerHigh)
            .border(Dimens.border, colors.outlineVariant, RoundedCornerShape(Dimens.radiusPill))
            .padding(horizontal = Dimens.space12, vertical = Dimens.space6),
    )
}

@Composable
private fun GroupLabel(text: String, icon: DrawableResource? = null) {
    val colors = LocalExtendedColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            AppIcon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconXs), tint = colors.warning)
            Spacer(Modifier.width(Dimens.space6))
        }
        Text(text, style = MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
            color = colors.onSurfaceVariant)
    }
}

@Composable
private fun WarningBanner(text: String) {
    val colors = LocalExtendedColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusSm))
            .background(colors.warningContainer).padding(Dimens.space10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(Icons.AlertTriangle, contentDescription = null, modifier = Modifier.size(Dimens.iconLg),
            tint = colors.onWarningContainer)
        Spacer(Modifier.width(Dimens.space8))
        Text(text, style = MaterialTheme.typography.caption, color = colors.onWarningContainer)
    }
}

@Composable
private fun FileOnlyNotice(path: String) {
    val colors = LocalExtendedColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Icons.Info, contentDescription = null, modifier = Modifier.size(Dimens.iconSm), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(Dimens.space6))
        Text(stringResource(Res.string.rules_file_only, path), style = MaterialTheme.typography.caption,
            color = colors.onSurfaceVariant)
    }
}

@Composable
internal fun ConfigWarnings(messages: List<String>) {
    if (messages.isEmpty()) return
    val colors = LocalExtendedColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusSm)).background(colors.warningContainer).padding(Dimens.space10),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Icons.AlertTriangle, contentDescription = null, modifier = Modifier.size(Dimens.iconSm),
                tint = colors.onWarningContainer)
            Spacer(Modifier.width(Dimens.space6))
            Text(stringResource(Res.string.config_warnings),
                style = MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
                color = colors.onWarningContainer)
        }
        messages.forEach { message ->
            Text(message, style = MaterialTheme.typography.caption, color = colors.onWarningContainer)
        }
    }
}

@Composable
internal fun ConfigErrors(messages: List<String>) {
    if (messages.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.radiusSm))
            .background(LocalExtendedColors.current.errorContainer).padding(Dimens.space10),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        messages.forEach { message -> ConfigError(message) }
    }
}

@Composable
internal fun ConfigError(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Icons.XCircle, contentDescription = null, modifier = Modifier.size(Dimens.iconXs),
            tint = MaterialTheme.colors.error)
        Spacer(Modifier.width(Dimens.space6))
        Text(message, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.error)
    }
}

private fun ConfigRules.withReadonly(cmd: String, on: Boolean): ConfigRules =
    copy(readonly = (if (on) readonly + cmd else readonly - cmd).distinct().sorted())

private fun ConfigRules.withValueFlag(flag: String, on: Boolean): ConfigRules =
    copy(valueflags = (if (on) valueflags + flag else valueflags - flag).distinct().sorted())
