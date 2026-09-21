package dev.corral

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ApiTest {
    private val testDevices = {
        listOf(Device("dev1", "device", model = "Pixel_6", osVersion = "13"),
               Device("dev2", "device", model = "SM-S918B", osVersion = "13"))
    }

    private fun ApplicationTestBuilder.corral(
        leases: LeaseManager = LeaseManager(testDevices, db = null),
        adbPath: String? = null,
    ) = application { corralModule(AppEnv(testDevices, leases, adbPath)) }

    private fun ApplicationTestBuilder.corralWithEvents(events: Events, leases: LeaseManager) =
        application { corralModule(AppEnv(testDevices, leases, events = events)) }

    private fun withApi(block: suspend CoroutineScope.(HttpClient) -> Unit) =
        testApplication { corral(); CoroutineScope(coroutineContext).block(client) }

    private fun withApi(leases: LeaseManager, block: suspend CoroutineScope.(HttpClient) -> Unit) =
        testApplication { corral(leases); CoroutineScope(coroutineContext).block(client) }

    private fun withApi(devices: List<Device>, block: suspend CoroutineScope.(HttpClient) -> Unit) =
        testApplication {
            application { corralModule(AppEnv({ devices }, LeaseManager({ devices }, db = null))) }
            CoroutineScope(coroutineContext).block(client)
        }

    private fun fakeScreenshotter(devices: List<Device>, fail: Boolean = false): Screenshotter {
        val dir = createTempDirectory("shot")
        val fakeAdb = dir.resolve("fake-adb")
        Files.writeString(fakeAdb, if (fail) "#!/usr/bin/env bash\necho boom >&2\nexit 3\n"
                                       else "#!/usr/bin/env bash\nprintf 'fake-screen'\n")
        fakeAdb.toFile().setExecutable(true)
        return Screenshotter({ devices }, fakeAdb.toString())
    }

    private fun withScreenshotApi(
        devices: List<Device> = testDevices(),
        policy: ScreenshotPolicy = ScreenshotPolicy("strict"),
        failAdb: Boolean = false,
        block: suspend CoroutineScope.(HttpClient) -> Unit,
    ) = testApplication {
        val leases = LeaseManager({ devices }, db = null)
        application {
            corralModule(AppEnv({ devices }, leases, screenshotter = fakeScreenshotter(devices, failAdb), policy = policy))
        }
        CoroutineScope(coroutineContext).block(client)
    }

    @Test fun devicesListed() = withApi { c ->
        assertEquals(HttpStatusCode.OK, c.get("/api/devices").status)
    }

    @Test fun claimCheckFreeCycle() = withApi { c ->
        val claim = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.OK, claim.status)
        assertTrue(claim.bodyAsText().contains("\"serial\":\"dev1\""))

        val check = c.get("/api/check?serial=dev1&owner=agentA").bodyAsText()
        assertTrue(check.contains("\"allowed\":true"))
        val foreign = c.get("/api/check?serial=dev1&owner=agentB").bodyAsText()
        assertTrue(foreign.contains("\"allowed\":false"))

        assertEquals(HttpStatusCode.OK, c.delete("/api/leases/dev1") {
            contentType(ContentType.Application.Json); setBody("""{"owner":"agentA"}""") }.status)
    }

    @Test fun foreignFreeConflicts() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.Conflict, c.delete("/api/leases/dev1") {
            contentType(ContentType.Application.Json); setBody("""{"owner":"agentB"}""") }.status)
    }

    @Test fun busyClaimConflictsWithOwnerInfo() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA","ttlSeconds":100}""") }
        val r = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentB"}""") }
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("agentA"))
    }

    @Test fun checkUnknownSerialDenied() = withApi { c ->
        assertTrue(c.get("/api/check?serial=zzz&owner=a").bodyAsText().contains("\"allowed\":false"))
    }

    @Test fun renewExtendsLease() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val r = c.post("/api/leases/dev1/renew") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentA","ttlSeconds":100}""") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"expiresAt\""))
    }

    @Test fun renewRefusesForeigner() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val r = c.post("/api/leases/dev1/renew") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentB"}""") }
        assertEquals(HttpStatusCode.Conflict, r.status)
    }

    @Test fun statusReportsTheDaemonsAdbPath() {
        testApplication {
            corral(adbPath = "/custom/sdk/platform-tools/adb")
            val r = client.get("/api/status")
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("\"adb\":\"/custom/sdk/platform-tools/adb\""))
        }
    }

    @Test fun statusListsLeases() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val r = c.get("/api/status")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"serial\":\"dev1\""))
    }

    @Test fun freeAllOnlyOwned() = withApi(
        listOf(Device("dev1", "device"), Device("dev2", "device"), Device("dev3", "device"))
    ) { c ->
        suspend fun claim(serial: String, owner: String) = c.post("/api/leases") {
            contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"$serial"},"owner":"$owner"}""") }
        suspend fun status() = Json.decodeFromString<StatusResponse>(c.get("/api/status").bodyAsText())

        claim("dev1", "agentA"); claim("dev2", "agentA"); claim("dev3", "agentB")

        // mirrors the CLI `free --all` loop: read status, DELETE every serial the caller owns
        val owned = status().devices.filter { it.lease?.owner == "agentA" }.map { it.serial }
        owned.forEach { serial ->
            c.delete("/api/leases/$serial") {
                contentType(ContentType.Application.Json); setBody("""{"owner":"agentA"}""") }
        }

        val after = status()
        assertNull(after.devices.first { it.serial == "dev1" }.lease)
        assertNull(after.devices.first { it.serial == "dev2" }.lease)
        assertEquals("agentB", after.devices.first { it.serial == "dev3" }.lease?.owner)
    }

    @Test fun modelSelectorClaimsMatchingDevice() = withApi { c ->
        val r = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"model":"Pixel_6"},"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"serial\":\"dev1\""))
    }

    @Test fun noMatchSelectorReportsMatchingZero() = withApi { c ->
        val r = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"model":"iPhone"},"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("\"matching\":0"))
    }

    @Test fun waitEnqueuesTicketAndPollOffers() = withApi { c ->
        val enq = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA","waitSeconds":60}""") }
        assertEquals(HttpStatusCode.Accepted, enq.status)
        assertTrue(enq.bodyAsText().contains("\"retryAfterMs\":1000"))
        val ticket = enq.bodyAsText().ticketOf()
        val poll = c.post("/api/leases/tickets/$ticket/poll") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentA","pid":1234}""") }
        assertEquals(HttpStatusCode.OK, poll.status)
        assertTrue(poll.bodyAsText().contains("\"serial\":\"dev1\""))
        assertTrue(poll.bodyAsText().contains("\"owner\":\"agentA\""))
        assertTrue(poll.bodyAsText().contains("\"pid\":1234"))
    }

    @Test fun claimTicketEchoesClampedClientCadence() = withApi { c ->
        suspend fun enqueue(body: String) = c.post("/api/leases") {
            contentType(ContentType.Application.Json); setBody(body) }

        val small = enqueue("""{"selector":{"serial":"dev1"},"owner":"agentA","waitSeconds":60,"retryAfterMs":500}""")
        assertTrue(small.bodyAsText().contains("\"retryAfterMs\":1000"))
        val big = enqueue("""{"selector":{"serial":"dev1"},"owner":"agentB","waitSeconds":60,"retryAfterMs":999999}""")
        assertTrue(big.bodyAsText().contains("\"retryAfterMs\":10000"))
        val absent = enqueue("""{"selector":{"serial":"dev1"},"owner":"agentC","waitSeconds":60}""")
        assertTrue(absent.bodyAsText().contains("\"retryAfterMs\":1000"))
    }

    @Test fun queuedTicketResolvedByFree() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val enq = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentB","waitSeconds":60}""") }
        assertEquals(HttpStatusCode.Accepted, enq.status)
        val ticket = enq.bodyAsText().ticketOf()

        val queued = c.post("/api/leases/tickets/$ticket/poll") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentB"}""") }
        assertEquals(HttpStatusCode.Accepted, queued.status)
        assertTrue(queued.bodyAsText().contains("\"state\":\"queued\""))
        assertTrue(queued.bodyAsText().contains("\"retryAfterMs\":1000"))

        c.delete("/api/leases/dev1") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentA"}""") }
        val offered = c.post("/api/leases/tickets/$ticket/poll") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentB"}""") }
        assertEquals(HttpStatusCode.OK, offered.status)
        assertTrue(offered.bodyAsText().contains("\"serial\":\"dev1\""))
        assertTrue(offered.bodyAsText().contains("\"owner\":\"agentB\""))
    }

    @Test fun unknownOrExpiredTicketPollGone() {
        val now = AtomicLong(1500)
        val leases = LeaseManager(testDevices, db = null, clock = { now.get() })
        withApi(leases) { c ->
            val unknown = c.post("/api/leases/tickets/nope/poll") { contentType(ContentType.Application.Json)
                setBody("""{"owner":"agentA"}""") }
            assertEquals(HttpStatusCode.Gone, unknown.status)
            assertTrue(unknown.bodyAsText().contains("ticket expired or unknown"))

            val expiredEnq = c.post("/api/leases") { contentType(ContentType.Application.Json)
                setBody("""{"selector":{"serial":"dev1"},"owner":"agentA","waitSeconds":5}""") }
            val expiredTicket = expiredEnq.bodyAsText().ticketOf()
            now.addAndGet(6)
            val expired = c.post("/api/leases/tickets/$expiredTicket/poll") { contentType(ContentType.Application.Json)
                setBody("""{"owner":"agentA"}""") }
            assertEquals(HttpStatusCode.Gone, expired.status)
            assertTrue(expired.bodyAsText().contains("ticket deadline expired"))

            val silentEnq = c.post("/api/leases") { contentType(ContentType.Application.Json)
                setBody("""{"selector":{"serial":"dev1"},"owner":"agentA","waitSeconds":600}""") }
            val silentTicket = silentEnq.bodyAsText().ticketOf()
            now.addAndGet(POLL_SILENCE_S + 1)
            val silent = c.post("/api/leases/tickets/$silentTicket/poll") { contentType(ContentType.Application.Json)
                setBody("""{"owner":"agentA"}""") }
            assertEquals(HttpStatusCode.Gone, silent.status)
            assertTrue(silent.bodyAsText().contains("ticket dropped: not polled in time"))

            val ghostEnq = c.post("/api/leases") { contentType(ContentType.Application.Json)
                setBody("""{"selector":{"serial":"dev2"},"owner":"ghost","pid":2000000000,"waitSeconds":60}""") }
            val ghostTicket = ghostEnq.bodyAsText().ticketOf()
            val swept = c.post("/api/leases/tickets/$ghostTicket/poll") { contentType(ContentType.Application.Json)
                setBody("""{"owner":"ghost"}""") }
            assertEquals(HttpStatusCode.Gone, swept.status)
            assertTrue(swept.bodyAsText().contains("ticket expired or unknown"))
        }
    }

    @Test fun cancelTicketRemovesIt() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val enq = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentB","waitSeconds":60}""") }
        val ticket = enq.bodyAsText().ticketOf()

        val cancelled = c.delete("/api/leases/tickets/$ticket") {
            contentType(ContentType.Application.Json); setBody("""{"owner":"agentB"}""") }
        assertEquals(HttpStatusCode.OK, cancelled.status)
        assertTrue(cancelled.bodyAsText().contains("\"cancelled\":true"))
        val gone = c.post("/api/leases/tickets/$ticket/poll") {
            contentType(ContentType.Application.Json); setBody("""{"owner":"agentB"}""") }
        assertEquals(HttpStatusCode.Gone, gone.status)
        assertTrue(gone.bodyAsText().contains("ticket expired or unknown"))
    }

    @Test fun cancelTicketWithWrongOwnerForbidden() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val enq = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentB","waitSeconds":60}""") }
        val ticket = enq.bodyAsText().ticketOf()

        val r = c.delete("/api/leases/tickets/$ticket") {
            contentType(ContentType.Application.Json); setBody("""{"owner":"agentC"}""") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test fun cancelUnknownTicketNotFound() = withApi { c ->
        val r = c.delete("/api/leases/tickets/nope") {
            contentType(ContentType.Application.Json); setBody("""{"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test fun cancelTicketQueryFallbackStillWorks() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val enq = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentB","waitSeconds":60}""") }
        val ticket = enq.bodyAsText().ticketOf()

        assertEquals(HttpStatusCode.OK, c.delete("/api/leases/tickets/$ticket?owner=agentB").status)
    }

    @Test fun cancelTicketWithoutOwnerBadRequest() = withApi { c ->
        val r = c.delete("/api/leases/tickets/nope") {
            contentType(ContentType.Application.Json); setBody("""{"notOwner":true}""") }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test fun waitReturnsTicketWithoutParkingWaiter() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev2"},"owner":"agentA"}""") }
        val enq = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{},"owner":"waiter","waitSeconds":60}""") }
        assertEquals(HttpStatusCode.Accepted, enq.status)
        assertTrue(c.get("/api/queue").bodyAsText().contains("\"owner\":\"waiter\""))
    }

    @Test fun malformedJsonIsClientError() = withApi { c ->
        val r = c.post("/api/leases") { contentType(ContentType.Application.Json); setBody("{not json") }
        assertTrue(r.status.value in 400..499, "malformed JSON must be a 4xx, got ${r.status}")
    }

    @Test fun unknownSerialClaimFreeRenew() = withApi { c ->
        val claim = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"zzz"},"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.Conflict, claim.status)
        assertTrue(claim.bodyAsText().contains("\"matching\":0"))

        val free = c.delete("/api/leases/zzz") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.OK, free.status)
        assertTrue(free.bodyAsText().contains("\"freed\":true"))

        val renew = c.post("/api/leases/zzz/renew") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.Conflict, renew.status)
        assertTrue(renew.bodyAsText().contains("no lease on zzz"))
    }

    @Test fun routeShapesStable() = withApi { c ->
        val devices = Json.parseToJsonElement(c.get("/api/devices").bodyAsText()).jsonArray
        val dev = devices.first().jsonObject
        assertTrue(dev["serial"] is JsonPrimitive && dev["serial"]!!.jsonPrimitive.isString)
        assertTrue(dev["state"] is JsonPrimitive && dev["state"]!!.jsonPrimitive.isString)

        val status = Json.parseToJsonElement(c.get("/api/status").bodyAsText()).jsonObject
        assertTrue(status["devices"] is JsonArray)
        assertTrue(status["adb"] is JsonPrimitive && status["adb"]!!.jsonPrimitive.isString)
        val ds = status["devices"]!!.jsonArray.first().jsonObject
        assertTrue(ds["serial"] is JsonPrimitive && ds["serial"]!!.jsonPrimitive.isString)
        assertTrue(ds.containsKey("lease"))

        // enqueue one ticket so the queue entry shape is pinned, not just "empty array"
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{},"owner":"waiter","waitSeconds":60}""") }
        val queue = Json.parseToJsonElement(c.get("/api/queue").bodyAsText()).jsonArray
        val q = queue.first().jsonObject
        assertTrue(q["position"] is JsonPrimitive && q["position"]!!.jsonPrimitive.content.toLongOrNull() != null)
        assertTrue(q["owner"] is JsonPrimitive && q["owner"]!!.jsonPrimitive.isString)
        assertTrue(q["selector"] is JsonPrimitive && q["selector"]!!.jsonPrimitive.isString)
        assertTrue(q["waitedSeconds"] is JsonPrimitive && q["waitedSeconds"]!!.jsonPrimitive.content.toLongOrNull() != null)
        assertTrue(q["deadlineIn"] is JsonPrimitive && q["deadlineIn"]!!.jsonPrimitive.content.toLongOrNull() != null)

        val check = Json.parseToJsonElement(c.get("/api/check?serial=dev1&owner=nobody").bodyAsText()).jsonObject
        assertEquals("false", check["allowed"]!!.jsonPrimitive.content)
        assertTrue(check["reason"] is JsonPrimitive && check["reason"]!!.jsonPrimitive.isString)
    }

    @Test fun checkIndependentOfQueueState() {
        val leases = LeaseManager(testDevices, db = null)
        withApi(leases) { c ->
            suspend fun post(body: String) = c.post("/api/leases") {
                contentType(ContentType.Application.Json); setBody(body) }
            post("""{"selector":{"serial":"dev1"},"owner":"holder"}""")
            post("""{"selector":{"serial":"dev1"},"owner":"waiter","waitSeconds":60}""")
            c.delete("/api/leases/dev1") { contentType(ContentType.Application.Json)
                setBody("""{"owner":"holder"}""") }

            assertTrue(leases.snapshot["dev1"]!!.pendingAccept)   // the queued ticket holds dev1

            val foreign = Json.parseToJsonElement(
                c.get("/api/check?serial=dev1&owner=someone-else").bodyAsText()).jsonObject
            assertEquals("false", foreign["allowed"]!!.jsonPrimitive.content)
            assertTrue(foreign["reason"]!!.jsonPrimitive.content.contains("waiter"))
            assertTrue(leases.snapshot["dev1"]!!.pendingAccept)   // a foreign check does not touch the offer
        }
    }

    @Test fun screenshotServesPngBytes() = withScreenshotApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"me"}""") }
        val r = c.get("/api/devices/dev1/screenshot?owner=me")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(ContentType.Image.PNG, r.contentType())
        assertEquals("fake-screen", r.bodyAsText())
    }

    @Test fun screenshotUnwiredIs503() = withApi { c ->
        assertEquals(HttpStatusCode.ServiceUnavailable,
            c.get("/api/devices/dev1/screenshot?owner=me").status)
    }

    @Test fun screenshotStrictDeniesStranger() = withScreenshotApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"me"}""") }
        val r = c.get("/api/devices/dev1/screenshot?owner=stranger")
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("held by me"))
        assertTrue(r.bodyAsText().contains("CORRAL_SCREENSHOTS=open"))
    }

    @Test fun screenshotStrictAllowsHolder() = withScreenshotApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"me"}""") }
        assertEquals(HttpStatusCode.OK, c.get("/api/devices/dev1/screenshot?owner=me").status)
    }

    @Test fun screenshotStrictAllowsObserver() =
        withScreenshotApi(policy = ScreenshotPolicy("strict", "corral-ui")) { c ->
            assertEquals(HttpStatusCode.OK, c.get("/api/devices/dev1/screenshot?owner=corral-ui").status)
        }

    @Test fun screenshotStrictUnknownSerialIs403Not404() =
        withScreenshotApi(policy = ScreenshotPolicy("strict", null)) { c ->
            val r = c.get("/api/devices/zzz/screenshot?owner=me")
            assertEquals(HttpStatusCode.Forbidden, r.status)          // policy first: no existence leak
            assertTrue(r.bodyAsText().contains("not leased"))
        }

    @Test fun screenshotUnknownSerialIs404() = withScreenshotApi(policy = ScreenshotPolicy("open")) { c ->
        assertEquals(HttpStatusCode.NotFound, c.get("/api/devices/zzz/screenshot?owner=me").status)
    }

    @Test fun screenshotUnusableDeviceIs404() = withScreenshotApi(
        devices = listOf(Device("dev1", "offline")), policy = ScreenshotPolicy("open")
    ) { c ->
        val r = c.get("/api/devices/dev1/screenshot?owner=me")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("offline"))
    }

    @Test fun screenshotAdbFailureIs502() =
        withScreenshotApi(policy = ScreenshotPolicy("open"), failAdb = true) { c ->
            assertEquals(HttpStatusCode.BadGateway, c.get("/api/devices/dev1/screenshot?owner=me").status)
        }

    @Test fun wsEventsSendsHelloThenNudges() = testApplication {
        val leases = LeaseManager(testDevices, db = null)
        val eventsScope = CoroutineScope(Job())
        val events = Events(
            { testDevices().map { d -> DeviceStatus(d.serial, leases.snapshot[d.serial]) } },
            { leases.queueSnapshot() },
            eventsScope,
            tickMs = 100,
        )
        corralWithEvents(events, leases)
        events.start()
        delay(500)                                       // let the baseline tick prime before mutating
        try {
            val ws = createClient { install(WebSockets) }
            ws.webSocket("/ws/events") {
                val hello = Json.parseToJsonElement(nextText()).jsonObject
                assertEquals("hello", hello["type"]?.jsonPrimitive?.content)
                assertEquals("devices", hello["resources"]!!.jsonArray.first().jsonPrimitive.content)

                leases.requestClaim(Selector(serial = "dev1"), "me", ttl = 100)
                val changed = Json.parseToJsonElement(nextText()).jsonObject
                assertEquals("state.changed", changed["type"]?.jsonPrimitive?.content)
                assertTrue(changed["resources"]!!.jsonArray.any { it.jsonPrimitive.content == "devices" })

                send(Frame.Text("not a real frame"))
                leases.free("dev1", "me")
                val next = Json.parseToJsonElement(nextText()).jsonObject
                assertEquals("state.changed", next["type"]?.jsonPrimitive?.content)
                assertTrue(next["resources"]!!.jsonArray.any { it.jsonPrimitive.content == "devices" })
            }
        } finally {
            eventsScope.cancel()
        }
    }

    @Test fun wsForeignOriginIsClosed() = testApplication {
        val leases = LeaseManager(testDevices, db = null)
        val events = Events({ emptyList() }, { emptyList() }, CoroutineScope(coroutineContext))
        corralWithEvents(events, leases)

        val ws = createClient { install(WebSockets) }
        val session = ws.webSocketSession("/ws/events") { header(HttpHeaders.Origin, "https://evil.example") }
        val reason: CloseReason? = withTimeout(5000) { session.closeReason.await() }
        assertEquals(CloseReason.Codes.VIOLATED_POLICY, reason?.knownReason)
        assertEquals("corral: local only", reason?.message)
    }

    @Test fun wsIpv6LoopbackOriginIsAccepted() = testApplication {
        val leases = LeaseManager(testDevices, db = null)
        val events = Events({ emptyList() }, { emptyList() }, CoroutineScope(coroutineContext))
        corralWithEvents(events, leases)

        val ws = createClient { install(WebSockets) }
        ws.webSocket("/ws/events", { header(HttpHeaders.Origin, "http://[::1]:3000") }) {
            val hello = Json.parseToJsonElement(nextText()).jsonObject
            assertEquals("hello", hello["type"]?.jsonPrimitive?.content)
        }
    }

    @Test fun wsUnconfiguredEventsIsClosed() = testApplication {
        corral()

        val ws = createClient { install(WebSockets) }
        val session = ws.webSocketSession("/ws/events")
        val reason: CloseReason? = withTimeout(5000) { session.closeReason.await() }
        assertEquals(CloseReason.Codes.VIOLATED_POLICY, reason?.knownReason)
        assertEquals("corral: events not configured", reason?.message)
    }

    private suspend fun WebSocketSession.nextText() =
        withTimeout(5000) { (incoming.receive() as Frame.Text).readText() }

    private fun String.ticketOf() = substringAfter("\"ticket\":\"").substringBefore("\"")
}
