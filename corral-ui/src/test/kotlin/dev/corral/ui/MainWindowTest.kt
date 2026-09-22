package dev.corral.ui

import dev.corral.DeviceStatus
import dev.corral.Lease
import kotlin.test.Test
import kotlin.test.assertEquals

class MainWindowTest {

    // Lease.expiresAt is epoch SECONDS (LeaseManager clock: currentTimeMillis()/1000), but
    // UiStore.now is epoch MILLIS — mixing them rendered every lease as "expired".
    @Test fun ttlCountsSecondsAgainstTheMillisecondClock() {
        val lease = Lease("dev1", "tester", expiresAt = 1_700_000_300)
        assertEquals("ttl 300s", ttlLeft(DeviceStatus("dev1", lease), 1_700_000_000_000))
    }

    @Test fun expiredLeaseAndFreeDevice() {
        val lease = Lease("dev1", "tester", expiresAt = 1_700_000_000)
        assertEquals("expired", ttlLeft(DeviceStatus("dev1", lease), 1_700_000_000_000))
        assertEquals("free", ttlLeft(DeviceStatus("dev1", null), 0))
    }
}
