package dev.corral

import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DbTest {
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

    @Test fun historyAppends() {
        val dir = createTempDirectory("corral")
        val db = Db(dir.resolve("t.db"))
        db.log("claim", "dev1", "agentA", "ttl=1800")
        db.log("free", "dev1", "agentA", null)
        assertEquals(2, db.countHistory())
    }

    @Test fun offeredAtRoundTrip() {
        val dir = createTempDirectory("corral")
        val db = Db(dir.resolve("t.db"))
        db.upsertLease(Lease("dev1", "agentA", 0, 99, pendingAccept = true, offeredAt = 77))
        assertEquals(77, db.loadLeases().single().offeredAt)
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
