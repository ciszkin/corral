package dev.corral.ui

import dev.corral.Device
import dev.corral.QueueEntry
import dev.corral.StatusResponse
import dev.corral.UpdateInfo
import dev.corral.corralJson
import dev.corral.ui.data.ConfigErrors
import dev.corral.ui.data.ConfigResponse
import dev.corral.ui.data.ConfigRules
import dev.corral.ui.data.DoctorResponse
import dev.corral.ui.data.PutConfigRequest
import dev.corral.ui.data.PutConfigResponse
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable private data class FreeBody(val owner: String, val force: Boolean)

@Serializable private data class StopBody(val force: Boolean)

/** Shared client config: non-2xx throws, so callers (the store) can surface policy denials. */
internal fun HttpClientConfig<*>.corralClientConfig() {
    expectSuccess = true
    install(ContentNegotiation) { json(corralJson) }
    install(WebSockets)
}

/** The one real CorralApi: ktor CIO + websockets against a daemon base URL. */
class CorralClient internal constructor(
    baseUrl: String,
    private val owner: String,
    private val http: HttpClient,
) : CorralApi {
    private val baseUrl = baseUrl.httpBaseUrl().trimEnd('/')
    private val wsBase = baseUrl.websocketBase()

    constructor(baseUrl: String, owner: String) : this(baseUrl, owner, HttpClient(CIO) { corralClientConfig() })

    override suspend fun status(): StatusResponse = http.get("$baseUrl/api/status").body()
    override suspend fun devices(): List<Device> = http.get("$baseUrl/api/devices").body()
    override suspend fun queue(): List<QueueEntry> = http.get("$baseUrl/api/queue").body()
    override suspend fun screenshot(serial: String): ByteArray = try {
        http.get("$baseUrl/api/devices/${serial.encodeURLParameter()}/screenshot?owner=${owner.encodeURLParameter()}").bodyAsBytes()
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.Forbidden) {
            val reason = try {
                e.response.bodyAsText().trim().takeIf(String::isNotEmpty)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                null
            }
            throw ScreenshotDeniedException(reason, e)
        } else throw e
    }

    override suspend fun forceFree(serial: String) {
        http.delete("$baseUrl/api/leases/${serial.encodeURLParameter()}") {
            contentType(ContentType.Application.Json)
            setBody(FreeBody(owner, force = true))
        }
    }

    override suspend fun config(): ConfigResponse = http.get("$baseUrl/api/config").body()

    override suspend fun putConfig(settings: Map<String, String>, rules: ConfigRules?): Result<PutConfigResponse> = try {
        Result.success(http.put("$baseUrl/api/config") {
            contentType(ContentType.Application.Json)
            setBody(PutConfigRequest(settings, rules))
        }.body())
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.BadRequest) {
            val errors = runCatching { e.response.body<ConfigErrors>().errors }
                .getOrElse { listOf(e.message ?: "bad request") }
            Result.failure(ConfigApiException(errors, e))
        } else throw e
    }

    override suspend fun update(force: Boolean): UpdateInfo = http.get("$baseUrl/api/update") {
        if (force) parameter("force", "1")
    }.body()

    override suspend fun doctor(): DoctorResponse = http.get("$baseUrl/api/doctor").body()

    override suspend fun emulators(): List<AvdStatus> = http.get("$baseUrl/api/emulators").body()

    override suspend fun emulatorStart(avd: String) {
        http.post("$baseUrl/api/emulators/${avd.encodeURLParameter()}/start")
    }

    override suspend fun emulatorStop(avd: String, force: Boolean) {
        http.post("$baseUrl/api/emulators/${avd.encodeURLParameter()}/stop") {
            contentType(ContentType.Application.Json)
            setBody(StopBody(force))
        }
    }

    override fun events(): Flow<String> = flow {
        val ws = http.webSocketSession { url("$wsBase/ws/events") }
        try { for (frame in ws.incoming) if (frame is Frame.Text) emit(frame.readText()) }
        finally { withContext(NonCancellable) { ws.close() } }
    }

    override fun capabilities(): Capabilities = Capabilities(canForceFree = true)   // the future's only swap point

    override fun close() { http.close() }
}

/** A bare `CORRAL_URL=host:port` would make every request URL unparseable — default the scheme. */
internal fun String.httpBaseUrl() = when {
    isEmpty() || startsWith("http") -> this
    else -> "http://$this"
}

private fun String.websocketBase() = when {
    startsWith("https") -> "wss${removePrefix("https")}"
    startsWith("http") -> "ws${removePrefix("http")}"
    else -> this
}
