package dev.corral

import kotlin.test.*

class DeviceMonitorTest {
    @Test fun parsesFullSnapshotMessages() {
        val payload = "dev1\tdevice\n192.168.1.5:5555\toffline\nemulator-5554\tdevice\n"
        val hex = payload.length.toString(16).padStart(4, '0')
        assertEquals(
            listOf("dev1" to "device", "192.168.1.5:5555" to "offline", "emulator-5554" to "device"),
            TrackParser.parse("$hex$payload"))
    }

    @Test fun transportClassified() {
        val d = TrackParser.parse("0011emulator-1\tdevice").first().let { Device(it.first, it.second) }
        assertEquals("emulator", d.transport)
    }
}
