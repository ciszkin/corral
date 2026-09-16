package dev.corral

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class ClaimRequest(val selector: Selector = Selector(),
    val owner: String, val pid: Long = 0, val ttlSeconds: Long = 1800, val waitSeconds: Long = 0)

@Serializable data class FreeRequest(val owner: String, val force: Boolean = false)

@Serializable data class BusyResponse(val busy: List<BusyInfo>, val matching: Int, val timedOut: Boolean)

@Serializable data class RenewRequest(val owner: String, val ttlSeconds: Long = 1800)

@Serializable data class DeviceStatus(val serial: String, val lease: Lease?)

class AppEnv(val devices: () -> List<Device>, val leases: LeaseManager)

fun Application.corralModule(env: AppEnv) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    routing {
        get("/api/devices") { call.respond(env.devices()) }
        get("/api/status") {
            call.respond(mapOf("devices" to env.devices().map { d ->
                DeviceStatus(d.serial, env.leases.snapshot[d.serial])
            }))
        }
        get("/api/check") {
            val r = env.leases.check(call.parameters["serial"] ?: "", call.parameters["owner"] ?: "")
            call.respond(r)
        }
        post("/api/leases") {
            val req = call.receive<ClaimRequest>()
            try {
                call.respond(env.leases.requestClaim(req.selector, req.owner, req.pid, req.ttlSeconds, req.waitSeconds))
            } catch (e: LeaseBusyException) {
                call.respond(HttpStatusCode.Conflict, BusyResponse(e.busy, e.matching, e.timedOut))
            }
        }
        get("/api/queue") { call.respond(env.leases.queueSnapshot()) }
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
