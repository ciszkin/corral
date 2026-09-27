package dev.corral.ui.theme

import androidx.compose.material.LocalContentColor
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

class CorralThemeTest {

    @Test fun darkThemeProvidesOnSurfaceAsContentColor() {
        assertEquals(Color(0xFFE7E2DA), contentColorOf(useDark = true))
    }

    @Test fun lightThemeProvidesOnSurfaceAsContentColor() {
        assertEquals(Color(0xFF1D1B16), contentColorOf(useDark = false))
    }

    private fun contentColorOf(useDark: Boolean): Color? {
        val recomposer = Recomposer(Dispatchers.Unconfined)
        val composition = Composition(NoopApplier, recomposer)
        var captured: Color? = null
        try {
            composition.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    CorralTheme(useDark = useDark) {
                        captured = LocalContentColor.current
                    }
                }
            }
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
        return captured
    }

    private object NoopApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun onClear() {}
    }
}
