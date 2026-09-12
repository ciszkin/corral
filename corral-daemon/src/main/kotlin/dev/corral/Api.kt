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
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class Selector(val serial: String? = null, val model: String? = null,
    val minOs: String? = null, val transport: String? = null, val avd: String? = null)

@Serializable data class ClaimRequest(val selector: Selector = Selector(),
    val owner: String, val pid: Long = 0, val ttlSeconds: Long = 1800, val waitSeconds: Long = 0)

@Serializable data class FreeRequest(val owner: String, val force: Boolean = false)

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
            val sel = req.selector
            if (sel.model != null || sel.minOs != null || sel.transport != null || sel.avd != null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "selector fields beyond serial arrive in Phase 2"))
                return@post
            }
            val serial = req.selector.serial
                ?: env.devices().firstOrNull { it.state == "device" && env.leases.snapshot[it.serial] == null }?.serial
                ?: run { call.respond(HttpStatusCode.Conflict, mapOf("error" to "no free device")); return@post }
            // Phase 1 wait: internal 2s poll to deadline (MVP parity); Phase 2 replaces with FIFO queue.
            val deadline = System.currentTimeMillis() / 1000 + req.waitSeconds
            while (true) {
                try {
                    val lease = env.leases.claim(serial, req.owner, req.pid, req.ttlSeconds)
                    if (lease == null) { call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown serial $serial")); return@post }
                    call.respond(lease); return@post
                } catch (e: LeaseBusyException) {
                    if (System.currentTimeMillis() / 1000 >= deadline) { call.respond(HttpStatusCode.Conflict, mapOf("busy" to e.busy)); return@post }
                    delay(2000)
                }
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
