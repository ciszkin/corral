package dev.corral

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QueueTest {
    private var now = 1500L
    private var devices = listOf(Device("dev1", "device"), Device("dev2", "device"))

    private fun mgr(db: Db? = null) = LeaseManager({ devices }, db, { now })

    @Test fun fifoOrderFirstWaiterWins() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val got = mutableListOf<Lease>()
        launch { got += m.requestClaim(Selector(), "W1", waitSeconds = 600) }
        launch { got += m.requestClaim(Selector(), "W2", waitSeconds = 600) }
        runCurrent()
        assertEquals(listOf("W1", "W2"), m.queueSnapshot().map { it.owner })  // arrival order
        m.free("dev1", "A")
        runCurrent()
        assertEquals("dev1", got.single().serial)
        assertEquals("W1", got.single().owner)
        assertEquals(1, m.queueSnapshot().size)
        m.free("dev2", "B")                       // offer the second device so W2 is not parked at test end
        runCurrent()
    }

    @Test fun nonMatchingWaiterDoesNotBlockMatchingOne() = runTest {
        devices = listOf(Device("emu1", "device", model = "Pixel_6"),
                         Device("ph1", "device", model = "SM-S918B"))
        val m = mgr()
        m.claim("emu1", "A"); m.claim("ph1", "B")
        val got = mutableListOf<Lease>()
        launch { got += m.requestClaim(Selector(model = "Pixel_6"), "W1", waitSeconds = 600) }
        launch { got += m.requestClaim(Selector(), "W2", waitSeconds = 600) }
        runCurrent()
        m.free("ph1", "B")                       // Samsung: only W2 matches — W1 must not hold it up
        runCurrent()
        assertEquals("W2", got.single().owner)
        m.free("emu1", "A"); runCurrent()
        assertEquals(2, got.size); assertEquals("W1", got[1].owner)
    }

    @Test fun expiryHandsDeviceToWaiter() = runTest {
        val m = mgr()
        m.claim("dev1", "A", ttl = 10); m.claim("dev2", "B")
        val got = mutableListOf<Lease>()
        launch { got += m.requestClaim(Selector(), "W", waitSeconds = 600) }
        runCurrent()
        now += 11; m.sweep()
        runCurrent()
        assertEquals("dev1", got.single().serial)
    }

    @Test fun waiterTimesOutAndLeavesQueue() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        var failure: Throwable? = null
        val job = launch { try { m.requestClaim(Selector(), "W", waitSeconds = 10) }
                          catch (e: Throwable) { failure = e } }
        advanceUntilIdle()
        advanceTimeBy(11_000)
        job.join()
        val thrown = failure
        assertTrue(thrown is LeaseBusyException && thrown.timedOut)
        assertEquals(0, m.queueSnapshot().size)
    }

    @Test fun cancelledWaiterRemovedFromQueue() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val job = launch { m.requestClaim(Selector(), "W", waitSeconds = 600) }
        runCurrent()
        assertEquals(1, m.queueSnapshot().size)
        job.cancelAndJoin()
        assertEquals(0, m.queueSnapshot().size)
    }

    @Test fun cancelledWaiterWithPendingOfferRollsBackLeaseAndReoffers() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val got = mutableListOf<Lease>()
        val job = launch { got += m.requestClaim(Selector(serial = "dev1"), "W", waitSeconds = 600) }
        runCurrent()
        launch { got += m.requestClaim(Selector(serial = "dev1"), "W2", waitSeconds = 600) }
        runCurrent()
        m.free("dev1", "A")               // offers dev1 to W; W's continuation not yet resumed
        job.cancelAndJoin()               // W dies before consuming: lease must roll back
        runCurrent()                      // rollback re-offers dev1 to W2
        assertEquals("W2", got.single().owner)  // W never took dev1, W2 got the re-offer
        assertEquals("W2", m.snapshot["dev1"]?.owner)
        assertEquals(0, m.queueSnapshot().size)
    }

    @Test fun directClaimCannotOvertakeQueuedWaiter() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        launch { m.requestClaim(Selector(serial = "dev1"), "W", waitSeconds = 20000) }
        runCurrent()
        now += 9999; m.sweep()                    // dev1's lease expired -> sweep must offer it to W
        runCurrent()
        val e = runCatching { m.claim("dev1", "Sneaky") }.exceptionOrNull()!!
        assertTrue(e is LeaseBusyException)       // waiter got it, not the late arrival
    }

    @Test fun offerIsPendingUntilOwnerTouches() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val got = mutableListOf<Lease>()
        launch { got += m.requestClaim(Selector(serial = "dev1"), "W", waitSeconds = 600) }
        runCurrent()
        m.free("dev1", "A"); runCurrent()
        assertTrue(got.single().pendingAccept)
        assertTrue(m.check("dev1", "W").allowed)  // first touch accepts
        assertFalse(m.snapshot["dev1"]!!.pendingAccept)
    }

    @Test fun offeredLeaseIsTtlBoundNotPinnedToWaiterPid() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val got = mutableListOf<Lease>()
        // the waiter's pid tracks the QUEUED CLIENT (dead-waiter skip), not the holder; a live pid
        // here must not end up on the offered lease, or it would die with the CLI that waited
        launch { got += m.requestClaim(Selector(serial = "dev1"), "W",
            pid = ProcessHandle.current().pid(), waitSeconds = 600) }
        runCurrent()
        m.free("dev1", "A"); runCurrent()
        assertEquals(0L, got.single().pid)
        assertTrue(got.single().pendingAccept)
    }

    @Test fun busyReportsOfferWindowNotFullTtl() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        launch { m.requestClaim(Selector(serial = "dev1"), "W", waitSeconds = 600) }
        runCurrent()
        m.free("dev1", "A"); runCurrent()          // dev1 is now a pending 30 s offer to W
        now += 5                                    // 5 s into the window
        val busy = runCatching { m.requestClaim(Selector(serial = "dev1"), "X") }
            .exceptionOrNull() as LeaseBusyException
        assertEquals(ACCEPT_WINDOW_S - 5, busy.busy.single().ttlLeft)   // not the 1800 s TTL
    }

    @Test fun unacceptedOfferReclaimedAfterWindowAndReoffered() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val got = mutableListOf<Lease>()
        launch { got += m.requestClaim(Selector(serial = "dev1"), "ghost", waitSeconds = 600) }
        runCurrent()
        m.free("dev1", "A"); runCurrent()
        assertEquals("ghost", got.single().owner) // offer delivered...
        // ...but ghost never checks/renews: window lapses
        launch { got += m.requestClaim(Selector(serial = "dev1"), "W2", waitSeconds = 600) }
        runCurrent()
        now += ACCEPT_WINDOW_S + 1
        m.sweep()
        runCurrent()
        assertEquals("W2", m.snapshot["dev1"]?.owner)   // ghost's offer reclaimed, re-offered to W2
    }

    @Test fun deadWaiterSkippedWithoutAcceptanceWindow() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val got = mutableListOf<Lease>()
        launch {
            try { m.requestClaim(Selector(serial = "dev1"), "ghost", pid = 2_000_000_000L, waitSeconds = 600) }
            catch (_: Throwable) {}   // skipped waiter times out at cleanup; that's the point
        }
        launch { got += m.requestClaim(Selector(serial = "dev1"), "W2", waitSeconds = 600) }
        runCurrent()
        assertEquals(listOf("ghost", "W2"), m.queueSnapshot().map { it.owner })
        m.free("dev1", "A")           // head waiter is a dead pid -> skipped, W2 gets it at once
        runCurrent()
        assertEquals("W2", got.single().owner)
        assertEquals("dev1", got.single().serial)
        assertTrue(got.single().pendingAccept)
    }

    @Test fun offerBlocksDirectClaimerInsideWindow() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        launch { m.requestClaim(Selector(serial = "dev1"), "W", waitSeconds = 600) }
        runCurrent()
        m.free("dev1", "A"); runCurrent()
        now += ACCEPT_WINDOW_S - 5                // still inside the window
        assertTrue(runCatching { m.claim("dev1", "Sneaky") }.exceptionOrNull() is LeaseBusyException)
    }

    @Test fun busyErrorCarriesMatchingCount() = runTest {
        val m = mgr()
        m.claim("dev1", "A")
        val busy = runCatching { m.requestClaim(Selector(serial = "dev1"), "B") }.exceptionOrNull() as LeaseBusyException
        assertEquals(1, busy.matching)
        assertEquals(listOf("dev1"), busy.busy.map { it.serial })
        val none = runCatching { m.requestClaim(Selector(model = "Nope"), "B") }.exceptionOrNull() as LeaseBusyException
        assertEquals(0, none.matching)
        assertTrue(none.busy.isEmpty())
    }

    @Test fun requestClaimImmediatePathPicksFirstFreeMatch() = runTest {
        devices = listOf(Device("d1", "device", model = "Pixel_6"), Device("d2", "device"))
        val m = mgr()
        assertEquals("d1", m.requestClaim(Selector(model = "Pixel_6"), "A").serial)
        assertEquals("d2", m.requestClaim(Selector(), "A").serial)
    }

    @Test fun recoverDropsUnacceptedOffers() {
        val db = Db(createTempDirectory("corral").resolve("q.db"))
        db.upsertLease(Lease("dev1", "A", expiresAt = now + 100))
        db.upsertLease(Lease("dev2", "ghost", expiresAt = now + 100, pendingAccept = true, offeredAt = now))
        val m = mgr(db)
        m.recoverPersisted()
        assertEquals(setOf("dev1"), m.snapshot.keys)   // pending offer did not survive
    }
}