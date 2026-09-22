package dev.corral.ui

import dev.corral.DeviceStatus
import dev.corral.QueueEntry
import dev.corral.StatusResponse
import dev.corral.corralJson
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
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
