package dev.corral

import java.nio.file.Path
import java.sql.DriverManager

/** Plain JDBC, WAL, two tables: live leases + append-only history. */
class Db(file: Path) {
    private val conn = DriverManager.getConnection("jdbc:sqlite:$file")

    init {
        conn.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("""CREATE TABLE IF NOT EXISTS lease(
                serial TEXT PRIMARY KEY, owner TEXT NOT NULL, pid INTEGER NOT NULL,
                expires_at INTEGER NOT NULL, pending INTEGER NOT NULL DEFAULT 0,
                offered_at INTEGER NOT NULL DEFAULT 0, pids TEXT NOT NULL DEFAULT '')""")
            // pre-Phase-2 databases: add the column in place; "duplicate column" on fresh files is fine
            runCatching { s.execute("ALTER TABLE lease ADD COLUMN offered_at INTEGER NOT NULL DEFAULT 0") }
            // row 42: the command pids registered on a lease, comma-separated, so they outlive a restart
            runCatching { s.execute("ALTER TABLE lease ADD COLUMN pids TEXT NOT NULL DEFAULT ''") }
            s.execute("""CREATE TABLE IF NOT EXISTS history(
                id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL,
                event TEXT NOT NULL, serial TEXT, owner TEXT, detail TEXT)""")
        }
    }

    fun upsertLease(l: Lease) = conn.prepareStatement(
        "INSERT INTO lease(serial,owner,pid,expires_at,pending,offered_at,pids) VALUES(?,?,?,?,?,?,?) " +
        "ON CONFLICT(serial) DO UPDATE SET owner=excluded.owner, pid=excluded.pid, " +
        "expires_at=excluded.expires_at, pending=excluded.pending, offered_at=excluded.offered_at, " +
        "pids=excluded.pids").use { p ->
        p.setString(1, l.serial); p.setString(2, l.owner); p.setLong(3, l.pid)
        p.setLong(4, l.expiresAt); p.setInt(5, if (l.pendingAccept) 1 else 0)
        p.setLong(6, l.offeredAt); p.setString(7, l.pids.joinToString(",")); p.executeUpdate()
    }

    fun deleteLease(serial: String) = conn.prepareStatement("DELETE FROM lease WHERE serial=?")
        .use { it.setString(1, serial); it.executeUpdate() }

    fun loadLeases(): List<Lease> = conn.createStatement().use { s ->
        s.executeQuery("SELECT serial,owner,pid,expires_at,pending,offered_at,pids FROM lease").use { rs ->
            buildList {
                while (rs.next()) add(Lease(rs.getString(1), rs.getString(2), rs.getLong(3),
                    rs.getLong(4), rs.getInt(5) == 1, rs.getLong(6),
                    rs.getString(7).split(',').mapNotNull { it.toLongOrNull() }))
            }
        }
    }

    fun log(event: String, serial: String?, owner: String?, detail: String?) =
        conn.prepareStatement("INSERT INTO history(at,event,serial,owner,detail) VALUES(?,?,?,?,?)")
            .use { p ->
                p.setLong(1, System.currentTimeMillis() / 1000); p.setString(2, event)
                p.setString(3, serial); p.setString(4, owner); p.setString(5, detail); p.executeUpdate()
            }

    fun countHistory(): Int = conn.createStatement().use { s ->
        s.executeQuery("SELECT COUNT(*) FROM history").use { it.next(); it.getInt(1) }
    }

    fun history(): List<String> = conn.createStatement().use { s ->
        s.executeQuery("SELECT event FROM history ORDER BY id ASC").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }
}
