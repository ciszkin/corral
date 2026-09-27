package dev.corral.ui.components

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.corral.ui.resources.Res
import dev.corral.ui.resources.selector_any
import dev.corral.ui.resources.selector_avd
import dev.corral.ui.resources.selector_model
import dev.corral.ui.resources.selector_os
import dev.corral.ui.resources.selector_serial
import dev.corral.ui.resources.selector_type
import org.jetbrains.compose.resources.stringResource

internal sealed interface SelectorPart {
    data object Any : SelectorPart
    data class Field(val kind: String, val value: String) : SelectorPart
}

internal fun selectorParts(raw: String): List<SelectorPart> {
    val inner = raw.trim().removePrefix("[").removeSuffix("]").trim()
    if (inner.isEmpty() || inner == "any") return listOf(SelectorPart.Any)
    return inner.split(" ").map { token ->
        val eq = token.indexOf('=')
        if (eq <= 0) {
            SelectorPart.Field("type", token)
        } else {
            val kind = when (val key = token.substring(0, eq)) {
                "os>" -> "os"
                else -> key
            }
            SelectorPart.Field(kind, token.substring(eq + 1))
        }
    }
}

@Composable
fun SelectorText(raw: String, modifier: Modifier = Modifier) {
    val labels = selectorParts(raw).map { part ->
        when (part) {
            SelectorPart.Any -> stringResource(Res.string.selector_any)
            is SelectorPart.Field -> when (part.kind) {
                "serial" -> stringResource(Res.string.selector_serial, part.value)
                "model" -> stringResource(Res.string.selector_model, part.value)
                "os" -> stringResource(Res.string.selector_os, part.value)
                "avd" -> stringResource(Res.string.selector_avd, part.value)
                else -> stringResource(Res.string.selector_type, part.value)
            }
        }
    }
    Text(labels.joinToString(" · "), modifier = modifier, style = MaterialTheme.typography.caption)
}
