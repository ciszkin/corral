package dev.corral

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class ClaimRequest(val selector: Selector = Selector(),
    val owner: String, val pid: Long = 0, val ttlSeconds: Long = 1800, val waitSeconds: Long = 0,
    val retryAfterMs: Long? = null)

@Serializable data class FreeRequest(val owner: String, val force: Boolean = false)

@Serializable data class BusyResponse(val busy: List<BusyInfo>, val matching: Int, val timedOut: Boolean)

@Serializable data class TicketResponse(val ticket: String, val retryAfterMs: Long)

@Serializable data class TicketPollRequest(val owner: String, val pid: Long = 0)

@Serializable data class TicketCancelRequest(val owner: String)

@Serializable data class QueuedResponse(val state: String, val position: Int, val retryAfterMs: Long)

@Serializable data class CancelledResponse(val cancelled: Boolean)

@Serializable data class RenewRequest(val owner: String, val ttlSeconds: Long = 1800)

class AppEnv(
    val devices: () -> List<Device>,
    val leases: LeaseManager,
    val adbPath: String? = null,
    val screenshotter: Screenshotter? = null,
    val policy: ScreenshotPolicy? = null,
    val events: Events? = null,
)

/** Accepts a `Host` header (`127.0.0.1:8798`, `[::1]:8798`) or an `Origin` URL (`http://[::1]:3000`). */
private fun bareHost(value: String): String {
    val host = value.substringAfter("//", value)
    val bare = if (host.startsWith("[")) host.substringAfter("[").substringBefore("]")
               else host.substringBefore(":")
    return bare.lowercase()
}

/** Browser DNS-rebinding guard: every local client (CLI, wrapper, MCP) uses a localhost Host. */
fun Application.guardLocalHost() = intercept(ApplicationCallPipeline.Plugins) {
    if (bareHost(call.request.header(HttpHeaders.Host) ?: "") !in setOf("127.0.0.1", "localhost", "::1"))
        call.respondText("corral: local only", status = HttpStatusCode.Forbidden)
}

fun Application.corralModule(env: AppEnv) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(WebSockets)
    routing {
        webSocket("/ws/events") {
            val ev = env.events
                ?: run { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "corral: events not configured")); return@webSocket }
            val origin = call.request.header(HttpHeaders.Origin)
            if (origin != null && bareHost(origin) !in setOf("127.0.0.1", "localhost", "::1")) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "corral: local only")); return@webSocket
            }
            send(Frame.Text(ev.helloFrame()))
            val pump = launch { ev.flow.collect { send(Frame.Text(it)) } }
            try { for (frame in incoming) { /* client frames are ignored; tolerance is part of the contract */ } }
            finally { pump.cancel() }
        }
        get("/api/devices") { call.respond(env.devices()) }
        get("/api/status") {
            call.respond(StatusResponse(
                devices = env.devices().map { d -> DeviceStatus(d.serial, env.leases.snapshot[d.serial]) },
                adb = env.adbPath ?: ""
            ))
        }
        get("/api/check") {
            val r = env.leases.check(call.parameters["serial"] ?: "", call.parameters["owner"] ?: "")
            call.respond(r)
        }
        post("/api/leases") {
            val req = call.receive<ClaimRequest>()
            if (req.waitSeconds > 0) {
                val t = env.leases.enqueue(req.selector, req.owner, req.pid, req.ttlSeconds, req.waitSeconds,
                    retryAfterMs = req.retryAfterMs ?: DEFAULT_RETRY_AFTER_MS)
                call.respond(HttpStatusCode.Accepted, TicketResponse(t.id, t.pollCadenceMs))
            } else {
                try {
                    call.respond(env.leases.requestClaim(req.selector, req.owner, req.pid, req.ttlSeconds))
                } catch (e: LeaseBusyException) {
                    call.respond(HttpStatusCode.Conflict, BusyResponse(e.busy, e.matching, e.timedOut))
                }
            }
        }
        post("/api/leases/tickets/{ticket}/poll") {
            val req = call.receive<TicketPollRequest>()
            when (val p = env.leases.poll(call.parameters["ticket"]!!, req.owner, req.pid)) {
                is Poll.Offered -> call.respond(p.lease)
                is Poll.Queued -> call.respond(HttpStatusCode.Accepted, QueuedResponse("queued", p.position, p.retryAfterMs))
                is Poll.Expired -> call.respond(HttpStatusCode.Gone, mapOf("error" to when (p.reason) {
                    TicketEnd.DEADLINE -> "ticket deadline expired"
                    TicketEnd.SILENCE -> "ticket dropped: not polled in time"
                }))
                Poll.Unknown -> call.respond(HttpStatusCode.Gone, mapOf("error" to "ticket expired or unknown"))
            }
        }
        delete("/api/leases/tickets/{ticket}") {
            val owner = runCatching { call.receive<TicketCancelRequest>().owner }
                .getOrElse { call.request.queryParameters["owner"] ?: "" }
            if (owner.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "owner is required"))
            } else {
                when (env.leases.cancelTicket(call.parameters["ticket"]!!, owner)) {
                    CancelResult.CANCELLED -> call.respond(CancelledResponse(true))
                    CancelResult.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "ticket not found"))
                    CancelResult.FORBIDDEN -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to "ticket not owned by you"))
                }
            }
        }
        get("/api/queue") { call.respond(env.leases.queueSnapshot()) }
        get("/api/devices/{serial}/screenshot") {
            val serial = call.parameters["serial"]!!
            val shotter = env.screenshotter
                ?: run { call.respondText("corral: screenshots not configured", status = HttpStatusCode.ServiceUnavailable); return@get }
            val policy = env.policy ?: ScreenshotPolicy()
            val owner = call.request.queryParameters["owner"] ?: ""
            val denial = policy.denies(serial, env.leases.snapshot[serial], owner)
            if (denial != null) { call.respondText(denial, status = HttpStatusCode.Forbidden); return@get }
            try {
                call.respondBytes(shotter.capture(serial), ContentType.Image.PNG)
            } catch (e: CaptureException.UnusableDevice) {
                call.respondText(e.message ?: "device not usable", status = HttpStatusCode.NotFound)
            } catch (e: CaptureException.UnknownDevice) {
                call.respondText(e.message ?: "device not connected", status = HttpStatusCode.NotFound)
            } catch (e: CaptureException.AdbFailure) {
                call.respondText(e.message ?: "screencap failed", status = HttpStatusCode.BadGateway)
            }
        }
        delete("/api/leases/{serial}") {
            val req = call.receive<FreeRequest>()
            try { env.leases.free(call.parameters["serial"]!!, req.owner, req.force); call.respond(mapOf("freed" to true)) }
            catch (e: IllegalStateException) { call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message)) }
        }
        post("/api/leases/{serial}/renew") {
            val req = call.receive<RenewRequest>()
            try { call.respond(env.leases.renew(call.parameters["serial"]!!, req.owner, req.ttlSeconds)) }
            catch (e: IllegalStateException) { call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message)) }
        }
    }
}
