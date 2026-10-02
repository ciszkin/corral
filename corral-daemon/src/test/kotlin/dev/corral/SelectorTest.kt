package dev.corral

import kotlin.test.*

class SelectorTest {
    private val pixel = Device("emulator-5554", "device", model = "Pixel_6", osVersion = "13", avd = "Pixel6AVD", booted = true)
    private val samsung = Device("devusb", "device", model = "SM-S918B", osVersion = "4.4")

    @Test fun emptyMatchesAnyUsable() {
        assertTrue(Selector().matches(pixel)); assertTrue(Selector().matches(samsung))
        assertTrue(Selector().isEmpty)
    }

    @Test fun emptyRejectsOffline() = assertFalse(Selector().matches(Device("x", "offline")))

    @Test fun serialPins() {
        assertTrue(Selector(serial = "devusb").matches(samsung))
        assertFalse(Selector(serial = "devusb").matches(pixel))
    }

    @Test fun modelExact() {
        assertTrue(Selector(model = "Pixel_6").matches(pixel))
        assertFalse(Selector(model = "Pixel_7").matches(pixel))
    }

    @Test fun transportMatches() {
        assertTrue(Selector(transport = "emulator").matches(pixel)) // emulator-5554 -> transport "emulator"
        assertTrue(Selector(transport = "usb").matches(samsung))
        assertFalse(Selector(transport = "usb").matches(pixel))
    }

    @Test fun avdMatchesEmulatorsOnly() {
        assertTrue(Selector(avd = "Pixel6AVD").matches(pixel))
        assertFalse(Selector(avd = "Pixel6AVD").matches(samsung))
    }

    @Test fun minOsComparesNumericallyNotLexicographically() {
        assertTrue(Selector(minOs = "12").matches(pixel))      // "13" >= 12
        assertFalse(Selector(minOs = "12").matches(samsung))   // "4.4" < 12 (string compare would say otherwise)
        assertTrue(Selector(minOs = "4.4").matches(samsung))   // equal
    }

    @Test fun minOsNeverMatchesUnknownOs() =
        assertFalse(Selector(minOs = "10").matches(Device("d", "device")))

    @Test fun fieldsCombineWithAnd() =
        assertFalse(Selector(model = "Pixel_6", transport = "usb").matches(pixel))

    @Test fun osAtLeastBoundaries() {
        assertTrue(osAtLeast("13", "13"))
        assertTrue(osAtLeast("13.1", "13"))
        assertFalse(osAtLeast("12", "13"))
        assertTrue(osAtLeast("13", "4.4"))
        assertTrue(osAtLeast("13.0.1", "13"))
    }

    @Test fun toStringIsHumanReadable() {
        assertEquals("[any]", Selector().toString())
        assertEquals("[model=Pixel_6 os>=13]", Selector(model = "Pixel_6", minOs = "13").toString())
    }
}