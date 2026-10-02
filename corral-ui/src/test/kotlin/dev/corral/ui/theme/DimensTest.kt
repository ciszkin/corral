package dev.corral.ui.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class DimensTest {

    @Test fun keyTokensKeepTheirValues() {
        assertEquals(1.dp, Dimens.border)
        assertEquals(8.dp, Dimens.radiusSm)
        assertEquals(12.dp, Dimens.radiusMd)
        assertEquals(999.dp, Dimens.radiusPill)
        assertEquals(264.dp, Dimens.cardMinWidth)
        assertEquals(1024, Dimens.minWindowWidthPx)
        assertEquals(640, Dimens.minWindowHeightPx)
    }
}
