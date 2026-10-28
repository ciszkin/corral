package dev.corral

import java.sql.DriverManager
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DbTest {
    @Test fun freshDbIsCreatedEmpty() {
        val dir = createTempDirectory("corral")
        val f = dir.resolve("fresh.db")
        assertFalse(f.toFile().exists())
        val db = Db(f)
        assertTrue(f.toFile().exists())
        assertTrue(db.loadLeases().isEmpty())
        assertEquals(0, db.countHistory())
    }

    @Test fun roundTripAndDelete() {
        val dir = createTempDirectory("corral")
        val db = Db(dir.resolve("t.db"))
        db.upsertLease(Lease("dev1", "agentA", 0, 99))
        assertEquals("agentA", db.loadLeases().single().owner)
        db.upsertLease(Lease("dev1", "agentB", 0, 100)) // overwrite
        val overwritten = db.loadLeases().single()
        assertEquals("agentB", overwritten.owner)
        assertEquals(100, overwritten.expiresAt)
        db.deleteLease("dev1")
        assertTrue(db.loadLeases().isEmpty())
    }

    @Test fun historyAppends() = runTest {
        val dir = createTempDirectory("corral")
        val db = Db(dir.resolve("t.db"))
        var now = 1500L
        val devices = listOf(Device("dev1", "device"), Device("dev2", "device"))
        val m = LeaseManager({ devices }, db, { now })
        m.claim("dev1", "agentA")                       // claim
        m.claim("dev2", "agentB", ttl = 10)
        val t = m.enqueue(Selector(serial = "dev1"), "agentW", 0, 1800, waitSeconds = 600)  // enqueue
        m.free("dev1", "agentA")                        // free, then offer dev1 to the ticket
        m.cancelTicket(t.id, "agentW")                  // dequeue
        now += 11
        m.sweep()                                       // dev2 expired -> expire

        val events = db.history()
        assertEquals(
            listOf("claim", "claim", "enqueue", "free", "offer", "dequeue", "expire"), events)
        assertEquals(setOf("claim", "free", "enqueue", "offer", "dequeue", "expire"), events.toSet())
        assertEquals(events.size, db.countHistory())
    }

    @Test fun offeredAtRoundTrip() {
        val dir = createTempDirectory("corral")
        val db = Db(dir.resolve("t.db"))
        db.upsertLease(Lease("dev1", "agentA", 0, 99, pendingAccept = true, offeredAt = 77))
        assertEquals(77, db.loadLeases().single().offeredAt)
    }

    /** Row 42: the registered command pids survive a daemon restart with the lease they protect. */
    @Test fun registeredPidsRoundTrip() {
        val dir = createTempDirectory("corral")
        val db = Db(dir.resolve("t.db"))
        db.upsertLease(Lease("dev1", "agentA", 12, 99, pids = listOf(34, 56)))
        assertEquals(listOf(34L, 56L), db.loadLeases().single().pids)
    }

    @Test fun migratesLegacyDbWithoutOfferedAt() {
        val dir = createTempDirectory("corral")
        val f = dir.resolve("legacy.db")
        DriverManager.getConnection("jdbc:sqlite:$f").use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE lease(serial TEXT PRIMARY KEY, owner TEXT NOT NULL, " +
                    "pid INTEGER NOT NULL, expires_at INTEGER NOT NULL, pending INTEGER NOT NULL DEFAULT 0)")
            }
            c.prepareStatement("INSERT INTO lease(serial,owner,pid,expires_at,pending) VALUES('d','o',1,99,0)")
                .use { it.executeUpdate() }
        }
        val db = Db(f)   // must not throw; must migrate
        db.upsertLease(Lease("d", "o", 1, 100))
        assertEquals(0, db.loadLeases().single().offeredAt)   // default 0 survives the round trip
    }
}
