package dev.corral

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val mcpJson = Json { ignoreUnknownKeys = true }
private const val SESSION_HEADER = "mcp-session-id"

/** MCP agents act on a model turn; ask them to poll far less often than the CLI's 1 s. */
private const val MCP_RETRY_AFTER_MS = 5000L

private const val EMULATOR_START_NOTE = "a started emulator appears in the pool within seconds and is " +
    "claimable once booted; claim with waitSeconds > 0 and transport=emulator to queue for boot"

@Serializable
data class ClaimArgs(val selector: Selector = Selector(), val ttlSeconds: Long = 1800,
                     val waitSeconds: Long = 0, val ticket: String? = null, val owner: String? = null,
                     val retryAfterMs: Long? = null)

@Serializable
data class FreeArgs(val serial: String)

@Serializable
data class RenewArgs(val serial: String, val ttlSeconds: Long = 1800)

@Serializable
data class ShotArgs(val serial: String? = null)

@Serializable
data class EmuStartArgs(val avd: String? = null)

@Serializable
data class EmuStopArgs(val avd: String? = null)

/**
 * MCP surface at /mcp: streamable HTTP, one Server per session. Manual wiring (not the
 * mcpStreamableHttp helper) because the helper's factory runs before the client handshake,
 * so clientInfo is unreachable there — we need ServerSession.clientVersion for ownership.
 */
fun Application.installMcp(env: AppEnv) {
    val transports = ConcurrentHashMap<String, StreamableHttpServerTransport>()
    val sessions = ConcurrentHashMap<String, ServerSession>()
    fun dropSessionTickets(session: ServerSession) {
        this@installMcp.launch { env.leases.cancelTickets(sessionOwner(session)) }
    }
    routing {
        route("/mcp") {
            post {
                val sid = call.request.header(SESSION_HEADER)
                val existing = sid?.let { transports[it] }
                when {
                    existing != null -> existing.handleRequest(null, call)
                    sid != null -> call.respondText("session not found", status = HttpStatusCode.NotFound)
                    else -> {
                        val t = StreamableHttpServerTransport(
                            StreamableHttpServerTransport.Configuration(enableJsonResponse = true))
                        var session: ServerSession? = null
                        t.setOnSessionInitialized { id -> transports[id] = t }
                        t.setOnSessionClosed { id ->
                            transports.remove(id)
                            session?.let { s -> sessions.remove(s.sessionId); dropSessionTickets(s) }
                        }
                        session = corralMcpServer(env, sessions).createSession(t)
                        val s = session
                        sessions[s.sessionId] = s
                        s.onClose { sessions.remove(s.sessionId); dropSessionTickets(s) }
                        t.handleRequest(null, call)
                    }
                }
            }
            delete {
                val transport = call.request.header(SESSION_HEADER)?.let { transports[it] }
                when {
                    transport != null -> transport.handleRequest(null, call)
                    call.request.header(SESSION_HEADER) == null ->
                        call.respondText("no session id", status = HttpStatusCode.BadRequest)
                    else -> call.respondText("session not found", status = HttpStatusCode.NotFound)
                }
            }
            get { call.respondText("streaming not supported", status = HttpStatusCode.MethodNotAllowed) }
        }
    }
}

/** sessionId -> session, so handlers can read the client's initialize info. */
fun corralMcpServer(env: AppEnv, sessions: ConcurrentHashMap<String, ServerSession>): Server {
    val server = Server(
        serverInfo = Implementation(name = "corral", version = "0.3.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )

    fun readOnly(name: String, description: String, block: suspend () -> String) =
        server.addTool(name, description, inputSchema = ToolSchema(),
            toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false)) { _ -> toolResult { block() } }

    readOnly("list_devices", "List known devices with model, OS, transport and current lease holder.") {
        mcpJson.encodeToString(env.devices().map { DeviceStatus(it.serial, env.leases.snapshot[it.serial], it.booted) })
    }
    readOnly("queue", "List agents waiting in the selector FIFO queue.") {
        mcpJson.encodeToString(env.leases.queueSnapshot())
    }

    server.addTool(
        name = "claim",
        description = "Claim a device matching the selector (empty = any usable device). " +
            "waitSeconds > 0 queues the claim and returns a ticket; re-call claim with that " +
            "ticket to poll on the returned retryAfterMs cadence (5 s for MCP, or your own " +
            "clamped proposal via retryAfterMs) until a matching device frees up, then the poll " +
            "returns the lease. A ticket is skipped by dispatch once it is a few intervals stale, " +
            "and dropped entirely after ~30 s of silence.",
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("selector") {
                put("type", "object")
                put("description", "Any combo of serial, model, os (minimum version), transport (usb|wifi|emulator), avd")
                putJsonObject("properties") {
                    putJsonObject("serial") { put("type", "string") }
                    putJsonObject("model") { put("type", "string") }
                    putJsonObject("os") { put("type", "string") }
                    putJsonObject("transport") { put("type", "string") }
                    putJsonObject("avd") { put("type", "string") }
                }
            }
            putJsonObject("ttlSeconds") { put("type", "integer"); put("description", "lease TTL, default 1800") }
            putJsonObject("waitSeconds") { put("type", "integer"); put("description", "queue wait in seconds; >0 returns a ticket to poll, 0 = fail fast, capped at 600") }
            putJsonObject("retryAfterMs") { put("type", "integer"); put("description", "proposed poll interval in ms; clamped to 1000–10000, default 5000") }
            putJsonObject("ticket") { put("type", "string"); put("description", "poll a queued claim: re-call claim with the ticket to pick up the device") }
            putJsonObject("owner") { put("type", "string"); put("description", "override the derived mcp: owner") }
        }),
        toolAnnotations = ToolAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false),
    ) { request ->
        toolResult {
            val args = request.arguments?.let { mcpJson.decodeFromJsonElement<ClaimArgs>(it) } ?: ClaimArgs()
            val owner = ownerOf(this, sessions, args.owner)
            when {
                args.ticket != null -> when (val p = env.leases.poll(args.ticket, owner)) {
                    is Poll.Offered -> mcpJson.encodeToString(p.lease)
                    is Poll.Queued -> mcpJson.encodeToString(QueuedResponse("queued", p.position, p.retryAfterMs))
                    is Poll.Expired -> throw IllegalStateException(when (p.reason) {
                        TicketEnd.DEADLINE -> "ticket deadline expired"
                        TicketEnd.SILENCE -> "ticket dropped: not polled in time; claim again to re-queue"
                    })
                    Poll.Unknown -> throw IllegalStateException("ticket expired or unknown; claim again to re-queue")
                }
                args.waitSeconds > 0 -> {
                    val t = env.leases.enqueue(args.selector, owner, 0, args.ttlSeconds, args.waitSeconds,
                        retryAfterMs = args.retryAfterMs ?: MCP_RETRY_AFTER_MS)
                    mcpJson.encodeToString(TicketResponse(t.id, t.pollCadenceMs))
                }
                else -> mcpJson.encodeToString(env.leases.requestClaim(args.selector, owner, 0, args.ttlSeconds))
            }
        }
    }

    server.addTool(
        name = "free",
        description = "Release the lease on a serial held by this session. Refuses if another owner holds it.",
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("serial") { put("type", "string") }
        }),
        toolAnnotations = ToolAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false),
    ) { request ->
        toolResult {
            val args = request.arguments?.let { mcpJson.decodeFromJsonElement<FreeArgs>(it) }
                ?: return@toolResult toolError("serial is required")
            env.leases.free(args.serial, ownerOf(this, sessions, null))
            "freed ${args.serial}"
        }
    }

    server.addTool(
        name = "screenshot",
        description = "Capture a PNG screenshot of a device screen. Read-only. serial is optional: " +
            "omit it to target the single usable connected device. Strict mode (default): your session " +
            "must hold the device's lease, or be a configured observer (CORRAL_SCREENSHOT_OBSERVERS).",
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("serial") { put("type", "string"); put("description", "device serial (omit = the single usable device)") }
        }),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, openWorldHint = false),
    ) { request ->
        try {
            val args = request.arguments?.let { mcpJson.decodeFromJsonElement<ShotArgs>(it) } ?: ShotArgs()
            val owner = ownerOf(this, sessions, null)   // the session is the principal; no owner override here
            val usable = env.devices().filter { it.state == "device" }
            val serial = args.serial
                ?: usable.singleOrNull()?.serial
                ?: throw IllegalStateException(
                    if (usable.isEmpty()) "no usable connected device; call list_devices"
                    else "several devices connected; pass one of: " + usable.joinToString(", ") { it.serial })
            (env.policy ?: env.config?.let(::screenshotPolicy) ?: ScreenshotPolicy())
                .denies(serial, env.leases.snapshot[serial], owner)
                ?.let { throw IllegalStateException(it) }
            val shotter = env.screenshotter ?: throw IllegalStateException("screenshots not configured on the daemon")
            val png = shotter.capture(serial)
            CallToolResult(content = listOf(
                TextContent(text = "$serial: ${png.size} bytes png"),
                ImageContent(data = Base64.getEncoder().encodeToString(png), mimeType = "image/png")))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(text = e.message ?: "screenshot failed")), isError = true)
        }
    }

    server.addTool(
        name = "renew",
        description = "Extend the lease TTL on a serial held by this session; also accepts a pending offer.",
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("serial") { put("type", "string") }
            putJsonObject("ttlSeconds") { put("type", "integer") }
        }),
        toolAnnotations = ToolAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false),
    ) { request ->
        toolResult {
            val args = request.arguments?.let { mcpJson.decodeFromJsonElement<RenewArgs>(it) }
                ?: return@toolResult toolError("serial is required")
            mcpJson.encodeToString(env.leases.renew(args.serial, ownerOf(this, sessions, null), args.ttlSeconds))
        }
    }

    server.addTool(
        name = "start_emulator",
        description = "Boot an AVD. A started emulator appears in the pool within seconds and is " +
            "claimable once booted; claim with waitSeconds > 0 and transport=emulator to queue for boot.",
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("avd") { put("type", "string"); put("description", "AVD name") }
        }),
        toolAnnotations = ToolAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = false),
    ) { request ->
        toolResult {
            val e = env.emulators ?: throw IllegalStateException("emulators not configured")
            val args = request.arguments?.let { mcpJson.decodeFromJsonElement<EmuStartArgs>(it) } ?: EmuStartArgs()
            val avd = args.avd ?: return@toolResult toolError("avd is required")
            val running = env.devices().filter { it.transport == "emulator" && it.avd == avd }
            val avds = e.list()
            when {
                running.isNotEmpty() || e.isRunning(avd) ->
                    "${mcpJson.encodeToString(Started(alreadyRunning = true))}\n$EMULATOR_START_NOTE"
                avds == null ->
                    throw IllegalStateException("emulator binary not usable — set CORRAL_EMULATOR")
                avd !in avds ->
                    throw IllegalStateException("unknown avd $avd — known: ${avds.joinToString(", ")}")
                else -> {
                    if (e.start(avd)) "${mcpJson.encodeToString(Started())}\n$EMULATOR_START_NOTE"
                    else "${mcpJson.encodeToString(Started(alreadyRunning = true))}\n$EMULATOR_START_NOTE"
                }
            }
        }
    }

    server.addTool(
        name = "stop_emulator",
        description = "Stop every running instance of an AVD. Refuses while one is leased, naming the " +
            "holder; free that lease first.",
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("avd") { put("type", "string"); put("description", "AVD name") }
        }),
        toolAnnotations = ToolAnnotations(destructiveHint = true, idempotentHint = false, openWorldHint = false),
    ) { request ->
        toolResult {
            val e = env.emulators ?: throw IllegalStateException("emulators not configured")
            val args = request.arguments?.let { mcpJson.decodeFromJsonElement<EmuStopArgs>(it) } ?: EmuStopArgs()
            val avd = args.avd ?: return@toolResult toolError("avd is required")
            val serials = env.devices().filter { it.transport == "emulator" && it.avd == avd }.map { it.serial }
            val held = serials.firstNotNullOfOrNull { env.leases.snapshot[it]?.owner }
            if (held != null) throw IllegalStateException("$avd is leased by $held — free that lease first")
            serials.forEach { e.stop(it); env.leases.free(it, owner = "", force = true) }
            mcpJson.encodeToString(Stopped(serials, e.stopAvd(avd)))
        }
    }

    return server
}

/** Declared owners are TTL-governed: never attach a pid (review-9 rule). */
private fun ownerOf(conn: ClientConnection, sessions: ConcurrentHashMap<String, ServerSession>,
                    overrideName: String?): String =
    overrideName ?: sessions[conn.sessionId]?.let { sessionOwner(it) } ?: "mcp:unknown:${conn.sessionId}"

/** Per-session principal; the sanitized client name comes from the initialize handshake. */
private fun sessionOwner(session: ServerSession): String =
    "mcp:${session.clientVersion?.name?.replace(Regex("[:\\s]"), "-") ?: "unknown"}:${session.sessionId}"

private suspend fun toolResult(block: suspend () -> String): CallToolResult = try {
    CallToolResult(content = listOf(TextContent(text = block())))
} catch (e: LeaseBusyException) {
    val busy = mcpJson.encodeToString(BusyResponse(e.busy, e.matching, e.timedOut, e.booting))
    val remedy = if (e.booting > 0)
        "${e.booting} emulator(s) booting: claim with waitSeconds > 0 and transport=emulator queues until boot."
    else "All matching devices are leased: claim with waitSeconds > 0 to queue, or refine the selector."
    CallToolResult(content = listOf(TextContent(text = "$busy\n$remedy")), isError = true)
} catch (e: IllegalStateException) {
    CallToolResult(content = listOf(TextContent(text = e.message ?: "lease error")), isError = true)
} catch (e: EmulatorException) {
    CallToolResult(content = listOf(TextContent(text = e.message ?: "emulator error")), isError = true)
}

private fun toolError(message: String): String = throw IllegalStateException(message)
