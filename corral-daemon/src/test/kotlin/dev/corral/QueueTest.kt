package dev.corral

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QueueTest {
    private var now = 1500L
    private var devices = listOf(Device("dev1", "device"), Device("dev2", "device"))

    private fun mgr(db: Db? = null, offerHold: Long = OFFER_HOLD_S) =
        LeaseManager({ devices }, db, { now }, offerHold = offerHold)

    @Test fun ticketsServedInArrivalOrder() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t1 = m.enqueue(Selector(serial = "dev1"), "W1", 0, 1800, waitSeconds = 600)
        val t2 = m.enqueue(Selector(serial = "dev1"), "W2", 0, 1800, waitSeconds = 600)
        assertEquals(listOf("W1", "W2"), m.queueSnapshot().map { it.owner })
        m.free("dev1", "A")
        assertEquals("W1", (m.poll(t1.id, "W1") as Poll.Offered).lease.owner)
        m.free("dev1", "W1")
        assertEquals("W2", (m.poll(t2.id, "W2") as Poll.Offered).lease.owner)
        assertEquals(0, m.queueSnapshot().size)
    }

    @Test fun nonMatchingTicketDoesNotBlockMatchingTicket() = runTest {
        devices = listOf(Device("emu1", "device", model = "Pixel_6"),
                         Device("ph1", "device", model = "SM-S918B"))
        val m = mgr()
        m.claim("emu1", "A"); m.claim("ph1", "B")
        val t1 = m.enqueue(Selector(model = "Pixel_6"), "W1", 0, 1800, waitSeconds = 600)
        val t2 = m.enqueue(Selector(), "W2", 0, 1800, waitSeconds = 600)
        m.free("ph1", "B")                        // Samsung: only W2 matches — W1 must not hold it up
        assertEquals("W2", (m.poll(t2.id, "W2") as Poll.Offered).lease.owner)
        m.free("emu1", "A")
        assertEquals("W1", (m.poll(t1.id, "W1") as Poll.Offered).lease.owner)
    }

    @Test fun expiryHandsDeviceToTicket() = runTest {
        val m = mgr()
        m.claim("dev1", "A", ttl = 10); m.claim("dev2", "B")
        val t = m.enqueue(Selector(), "W", 0, 1800, waitSeconds = 600)
        now += 11
        assertTrue(m.poll(t.id, "W") is Poll.Queued)   // clients keep polling; refresh before dispatch
        m.sweep()
        assertEquals("dev1", (m.poll(t.id, "W") as Poll.Offered).lease.serial)
    }

    @Test fun pidDeathHandsDeviceToTicket() = runTest {
        val m = mgr()
        val victim = ProcessBuilder("sleep", "60").start()
        try {
            m.claim("dev1", "A", pid = victim.pid(), ttl = 99999); m.claim("dev2", "B")
            val t = m.enqueue(Selector(), "W", 0, 1800, waitSeconds = 600)
            assertTrue(m.poll(t.id, "W") is Poll.Queued)   // holder alive: no offer yet
            victim.destroyForcibly(); victim.waitFor()     // holder pid dies
            m.sweep()                                      // pid-liveness reclaim -> ticket offered
            assertEquals("dev1", (m.poll(t.id, "W") as Poll.Offered).lease.serial)
        } finally {
            victim.destroyForcibly()
        }
    }

    @Test fun directClaimCannotOvertakeQueuedTicket() = runTest {
        val m = mgr()
        m.claim("dev1", "A", ttl = 10); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 600)
        now += 11
        assertTrue(m.poll(t.id, "W") is Poll.Queued)   // refresh before dev1 frees
        m.sweep()                      // dev1's lease expired -> sweep offers it to the ticket
        val e = runCatching { m.claim("dev1", "Sneaky") }.exceptionOrNull()!!
        assertTrue(e is LeaseBusyException)       // ticket holds the offer, not the late arrival
        assertEquals("W", (m.poll(t.id, "W") as Poll.Offered).lease.owner)
    }

    @Test fun heldTicketOfferBlocksDirectClaim() = runTest {
        val m = mgr(offerHold = 10)
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 600)
        m.free("dev1", "A")                       // dev1 is a pending offer to W
        now += 5                                  // still inside the hold
        assertTrue(runCatching { m.claim("dev1", "Sneaky") }.exceptionOrNull() is LeaseBusyException)
        assertEquals("W", (m.poll(t.id, "W") as Poll.Offered).lease.owner)
    }

    @Test fun unpolledTicketKeepsDevicePending() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 100)
        m.free("dev1", "A")
        assertTrue(m.snapshot["dev1"]!!.pendingAccept)   // offered but not yet polled
        assertFalse((m.poll(t.id, "W") as Poll.Offered).lease.pendingAccept)  // the poll accepted it
        assertFalse(m.snapshot["dev1"]!!.pendingAccept)
    }

    @Test fun busyReportsOfferHoldNotFullTtl() = runTest {
        val hold = 30L
        val m = mgr(offerHold = hold)
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 600)
        m.free("dev1", "A")                       // dev1 is a pending 30 s offer to W
        now += 5                                  // 5 s into the hold
        val busy = runCatching { m.requestClaim(Selector(serial = "dev1"), "X") }
            .exceptionOrNull() as LeaseBusyException
        assertEquals(hold - 5, busy.busy.single().ttlLeft)   // not the 1800 s TTL
        assertEquals("W", (m.poll(t.id, "W") as Poll.Offered).lease.owner)
    }

    @Test fun enqueueCapsWaitSeconds() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 99999)
        assertEquals(MAX_WAIT_S, m.queueSnapshot().single().deadlineIn)
    }

    @Test fun queueSnapshotHidesDeadTickets() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        m.enqueue(Selector(serial = "dev1"), "ghost", 2_000_000_000L, 1800, waitSeconds = 600)
        m.enqueue(Selector(serial = "dev1"), "live", 0, 1800, waitSeconds = 600)
        assertEquals(listOf(1 to "live"), m.queueSnapshot().map { it.position to it.owner })
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

    @Test fun ticketEnqueuePollAndSweep() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 100)
        assertEquals(TicketState.QUEUED, (m.poll(t.id, "W") as Poll.Queued).state)
        m.free("dev1", "A"); runCurrent()
        val p = m.poll(t.id, "W")
        assertTrue(p is Poll.Offered); assertEquals("dev1", (p as Poll.Offered).lease.serial)
        assertFalse(p.lease.pendingAccept)        // the poll accepted it
    }

    @Test fun offeredTicketNotPolledIsDroppedAfterHold() = runTest {
        val m = mgr(offerHold = 5)
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 100)
        m.free("dev1", "A")                        // offered to ticket W
        now += 6; m.sweep(); runCurrent()          // offer hold lapsed -> ticket dropped, device freed
        assertEquals(TicketEnd.SILENCE, (m.poll(t.id, "W") as Poll.Expired).reason)
        assertNull(m.snapshot["dev1"])             // dev1 no longer leased
    }

    @Test fun ticketExpiresAndPidsAreSkipped() = runTest {
        val m = mgr()
        m.claim("dev1", "A")
        val dead = m.enqueue(Selector(serial = "dev1"), "ghost", 2_000_000_000L, 1800, waitSeconds = 100)
        val live = m.enqueue(Selector(serial = "dev1"), "W2", 0, 1800, waitSeconds = 100)
        m.free("dev1", "A"); runCurrent()
        assertEquals("W2", (m.poll(live.id, "W2") as Poll.Offered).lease.owner)  // dead ticket skipped
        assertTrue(m.poll(dead.id, "ghost") is Poll.Unknown)
    }

    @Test fun ticketDeadlineExpiryIsPollExpired() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 5)
        now += 6; m.sweep(); runCurrent()
        assertEquals(TicketEnd.DEADLINE, (m.poll(t.id, "W") as Poll.Expired).reason)   // deadline passed, ticket dropped
    }

    @Test fun silentTicketIsPruned() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 600)
        now += POLL_SILENCE_S + 1; m.sweep(); runCurrent()
        assertEquals(TicketEnd.SILENCE, (m.poll(t.id, "W") as Poll.Expired).reason)
    }

    @Test fun pollingKeepsTicketAlive() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 600)
        now += POLL_SILENCE_S - 5
        assertTrue(m.poll(t.id, "W") is Poll.Queued)     // refreshes lastPolledAt
        now += POLL_SILENCE_S - 5; m.sweep(); runCurrent()
        assertTrue(m.poll(t.id, "W") is Poll.Queued)     // still alive
    }

    @Test fun wrongOwnerPollDoesNotExtendTicketLife() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 600)
        now += POLL_SILENCE_S - 5
        assertTrue(m.poll(t.id, "other") is Poll.Unknown)   // wrong owner: must not refresh
        now += 10; m.sweep(); runCurrent()
        assertEquals(TicketEnd.SILENCE, (m.poll(t.id, "W") as Poll.Expired).reason)   // silence measured from enqueue -> pruned
    }

    @Test fun silentTicketIsNotDispatched() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        m.enqueue(Selector(serial = "dev1"), "ghost", 0, 1800, waitSeconds = 600)
        now += POLL_SILENCE_S + 1
        val live = m.enqueue(Selector(serial = "dev1"), "W2", 0, 1800, waitSeconds = 600)
        m.free("dev1", "A"); runCurrent()
        assertEquals("W2", (m.poll(live.id, "W2") as Poll.Offered).lease.owner)
    }

    @Test fun ticketHoldFreedExternallyIsReofferedNotClobbered() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t1 = m.enqueue(Selector(serial = "dev1"), "W1", 0, 1800, waitSeconds = 100)
        m.free("dev1", "A"); runCurrent()                  // dev1 offered to t1 (pending)
        m.free("dev1", "W1", force = true); runCurrent()   // external force-free of the held offer
        val t2 = m.enqueue(Selector(serial = "dev1"), "W2", 0, 1800, waitSeconds = 100)
        runCurrent()                                       // t2 may now hold dev1
        val got = runCatching { m.poll(t2.id, "W2") }.getOrNull()
        // t2 must own a live lease; the stale t1 pointer must not have deleted it
        if (got is Poll.Offered) assertEquals("W2", m.snapshot["dev1"]?.owner)
        assertEquals("W2", m.snapshot["dev1"]?.owner)
    }

    @Test fun ticketPollIsSingleAccept() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 100)
        m.free("dev1", "A"); runCurrent()
        assertTrue(m.poll(t.id, "W") is Poll.Offered)
        assertTrue(m.poll(t.id, "W") is Poll.Unknown)      // ticket consumed exactly once
    }

    @Test fun pollAcceptsWithHolderPid() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 60)
        m.free("dev1", "A"); runCurrent()
        assertEquals(1234L, (m.poll(t.id, "W", holderPid = 1234) as Poll.Offered).lease.pid)
    }

    @Test fun pollAcceptsWithoutHolderPidLeavesPidZero() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "W", 0, 1800, waitSeconds = 60)
        m.free("dev1", "A"); runCurrent()
        assertEquals(0L, (m.poll(t.id, "W") as Poll.Offered).lease.pid)
    }

    @Test fun cancelTicketReleasesAndReoffers() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t1 = m.enqueue(Selector(serial = "dev1"), "W1", 0, 1800, waitSeconds = 100)
        val t2 = m.enqueue(Selector(serial = "dev1"), "W2", 0, 1800, waitSeconds = 100)
        m.free("dev1", "A"); runCurrent()                  // offered to t1
        assertEquals(CancelResult.FORBIDDEN, m.cancelTicket(t1.id, "W2"))
        assertEquals(CancelResult.NOT_FOUND, m.cancelTicket("nope", "W1"))
        assertEquals(CancelResult.CANCELLED, m.cancelTicket(t1.id, "W1"))
        assertTrue(m.poll(t1.id, "W1") is Poll.Unknown)     // cancelled: gone, no end reason
        runCurrent()
        assertEquals("W2", (m.poll(t2.id, "W2") as Poll.Offered).lease.owner)
    }

    @Test fun cancelTicketsDropsOwnersQueuedTickets() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        m.enqueue(Selector(serial = "dev1"), "mcp:s:1", 0, 1800, waitSeconds = 60)
        m.enqueue(Selector(serial = "dev1"), "mcp:s:2", 0, 1800, waitSeconds = 60)
        assertEquals(1, m.cancelTickets("mcp:s:1"))
        assertEquals(0, m.cancelTickets("mcp:s:1"))       // idempotent
        assertEquals(0, m.cancelTickets("nobody"))
    }

    @Test fun cancelTicketsReleasesOfferedTicketAndReoffers() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        m.enqueue(Selector(serial = "dev1"), "mcp:s:1", 0, 1800, waitSeconds = 60)
        val next = m.enqueue(Selector(serial = "dev1"), "mcp:s:2", 0, 1800, waitSeconds = 60)
        m.free("dev1", "A"); runCurrent()                 // dev1 offered (pending) to s:1
        assertEquals(1, m.cancelTickets("mcp:s:1"))       // drops s:1 and its offer
        runCurrent()
        assertEquals("mcp:s:2", (m.poll(next.id, "mcp:s:2") as Poll.Offered).lease.owner)
    }

    @Test fun enqueueImmediateMatchIsTtlBoundNotPinnedToWaiterPid() = runTest {
        val m = mgr()
        val t = m.enqueue(Selector(serial = "dev1"), "W", ProcessHandle.current().pid(), 1800, waitSeconds = 60)
        val lease = (m.poll(t.id, "W") as Poll.Offered).lease
        assertEquals(0L, lease.pid)      // the waiter's pid must not pin the handed-over lease
    }

    @Test fun lapsedUnpolledTicketDropsAndNextTicketServed() = runTest {
        val m = mgr(offerHold = 5)
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val ghost = m.enqueue(Selector(serial = "dev1"), "ghost", 0, 1800, waitSeconds = 60)
        val next = m.enqueue(Selector(serial = "dev1"), "W2", 0, 1800, waitSeconds = 60)
        m.free("dev1", "A"); runCurrent()                 // offered to ghost
        now += 6; m.sweep(); runCurrent()                 // hold lapses -> ghost dropped
        assertEquals("W2", (m.poll(next.id, "W2") as Poll.Offered).lease.owner)  // one poll serves W2
        assertEquals(TicketEnd.SILENCE, (m.poll(ghost.id, "ghost") as Poll.Expired).reason)
    }

    @Test fun staleTicketIsSkippedForAFreshOne() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val quiet = m.enqueue(Selector(serial = "dev1"), "quiet", 0, 1800, waitSeconds = 600)
        now += STALE_POLLS * DEFAULT_RETRY_AFTER_MS / 1000 + 1
        val live = m.enqueue(Selector(serial = "dev1"), "live", 0, 1800, waitSeconds = 600)
        m.free("dev1", "A"); runCurrent()
        assertEquals("live", (m.poll(live.id, "live") as Poll.Offered).lease.owner)
        assertTrue(m.queueSnapshot().any { it.owner == "quiet" })   // skipped, not dropped
        m.free("dev1", "live")
        assertEquals("quiet", (m.poll(quiet.id, "quiet") as Poll.Offered).lease.owner)  // alive after refreshing
    }

    @Test fun pausedTicketLosesOfferToDirectClaim() = runTest {
        val m = mgr()
        m.claim("dev1", "A")
        m.enqueue(Selector(serial = "dev1"), "quiet", 0, 1800, waitSeconds = 600)
        now += STALE_POLLS * DEFAULT_RETRY_AFTER_MS / 1000 + 1   // past the 3 s stale window, under 30 s silence
        m.free("dev1", "A"); runCurrent()
        assertEquals("Sneaky", m.claim("dev1", "Sneaky")?.owner)   // paused ticket does not shield the device
        assertTrue(m.queueSnapshot().any { it.owner == "quiet" })  // keeps position, not dropped
    }

    @Test fun subMinimumCadenceClampsToOneSecond() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val t = m.enqueue(Selector(serial = "dev1"), "fast", 0, 1800, waitSeconds = 600, retryAfterMs = 200)
        assertEquals(1000L, t.pollCadenceMs)          // clamped up to MIN
        assertEquals(1000L, (m.poll(t.id, "fast") as Poll.Queued).retryAfterMs)
        now += 3
        m.free("dev1", "A"); runCurrent()
        assertTrue(m.snapshot.containsKey("dev1"))    // 3 x 1000 ms window kept it eligible
        assertEquals("fast", (m.poll(t.id, "fast") as Poll.Offered).lease.owner)
    }

    @Test fun clampRetryAfterMsRoundsToServerRange() {
        assertEquals(1000L, clampRetryAfterMs(0))
        assertEquals(1000L, clampRetryAfterMs(50))
        assertEquals(1000L, clampRetryAfterMs(200))
        assertEquals(1000L, clampRetryAfterMs(500))
        assertEquals(1000L, clampRetryAfterMs(1000))
        assertEquals(10000L, clampRetryAfterMs(999999))
        assertEquals(10000L, clampRetryAfterMs(10000))
    }

    @Test fun largerCadenceTicketStaysEligibleLonger() = runTest {
        val m = mgr()
        m.claim("dev1", "A"); m.claim("dev2", "B")
        val slow = m.enqueue(Selector(serial = "dev1"), "slow", 0, 1800, waitSeconds = 600,
            retryAfterMs = 5000)
        now += STALE_POLLS * 5000 / 1000 - 1      // still inside the slow ticket's window
        m.free("dev1", "A"); runCurrent()
        assertEquals("slow", (m.poll(slow.id, "slow") as Poll.Offered).lease.owner)
    }
}
