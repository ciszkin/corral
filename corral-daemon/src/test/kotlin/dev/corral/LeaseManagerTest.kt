package dev.corral

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class LeaseManagerTest {
    private var now = 1000L

    private fun freshMgr() = LeaseManager(
        devices = { listOf(Device("dev1", "device"), Device("dev2", "device")) },
        db = null, clock = { now })

    @Test fun concurrentClaimsSingleWinner() = runBlocking(Dispatchers.Default) {
        val mgr = freshMgr()
        val start = CountDownLatch(1)
        val results = (1..50).map { i ->
            async {
                start.await()
                runCatching { mgr.claim("dev1", "agent$i") }.getOrNull()
            }
        }
        start.countDown()
        val winners = results.awaitAll().count { it != null }
        assertEquals(1, winners)
    }

    @Test fun expiredLeaseReclaimed() = runTest {
        val mgr = freshMgr()
        mgr.claim("dev1", "agentA", ttl = 10)
        now += 11
        assertEquals("dev1", mgr.claim("dev1", "agentB")?.serial)
    }

    @Test fun deadPidReclaimedInstantly() = runTest {
        val mgr = freshMgr()
        val pid = ProcessHandle.current().pid()
        mgr.claim("dev1", "agentA", pid = pid, ttl = 99999)
        assertTrue(runCatching { mgr.claim("dev1", "agentB") }.exceptionOrNull() is LeaseBusyException)
        val java = ProcessHandle.current().info().command().orElse("java")
        val p = ProcessBuilder(java, "-version").start(); p.waitFor()
        val deadPid = p.pid()
        mgr.free("dev1", "agentA", force = true)
        mgr.claim("dev1", "agentA2", pid = deadPid, ttl = 99999)
        assertEquals("dev1", mgr.claim("dev1", "agentB")?.serial)
    }

    @Test fun freeRefusesForeigner() = runTest {
        val mgr = freshMgr()
        mgr.claim("dev1", "agentA")
        val e = runCatching { mgr.free("dev1", "agentB") }.exceptionOrNull()!!
        assertTrue(e is IllegalStateException && "not yours" in e.message!!)
        mgr.free("dev1", "agentB", force = true)
    }

    @Test fun checkAllowsOnlyLiveOwner() = runTest {
        val mgr = freshMgr()
        val serial = mgr.claim("dev1", "agentA")!!.serial
        assertTrue(mgr.check(serial, "agentA").allowed)
        assertFalse(mgr.check(serial, "agentB").allowed)
        assertFalse(mgr.check("unknown", "agentA").allowed)
    }

    @Test fun claimBusyReportsOwnerAndTtl() = runTest {
        val mgr = freshMgr()
        mgr.claim("dev1", "agentA", ttl = 100)
        val e = runCatching { mgr.claim("dev1", "agentB") }.exceptionOrNull()!!
        assertTrue(e is LeaseBusyException && e.busy.single().owner == "agentA")
    }

    @Test fun unknownSerialRejected() = runTest {
        val mgr = freshMgr()
        assertNull(mgr.claim("nope", "agentA"))
    }

    @Test fun renewExtendsTtlForOwner() = runTest {
        val mgr = freshMgr()
        mgr.claim("dev1", "agentA", ttl = 10)
        now += 5
        val renewed = mgr.renew("dev1", "agentA", ttl = 100)
        assertEquals(now + 100, renewed.expiresAt)
        assertTrue(mgr.check("dev1", "agentA").allowed)
    }

    @Test fun renewRefusesForeigner() = runTest {
        val mgr = freshMgr()
        mgr.claim("dev1", "agentA")
        val e = runCatching { mgr.renew("dev1", "agentB") }.exceptionOrNull()!!
        assertTrue(e is IllegalStateException)
    }

    @Test fun sweepRemovesExpiredKeepsLive() = runTest {
        val mgr = freshMgr()
        mgr.claim("dev1", "agentA", ttl = 10)
        mgr.claim("dev2", "agentB", ttl = 100)
        now += 11
        mgr.sweep()
        assertNull(mgr.snapshot["dev1"])
        assertEquals("agentB", mgr.snapshot["dev2"]?.owner)
    }

    @Test fun recoverPersistedKeepsOnlyLive() = runTest {
        val db = Db(createTempDirectory("corral").resolve("leases.db"))
        db.upsertLease(Lease("dev1", "agentA", expiresAt = now + 100))
        db.upsertLease(Lease("dev2", "agentB", expiresAt = now - 1))
        val mgr = LeaseManager(
            devices = { listOf(Device("dev1", "device"), Device("dev2", "device")) },
            db = db, clock = { now })
        mgr.recoverPersisted()
        assertEquals(setOf("dev1"), mgr.snapshot.keys)
    }

    @Test fun claimAnyPicksFirstFreeThenReportsBusy() = runTest {
        val mgr = freshMgr()
        assertEquals("dev1", mgr.claimAny("agentA")?.serial)
        assertEquals("dev2", mgr.claimAny("agentB")?.serial)
        val e = runCatching { mgr.claimAny("agentC") }.exceptionOrNull()!!
        assertTrue(e is LeaseBusyException)
    }
}
