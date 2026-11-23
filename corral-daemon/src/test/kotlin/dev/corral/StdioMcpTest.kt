package dev.corral

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `--stdio` is the Glama transport: the same MCP surface as `/mcp`, on the process's own
 * stdin/stdout. This drives a real child JVM rather than the server factory, so it pins the whole
 * process contract at once — the flag is what the jar is started with, no TCP port opens, and
 * closing stdin ends the session cleanly.
 */
class StdioMcpTest {
    private val devices = listOf(Device("dev1", "device"))
    private val expectedTools = setOf(
        "list_devices", "queue", "claim", "free", "renew", "screenshot", "start_emulator", "stop_emulator")

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun javaBinary(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    @Test
    fun stdioServesTheSameEightToolsAsHttpAndOpensNoPort() = testApplication {
        testDaemon(devices)
        val httpTools = httpToolNames()

        val home = createTempDirectory("stdio-home")
        val port = freePort()
        val proc = ProcessBuilder(javaBinary(), "-Duser.home=$home",
                "-cp", System.getProperty("java.class.path"), "dev.corral.MainKt", "--stdio")
            .apply {
                environment()["CORRAL_PORT"] = port.toString()
                environment()["CORRAL_ADB"] = "/nonexistent/adb"
                environment()["CORRAL_ENV_FILE"] = home.resolve("daemon.env").toString()
                environment()["CORRAL_RULES"] = home.resolve("rules").toString()
            }
            .start()
        val stderr = StringBuilder()
        Thread {
            proc.errorStream.bufferedReader().forEachLine { synchronized(stderr) { stderr.appendLine(it) } }
        }.apply { isDaemon = true; start() }
        val lines = LinkedBlockingQueue<String>()
        Thread {
            proc.inputStream.bufferedReader().forEachLine { lines.put(it) }
        }.apply { isDaemon = true; start() }

        try {
            fun rpc(id: Int, method: String, params: String = "{}") {
                val request = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""" + "\n"
                proc.outputStream.write(request.toByteArray())
                proc.outputStream.flush()
            }
            fun await(id: Int): kotlinx.serialization.json.JsonObject {
                while (true) {
                    val line = lines.poll(30, TimeUnit.SECONDS)
                        ?: fail("no JSON-RPC response for id $id; stderr:\n$stderr")
                    val message = Json.parseToJsonElement(line).jsonObject
                    if (message["id"]?.jsonPrimitive?.int == id) return message
                }
            }

            rpc(1, "initialize",
                """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"stdio-harness","version":"1"}}""")
            val init = await(1)["result"]!!.jsonObject
            assertEquals("corral", init["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals(VersionSource.version,
                init["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content)
            assertEquals("2025-06-18", init["protocolVersion"]!!.jsonPrimitive.content)

            rpc(2, "tools/list")
            val stdioTools = await(2)["result"]!!.jsonObject["tools"]!!.jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            assertEquals(expectedTools, stdioTools)
            assertEquals(httpTools, stdioTools)               // identical to the /mcp surface

            // No TCP listener: the port HTTP mode would have taken refuses connections.
            val refused = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
            }.exceptionOrNull()
            assertIs<ConnectException>(refused, "stdio mode must not listen on CORRAL_PORT=$port")

            // A stdio server is a child process: HOME is neither written nor self-healed.
            assertFalse(Files.exists(home.resolve(".corral")), "stdio mode wrote a db under HOME")
            assertFalse(Files.exists(home.resolve(".config")), "stdio mode wrote config under HOME")
            assertFalse(Files.exists(home.resolve("rules")), "stdio mode self-healed the gate rules")

            // EOF ends the session, and the process exits without a shutdown exception.
            proc.outputStream.close()
            assertTrue(proc.waitFor(30, TimeUnit.SECONDS), "daemon did not exit on stdin EOF; stderr:\n$stderr")
            assertEquals(0, proc.exitValue(), "stderr:\n$stderr")
        } finally {
            proc.destroyForcibly()
        }
    }

    /** The HTTP route's own answer, so the stdio list is compared with the shipped surface. */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.httpToolNames(): Set<String> {
        val init = client.post("/mcp") {
            header(HttpHeaders.Host, "127.0.0.1:8798")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"http-harness","version":"1"}}}""")
        }
        val sid = init.headers["mcp-session-id"]!!
        val body = client.post("/mcp") {
            header(HttpHeaders.Host, "127.0.0.1:8798")
            header("mcp-session-id", sid)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
        }.bodyAsText()
        return Json.parseToJsonElement(body).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
    }
}
