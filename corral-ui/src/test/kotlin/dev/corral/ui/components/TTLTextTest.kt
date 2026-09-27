package dev.corral.ui.components

import dev.corral.Lease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TTLTextTest {

    // Lease.expiresAt is epoch SECONDS (LeaseManager clock: currentTimeMillis()/1000) while the
    // frame clock is epoch MILLIS — mixing them would render every lease as already expired.
    @Test fun ttlCountsSecondsAgainstTheMillisecondClock() {
        val lease = Lease("dev1", "tester", expiresAt = 1_700_000_300)
        assertEquals(300L, ttlSeconds(lease, 1_700_000_000_000))
        assertEquals(0L, ttlSeconds(lease, 1_700_000_300_000))
        assertEquals("05:00", formatDuration(300))
    }

    @Test fun expiredLeaseAndFreeDevice() {
        val lease = Lease("dev1", "tester", expiresAt = 1_700_000_000)
        assertEquals(0L, ttlSeconds(lease, 1_700_000_000_000))
        assertNull(ttlSeconds(null, 0))
        assertEquals("00:07", formatDuration(7))
    }
}
