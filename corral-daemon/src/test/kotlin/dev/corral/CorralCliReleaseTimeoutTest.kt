package dev.corral

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Row 52: the CLI's release must be bounded. A daemon that accepts the connection and never
 * answers used to park `corral lease` forever — the command had already finished, but the CLI
 * never exited (the winbox QA gate caught it at 41 s and 83 s with `main` inside
 * `CorralCli.release`).
 *
 * The fake daemon here answers the claim and the renew, and blackholes the release: the
 * connection is accepted, the request is read, and nothing is ever written back — the socket stays
 * open, so the client waits for an answer that never comes. The CLI must still exit, and it must
 * have attempted the release.
 */
class CorralCliReleaseTimeoutTest {

    /**
     * The CLI's budget: the claim's bound plus the release's bound (the two calls the fake daemon
     * can stall) plus room for the two JVMs to start and the command to run. The pre-change CLI
     * never returned at all, so any finite budget separates "bounded" from "hung".
     */
    private val exitBudgetSeconds = DAEMON_CALL_TIMEOUT_SECONDS + EXIT_PATH_TIMEOUT_SECONDS + 13

    /** The release is attempted right after the command ends, so it is seen well inside this. */
    private val releaseWaitSeconds = EXIT_PATH_TIMEOUT_SECONDS + 10

    @Test fun theCliExitsWhenTheDaemonNeverAnswersTheRelease() {
        val daemon = FakeDaemon()
        try {
            val cli = startCli(daemon.url)
            try {
                assertTrue(daemon.releaseSeen.await(releaseWaitSeconds, TimeUnit.SECONDS),
                    "corral lease must attempt the release before it exits")
                val exited = cli.process.waitFor(exitBudgetSeconds, TimeUnit.SECONDS)
                assertTrue(exited,
                    "corral lease must exit within $exitBudgetSeconds s when the daemon never answers " +
                        "the release; it was still running after ${exitBudgetSeconds}s. CLI output:\n${cli.output()}")
                assertEquals(0, cli.process.exitValue(),
                    "the command's own exit code must survive a release that timed out. CLI output:\n${cli.output()}")
            } finally {
                cli.process.destroyForcibly()
                cli.process.waitFor(5, TimeUnit.SECONDS)
            }
        } finally {
            daemon.close()
        }
    }

    @Test fun theBoundsSuitWhatEachCallWaitsFor() {
        assertEquals(2L, EXIT_PATH_TIMEOUT_SECONDS,
            "the release runs on the exit path and in the shutdown hook: it must never hold the exit")
        assertTrue(DAEMON_CALL_TIMEOUT_SECONDS >= EXIT_PATH_TIMEOUT_SECONDS,
            "a call that answers at once may wait longer than the exit path, never less")
        assertTrue(EMULATOR_CALL_TIMEOUT_SECONDS >= 60L,
            "booting an AVD takes minutes: the emulator bound must stay generous")
    }

    /** The CLI under test: the real jar's main class, with CORRAL_URL pointed at the fake daemon. */
    private class Cli(val process: Process, private val log: Path) {
        fun output(): String = runCatching { Files.readString(log) }.getOrDefault("")
    }

    private fun startCli(url: String): Cli {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("java.class.path")
        val log = Files.createTempFile("corral-row52-cli", ".log")
        // The command the CLI runs is the CLI's own --version: it exits 0 and needs no shell, so
        // the test is the same on Windows and on POSIX.
        val process = ProcessBuilder(java, "-Duser.home=${System.getProperty("user.home")}",
            "-cp", classpath, "dev.corral.CorralCli", "lease", "--",
            java, "-cp", classpath, "dev.corral.CorralCli", "--version")
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .apply {
                environment()["CORRAL_URL"] = url
                environment()["CORRAL_OWNER"] = "row52-test"
            }
            .start()
        return Cli(process, log)
    }

    /**
     * A fake daemon on a real socket. It answers the claim and the renew, and it blackholes the
     * release: the connection is accepted and the request is read, but nothing is ever written
     * back and the socket is never closed. That is the wedge the QA gate measured.
     */
    private class FakeDaemon : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val open = CopyOnWriteArrayList<Socket>()
        val releaseSeen = CountDownLatch(1)
        val url: String = "http://127.0.0.1:${server.localPort}"

        init {
            Thread({ accept() }, "row52-fake-daemon").apply { isDaemon = true; start() }
        }

        private fun accept() {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { return }
                open += socket
                Thread({ handle(socket) }, "row52-fake-daemon-conn").apply { isDaemon = true; start() }
            }
        }

        private fun handle(socket: Socket) {
            val head = readHead(socket) ?: return
            val request = head.lineSequence().firstOrNull().orEmpty()
            when {
                request.startsWith("POST /api/leases ") -> respond(socket, LEASE)
                request.startsWith("POST /api/leases/dev1/renew") -> respond(socket, LEASE)
                // The blackhole: the release is seen, and the client waits for an answer forever.
                request.startsWith("DELETE /api/leases/dev1") -> releaseSeen.countDown()
                else -> respond(socket, """{"error":"not found"}""")
            }
        }

        /** The request head, then the body (so the client's write never blocks on us). */
        private fun readHead(socket: Socket): String? {
            socket.soTimeout = 10_000
            val buf = ByteArrayOutputStream()
            val one = ByteArray(1)
            while (true) {
                val n = try { socket.inputStream.read(one) } catch (_: Exception) { return null }
                if (n < 0) return null
                buf.write(one[0].toInt())
                val bytes = buf.toByteArray()
                val size = bytes.size
                if (size >= 4 && bytes[size - 4] == '\r'.code.toByte() && bytes[size - 3] == '\n'.code.toByte() &&
                    bytes[size - 2] == '\r'.code.toByte() && bytes[size - 1] == '\n'.code.toByte()) break
                if (size > 64 * 1024) return null
            }
            val head = buf.toString(StandardCharsets.ISO_8859_1)
            val length = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            repeat(length) { socket.inputStream.read() }
            return head
        }

        private fun respond(socket: Socket, body: String) {
            val payload = body.toByteArray(StandardCharsets.UTF_8)
            runCatching {
                val out = socket.getOutputStream()
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1))
                out.write(payload)
                out.flush()
            }
            runCatching { socket.close() }
        }

        override fun close() {
            runCatching { server.close() }
            open.forEach { runCatching { it.close() } }
        }

        companion object {
            private const val LEASE =
                """{"serial":"dev1","owner":"row52-test","pid":0,"expiresAt":0,"pendingAccept":false,"offeredAt":0,"pids":[]}"""
        }
    }
}
