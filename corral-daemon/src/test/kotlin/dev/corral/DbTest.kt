package dev.corral

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
}
