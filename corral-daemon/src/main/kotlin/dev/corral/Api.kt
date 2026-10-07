package dev.corral

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
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
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class ClaimRequest(val selector: Selector = Selector(),
    val owner: String, val pid: Long = 0, val ttlSeconds: Long = 1800, val waitSeconds: Long = 0,
    val retryAfterMs: Long? = null)

@Serializable data class FreeRequest(val owner: String, val force: Boolean = false)

@Serializable data class AvdStatus(val avd: String, val runningSerials: List<String>, val error: String? = null)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class Started(
    @EncodeDefault val started: Boolean = true,
    @EncodeDefault val alreadyRunning: Boolean = false)

@Serializable data class Stopped(@SerialName("stopped") val serials: List<String>, val terminated: String? = null)

@Serializable data class StopBody(val force: Boolean = false)

@Serializable data class BusyResponse(val busy: List<BusyInfo>, val matching: Int, val timedOut: Boolean,
                                       val booting: Int = 0)

@Serializable data class TicketResponse(val ticket: String, val retryAfterMs: Long)

@Serializable data class TicketPollRequest(val owner: String, val pid: Long = 0)

@Serializable data class TicketCancelRequest(val owner: String)

@Serializable data class QueuedResponse(val state: String, val position: Int, val retryAfterMs: Long)

@Serializable data class CancelledResponse(val cancelled: Boolean)

@Serializable data class RenewRequest(val owner: String, val ttlSeconds: Long = 1800)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class ConfigRules(
    @EncodeDefault val readonly: List<String> = emptyList(),
    @EncodeDefault val valueflags: List<String> = emptyList(),
    @EncodeDefault val shell_skip: List<String> = emptyList(),
    @EncodeDefault val init_floor: List<String> = emptyList())

@Serializable data class ConfigResponse(val settings: List<ConfigSetting>, val rules: ConfigRules,
                                        val rulesPath: String, val envPath: String)

@Serializable data class PutConfigRequest(val settings: Map<String, String> = emptyMap(), val rules: ConfigRules? = null)

@OptIn(ExperimentalSerializationApi::class)
@Serializable data class PutConfigResponse(
    @EncodeDefault val restartRequired: Boolean = true,
    /** Rules are re-read by the adb wrapper on every invocation, so a rules-only PUT needs no restart. */
    @EncodeDefault val rulesLive: Boolean = true,
    @EncodeDefault val warnings: List<String> = emptyList())

@Serializable data class ConfigErrors(val errors: List<String>)

@Serializable data class DoctorLayer(val layer: String, val status: String, val detail: String)

@Serializable data class DoctorResponse(val adb: String, val layers: List<DoctorLayer>)

class AppEnv(
    val devices: () -> List<Device>,
    val leases: LeaseManager,
    val adbPath: String? = null,
    val screenshotter: Screenshotter? = null,
    val policy: ScreenshotPolicy? = null,
    val events: Events? = null,
    val config: DaemonConfig? = null,
    val emulators: Emulators? = null,
)

/** Any control char (C0 + DEL) smuggled into a value could inject lines or corrupt env/rules files. */
private fun String.hasControlChars() = any { it < ' ' || it == '\u007F' }

/** systemd reads `EnvironmentFile` with `\`-continuations and quote handling; the daemon reads it verbatim. */
private fun String.envUnsafe(): Boolean =
    endsWith("\\") || startsWith("\"") || endsWith("\"") || startsWith("'") || endsWith("'")

/** The writer joins entries with `,`; the parser splits on `,`/`#`/whitespace — an entry must round-trip. */
private fun String.badRulesEntry(): Boolean =
    isBlank() || hasControlChars() || any { it.isWhitespace() || it == ',' || it == '#' || it == '=' }

/** Only host-only subcommands may be exempted from the unpinned guard over the API. */
private val SAFE_READONLY = setOf("devices", "start-server", "kill-server", "track-devices", "version", "help")

/** Every local client (CLI, wrapper, MCP, UI) resolves to one of these loopback hosts. */
private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1")

/** Accepts a `Host` header (`127.0.0.1:8798`, `[::1]:8798`) or an `Origin` URL (`http://[::1]:3000`). */
private fun bareHost(value: String): String {
    val host = value.substringAfter("//", value)
    val bare = if (host.startsWith("[")) host.substringAfter("[").substringBefore("]")
               else host.substringBefore(":")
    return bare.lowercase()
}

/** The explicit port in a Host header or Origin URL (null when absent). */
private fun authorityPort(value: String): String? {
    val authority = value.substringAfter("//", value).substringBefore('/')
    val afterV6 = if (authority.startsWith("[")) authority.substringAfter(']') else authority
    return afterV6.substringAfter(':', "").takeIf { it.isNotEmpty() }
}

/** An Origin is allowed only when it is loopback AND its port matches the request Host port. */
private fun originAllowed(origin: String?, host: String?): Boolean {
    if (origin == null) return true
    if (bareHost(origin) !in LOCAL_HOSTS) return false
    return authorityPort(origin) == authorityPort(host ?: "")
}

/** Refuses, with a 403, a browser Origin that is not loopback on the request's port; true when refused. */
private suspend fun ApplicationCall.rejectsForeignOrigin(): Boolean {
    if (originAllowed(request.header(HttpHeaders.Origin), request.header(HttpHeaders.Host))) return false
    respondText("corral: local only", status = HttpStatusCode.Forbidden)
    return true
}

/** Whether a bare command name resolves to an executable on PATH. */
private fun onPath(name: String): Boolean =
    (System.getenv("PATH") ?: "").split(File.pathSeparator)
        .any { dir -> runCatching { Files.isExecutable(Path.of(dir, name)) }.getOrDefault(false) }

/**
 * Ktor decodes `%20` to a space in a path parameter but leaves a raw `+` as-is, while form-encoded
 * clients send a space as `+`. Re-read the raw segment so a bare `+` also maps to a space, without
 * turning an encoded literal `+` (`%2B`) into one.
 */
private fun ApplicationCall.emulatorAvd(): String {
    val decoded = parameters["avd"]!!
    if ('+' !in decoded) return decoded
    val segments = request.local.uri.substringBefore('?').split('/')
    val i = segments.indexOfLast { it == "emulators" }
    val raw = if (i < 0) null else segments.getOrNull(i + 1)
    return raw?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8) }.getOrNull() } ?: decoded
}

/** Browser DNS-rebinding guard: every local client (CLI, wrapper, MCP) uses a localhost Host. */
fun Application.guardLocalHost() = intercept(ApplicationCallPipeline.Plugins) {
    if (bareHost(call.request.header(HttpHeaders.Host) ?: "") !in LOCAL_HOSTS)
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
            if (origin != null && bareHost(origin) !in LOCAL_HOSTS) {
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
                devices = env.devices().map { d -> DeviceStatus(d.serial, env.leases.snapshot[d.serial], d.booted) },
                adb = env.adbPath ?: ""
            ))
        }
        get("/api/doctor") {
            val adbPath = env.adbPath ?: "adb"
            val rulesPath = RulesFile.defaultPath()
            val rules = RulesFile.load()
            val (shimStatus, shimDetail) = SdkShim.probe(Path.of(adbPath))
            val nonHostOnly = rules.readonly - SAFE_READONLY
            val identityDiverged = rules.shellSkip != DEFAULT_SHELL_SKIP || rules.initFloor != DEFAULT_INIT_FLOOR
            val reasons = buildList {
                if (nonHostOnly.isNotEmpty()) add("lists non-host-only readonly ${nonHostOnly.sorted().joinToString(", ")}")
                if (identityDiverged) add("identity lists differ from the defaults")
            }
            val rulesLayer = when {
                !rulesPath.toFile().exists() -> DoctorLayer("rules", "fail", "$rulesPath missing")
                rules.readonly.isEmpty() || rules.valueFlags.isEmpty() ->
                    DoctorLayer("rules", "fail", "$rulesPath has no readonly or valueflags list")
                reasons.isNotEmpty() -> DoctorLayer("rules", "warn", "$rulesPath ${reasons.joinToString("; ")}")
                else -> DoctorLayer("rules", "ok", "$rulesPath (${rules.readonly.size} readonly)")
            }
            call.respond(DoctorResponse(adbPath, listOf(
                DoctorLayer("daemon", "ok", "serving ${env.devices().size} device(s)"),
                DoctorLayer("sdk-shim", shimStatus, shimDetail),
                rulesLayer)))
        }
        get("/api/check") {
            val r = env.leases.check(call.parameters["serial"] ?: "", call.parameters["owner"] ?: "")
            call.respond(r)
        }
        post("/api/leases") {
            if (call.rejectsForeignOrigin()) return@post
            val req = call.receive<ClaimRequest>()
            if (req.waitSeconds > 0) {
                val t = env.leases.enqueue(req.selector, req.owner, req.pid, req.ttlSeconds, req.waitSeconds,
                    retryAfterMs = req.retryAfterMs ?: DEFAULT_RETRY_AFTER_MS)
                call.respond(HttpStatusCode.Accepted, TicketResponse(t.id, t.pollCadenceMs))
            } else {
                try {
                    call.respond(env.leases.requestClaim(req.selector, req.owner, req.pid, req.ttlSeconds))
                } catch (e: LeaseBusyException) {
                    call.respond(HttpStatusCode.Conflict, BusyResponse(e.busy, e.matching, e.timedOut, e.booting))
                }
            }
        }
        post("/api/leases/tickets/{ticket}/poll") {
            if (call.rejectsForeignOrigin()) return@post
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
            if (call.rejectsForeignOrigin()) return@delete
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
        get("/api/config") {
            val cfg = env.config ?: run {
                call.respondText("corral: config not wired", status = HttpStatusCode.ServiceUnavailable); return@get }
            val r = RulesFile.load()
            call.respond(ConfigResponse(
                cfg.settings(),
                ConfigRules(r.readonly.sorted(), r.valueFlags.sorted(), r.shellSkip.sorted(), r.initFloor.sorted()),
                RulesFile.defaultPath().toString(), DaemonConfig.defaultPath().toString()))
        }
        put("/api/config") {
            if (call.rejectsForeignOrigin()) return@put
            env.config ?: run {
                call.respondText("corral: config not wired", status = HttpStatusCode.ServiceUnavailable); return@put }
            val req = try {
                call.receive<PutConfigRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // decode failures must teach, not sit mute (the Windows smoke hit a bare 4xx here)
                val malformed = e is JsonConvertException || e.cause is JsonConvertException
                if (malformed) {
                    val why = (e as? JsonConvertException ?: e.cause as? JsonConvertException)
                        ?.message?.lineSequence()?.firstOrNull()
                    call.respond(HttpStatusCode.BadRequest, ConfigErrors(listOf(
                        "malformed JSON body" + (why?.let { ": $it" } ?: ""))))
                } else {
                    call.respond(HttpStatusCode.UnsupportedMediaType, ConfigErrors(listOf(
                        "send Content-Type: application/json (PowerShell: Invoke-RestMethod -ContentType 'application/json')")))
                }
                return@put
            }
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val clean = mutableMapOf<String, String>()
            var rulesToWrite: Pair<Set<String>?, Set<String>?>? = null
            req.settings.forEach { (k, raw) ->
                when (k) {
                    "CORRAL_PORT" -> parsePort(raw.trim())?.let { clean[k] = it.toString() }
                        ?: run { errors += "CORRAL_PORT must be 1024–65535" }
                    "CORRAL_SCREENSHOTS" -> raw.trim().lowercase().takeIf { it == "strict" || it == "open" }
                        ?.let { clean[k] = it } ?: run { errors += "CORRAL_SCREENSHOTS must be strict or open" }
                    "CORRAL_EMULATOR_LAUNCHER" -> raw.trim().lowercase()
                        .takeIf { it == "" || it == "systemd" || it == "direct" }
                        ?.let { clean[k] = it }
                        ?: run { errors += "CORRAL_EMULATOR_LAUNCHER must be blank, systemd or direct" }
                    "CORRAL_SCREENSHOT_OBSERVERS" -> {
                        val names = raw.split(',').map(String::trim).filter(String::isNotEmpty)
                        when {
                            names.any { it.hasControlChars() } ->
                                errors += "CORRAL_SCREENSHOT_OBSERVERS must not contain control characters"
                            names.any { it.envUnsafe() } ->
                                errors += "$k must not end with '\\' or start/end with a quote"
                            else -> clean[k] = names.distinct().joinToString(",")
                        }
                    }
                    "CORRAL_ADB" -> raw.trim().takeIf { it.isNotEmpty() }
                        ?.let {
                            when {
                                it.envUnsafe() -> errors += "$k must not end with '\\' or start/end with a quote"
                                it.hasControlChars() -> errors += "CORRAL_ADB must not contain control characters"
                                adbValueUsable(it) -> {
                                    clean["CORRAL_ADB"] = it
                                    if (!it.contains('/') && !it.contains('\\') && !onPath(it))
                                        warnings += "CORRAL_ADB '$it' is not on PATH — device discovery will fail"
                                }
                                else -> errors += "CORRAL_ADB must be an existing executable path or a bare command name"
                            }
                        } ?: run { errors += "CORRAL_ADB must be a non-blank path" }
                    "CORRAL_EMULATOR" -> {
                        val v = raw.trim()
                        when {
                            v.isEmpty() -> clean[k] = ""   // blank clears it: resolve from the SDK sibling/PATH
                            v.envUnsafe() -> errors += "$k must not end with '\\' or start/end with a quote"
                            v.hasControlChars() -> errors += "CORRAL_EMULATOR must not contain control characters"
                            adbValueUsable(v) -> {
                                clean["CORRAL_EMULATOR"] = v
                                if (!v.contains('/') && !v.contains('\\') && !onPath(v))
                                    warnings += "CORRAL_EMULATOR '$v' is not on PATH — emulator start will fail"
                            }
                            else -> errors += "CORRAL_EMULATOR must be an existing executable path or a bare command name"
                        }
                    }
                    "CORRAL_DB" -> raw.trim().takeIf { it.isNotEmpty() }
                        ?.let {
                            when {
                                it.envUnsafe() -> errors += "$k must not end with '\\' or start/end with a quote"
                                it.hasControlChars() -> errors += "CORRAL_DB must not contain control characters"
                                dbPathUsable(it) -> clean["CORRAL_DB"] = it
                                else -> errors += "CORRAL_DB must be an absolute path under a writable directory"
                            }
                        } ?: run { errors += "CORRAL_DB must be a non-blank path" }
                    "CORRAL_GRADLE_TASKS" -> raw.trim()
                        .let {
                            when {
                                it.hasControlChars() -> errors += "$k must not contain control characters"
                                it.envUnsafe() -> errors += "$k must not end with '\\' or start/end with a quote"
                                else -> clean[k] = it
                            }
                        }
                    else -> {}   // unknown keys ignored by design
                }
            }
            req.rules?.let { r ->
                val badEntries = listOf("readonly" to r.readonly, "valueflags" to r.valueflags,
                    "shell_skip" to r.shell_skip, "init_floor" to r.init_floor)
                    .flatMap { (name, l) -> l.filter { it.badRulesEntry() }.map { "$name='$it'" } }
                if (badEntries.isNotEmpty())
                    errors += "rules entries must be non-blank and free of whitespace, ',' '#' '=' (${badEntries.joinToString(", ")})"
                if (r.valueflags.any { !it.startsWith("-") })
                    errors += "valueflags entries must start with '-' (${r.valueflags.filter { !it.startsWith("-") }.joinToString(", ") { "'$it'" }})"
                if (r.valueflags.any { it.startsWith("-s") })
                    errors += "-s is the pin, not a value flag"

                val current = RulesFile.load()
                // an empty list or one equal to the file's list means "no change" — a UI echo-back
                // is a silent no-op, so only a real change is validated and written
                val readonly = r.readonly.map(String::trim).filter(String::isNotEmpty).toSet()
                    .takeIf { it.isNotEmpty() && it != current.readonly }
                val valueflags = r.valueflags.map(String::trim).filter(String::isNotEmpty).toSet()
                    .takeIf { it.isNotEmpty() && it != current.valueFlags }
                val shellSkip = r.shell_skip.map(String::trim).filter(String::isNotEmpty).toSet()
                val initFloor = r.init_floor.map(String::trim).filter(String::isNotEmpty).toSet()

                // readonly: only a CHANGE that introduces a non-host-only command is refused
                readonly?.let {
                    val nonHostOnly = it - SAFE_READONLY
                    if (nonHostOnly.isNotEmpty())
                        errors += "readonly must only list host-only commands over the API (${nonHostOnly.sorted().joinToString(", ")}) — edit ${RulesFile.defaultPath()} to exempt a device-touching command deliberately"
                }
                // identity lists: file-only; any real (non-empty, changed) list is refused — an omitted/empty/echoed one is a no-op
                if (shellSkip.isNotEmpty() && shellSkip != current.shellSkip)
                    errors += "shell_skip is file-only; edit ${RulesFile.defaultPath()} to change the identity walk"
                if (initFloor.isNotEmpty() && initFloor != current.initFloor)
                    errors += "init_floor is file-only; edit ${RulesFile.defaultPath()} to change the identity walk"
                rulesToWrite = readonly to valueflags
            }
            if (errors.isNotEmpty()) { call.respond(HttpStatusCode.BadRequest, ConfigErrors(errors)); return@put }
            if (clean.isNotEmpty()) EnvFile.write(DaemonConfig.defaultPath(), clean)
            rulesToWrite?.let { (readonly, valueflags) ->
                if (readonly != null || valueflags != null) RulesFile.writeGate(readonly, valueflags) }
            call.respond(PutConfigResponse(
                restartRequired = clean.isNotEmpty(), rulesLive = req.rules != null, warnings = warnings))
        }
        get("/api/devices/{serial}/screenshot") {
            val serial = call.parameters["serial"]!!
            val shotter = env.screenshotter
                ?: run { call.respondText("corral: screenshots not configured", status = HttpStatusCode.ServiceUnavailable); return@get }
            val policy = env.policy ?: env.config?.let(::screenshotPolicy) ?: ScreenshotPolicy()
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
        get("/api/emulators") {
            val e = env.emulators ?: run {
                call.respondText("corral: emulators not configured", status = HttpStatusCode.ServiceUnavailable); return@get }
            val list = e.list() ?: run {
                // a missing binary is not "no AVDs" — the Windows smoke read that lie twice
                call.respondText("corral: emulator binary not found — set CORRAL_EMULATOR (or place it beside the SDK adb)",
                    status = HttpStatusCode.BadGateway); return@get }
            val running = env.devices().filter { it.transport == "emulator" }
            call.respond(list.map { avd ->
                val serials = running.filter { it.avd == avd }.map { it.serial }
                AvdStatus(avd, serials, error = if (serials.isEmpty()) e.lastError(avd) else null)
            })
        }
        post("/api/emulators/{avd}/start") {
            if (call.rejectsForeignOrigin()) return@post
            val e = env.emulators ?: run {
                call.respondText("corral: emulators not configured", status = HttpStatusCode.ServiceUnavailable); return@post }
            val avd = call.emulatorAvd()
            val running = env.devices().filter { it.transport == "emulator" && it.avd == avd }
            val avds = e.list()
            when {
                running.isNotEmpty() || e.isRunning(avd) -> call.respond(Started(alreadyRunning = true))
                avds == null ->
                    call.respondText("corral: emulator binary not usable — set CORRAL_EMULATOR", status = HttpStatusCode.BadGateway)
                avd !in avds ->
                    call.respondText("corral: unknown avd $avd — known: ${avds.joinToString(", ")}", status = HttpStatusCode.NotFound)
                else -> try {
                    if (e.start(avd)) call.respond(HttpStatusCode.Accepted, Started())
                    else call.respond(Started(alreadyRunning = true))
                } catch (ex: EmulatorException) {
                    call.respondText("corral: ${ex.message}", status = HttpStatusCode.BadGateway)
                }
            }
        }
        post("/api/emulators/{avd}/stop") {
            if (call.rejectsForeignOrigin()) return@post
            val e = env.emulators ?: run {
                call.respondText("corral: emulators not configured", status = HttpStatusCode.ServiceUnavailable); return@post }
            val avd = call.emulatorAvd()
            val force = call.receive<StopBody>().force
            val serials = env.devices().filter { it.transport == "emulator" && it.avd == avd }.map { it.serial }
            val held = serials.firstNotNullOfOrNull { env.leases.snapshot[it]?.owner }
            if (held != null && !force) {
                call.respondText("corral: $avd is leased by $held — free it first, or POST {\"force\":true}",
                    status = HttpStatusCode.Conflict); return@post
            }
            try {
                serials.forEach { e.stop(it); env.leases.free(it, owner = "", force = true) }
            } catch (ex: EmulatorException) {
                // end the unit/process even when emu kill failed (wedged console), so the AVD
                // doesn't stay stuck as "already running" with no way to stop it
                e.stopAvd(avd)
                call.respondText("corral: ${ex.message}", status = HttpStatusCode.BadGateway)
                return@post
            }
            call.respond(Stopped(serials, e.stopAvd(avd)))
        }
        delete("/api/leases/{serial}") {
            if (call.rejectsForeignOrigin()) return@delete
            val req = call.receive<FreeRequest>()
            try { env.leases.free(call.parameters["serial"]!!, req.owner, req.force); call.respond(mapOf("freed" to true)) }
            catch (e: IllegalStateException) { call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message)) }
        }
        post("/api/leases/{serial}/renew") {
            if (call.rejectsForeignOrigin()) return@post
            val req = call.receive<RenewRequest>()
            try { call.respond(env.leases.renew(call.parameters["serial"]!!, req.owner, req.ttlSeconds)) }
            catch (e: IllegalStateException) { call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message)) }
        }
    }
}
