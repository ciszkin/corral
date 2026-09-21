package dev.corral

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Exclusive advisory lock on the database file, held for the daemon's lifetime.
 * Two daemons sharing one CORRAL_DB would each keep their own in-memory leases over one
 * SQLite file and happily double-lease the same device; the OS releases this lock on crash,
 * so no liveness heuristic is needed.
 */
object DbLock {
    fun acquire(dbPath: Path): FileLock? {
        val lockFile = dbPath.resolveSibling(dbPath.fileName.toString() + ".lock")
        lockFile.parent?.let { Files.createDirectories(it) }
        val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        return try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
    }
}
