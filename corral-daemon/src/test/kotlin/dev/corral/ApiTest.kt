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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.test.*

class ApiTest {
    private val testDevices = {
        listOf(Device("dev1", "device", model = "Pixel_6", osVersion = "13"),
               Device("dev2", "device", model = "SM-S918B", osVersion = "13"))
    }

    private fun ApplicationTestBuilder.corral() =
        application { corralModule(AppEnv(testDevices, LeaseManager(testDevices, db = null))) }

    private fun withApi(block: suspend CoroutineScope.(HttpClient) -> Unit) =
        testApplication { corral(); CoroutineScope(coroutineContext).block(client) }

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

    @Test fun queuedClaimLongPollResolvedByFree() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        launch { delay(500); c.delete("/api/leases/dev1") {
            contentType(ContentType.Application.Json); setBody("""{"owner":"agentA"}""") } }
        val r = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentB","waitSeconds":60}""") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"serial\":\"dev1\""))
    }

    @Test fun queueListsParkedWaiters() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev2"},"owner":"agentA"}""") }
        val parked = async { c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{},"owner":"waiter","waitSeconds":60}""") } }
        delay(100)   // yield: let the parked request reach the queue
        val q = c.get("/api/queue").bodyAsText()
        assertTrue(q.contains("\"owner\":\"waiter\"") && q.contains("\"position\":1"))
        c.delete("/api/leases/dev1") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentA"}""") }   // unblock the parked request
        assertEquals(HttpStatusCode.OK, parked.await().status)
    }
}
