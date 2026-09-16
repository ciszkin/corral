package dev.corral

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

class LeaseBusyException(val busy: List<BusyInfo>, val matching: Int = 0, val timedOut: Boolean = false) :
    RuntimeException("busy: " + busy.joinToString { "${it.serial}/${it.owner}/${it.ttlLeft}s" })

@Serializable data class BusyInfo(val serial: String, val owner: String, val ttlLeft: Long)
@Serializable data class CheckResult(val allowed: Boolean, val reason: String)

/** Offer window: a queued claimant must touch its lease (check/renew) within this, or lose it. */
const val ACCEPT_WINDOW_S = 30L

/** A parked claim. The offerer sets [offered] then completes [deferred]; the parker reads [offered]. */
class Waiter(val selector: Selector, val owner: String, val pid: Long, val ttl: Long,
             val enqueuedAt: Long, val deadline: Long) {
    val deferred = CompletableDeferred<Unit>()
    var offered: Lease? = null
}

@Serializable data class QueueEntry(val position: Int, val owner: String, val selector: String,
    val waitedSeconds: Long, val deadlineIn: Long)

/**
 * Mutex-guarded lease map + FIFO waiter queue. SQLite mirrors leases for restart recovery;
 * the queue is in-memory only (a parked HTTP connection cannot outlive the daemon).
 * ponytail: single mutex — fine at bench scale; per-serial locks if contention ever logs.
 */
class LeaseManager(private val devices: () -> List<Device>,
                   private val db: Db?,
                   private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val mutex = Mutex()
    private val leases = ConcurrentHashMap<String, Lease>()
    private val queue = ArrayDeque<Waiter>()

    val snapshot: Map<String, Lease> get() = leases.toMap()

    suspend fun queueSnapshot(): List<QueueEntry> = mutex.withLock {
        queue.mapIndexed { i, w -> QueueEntry(i + 1, w.owner, w.selector.toString(),
            clock() - w.enqueuedAt, w.deadline - clock()) }
    }

    /** Long-poll claim: free match now, else park FIFO until offered or waitSeconds elapse. */
    suspend fun requestClaim(selector: Selector, owner: String, pid: Long = 0,
                             ttl: Long = 1800, waitSeconds: Long = 0): Lease {
        val waiter = mutex.withLock {
            sweepLocked()
            takeFree(selector)?.let { return createLeaseLocked(it.serial, owner, pid, ttl, "claim") }
            if (waitSeconds <= 0) throw busyFor(selector)
            Waiter(selector, owner, pid, ttl, clock(), clock() + waitSeconds)
                .also { queue.addLast(it); db?.log("enqueue", null, owner, selector.toString()) }
        }
        var consumed = false
        try {
            withTimeoutOrNull(waitSeconds * 1000) { waiter.deferred.await() }
                ?: throw busyFor(selector, timedOut = true)
            consumed = true
            return waiter.offered!!
        } finally {
            if (!consumed) withContext(NonCancellable) { mutex.withLock {
                if (queue.remove(waiter)) db?.log("dequeue", null, owner, "timeout/cancel")
                waiter.offered?.let { orphan ->   // offer raced our exit: give the device back
                    leases.remove(orphan.serial); db?.deleteLease(orphan.serial)
                    db?.log("dequeue", orphan.serial, owner, "orphan-offer")
                    dispatchOffersLocked()
                }
            } }
        }
    }

    suspend fun claim(serial: String, owner: String, pid: Long = 0, ttl: Long = 1800): Lease? {
        if (devices().none { it.serial == serial && it.state == "device" }) return null
        return mutex.withLock {
            sweepLocked()   // expiry/dead-pid/stale-offer reclaim + queue dispatch happen first
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
            throw IllegalStateException("lease on $serial is not yours (owner=${l.owner}); force=true overrides")
        leases.remove(serial); db?.deleteLease(serial); db?.log("free", serial, owner, "force=$force")
        dispatchOffersLocked()
        Unit
    }

    suspend fun renew(serial: String, owner: String, ttl: Long = 1800): Lease = mutex.withLock {
        reclaimIfNeeded(serial)
        val l = leases[serial] ?: throw IllegalStateException("no lease on $serial")
        if (l.owner != owner) throw IllegalStateException("lease on $serial is not yours")
        l.copy(expiresAt = clock() + ttl, pendingAccept = false)
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

    /** Inventory changed (hot-plug, emulator start): sweep and hand free devices to waiters. */
    suspend fun dispatchFreeDevices() = mutex.withLock { sweepLocked() }

    fun recoverPersisted() {
        db?.loadLeases()?.forEach { l ->
            // un-accepted offers died with the daemon; live accepted leases survive
            if (l.expiresAt > clock() && !isDead(l) && !l.pendingAccept) leases[l.serial] = l
            else db.deleteLease(l.serial)
        }
    }

    private fun sweepLocked() {
        leases.values.filter { it.expiresAt <= clock() || isDead(it) || staleOffer(it) }.forEach {
            leases.remove(it.serial); db?.deleteLease(it.serial); db?.log("expire", it.serial, it.owner, null)
        }
        queue.toList().filter { it.deadline <= clock() }.forEach {
            queue.remove(it); db?.log("dequeue", null, it.owner, "deadline")
        }
        dispatchOffersLocked()
    }

    /** Waiters in arrival order; the first matching waiter gets the device (30 s acceptance window). */
    private fun dispatchOffersLocked() {
        for (d in devices()) {
            if (leases.containsKey(d.serial)) continue
            while (true) {
                val w = queue.firstOrNull { it.selector.matches(d) } ?: break
                queue.remove(w)
                if (w.deadline <= clock() || isDeadPid(w.pid)) continue   // stale/dead waiter; try the next one
                // w.pid tracks the QUEUED CLIENT (dead-waiter skip above), not the holder: a
                // long-polling CLI exits on accept, so pinning the lease to it would reclaim the
                // device the moment it is handed over. Offers start TTL-bound; the holder renews.
                val lease = createLeaseLocked(d.serial, w.owner, 0, w.ttl, "offer", pending = true)
                w.offered = lease
                if (w.deferred.complete(Unit)) break
                // waiter vanished mid-offer: roll back and offer to the next waiter
                leases.remove(d.serial); db?.deleteLease(d.serial)
            }
        }
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
            if (it.expiresAt <= clock() || isDead(it) || staleOffer(it)) {
                leases.remove(serial); db?.deleteLease(serial); db?.log("expire", serial, it.owner, null)
                dispatchOffersLocked()
            } }
    }

    private fun staleOffer(l: Lease) = l.pendingAccept && clock() - l.offeredAt > ACCEPT_WINDOW_S
    private fun isDead(l: Lease) = isDeadPid(l.pid)
    private fun isDeadPid(pid: Long) = pid != 0L && ProcessHandle.of(pid).isEmpty

    private fun busyFor(selector: Selector, timedOut: Boolean = false): LeaseBusyException {
        val matching = devices().filter { selector.matches(it) }
        val busy = matching.mapNotNull { d ->
            leases[d.serial]?.let { l ->
                val ttlLeft = maxOf(0, l.expiresAt - clock())
                // a pending offer is reclaimed at the accept window, not the TTL — report the truth
                val offerLeft = if (l.pendingAccept) maxOf(0, ACCEPT_WINDOW_S - (clock() - l.offeredAt)) else ttlLeft
                BusyInfo(d.serial, l.owner, minOf(ttlLeft, offerLeft))
            } }
        return LeaseBusyException(busy, matching.size, timedOut)
    }
}

fun Device.usable() = state == "device"