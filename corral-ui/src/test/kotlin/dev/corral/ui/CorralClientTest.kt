package dev.corral.ui

import dev.corral.Device
import dev.corral.DeviceStatus
import dev.corral.QueueEntry
import dev.corral.StatusResponse
import dev.corral.corralJson
import dev.corral.ui.data.ConfigRules
import dev.corral.ui.data.ConfigSetting
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CorralClientTest {
    private val freed = mutableListOf<Pair<String, String>>()

    private fun ApplicationTestBuilder.cannedServer() {
        application {
            install(ServerWebSockets)
            install(ServerContentNegotiation) { json(corralJson) }
            routing {
                get("/api/status") { call.respond(StatusResponse(listOf(DeviceStatus("dev1", null)), "/fake/adb")) }
                get("/api/devices") {
                    call.respondText(
                        """[{"serial":"dev1","state":"device","transport":"usb","model":"Pixel 6","osVersion":"13","avd":null}]""",
                        ContentType.Application.Json)
                }
                get("/api/queue") { call.respond(listOf(QueueEntry(1, "w", "[any]", 0, 600))) }
                get("/api/devices/{serial}/screenshot") {
                    if (call.parameters["serial"] == "denied") { call.respond(HttpStatusCode.Forbidden); return@get }
                    assertEquals("dev1", call.parameters["serial"])
                    assertEquals("ui-owner", call.request.queryParameters["owner"])
                    call.respondBytes("png".encodeToByteArray(), ContentType.Image.PNG)
                }
                delete("/api/leases/{serial}") {
                    if (call.parameters["serial"] == "denied") { call.respond(HttpStatusCode.Conflict); return@delete }
                    freed += call.parameters["serial"]!! to call.receiveText()
                    call.respond(HttpStatusCode.OK)
                }
                get("/api/config") {
                    call.respondText(
                        """{"settings":[{"key":"CORRAL_PORT","value":"8798","default":"8798","restartRequired":true}],""" +
                        """"rules":{"readonly":["devices"],"valueflags":["-s"],"shell_skip":[],"init_floor":[]},""" +
                        """"rulesPath":"/fake/rules","envPath":"/fake/env"}""",
                        ContentType.Application.Json)
                }
                put("/api/config") {
                    val payload = call.receiveText()
                    when {
                        payload.contains("\"CORRAL_PORT\":\"80\"") ->
                            call.respondText("""{"errors":["CORRAL_PORT must be 1024–65535"]}""",
                                ContentType.Application.Json, HttpStatusCode.BadRequest)
                        payload.contains("\"CORRAL_PORT\":\"81\"") ->
                            call.respondText("corral: bad request", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                        payload.contains("\"CORRAL_PORT\":\"82\"") ->
                            call.respondText("corral: local only", status = HttpStatusCode.Forbidden)
                        else ->
                            call.respondText("""{"restartRequired":true,"rulesLive":false,"warnings":["CORRAL_ADB not on PATH"]}""",
                                ContentType.Application.Json)
                    }
                }
                get("/api/doctor") {
                    call.respondText(
                        """{"adb":"/fake/adb","layers":[{"layer":"daemon","status":"ok","detail":"serving 1 device(s)"},""" +
                        """{"layer":"sdk-shim","status":"warn","detail":"shim missing"}]}""",
                        ContentType.Application.Json)
                }
                webSocket("/ws/events") {
                    send(Frame.Text("""{"type":"hello","resources":["devices","queue"],"seq":0}"""))
                }
            }
        }
    }

    private fun ApplicationTestBuilder.client(): CorralClient {
        cannedServer()
        val http = createClient { corralClientConfig() }
        return CorralClient("", "ui-owner", http)
    }

    @Test fun statusParsesResponse() = testApplication {
        val s = client().status()
        assertEquals("/fake/adb", s.adb)
        assertEquals("dev1", s.devices.single().serial)
    }

    @Test fun queueParsesEntries() = testApplication {
        assertEquals(listOf(QueueEntry(1, "w", "[any]", 0, 600)), client().queue())
    }

    @Test fun devicesParsesInventory() = testApplication {
        assertEquals(
            Device("dev1", "device", "usb", "Pixel 6", "13", null),
            client().devices().single(),
        )
    }

    @Test fun screenshotSendsOwnerAndReturnsBytes() = testApplication {
        assertEquals("png", client().screenshot("dev1").decodeToString())
    }

    @Test fun forceFreeSendsDeleteWithForceBody() = testApplication {
        client().forceFree("dev1")
        assertEquals(listOf("dev1" to """{"owner":"ui-owner","force":true}"""), freed)
    }

    @Test fun eventsFirstFrameIsHello() = testApplication {
        assertTrue(client().events().first().contains("\"hello\""))
    }

    @Test fun capabilitiesAllowForceFree() = testApplication {
        assertEquals(Capabilities(canForceFree = true), client().capabilities())
    }

    @Test fun configParsesResponse() = testApplication {
        val c = client().config()
        assertEquals(ConfigSetting("CORRAL_PORT", "8798", "8798", true), c.settings.single())
        assertEquals(ConfigRules(listOf("devices"), listOf("-s")), c.rules)
        assertEquals("/fake/rules", c.rulesPath)
        assertEquals("/fake/env", c.envPath)
    }

    @Test fun putConfigSuccessCarriesWarnings() = testApplication {
        val res = client().putConfig(mapOf("CORRAL_ADB" to "adb"), null)
        assertEquals(listOf("CORRAL_ADB not on PATH"), res.getOrThrow().warnings)
    }

    @Test fun putConfigBadRequestBecomesConfigApiException() = testApplication {
        val e = client().putConfig(mapOf("CORRAL_PORT" to "80"), null).exceptionOrNull()
        assertTrue(e is ConfigApiException)
        assertEquals(listOf("CORRAL_PORT must be 1024–65535"), (e as ConfigApiException).errors)
    }

    @Test fun putConfigNonJsonBadRequestStaysInResult() = testApplication {
        val e = client().putConfig(mapOf("CORRAL_PORT" to "81"), null).exceptionOrNull()
        assertTrue(e is ConfigApiException)
        assertTrue((e as ConfigApiException).errors.single().isNotEmpty())
    }

    @Test fun putConfigNonBadRequest4xxRethrows() = testApplication {
        assertFailsWith<ClientRequestException> { client().putConfig(mapOf("CORRAL_PORT" to "82"), null) }
    }

    @Test fun doctorParsesLayers() = testApplication {
        val d = client().doctor()
        assertEquals("/fake/adb", d.adb)
        assertEquals(listOf("daemon", "sdk-shim"), d.layers.map { it.layer })
        assertEquals(listOf("ok", "warn"), d.layers.map { it.status })
    }

    @Test fun screenshotForbiddenSurfacesAsPolicyDenial() = testApplication {
        assertFailsWith<ScreenshotDeniedException> { client().screenshot("denied") }
    }

    @Test fun forceFreeConflictThrows() = testApplication {
        assertFailsWith<ClientRequestException> { client().forceFree("denied") }
    }

    @Test fun bareBaseUrlGainsTheHttpScheme() {
        assertEquals("http://localhost:8798", "localhost:8798".httpBaseUrl())
    }

    @Test fun explicitSchemesPassThroughUntouched() {
        assertEquals("http://h:1", "http://h:1".httpBaseUrl())
        assertEquals("https://h:1", "https://h:1".httpBaseUrl())
    }

    @Test fun emptyBaseUrlStaysRelative() {   // the embedded-server seam used by these tests
        assertEquals("", "".httpBaseUrl())
    }
}
