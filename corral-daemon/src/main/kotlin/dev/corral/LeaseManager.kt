package dev.corral

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

class LeaseBusyException(val busy: List<BusyInfo>) :
    RuntimeException("busy: " + busy.joinToString { "${it.serial}/${it.owner}/${it.ttlLeft}s" })

@Serializable data class BusyInfo(val serial: String, val owner: String, val ttlLeft: Long)
@Serializable data class CheckResult(val allowed: Boolean, val reason: String)

/**
 * Mutex-guarded map. State is the map itself; SQLite mirrors it for restart recovery.
 * ponytail: single mutex — fine at bench scale; per-serial locks if contention ever logs.
 */
class LeaseManager(private val devices: () -> List<Device>,
                   private val db: Db?,
                   private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val mutex = Mutex()
    private val leases = ConcurrentHashMap<String, Lease>()

    val snapshot: Map<String, Lease> get() = leases.toMap()

    suspend fun claim(serial: String, owner: String, pid: Long = 0, ttl: Long = 1800): Lease? {
        if (devices().none { it.serial == serial && it.state == "device" }) return null
        return mutex.withLock {
            reclaimIfNeeded(serial)
            if (leases.containsKey(serial)) throw leaseBusy(serial)
            val l = Lease(serial, owner, pid, clock() + ttl).also {
                leases[serial] = it; db?.upsertLease(it); db?.log("claim", serial, owner, "pid=$pid ttl=$ttl")
            }
            l
        }
    }

    /** First free device (MVP parity). Selector queue arrives in Phase 2. */
    suspend fun claimAny(owner: String, pid: Long = 0, ttl: Long = 1800): Lease? {
        val serial = devices().firstOrNull { it.state == "device" && it.serial !in snapshot }?.serial
            ?: throw leaseBusy(devices().filter { it.state == "device" }.map { it.serial })
        return claim(serial, owner, pid, ttl)
    }

    suspend fun free(serial: String, owner: String, force: Boolean = false) = mutex.withLock {
        val l = leases[serial] ?: return
        if (l.owner != owner && !force)
            throw IllegalStateException("lease on $serial is not yours (owner=${l.owner}); force=true overrides")
        leases.remove(serial); db?.deleteLease(serial); db?.log("free", serial, owner, "force=$force")
        Unit
    }

    suspend fun renew(serial: String, owner: String, ttl: Long = 1800): Lease = mutex.withLock {
        val l = leases[serial] ?: throw IllegalStateException("no lease on $serial")
        if (l.owner != owner) throw IllegalStateException("lease on $serial is not yours")
        l.copy(expiresAt = clock() + ttl, pendingAccept = false).also { leases[serial] = it; db?.upsertLease(it) }
    }

    suspend fun check(serial: String, owner: String): CheckResult = mutex.withLock {
        reclaimIfNeeded(serial)
        val l = leases[serial] ?: return@withLock CheckResult(false, "no live lease on $serial")
        if (l.owner != owner) CheckResult(false, "$serial is leased by ${l.owner}, ${l.expiresAt - clock()}s left")
        else CheckResult(true, "ok")
    }

    suspend fun sweep() = mutex.withLock {
        leases.values.filter { it.expiresAt <= clock() || isDead(it) }.forEach {
            leases.remove(it.serial); db?.deleteLease(it.serial); db?.log("expire", it.serial, it.owner, null)
        }
    }

    fun recoverPersisted() {
        db?.loadLeases()?.forEach { l ->
            if (l.expiresAt > clock() && !isDead(l)) leases[l.serial] = l
            else db.deleteLease(l.serial)
        }
    }

    private fun reclaimIfNeeded(serial: String) {
        leases[serial]?.let { if (it.expiresAt <= clock() || isDead(it)) { leases.remove(serial); db?.deleteLease(serial); db?.log("expire", serial, it.owner, null) } }
    }
    private fun isDead(l: Lease) = l.pid != 0L && ProcessHandle.of(l.pid).isEmpty
    private fun leaseBusy(serials: List<String>): LeaseBusyException {
        val infos = serials.mapNotNull { s -> snapshot[s]?.let { BusyInfo(s, it.owner, maxOf(0, it.expiresAt - clock())) } }
        return LeaseBusyException(infos)
    }
    private fun leaseBusy(serial: String) = leaseBusy(listOf(serial))
}

fun Device.usable() = state == "device"
