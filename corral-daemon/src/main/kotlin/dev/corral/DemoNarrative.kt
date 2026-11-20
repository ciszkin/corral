package dev.corral

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale

/*
 * The demo narrative (plan §8): a scripted run of beats against the demo daemon, driven over the
 * same REST surface a CLI or the console uses. Nothing here may spawn a process, read a device or
 * open a socket that is not loopback (I1, I5) — the "work" the agents appear to do is printed, the
 * leases are real leases taken through `POST /api/leases`, and the ticket handoffs are the real
 * FIFO queue's. That is what keeps the demo's denial text and queue behaviour identical to the
 * product's (D14) instead of a re-telling of it.
 */

/** One beat: when it happens (seconds at `--speed 1`), which handler runs, and the wording. */
@Serializable internal data class DemoBeat(
    val at: Double,
    val beat: String,
    val title: String = "",
    /** A line the runner prints under the beat (simulated work, a hint) — never logic. */
    val note: String = "",
)

/**
 * `demo/script.json`, shipped in the jar: the beats, the serial the story is about, the AVD the
 * boot gate opens, the three working names and the TTL/queue windows. Everything a tune would
 * touch is here rather than compiled in.
 */
@Serializable internal data class DemoScript(
    val serial: String = "DEMO01234567",
    val avd: String = "DemoAVD",
    val agentA: String = "demo:agent-a",
    val agentB: String = "demo:agent-b",
    val agentC: String = "demo:agent-c",
    val ttlSeconds: Long = 60,
    val queueSeconds: Long = 30,
    val beats: List<DemoBeat> = emptyList(),
) {
    companion object {
        fun parse(text: String): DemoScript = corralJson.decodeFromString(text)

        fun load(): DemoScript {
            val text = DemoScript::class.java.getResourceAsStream("/demo/script.json")
                ?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("the demo script is missing from the jar (/demo/script.json)")
            return runCatching { parse(text) }.getOrElse {
                throw IllegalStateException("the demo script is not valid JSON: ${it.message}")
            }
        }
    }
}

/**
 * The narrative's whole output surface. `plain` drops the ANSI codes and never moves the cursor,
 * so a recording (or a log) reads as text — the beats are lines, not an animation.
 */
internal class DemoConsole(private val out: PrintStream, val plain: Boolean = false) {
    private var beatNo = 0

    fun beat(atSeconds: Double, title: String) {
        beatNo++
        val head = "-- beat $beatNo · t=${oneDecimal(atSeconds)}s · $title"
        out.println(if (plain) head else "$BOLD$CYAN$head$RESET")
    }

    /** An indented line under the current beat. */
    fun line(text: String) = out.println("  $text")

    /**
     * A line exactly as it was produced. The denial text is printed this way on purpose: it is the
     * daemon's own wording, and D14 compares it byte-for-byte with a real daemon's.
     */
    fun verbatim(text: String) = out.println(text)

    fun summary(title: String, lines: List<String>) {
        if (!plain) out.println("$BOLD$GREEN$title$RESET") else out.println(title)
        lines.forEach { out.println("  $it") }
    }

    private fun oneDecimal(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    private companion object {
        const val RESET = "\u001B[0m"
        const val BOLD = "\u001B[1m"
        const val CYAN = "\u001B[36m"
        const val GREEN = "\u001B[32m"
    }
}

/** A daemon call's outcome: the status the demo asserts on, and the body it may re-print. */
internal data class DemoResponse(val code: Int, val body: String)

/** The narrative's one dependency, so a unit test can drive every beat with no daemon at all. */
internal interface DemoTransport {
    fun exchange(method: String, path: String, body: String?): DemoResponse
}

/** Loopback HTTP against the demo daemon. Bounded like every other local client call. */
internal class HttpDemoTransport(private val base: String) : DemoTransport {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    override fun exchange(method: String, path: String, body: String?): DemoResponse {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofString(body)
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(5))
            .method(method, publisher)
            .header("Content-Type", "application/json")
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return DemoResponse(response.statusCode(), response.body())
    }
}

/** What the run actually did — counted from the responses, never written down in advance. */
internal data class DemoSummary(val leases: Int, val denials: Int, val handoffs: Int, val elapsedMs: Long)

/**
 * Runs the scripted beats against [client]. The two clocks are injected so a unit test can run the
 * whole narrative instantly and still assert the beat order and the measured handoff: [now] is a
 * monotonic millisecond clock for delays and latencies, [wall] is epoch seconds (what a lease's
 * `expiresAt` speaks).
 */
internal class DemoNarrative(
    private val client: DemoTransport,
    private val console: DemoConsole,
    private val url: String,
    private val speed: Double = 1.0,
    private val script: DemoScript = DemoScript.load(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wall: () -> Long = { System.currentTimeMillis() / 1000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val pollEveryMs: Long = 250,
) {
    private var leases = 0
    private var denials = 0
    private var handoffs = 0
    private var claimedAt = 0L
    private var ticket: String? = null

    fun run(): DemoSummary {
        val start = now()
        script.beats.forEach { beat ->
            waitUntil(start + (beat.at * 1000.0 / speed).toLong())
            console.beat((now() - start) / 1000.0, beat.title)
            when (beat.beat) {
                "devices" -> devices()
                "claim" -> claim(beat)
                "deny" -> deny()
                "queue" -> queue()
                "hint" -> hint()
                "release" -> release(beat)
                "work-end" -> workEnd()
                "avd" -> avd()
                "summary" -> summary()
                else -> throw IllegalStateException("the demo script names a beat nothing handles: '${beat.beat}'")
            }
        }
        return DemoSummary(leases, denials, handoffs, now() - start)
    }

    // --- beats -----------------------------------------------------------------------------------

    private fun devices() {
        val pool = decodeDeviceList(ok("GET", "/api/devices"))
        pool.forEach { console.verbatim(demoDeviceLine(it)) }
        val ready = pool.count { it.usable() }
        val booting = pool.count { it.transport == "emulator" && !it.booted }
        console.line("${plural(pool.size, "device")}: $ready ready, $booting booting — " +
            "a booting emulator is listed but cannot be claimed")
    }

    private fun claim(beat: DemoBeat) {
        val response = ask(script.agentA, Selector(serial = script.serial))
        if (response.code != 200) fail("claiming ${script.serial}", response)
        leases++
        claimedAt = now()
        val lease = decode<Lease>(response.body)
        console.line("${lease.owner} holds ${lease.serial} — ttl " +
            "${(lease.expiresAt - wall()).coerceAtLeast(0)}s of ${script.ttlSeconds}s left; " +
            "corral status names the holder")
        note(beat)
    }

    private fun deny() {
        val selector = Selector(serial = script.serial)
        val response = ask(script.agentB, selector)
        if (response.code != 409) fail("the second claim for ${script.serial}", response, expected = 409)
        denials++
        console.line("refused, and the answer is the daemon's own text (printed verbatim):")
        console.verbatim(busyLine(selector, response.body))
    }

    private fun queue() {
        val selector = Selector(serial = script.serial)
        val response = ask(script.agentB, selector, waitSeconds = script.queueSeconds)
        if (response.code != 202) fail("queueing for ${script.serial}", response, expected = 202)
        ticket = decode<TicketResponse>(response.body).ticket
        val mine = queueEntries().firstOrNull { it.owner == script.agentB }
            ?: throw IllegalStateException("/api/queue does not show demo:agent-b's ticket")
        console.line("/api/queue: position ${mine.position}, ${mine.owner}, ${mine.selector}, " +
            "deadline in ${mine.deadlineIn}s")
    }

    private fun hint() {
        // No "DEMO banner on every screen" here: the console's banner is S4. This hint must not
        // promise a marking a visitor cannot see today (review F2).
        console.line("console: corral-ui --url $url")
        console.line("CLI:     CORRAL_URL=$url corral queue")
    }

    private fun release(beat: DemoBeat) {
        val released = now()
        free(script.agentA)
        console.line("${script.agentA} released ${script.serial} after " +
            "${oneDecimal((released - claimedAt) / 1000.0)}s")
        val id = ticket ?: throw IllegalStateException("no queued ticket to serve")
        val servedIn = awaitServed(id, script.agentB, script.queueSeconds * 1000)
        handoffs++
        leases++
        console.line("served to ${script.agentB} in $servedIn ms — the queued ticket's own poll, not a re-claim")
        note(beat)
    }

    private fun workEnd() {
        free(script.agentB)
        val pool = decodeDeviceList(ok("GET", "/api/devices"))
        val status = decode<StatusResponse>(ok("GET", "/api/status"))
        val held = status.devices.count { it.lease != null }
        val queued = queueEntries().size
        if (held != 0 || queued != 0)
            throw IllegalStateException("expected a clean pool, found $held lease(s) and $queued ticket(s)")
        console.line("pool clean: ${plural(pool.size, "device")}, no lease, queue empty")
    }

    private fun avd() {
        val response = ask(script.agentC, Selector(transport = "emulator"), waitSeconds = script.queueSeconds)
        if (response.code != 202) fail("queueing for an emulator", response, expected = 202)
        val id = decode<TicketResponse>(response.body).ticket
        val position = queueEntries().firstOrNull()?.position ?: 1
        console.line("${script.agentC} queued --type emulator — position $position")
        val started = client.exchange("POST", "/api/emulators/${script.avd}/start", "{}")
        if (started.code != 202 && started.code != 200) fail("avd start ${script.avd}", started)
        console.line("avd start ${script.avd}: accepted — the console would show it booting")

        val waitingSince = now()
        var flippedAt: Long? = null
        while (true) {
            if (flippedAt == null && poolHasBootedEmulator()) {
                flippedAt = now()
                console.line("boot flip after ${oneDecimal((flippedAt - waitingSince) / 1000.0)}s — " +
                    "the emulator is claimable now")
            }
            val polled = pollTicket(id, script.agentC)
            if (polled.code == 200) {
                handoffs++
                leases++
                val lease = decode<Lease>(polled.body)
                val after = flippedAt?.let { " ${now() - it} ms after the flip" } ?: ""
                console.line("served to ${script.agentC}: ${lease.serial}$after")
                return
            }
            if (polled.code != 202) fail("polling the emulator ticket", polled, expected = 202)
            if (now() - waitingSince > BOOT_GATE_BOUND_MS)
                throw IllegalStateException("the emulator did not finish booting within ${BOOT_GATE_BOUND_MS / 1000}s")
            sleep(pollEveryMs)
        }
    }

    private fun summary() {
        console.summary(
            listOf(
                plural(leases, "lease"), plural(denials, "denial"),
                plural(handoffs, "queue handoff"), "0 real devices touched",
            ).joinToString(" · "),
            listOf(
                "this was a simulation: no phones, no adb, no SDK, no gates, no network",
                "for real: README.md — install the daemon, then `corral doctor`",
                "point an agent at this pool while it runs: " +
                    "claude mcp add --transport http corral-demo $url/mcp",
                "the script is done and the daemon stops here; `--no-script` leaves a pool to poke at, " +
                    "`--loop` runs it again with a fresh world",
            ))
    }

    // --- plumbing --------------------------------------------------------------------------------

    private fun ask(owner: String, selector: Selector, waitSeconds: Long = 0): DemoResponse =
        client.exchange("POST", "/api/leases", corralJson.encodeToString(ClaimRequest(
            selector = selector, owner = owner, pid = ProcessHandle.current().pid(),
            ttlSeconds = script.ttlSeconds, waitSeconds = waitSeconds)))

    private fun pollTicket(id: String, owner: String): DemoResponse =
        client.exchange("POST", "/api/leases/tickets/$id/poll",
            corralJson.encodeToString(TicketPollRequest(owner = owner, pid = ProcessHandle.current().pid())))

    private fun free(owner: String) {
        val response = client.exchange("DELETE", "/api/leases/${script.serial}",
            corralJson.encodeToString(FreeRequest(owner = owner)))
        if (response.code != 200) fail("releasing ${script.serial}", response)
    }

    private fun queueEntries(): List<QueueEntry> = decode(ok("GET", "/api/queue"))

    private fun poolHasBootedEmulator(): Boolean =
        decodeDeviceList(ok("GET", "/api/devices")).any { it.transport == "emulator" && it.booted }

    private fun awaitServed(id: String, owner: String, boundMs: Long): Long {
        val from = now()
        while (true) {
            val polled = pollTicket(id, owner)
            if (polled.code == 200) return now() - from
            if (polled.code != 202) fail("polling ticket $id", polled, expected = 202)
            if (now() - from > boundMs)
                throw IllegalStateException("ticket $id was not served within ${boundMs / 1000}s")
            sleep(pollEveryMs)
        }
    }

    private fun ok(method: String, path: String): String {
        val response = client.exchange(method, path, null)
        if (response.code != 200) fail("$method $path", response)
        return response.body
    }

    private fun fail(what: String, response: DemoResponse, expected: Int = 200): Nothing =
        throw IllegalStateException("$what answered ${response.code} (expected $expected): ${response.body.take(400)}")

    private inline fun <reified T> decode(body: String): T = runCatching { corralJson.decodeFromString<T>(body) }
        .getOrElse { throw IllegalStateException("the demo daemon answered something this script cannot read: ${it.message}") }

    private fun waitUntil(target: Long) {
        while (true) {
            val left = target - now()
            if (left <= 0) return
            sleep(minOf(left, 50))
        }
    }

    private fun note(beat: DemoBeat) {
        if (beat.note.isNotBlank()) console.line(beat.note)
    }

    private fun oneDecimal(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

    private companion object {
        /** How long the boot-gate beat waits for the world's boot flip before it gives up. */
        const val BOOT_GATE_BOUND_MS = 20_000L
    }
}
