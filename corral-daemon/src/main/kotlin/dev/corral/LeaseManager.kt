package dev.corral

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

class LeaseBusyException(val busy: List<BusyInfo>, val matching: Int = 0, val timedOut: Boolean = false,
                         val booting: Int = 0) :
    RuntimeException("busy: " + busy.joinToString { "${it.serial}/${it.owner}/${it.ttlLeft}s" } +
        if (booting > 0)
            " — $booting emulator(s) booting — corral wait --type emulator queues until boot" else "")

@Serializable data class BusyInfo(val serial: String, val owner: String, val ttlLeft: Long)
@Serializable data class CheckResult(val allowed: Boolean, val reason: String)

/** Upper bound on a ticket wait, enforced for every caller. */
const val MAX_WAIT_S = 600L

/** Reservation window: an offered ticket that is not polled within this loses the device. */
const val OFFER_HOLD_S = 10L

/** A live client polls on a cadence; a ticket silent this long is treated as abandoned. */
const val POLL_SILENCE_S = 30L

/** Missed poll cadences before a quiet-but-live ticket is skipped by dispatch. */
const val STALE_POLLS = 3L

/** Poll cadence advertised to a ticket that does not name its own. */
const val DEFAULT_RETRY_AFTER_MS = 1000L

/** Smallest poll cadence a client may propose. */
const val MIN_RETRY_AFTER_MS = 1000L

/** Largest cadence a client may propose: the stale window must not exceed the silence drop. */
const val MAX_RETRY_AFTER_MS = POLL_SILENCE_S * 1000 / STALE_POLLS

fun clampRetryAfterMs(ms: Long): Long = ms.coerceIn(MIN_RETRY_AFTER_MS, MAX_RETRY_AFTER_MS)

/** How long a removed ticket's end reason is remembered so a late poll can report it. */
private const val ENDED_TTL_S = 60L

enum class TicketState { QUEUED, OFFERED, EXPIRED }

enum class TicketEnd { DEADLINE, SILENCE }

enum class CancelResult { CANCELLED, NOT_FOUND, FORBIDDEN }

sealed interface Poll {
    data class Offered(val lease: Lease) : Poll
    data class Queued(val position: Int, val retryAfterMs: Long,
                      val state: TicketState = TicketState.QUEUED) : Poll
    data class Expired(val reason: TicketEnd) : Poll
    data object Unknown : Poll
}

/** A client-polled claim. The client holds [id] and polls until offered or past [deadline]. */
class Ticket internal constructor(
    val id: String, val selector: Selector, val owner: String, val pid: Long,
    val ttl: Long, val enqueuedAt: Long, val deadline: Long, internal var lastPolledAt: Long,
    val pollCadenceMs: Long,
) { internal var offered: Lease? = null; internal var offeredAt: Long = 0 }

/**
 * Mutex-guarded lease map + FIFO ticket queue. SQLite mirrors leases for restart recovery;
 * the queue is in-memory only (a parked client cannot outlive the daemon).
 * ponytail: single mutex — fine at bench scale; per-serial locks if contention ever logs.
 */
class LeaseManager(private val devices: () -> List<Device>,
                   private val db: Db?,
                   private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
                   private val offerHold: Long = OFFER_HOLD_S) {
    private val mutex = Mutex()
    private val leases = ConcurrentHashMap<String, Lease>()
    private val tickets = LinkedHashMap<String, Ticket>()
    private val ended = LinkedHashMap<String, Pair<TicketEnd, Long>>()

    val snapshot: Map<String, Lease> get() = leases.toMap()

    suspend fun queueSnapshot(): List<QueueEntry> = mutex.withLock {
        tickets.values.filter {
            !isDeadPid(it.pid) && it.deadline > clock() && clock() - it.lastPolledAt <= POLL_SILENCE_S
        }.mapIndexed { i, t ->
                QueueEntry(i + 1, t.owner, t.selector.toString(), clock() - t.enqueuedAt, t.deadline - clock())
            }
    }

    /** Immediate claim: hand over a free match now, else fail fast. Waiting is ticket-based ([enqueue]/[poll]). */
    suspend fun requestClaim(selector: Selector, owner: String, pid: Long = 0,
                             ttl: Long = 1800): Lease = mutex.withLock {
        sweepLocked()
        takeFree(selector)?.let { return createLeaseLocked(it.serial, owner, pid, ttl, "claim") }
        throw busyFor(selector)
    }

    /** Register a ticket and return at once; the client polls [poll] for its outcome. */
    suspend fun enqueue(selector: Selector, owner: String, pid: Long, ttl: Long,
                        waitSeconds: Long, retryAfterMs: Long = DEFAULT_RETRY_AFTER_MS): Ticket = mutex.withLock {
        sweepLocked()
        val now = clock()
        val t = Ticket(UUID.randomUUID().toString(), selector, owner, pid, ttl,
            now, now + waitSeconds.coerceIn(0, MAX_WAIT_S), now, clampRetryAfterMs(retryAfterMs))
        takeFree(selector)?.let { d ->                       // immediate match: hand straight over
            t.offered = createLeaseLocked(d.serial, owner, 0, ttl, "claim")
        }
        tickets[t.id] = t; db?.log("enqueue", null, owner, selector.toString()); t
    }

    /** Poll a ticket: the offer is accepted here (the polling client is present now). */
    suspend fun poll(ticketId: String, owner: String, holderPid: Long = 0): Poll = mutex.withLock {
        sweepTicketsLocked()
        val t = tickets[ticketId]
        if (t == null) return ended[ticketId]?.let { Poll.Expired(it.first) } ?: Poll.Unknown
        if (t.owner != owner) return Poll.Unknown
        t.lastPolledAt = clock()
        dispatchTicketsLocked()
        val lease = t.offered
        if (lease != null && t.hasLiveOffer()) {
            t.offered = null
            val accepted = lease.copy(pendingAccept = false, pid = holderPid)
                .also { leases[it.serial] = it; db?.upsertLease(it) }
            tickets.remove(ticketId)
            Poll.Offered(accepted)
        } else {
            t.offered = null
            Poll.Queued(tickets.values.indexOf(t) + 1, t.pollCadenceMs)
        }
    }

    suspend fun cancelTicket(ticketId: String, owner: String): CancelResult = mutex.withLock {
        val t = tickets[ticketId] ?: return CancelResult.NOT_FOUND
        if (t.owner != owner) return CancelResult.FORBIDDEN
        releaseOfferLocked(t); tickets.remove(ticketId)
        db?.log("dequeue", null, owner, "ticket-cancel")
        dispatchTicketsLocked(); CancelResult.CANCELLED
    }

    /** Drop every queued ticket for a principal (its MCP session/process is gone), releasing offers. */
    suspend fun cancelTickets(owner: String): Int = mutex.withLock {
        val mine = tickets.values.filter { it.owner == owner }
        for (t in mine) { releaseOfferLocked(t); tickets.remove(t.id); db?.log("dequeue", null, owner, "owner-gone") }
        if (mine.isNotEmpty()) dispatchTicketsLocked()
        mine.size
    }

    suspend fun claim(serial: String, owner: String, pid: Long = 0, ttl: Long = 1800): Lease? {
        if (devices().none { it.serial == serial && it.usable() }) return null
        return mutex.withLock {
            sweepLocked()   // expiry/dead-pid reclaim + ticket dispatch happen first
            if (leases.containsKey(serial)) throw busyFor(Selector(serial = serial))
            createLeaseLocked(serial, owner, pid, ttl, "claim")
        }
    }

    /** First free device (MVP parity). */
    suspend fun claimAny(owner: String, pid: Long = 0, ttl: Long = 1800): Lease =
        requestClaim(Selector(), owner, pid, ttl)

    suspend fun free(serial: String, owner: String, force: Boolean = false) = mutex.withLock {
        val l = leases[serial] ?: return
        if (l.owner != owner && !force)
            throw IllegalStateException("lease on $serial is not yours (owner=${l.owner}); it can be force-freed from the console")
        leases.remove(serial); db?.deleteLease(serial); db?.log("free", serial, owner, "force=$force")
        dispatchTicketsLocked()
        Unit
    }

    /**
     * Extend a lease's TTL, and (row 42, review 46 §2) register the pids of the command it is running.
     * A SIGKILLed CLI runs no hook, so the command outlives it; [pids] lets the prune see that. The
     * caller reports immediately after `start()`, and an empty list leaves the registered pids alone.
     */
    suspend fun renew(serial: String, owner: String, ttl: Long = 1800,
                      pids: List<Long> = emptyList()): Lease = mutex.withLock {
        reclaimIfNeeded(serial)
        val l = leases[serial] ?: throw IllegalStateException("no lease on $serial")
        if (l.owner != owner) throw IllegalStateException("lease on $serial is not yours")
        l.copy(expiresAt = clock() + ttl, pendingAccept = false,
               pids = if (pids.isEmpty()) l.pids else pids)
            .also { leases[serial] = it; db?.upsertLease(it) }
    }

    suspend fun check(serial: String, owner: String): CheckResult = mutex.withLock {
        reclaimIfNeeded(serial)
        val l = leases[serial] ?: return@withLock CheckResult(false, "no live lease on $serial")
        if (l.owner != owner) CheckResult(false, "$serial is leased by ${l.owner}, ${l.expiresAt - clock()}s left")
        else {
            if (l.pendingAccept) {   // the owner's first touch (typically the adb wrapper) accepts the offer
                val accepted = l.copy(pendingAccept = false)
                leases[serial] = accepted; db?.upsertLease(accepted)
            }
            CheckResult(true, "ok")
        }
    }

    suspend fun sweep() = mutex.withLock { sweepLocked() }

    /** Inventory changed (hot-plug, emulator start): sweep and hand free devices to tickets. */
    suspend fun dispatchFreeDevices() = mutex.withLock { sweepLocked() }

    fun recoverPersisted() {
        db?.loadLeases()?.forEach { l ->
            // un-accepted offers died with the daemon; live accepted leases survive
            if (l.expiresAt > clock() && !isDead(l) && !l.pendingAccept) leases[l.serial] = l
            else db.deleteLease(l.serial)
        }
    }

    private fun sweepLocked() {
        leases.values.filter { it.expiresAt <= clock() || isDead(it) }.forEach {
            leases.remove(it.serial); db?.deleteLease(it.serial); db?.log("expire", it.serial, it.owner, null)
        }
        // an emulator serial is a reused console port: a lease must not attach to a later AVD that
        // reuses it. Physical/wifi serials are stable, so their leases survive a replug. Only
        // reconcile with a non-empty pool: at startup devices() is empty until the first
        // track-devices frame, and sweeping then would wrongly free every recovered emulator lease.
        if (devices().isNotEmpty()) {
            leases.values.filter {
                it.serial.startsWith("emulator-") && devices().none { d -> d.serial == it.serial }
            }.forEach {
                leases.remove(it.serial); db?.deleteLease(it.serial)
                db?.log("expire", it.serial, it.owner, "emulator-gone")
            }
        }
        dispatchTicketsLocked()
        sweepTicketsLocked()
    }

    /** First live ticket that matches a free device gets a short, reclaimable reservation. */
    private fun dispatchTicketsLocked() {
        for (d in devices()) {
            if (leases.containsKey(d.serial)) continue
            val t = tickets.values.firstOrNull {
                it.offered == null && it.deadline > clock() && !isDeadPid(it.pid) &&
                    it.selector.matches(d) && clock() - it.lastPolledAt <= POLL_SILENCE_S &&
                    clock() - it.lastPolledAt <= it.staleAfterS()
            } ?: continue
            t.offered = createLeaseLocked(d.serial, t.owner, 0, t.ttl, "offer", pending = true); t.offeredAt = clock()
        }
    }

    private fun sweepTicketsLocked() {
        ended.entries.removeAll { clock() - it.value.second > ENDED_TTL_S }
        tickets.values.removeAll { t ->
            val expired = t.deadline <= clock()
            val dead = !expired && isDeadPid(t.pid)
            val silent = clock() - t.lastPolledAt > POLL_SILENCE_S
            val gone = expired || dead
            if (gone || silent) {
                releaseOfferLocked(t)
                db?.log("dequeue", null, t.owner, if (gone) "ticket-gone" else "poll-silence")
                when {
                    expired -> ended[t.id] = TicketEnd.DEADLINE to clock()
                    dead -> Unit                              // process gone: no reason to report
                    silent -> ended[t.id] = TicketEnd.SILENCE to clock()
                }
            }
            gone || silent
        }
        val lapsed = tickets.values.filter { it.offered?.pendingAccept == true && clock() - it.offeredAt > offerHold }
        lapsed.forEach { t ->
            releaseOfferLocked(t); tickets.remove(t.id)      // unpolled offer: drop the ticket, move on
            ended[t.id] = TicketEnd.SILENCE to clock()
            db?.log("dequeue", null, t.owner, "offer-lapsed")
        }
    }

    private fun Ticket.hasLiveOffer() = offered?.let { leases[it.serial] === it } == true

    private fun Ticket.staleAfterS() = maxOf(1L, STALE_POLLS * pollCadenceMs / 1000)

    private fun releaseOfferLocked(t: Ticket) {
        t.offered?.let { if (leases[it.serial] === it) { leases.remove(it.serial); db?.deleteLease(it.serial) } }
        t.offered = null
    }

    private fun takeFree(selector: Selector): Device? =
        devices().firstOrNull { selector.matches(it) && !leases.containsKey(it.serial) }

    private fun createLeaseLocked(serial: String, owner: String, pid: Long, ttl: Long,
                                  event: String, pending: Boolean = false): Lease {
        val l = Lease(serial, owner, pid, clock() + ttl, pendingAccept = pending,
                      offeredAt = if (pending) clock() else 0)
        leases[serial] = l; db?.upsertLease(l); db?.log(event, serial, owner, "pid=$pid ttl=$ttl")
        return l
    }

    private fun reclaimIfNeeded(serial: String) {
        leases[serial]?.let {
            if (it.expiresAt <= clock() || isDead(it)) {
                leases.remove(serial); db?.deleteLease(serial); db?.log("expire", serial, it.owner, null)
                dispatchTicketsLocked()
            } }
    }

    /**
     * A lease whose holder is gone. The holder's own pid is not the whole truth: the command it
     * started (registered in [Lease.pids]) outlives a SIGKILLed CLI, so the lease is dead only once
     * every registered pid is gone too. The TTL still bounds it — this frees earlier, never later.
     */
    private fun isDead(l: Lease) =
        isDeadPid(l.pid) && l.pids.none { it != 0L && ProcessHandle.of(it).isPresent }
    private fun isDeadPid(pid: Long) = pid != 0L && ProcessHandle.of(pid).isEmpty

    private fun busyFor(selector: Selector, timedOut: Boolean = false): LeaseBusyException {
        val matching = devices().filter { it.state == "device" && selector.matchesFields(it) }
        val booting = matching.count { it.transport == "emulator" && !it.booted }
        val busy = matching.mapNotNull { d ->
            leases[d.serial]?.let { l ->
                val ttlLeft = maxOf(0, l.expiresAt - clock())
                // a pending offer is reclaimed at the offer hold, not the TTL — report the truth
                val offerLeft = if (l.pendingAccept) maxOf(0, offerHold - (clock() - l.offeredAt)) else ttlLeft
                BusyInfo(d.serial, l.owner, minOf(ttlLeft, offerLeft))
            } }
        return LeaseBusyException(busy, matching.size, timedOut, booting)
    }
}

fun Device.usable() = state == "device" && booted