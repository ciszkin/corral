package dev.corral

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.*

/**
 * The narrative (plan §8) without a daemon, a port or a real clock: a fake transport that speaks the
 * same routes and DTOs, and two injected clocks. What a row here pins is exactly what the demo
 * promises and cannot be allowed to drift — the beat order, the counts the summary reports, the
 * denial text being the product's own, and the boot gate really gating the queued ticket.
 */
class DemoNarrativeTest {
    private var nowMs = 0L
    private var wall = 1_700_000_000L
    private val daemon = FakeDemoDaemon(nowMs = { nowMs }, wall = { wall })
    private val out = ByteArrayOutputStream()

    private fun run(speed: Double = 1.0, script: DemoScript = DemoScript.load()): Pair<DemoSummary, String> {
        val console = DemoConsole(PrintStream(out, true, Charsets.UTF_8), plain = true)
        val summary = DemoNarrative(
            client = daemon, console = console, url = "http://127.0.0.1:8799", speed = speed, script = script,
            now = { nowMs }, wall = { wall }, sleep = { nowMs += it }, pollEveryMs = 250).run()
        return summary to out.toString(Charsets.UTF_8)
    }

    @Test fun theShippedScriptParsesAndEveryBeatIsOneTheRunnerHandles() {
        val script = DemoScript.load()
        assertEquals(9, script.beats.size, "the plan's narrative is nine beats")
        val handled = setOf("devices", "claim", "deny", "queue", "hint", "release", "work-end", "avd", "summary")
        script.beats.forEach { assertTrue(it.beat in handled, "beat '${it.beat}' has no handler") }
        assertEquals("summary", script.beats.last().beat, "a script that ends on anything else never reports")
        assertTrue(script.beats.last().at <= 28.0, "the script must fit G2's 35 s with room for startup")
        assertEquals(script.beats.map { it.at }.sorted(), script.beats.map { it.at }, "beats run in time order")
        assertEquals("DEMO01234567", script.serial)
        assertEquals("DemoAVD", script.avd)
        assertTrue(script.agentA.startsWith("demo:"), "every working name says demo")
        assertTrue(script.agentB.startsWith("demo:") && script.agentC.startsWith("demo:"))
    }

    @Test fun theShippedScriptShipsInsideTheJarWithTheThreeScreenAssets() {
        for (asset in listOf("/demo/script.json", "/demo/world.json", "/demo/screen.png",
            "/demo/screen-usb.png", "/demo/screen-wifi.png", "/demo/screen-emu.png")) {
            val bytes = DemoWorld::class.java.getResourceAsStream(asset)?.use { it.readBytes() }
            assertNotNull(bytes, "$asset must ship in the daemon jar")
            assertTrue(bytes.isNotEmpty(), "$asset is empty")
            if (asset.endsWith(".png")) {
                assertTrue(bytes.size <= 60 * 1024, "$asset is ${bytes.size}B, over the plan's 60KB screen budget")
                assertEquals(listOf(0x89.toByte(), 0x50, 0x4E, 0x47), bytes.take(4), "$asset is not a PNG")
            }
        }
    }

    @Test fun theWholeScriptRunsInOrderAndTheSummaryCountsWhatHappened() {
        val (summary, text) = run()
        assertEquals(DemoSummary(leases = 3, denials = 1, handoffs = 2, elapsedMs = summary.elapsedMs), summary)
        assertTrue(summary.elapsedMs in 28_000..29_500, "the script is ~28 s at --speed 1, was ${summary.elapsedMs}ms")
        val beats = text.lineSequence().filter { it.startsWith("-- beat ") }.toList()
        assertEquals(9, beats.size, "nine beats were printed: $beats")
        assertTrue(beats.first().startsWith("-- beat 1 · t=0.0s · devices"), beats.first())
        assertTrue(beats.last().startsWith("-- beat 9 · t=28.0s · summary"), beats.last())
        assertContains(text, "3 leases · 1 denial · 2 queue handoffs · 0 real devices touched")
    }

    @Test fun theDenialIsTheDaemonsOwnLinePrintedVerbatim() {
        val (_, text) = run()
        val printed = text.lines().first { it.startsWith("corral: no free device matches") }
        val fromTheProduct = busyLine(Selector(serial = "DEMO01234567"),
            corralJson.encodeToString(BusyResponse(listOf(BusyInfo("DEMO01234567", "demo:agent-a", 60)), 1, false, 0)))
        assertEquals(fromTheProduct, printed, "the demo must print the CLI's own busy line, not a re-telling")
        assertContains(printed, "corral lease --timeout S -- <cmd> queues and holds")
        assertContains(printed, "corral status for holders")
    }

    @Test fun theBootFlipIsWaitedForAndThenServesTheQueuedEmulatorTicket() {
        val (_, text) = run()
        assertContains(text, "demo:agent-c queued --type emulator — position 1")
        assertContains(text, "boot flip after 6.0s")
        assertContains(text, "served to demo:agent-c: emulator-5554")
        val flip = text.indexOf("boot flip after")
        val served = text.indexOf("served to demo:agent-c")
        assertTrue(flip in 1 until served, "the offer must follow the flip, not precede it")
    }

    @Test fun speedDividesTheBeatWaitsAndNeverTheLeaseOrTheBoot() {
        val (atOne, one) = run(speed = 1.0)
        nowMs = 0; wall = 1_700_000_000L; out.reset()
        daemon.reset()
        val (atFour, four) = run(speed = 4.0)
        assertEquals(listOf(atOne.leases, atOne.denials, atOne.handoffs),
            listOf(atFour.leases, atFour.denials, atFour.handoffs), "--speed must not change what happens")
        assertContains(four, "-- beat 2 · t=0.5s · demo:agent-a claims the USB device")
        assertContains(four, "boot flip after 6.0s", message = "a boot is a device fact: --speed does not shorten it")
        assertTrue(atFour.elapsedMs < atOne.elapsedMs / 2,
            "speed 4 should take well under half the wall clock (${atFour.elapsedMs}ms vs ${atOne.elapsedMs}ms)")
    }

    @Test fun anUnknownBeatFailsLoudlyRatherThanSkipping() {
        val broken = DemoScript.parse("""{"beats":[{"at":0,"beat":"teleport","title":"nope"}]}""")
        val e = assertFailsWith<IllegalStateException> { run(script = broken) }
        assertContains(e.message ?: "", "teleport")
    }

    @Test fun aWrongAnswerFromTheDaemonStopsTheRunWithTheCallNamed() {
        val broken = object : DemoTransport {
            override fun exchange(method: String, path: String, body: String?) = DemoResponse(500, "boom")
        }
        val console = DemoConsole(PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8), plain = true)
        val script = DemoScript.parse("""{"beats":[{"at":0,"beat":"devices","title":"devices"}]}""")
        val e = assertFailsWith<IllegalStateException> {
            DemoNarrative(broken, console, "http://127.0.0.1:8799", 1.0, script,
                now = { nowMs }, wall = { wall }, sleep = { nowMs += it }).run()
        }
        assertContains(e.message ?: "", "GET /api/devices")
        assertContains(e.message ?: "", "500")
    }

    @Test fun plainPrintsNoEscapeCodesAndTheColouredConsoleStillHasThem() {
        val plain = ByteArrayOutputStream()
        DemoConsole(PrintStream(plain, true, Charsets.UTF_8), plain = true).beat(0.0, "devices")
        assertFalse(plain.toString(Charsets.UTF_8).contains('\u001B'))

        val fancy = ByteArrayOutputStream()
        DemoConsole(PrintStream(fancy, true, Charsets.UTF_8), plain = false).beat(0.0, "devices")
        assertTrue(fancy.toString(Charsets.UTF_8).contains('\u001B'), "a terminal gets colour")
    }
}

/**
 * A stand-in for the demo daemon: the three routes the narrative uses, the real DTOs, and a boot
 * flip tied to the injected clock. It answers the way LeaseManager does where the narrative can
 * tell the difference (409 with a BusyResponse, 202 with a ticket, 200 with a Lease).
 */
private class FakeDemoDaemon(private val nowMs: () -> Long, private val wall: () -> Long) : DemoTransport {
    private var pool = listOf(
        Device("DEMO01234567", "device", model = "Pixel 7 (demo)", osVersion = "14"),
        Device("192.168.1.50:5555", "device", model = "Galaxy A54 (demo)", osVersion = "13"),
        Device("emulator-5554", "device", model = "sdk_gphone64 (demo)", osVersion = "14", booted = false),
    )
    private val leases = mutableMapOf<String, Lease>()
    private val tickets = LinkedHashMap<String, TicketSpec>()
    private var bootAt: Long? = null

    private data class TicketSpec(val owner: String, val selector: Selector)

    fun reset() {
        pool = pool.map { it.copy(booted = it.transport != "emulator") }
        leases.clear(); tickets.clear(); bootAt = null
    }

    override fun exchange(method: String, path: String, body: String?): DemoResponse = when {
        method == "GET" && path == "/api/devices" -> {
            flip()
            DemoResponse(200, corralJson.encodeToString(pool))
        }
        method == "GET" && path == "/api/status" -> DemoResponse(200, corralJson.encodeToString(
            StatusResponse(pool.map { DeviceStatus(it.serial, leases[it.serial], it.booted) }, "adb", "0.0.0-demo")))
        method == "GET" && path == "/api/queue" -> DemoResponse(200, corralJson.encodeToString(
            tickets.values.mapIndexed { i, t -> QueueEntry(i + 1, t.owner, t.selector.toString(), 0, 30) }))
        method == "POST" && path == "/api/leases" -> claim(body!!)
        method == "POST" && path.startsWith("/api/leases/tickets/") -> poll(path, body!!)
        method == "DELETE" && path.startsWith("/api/leases/") -> free(path, body!!)
        method == "POST" && path == "/api/emulators/DemoAVD/start" -> {
            bootAt = nowMs() + 6_000
            pool = pool.map { if (it.transport == "emulator") it.copy(avd = "DemoAVD", booted = false) else it }
            DemoResponse(202, """{"started":true,"alreadyRunning":false}""")
        }
        else -> DemoResponse(404, "no route $method $path")
    }

    private fun flip() {
        val at = bootAt ?: return
        if (nowMs() >= at) {
            pool = pool.map { if (it.transport == "emulator") it.copy(booted = true) else it }
            bootAt = null
        }
    }

    private fun claim(body: String): DemoResponse {
        val req = corralJson.decodeFromString<ClaimRequest>(body)
        if (req.waitSeconds > 0) {
            val id = "t${tickets.size + 1}"
            tickets[id] = TicketSpec(req.owner, req.selector)
            return DemoResponse(202, corralJson.encodeToString(TicketResponse(id, 250)))
        }
        val free = pool.firstOrNull { req.selector.matches(it) && it.serial !in leases }
        if (free != null) {
            val lease = Lease(free.serial, req.owner, req.pid, wall() + req.ttlSeconds)
            leases[free.serial] = lease
            return DemoResponse(200, corralJson.encodeToString(lease))
        }
        val matching = pool.count { it.state == "device" && req.selector.matchesFields(it) }
        val busy = pool.filter { req.selector.matchesFields(it) }.mapNotNull { d ->
            leases[d.serial]?.let { BusyInfo(d.serial, it.owner, 60) }
        }
        return DemoResponse(409, corralJson.encodeToString(BusyResponse(busy, matching, false, 0)))
    }

    private fun poll(path: String, body: String): DemoResponse {
        val id = path.removePrefix("/api/leases/tickets/").removeSuffix("/poll")
        val t = tickets[id] ?: return DemoResponse(410, """{"error":"ticket expired or unknown"}""")
        corralJson.decodeFromString<TicketPollRequest>(body)
        val free = pool.firstOrNull { t.selector.matches(it) && it.serial !in leases }
            ?: return DemoResponse(202, """{"state":"queued","position":1,"retryAfterMs":250}""")
        tickets.remove(id)
        val lease = Lease(free.serial, t.owner, 0, wall() + 60)
        leases[free.serial] = lease
        return DemoResponse(200, corralJson.encodeToString(lease))
    }

    private fun free(path: String, body: String): DemoResponse {
        val serial = path.removePrefix("/api/leases/")
        val req = corralJson.decodeFromString<FreeRequest>(body)
        val held = leases[serial] ?: return DemoResponse(409, """{"error":"no lease on $serial"}""")
        if (held.owner != req.owner) return DemoResponse(409, """{"error":"lease on $serial is not yours"}""")
        leases.remove(serial)
        return DemoResponse(200, """{"freed":true}""")
    }
}
