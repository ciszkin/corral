package dev.corral

import kotlin.test.Test
import kotlin.test.assertEquals

class CorralCliPathTest {
    @Test fun percentEncodesSpaces() {
        assertEquals("Wide%20AVD", pathSegment("Wide AVD"))
    }

    @Test fun leavesSafeNamesAlone() {
        assertEquals("Pixel_6", pathSegment("Pixel_6"))
    }

    @Test fun encodesLiteralPlus() {
        assertEquals("a%2Bb", pathSegment("a+b"))
    }

    @Test fun encodesSlash() {
        assertEquals("a%2Fb", pathSegment("a/b"))
    }
}
