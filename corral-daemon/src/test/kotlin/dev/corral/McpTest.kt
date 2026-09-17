package dev.corral

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.test.*

@OptIn(ExperimentalMcpApi::class)
class McpTest {
    private var now = 1500L
    private var devices = listOf(Device("dev1", "device"), Device("dev2", "device"))

    private fun server(sessions: ConcurrentHashMap<String, ServerSession>) =
        corralMcpServer(AppEnv({ devices }, LeaseManager({ devices }, null, { now })), sessions)

    private suspend fun harness(
        sessions: ConcurrentHashMap<String, ServerSession>,
        server: Server,
        name: String,
    ): Client {
        val (clientT, serverT) = ChannelTransport.createLinkedPair()
        val session = server.createSession(serverT)
        sessions[session.sessionId] = session
        val client = Client(clientInfo = Implementation(name = name, version = "1.0"))
        client.connect(clientT)
        return client
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                null -> put(k, null as String?)
                is String -> put(k, v)
                is Long -> put(k, v)
                is JsonObject -> put(k, v)
            }
        }
    }

    private suspend fun Client.tool(name: String, args: JsonObject) =
        callTool(CallToolRequest(CallToolRequestParams(name = name, arguments = args)))

    @Test
    fun fiveToolsListedAndClaimCarriesDerivedOwner() = runBlocking {
        val sessions = ConcurrentHashMap<String, ServerSession>()
        val s = server(sessions)
        val client = harness(sessions, s, "test-harness")

        assertEquals(
            setOf("list_devices", "queue", "claim", "free", "renew"),
            client.listTools().tools.map { it.name }.toSet(),
        )

        val res = client.tool("claim", obj("selector" to obj("serial" to "dev1")))
        val text = res.content.filterIsInstance<TextContent>().single().text
        assertTrue(text.contains("\"serial\":\"dev1\""))
        assertTrue(text.contains("mcp:test-harness:"))           // derived from clientInfo, unique per session

        val renewed = client.tool("renew", obj("serial" to "dev1", "ttlSeconds" to 100L))
        assertFalse(renewed.isError == true)

        val freed = client.tool("free", obj("serial" to "dev1"))
        assertFalse(freed.isError == true)
        client.close(); s.close()
    }

    @Test
    fun sessionsAreDistinctPrincipals() = runBlocking {
        val sessions = ConcurrentHashMap<String, ServerSession>()
        val s = server(sessions)
        val a = harness(sessions, s, "harness-a")
        val b = harness(sessions, s, "harness-b")

        val aText = a.tool("claim", obj("selector" to obj("serial" to "dev1")))
            .content.filterIsInstance<TextContent>().single().text
        val aOwner = Regex("\"owner\":\"([^\"]+)\"").find(aText)!!.groupValues[1]

        val denied = b.tool("free", obj("serial" to "dev1"))
        assertTrue(denied.isError == true)                        // b cannot free a's lease

        val spoofed = b.tool("free", obj("serial" to "dev1", "owner" to aOwner))
        assertTrue(spoofed.isError == true)                       // naming a's owner does not bypass the check

        val override = b.tool("claim", obj(
            "selector" to obj("serial" to "dev2"), "owner" to "declared-owner"))
        assertTrue(override.content.filterIsInstance<TextContent>().single().text.contains("declared-owner"))

        a.close(); b.close(); s.close()
    }

    @Test
    fun mcpClaimWaitReturnsTicketThenPollOffers() = runBlocking {
        val sessions = ConcurrentHashMap<String, ServerSession>()
        val leases = LeaseManager({ devices }, null, { now })
        val s = corralMcpServer(AppEnv({ devices }, leases), sessions)
        val a = harness(sessions, s, "harness-a")
        val b = harness(sessions, s, "harness-b")

        a.tool("claim", obj("selector" to obj("serial" to "dev1")))
        val enq = b.tool("claim", obj("selector" to obj("serial" to "dev1"), "waitSeconds" to 10L))
            .content.filterIsInstance<TextContent>().single().text
        assertTrue(enq.contains("\"ticket\""))
        assertTrue(enq.contains("\"retryAfterMs\":5000"))
        val ticket = Regex("\"ticket\":\"([^\"]+)\"").find(enq)!!.groupValues[1]

        val queued = b.tool("claim", obj("ticket" to ticket))
            .content.filterIsInstance<TextContent>().single().text
        assertTrue(queued.contains("\"queued\""))
        assertTrue(queued.contains("\"retryAfterMs\":5000"))

        a.tool("free", obj("serial" to "dev1"))
        val offered = b.tool("claim", obj("ticket" to ticket))
            .content.filterIsInstance<TextContent>().single().text
        assertTrue(offered.contains("\"owner\":\"mcp:harness-b:"))
        assertFalse(offered.contains("\"pendingAccept\":true"))
        assertFalse(leases.snapshot["dev1"]!!.pendingAccept)
        a.close(); b.close(); s.close()
    }

    @Test
    fun mcpClaimEchoesClampedClientCadence() = runBlocking {
        val sessions = ConcurrentHashMap<String, ServerSession>()
        val leases = LeaseManager({ devices }, null, { now })
        val s = corralMcpServer(AppEnv({ devices }, leases), sessions)
        val a = harness(sessions, s, "harness-a")
        val b = harness(sessions, s, "harness-b")
        val c = harness(sessions, s, "harness-c")
        suspend fun text(client: Client, args: JsonObject) =
            client.tool("claim", args).content.filterIsInstance<TextContent>().single().text

        a.tool("claim", obj("selector" to obj("serial" to "dev1")))
        val proposed = text(b, obj("selector" to obj("serial" to "dev1"),
            "waitSeconds" to 10L, "retryAfterMs" to 3000L))
        assertTrue(proposed.contains("\"retryAfterMs\":3000"))
        val ticket = Regex("\"ticket\":\"([^\"]+)\"").find(proposed)!!.groupValues[1]
        assertTrue(text(b, obj("ticket" to ticket)).contains("\"retryAfterMs\":3000"))

        assertTrue(text(c, obj("selector" to obj("serial" to "dev1"), "waitSeconds" to 10L))
            .contains("\"retryAfterMs\":5000"))
        assertTrue(text(c, obj("selector" to obj("serial" to "dev1"),
            "waitSeconds" to 10L, "retryAfterMs" to 100L))
            .contains("\"retryAfterMs\":1000"))
        assertTrue(text(b, obj("selector" to obj("serial" to "dev1"),
            "waitSeconds" to 10L, "retryAfterMs" to 999999L))
            .contains("\"retryAfterMs\":10000"))
        a.close(); b.close(); c.close(); s.close()
    }

    @Test
    fun mcpClaimTicketExpired() = runBlocking {
        val sessions = ConcurrentHashMap<String, ServerSession>()
        val leases = LeaseManager({ devices }, null, { now })
        val s = corralMcpServer(AppEnv({ devices }, leases), sessions)
        val a = harness(sessions, s, "harness-a")
        val b = harness(sessions, s, "harness-b")

        a.tool("claim", obj("selector" to obj("serial" to "dev1")))
        val enq = b.tool("claim", obj("selector" to obj("serial" to "dev1"), "waitSeconds" to 1L))
            .content.filterIsInstance<TextContent>().single().text
        val ticket = Regex("\"ticket\":\"([^\"]+)\"").find(enq)!!.groupValues[1]

        now += 2
        val expired = b.tool("claim", obj("ticket" to ticket))
        assertTrue(expired.isError == true)
        a.close(); b.close(); s.close()
    }

    @Test
    fun queuedClaimPolledWithTicketIsAccepted() = runBlocking {
        val sessions = ConcurrentHashMap<String, ServerSession>()
        val leases = LeaseManager({ devices }, null, { now })
        val s = corralMcpServer(AppEnv({ devices }, leases), sessions)
        val a = harness(sessions, s, "harness-a")
        val b = harness(sessions, s, "harness-b")

        a.tool("claim", obj("selector" to obj("serial" to "dev1")))
        val enq = b.tool("claim", obj("selector" to obj("serial" to "dev1"), "waitSeconds" to 10L))
            .content.filterIsInstance<TextContent>().single().text
        val ticket = Regex("\"ticket\":\"([^\"]+)\"").find(enq)!!.groupValues[1]

        a.tool("free", obj("serial" to "dev1"))
        val offered = b.tool("claim", obj("ticket" to ticket))
            .content.filterIsInstance<TextContent>().single().text
        assertTrue(offered.contains("mcp:harness-b:"))
        assertFalse(offered.contains("\"pendingAccept\":true"))
        assertFalse(leases.snapshot["dev1"]!!.pendingAccept)         // the poll accepted it
        a.close(); b.close(); s.close()
    }

    @Test
    fun busyPoolIsErrorWithRemedy() = runBlocking {
        val sessions = ConcurrentHashMap<String, ServerSession>()
        val s = server(sessions)
        val a = harness(sessions, s, "harness-a")
        val b = harness(sessions, s, "harness-b")

        a.tool("claim", obj("selector" to obj("serial" to "dev1")))
        a.tool("claim", obj("selector" to obj("serial" to "dev2")))
        val busy = b.tool("claim", obj("selector" to obj()))
        assertTrue(busy.isError == true)
        assertTrue(busy.content.filterIsInstance<TextContent>().single().text.contains("waitSeconds"))

        a.close(); b.close(); s.close()
    }

    @Test
    fun mcpRoutesCoexistWithApi() = testApplication {
        val env = AppEnv({ devices }, LeaseManager({ devices }, db = null))
        application { corralModule(env); installMcp(env) }
        assertEquals(HttpStatusCode.OK, client.get("/api/devices").status)
        assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/mcp").status)
    }

    @Test
    fun guardAllowsLocalHostsAndRejectsForeign() = testApplication {
        val env = AppEnv({ devices }, LeaseManager({ devices }, db = null))
        application { guardLocalHost(); corralModule(env) }
        suspend fun req(host: String) = client.get("/api/devices") { header(HttpHeaders.Host, host) }
        assertEquals(HttpStatusCode.OK, req("127.0.0.1:8798").status)
        assertEquals(HttpStatusCode.OK, req("localhost:8798").status)
        assertEquals(HttpStatusCode.OK, req("[::1]:8798").status)
        assertEquals(HttpStatusCode.Forbidden, req("evil.example").status)
    }

    @Test
    fun deleteMcpSessionDropsItsWaiters() = testApplication {
        val devs = listOf(Device("dev1", "device"))
        val leases = LeaseManager({ devs }, null)
        val env = AppEnv({ devs }, leases)
        application {
            corralModule(env)
            installMcp(env)
        }
        leases.claim("dev1", "occupier")

        suspend fun post(body: String, sid: String? = null) = client.post("/mcp") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            sid?.let { header("mcp-session-id", it) }
            setBody(body)
        }

        val init = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"trigger","version":"1"}}}""")
        val sid = init.headers["mcp-session-id"]!!
        val claim = CoroutineScope(coroutineContext).launch {
            post("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"claim","arguments":{"selector":{"serial":"dev1"},"waitSeconds":60}}}""", sid)
        }
        withTimeout(5_000) { while (leases.queueSnapshot().isEmpty()) delay(10) }
        assertTrue(leases.queueSnapshot().single().owner.startsWith("mcp:trigger:"))

        val deleted = client.delete("/mcp") {
            header("mcp-session-id", sid)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
        }
        assertEquals(HttpStatusCode.OK, deleted.status)

        withTimeout(5_000) { while (leases.queueSnapshot().isNotEmpty()) delay(10) }
        claim.cancelAndJoin()
    }
}
