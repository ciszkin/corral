package dev.corral.ui.theme

import java.util.Locale

enum class ThemeMode { SYSTEM, DARK, LIGHT }

enum class Language { ENGLISH, BELARUSIAN }

fun Language.locale(): Locale = when (this) {
    Language.ENGLISH -> Locale.ENGLISH
    Language.BELARUSIAN -> Locale("be")
}
