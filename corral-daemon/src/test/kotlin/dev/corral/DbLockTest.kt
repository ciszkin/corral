package dev.corral

import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DbLockTest {
    @Test fun secondAcquireOnSameDbIsRefused() {
        val db = createTempDirectory("corral").resolve("corral.db")
        val first = assertNotNull(DbLock.acquire(db), "first daemon should take the lock")
        assertNull(DbLock.acquire(db), "second daemon on the same DB must be refused")
        first.release()
        assertNotNull(DbLock.acquire(db), "lock is reusable once released")
    }

    @Test fun differentDbsDoNotContend() {
        val dir = createTempDirectory("corral")
        val a = assertNotNull(DbLock.acquire(dir.resolve("a.db")))
        val b = assertNotNull(DbLock.acquire(dir.resolve("b.db")))
        a.release(); b.release()
    }
}
