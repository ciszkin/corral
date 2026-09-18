package dev.corral

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
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

    private fun withApi(block: suspend CoroutineScope.(HttpClient) -> Unit) =
        testApplication { corral(); CoroutineScope(coroutineContext).block(client) }

    private fun withApi(leases: LeaseManager, block: suspend CoroutineScope.(HttpClient) -> Unit) =
        testApplication { corral(leases); CoroutineScope(coroutineContext).block(client) }

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

    private fun String.ticketOf() = substringAfter("\"ticket\":\"").substringBefore("\"")
}
