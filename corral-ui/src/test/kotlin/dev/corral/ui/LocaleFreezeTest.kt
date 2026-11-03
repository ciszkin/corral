package dev.corral.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class LocaleFreezeTest {
    @Test fun pinEnglishLocaleOverridesABelarusianDefault() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("be"))
            pinEnglishLocale()
            assertEquals("en", Locale.getDefault().language)
        } finally {
            Locale.setDefault(original)
        }
    }
}
