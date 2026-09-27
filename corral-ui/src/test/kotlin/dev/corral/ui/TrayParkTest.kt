package dev.corral.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayParkTest {

    @Test fun waylandDisablesParking() {
        assertFalse(trayParkEnabled(true, "wayland", true, "X-Cinnamon"))
    }

    @Test fun x11WithHostEnablesParking() {
        assertTrue(trayParkEnabled(true, "x11", true, "X-Cinnamon"))
    }

    @Test fun unsupportedPlatformDisablesParking() {
        assertFalse(trayParkEnabled(false, "x11", true, "X-Cinnamon"))
    }

    @Test fun absentTrayHostDisablesParking() {
        assertFalse(trayParkEnabled(true, "x11", false, "X-Cinnamon"))
    }

    @Test fun gnomeOnX11DisablesParking() {
        assertFalse(trayParkEnabled(true, "x11", true, "GNOME"))
    }

    @Test fun nullSessionOnNonGnomeDesktopEnablesParking() {
        assertTrue(trayParkEnabled(true, null, true, null))
    }
}
