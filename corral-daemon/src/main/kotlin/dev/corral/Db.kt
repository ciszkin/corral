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
                expires_at INTEGER NOT NULL, pending INTEGER NOT NULL DEFAULT 0)""")
            s.execute("""CREATE TABLE IF NOT EXISTS history(
                id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL,
                event TEXT NOT NULL, serial TEXT, owner TEXT, detail TEXT)""")
        }
    }

    fun upsertLease(l: Lease) = conn.prepareStatement(
        "INSERT INTO lease(serial,owner,pid,expires_at,pending) VALUES(?,?,?,?,?) " +
        "ON CONFLICT(serial) DO UPDATE SET owner=excluded.owner, pid=excluded.pid, " +
        "expires_at=excluded.expires_at, pending=excluded.pending").use { p ->
        p.setString(1, l.serial); p.setString(2, l.owner); p.setLong(3, l.pid)
        p.setLong(4, l.expiresAt); p.setInt(5, if (l.pendingAccept) 1 else 0); p.executeUpdate()
    }

    fun deleteLease(serial: String) = conn.prepareStatement("DELETE FROM lease WHERE serial=?")
        .use { it.setString(1, serial); it.executeUpdate() }

    fun loadLeases(): List<Lease> = conn.createStatement().use { s ->
        s.executeQuery("SELECT serial,owner,pid,expires_at,pending FROM lease").use { rs ->
            buildList {
                while (rs.next()) add(Lease(rs.getString(1), rs.getString(2), rs.getLong(3),
                    rs.getLong(4), rs.getInt(5) == 1))
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
}
