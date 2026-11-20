package dev.corral

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

/**
 * I9 — the write barrier is server-side. In demo mode the config route refuses the knobs that would
 * walk the demo into I1 (a real adb/emulator), I3 (a DB outside the demo home) or I5 (an update
 * check), and writes nothing. The same requests must stay writable on a real daemon, so the refusal
 * cannot hide a regression.
 */
class DemoApiTest {
    private val devices = { listOf(Device("dev1", "device", model = "Pixel_6")) }

    @BeforeTest @AfterTest fun cleanConfigFixtures() {
        Files.deleteIfExists(RulesFile.defaultPath())
        Files.deleteIfExists(DaemonConfig.defaultPath())
    }

    private fun ApplicationTestBuilder.corral(demo: Boolean, config: DaemonConfig) =
        application { corralModule(AppEnv(devices, LeaseManager(devices, db = null), config = config, demo = demo)) }

    private fun withApi(
        demo: Boolean,
        config: DaemonConfig = DaemonConfig(fileValues = emptyMap(), inherited = emptyMap()),
        block: suspend CoroutineScope.(HttpClient) -> Unit,
    ) = testApplication {
        corral(demo, config)
        CoroutineScope(coroutineContext).block(client)
    }

    private suspend fun HttpClient.putConfig(json: String) =
        put("/api/config") { contentType(ContentType.Application.Json); setBody(json) }

    @Test fun demoRefusesThePathBearingKnobsAndWritesNothing() = withApi(demo = true) { c ->
        val before = Files.exists(DaemonConfig.defaultPath())
        for ((key, value) in listOf(
            "CORRAL_ADB" to "/usr/bin/adb",
            "CORRAL_EMULATOR" to "/usr/bin/emulator",
            "CORRAL_DB" to Path_slash_tmp_db(),
            "CORRAL_UPDATE_CHECK" to "on",
        )) {
            val r = c.putConfig("""{"settings":{"$key":"$value"}}""")
            assertEquals(HttpStatusCode.BadRequest, r.status, "$key must be refused in demo mode")
            val err = Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.joinToString(" ")
            assertContains(err, key)
            assertContains(err, "demo mode")
            assertFalse(Files.exists(DaemonConfig.defaultPath()), "$key must write nothing")
        }
        assertEquals(before, Files.exists(DaemonConfig.defaultPath()))
    }

    /**
     * D10 — every knob the plan names as path-bearing is refused in demo mode, each with a message
     * that names demo mode, and the config file is byte-identical afterwards. `CORRAL_RULES` and
     * `CORRAL_ENV_FILE` are unregistered keys (refused as "unknown setting" on a real daemon); in
     * demo mode they must be refused for the *demo* reason, or an operator would be told the key
     * does not exist while the demo is the thing saying no.
     */
    @Test fun demoRefusesEveryPathBearingKnobAndLeavesTheConfigFileByteIdentical() = withApi(demo = true) { c ->
        val path = DaemonConfig.defaultPath()
        Files.createDirectories(path.parent)
        val seeded = "# seeded by the demo suite\nCORRAL_SCREENSHOTS=strict\n"
        Files.writeString(path, seeded)
        for ((key, value) in listOf(
            "CORRAL_ADB" to "/usr/bin/adb",
            "CORRAL_EMULATOR" to "/usr/bin/emulator",
            "CORRAL_RULES" to "/usr/bin/rules",
            "CORRAL_ENV_FILE" to "/usr/bin/daemon.env",
            "CORRAL_DB" to Path_slash_tmp_db(),
            "CORRAL_UPDATE_CHECK" to "on",
        )) {
            val r = c.putConfig("""{"settings":{"$key":"$value"}}""")
            assertEquals(HttpStatusCode.BadRequest, r.status, "$key must be refused in demo mode")
            val err = Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.joinToString(" ")
            assertContains(err, key)
            assertContains(err, "demo mode")
            assertEquals(seeded, Files.readString(path), "$key must not touch the config file")
        }
    }

    @Test fun demoKeepsShellSkipAndInitFloorFileOnly() = withApi(demo = true) { c ->
        val path = DaemonConfig.defaultPath()
        Files.createDirectories(path.parent)
        val seeded = "CORRAL_SCREENSHOTS=strict\n"
        Files.writeString(path, seeded)
        val rulesBefore = runCatching { Files.readString(RulesFile.defaultPath()) }.getOrNull()
        val r = c.putConfig("""{"rules":{"shell_skip":["bash","sh"]}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val err = Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.joinToString(" ")
        assertContains(err, "shell_skip")
        assertContains(err, "file-only")
        assertEquals(seeded, Files.readString(path), "a refused body writes nothing")
        assertEquals(rulesBefore, runCatching { Files.readString(RulesFile.defaultPath()) }.getOrNull(),
            "the rules file is file-only in demo mode too")
    }

    /** D9 — the machine-readable label on every payload a client decodes. */
    @Test fun demoLabelsTheDevicesStatusAndConfigPayloads() = withApi(demo = true) { c ->
        val devices = c.get("/api/devices").bodyAsText()
        assertEquals(1, Regex("\"demo\":true").findAll(devices).count(), "every device is labelled: $devices")
        assertContains(c.get("/api/status").bodyAsText(), "\"demo\":true")
        assertContains(c.get("/api/config").bodyAsText(), "\"demo\":true")
    }

    /** The same payloads from a real daemon must not carry the label — that is what makes it a label. */
    @Test fun aRealDaemonCarriesNoDemoLabelAnywhere() = withApi(demo = false) { c ->
        assertFalse(c.get("/api/devices").bodyAsText().contains("\"demo\""))
        assertFalse(c.get("/api/status").bodyAsText().contains("\"demo\""))
        assertFalse(c.get("/api/config").bodyAsText().contains("\"demo\""))
    }

    @Test fun demoDoctorReportsTheDemoLayerAndSkipsTheGateLayersNeverFails() = withApi(demo = true) { c ->
        val layers = Json { ignoreUnknownKeys = true }
            .decodeFromString<DoctorResponse>(c.get("/api/doctor").bodyAsText()).layers
        val demo = layers.first { it.layer == "demo" }
        assertEquals("ok", demo.status)
        assertContains(demo.detail, "fake pool")
        assertContains(demo.detail, "no gates installed")
        for (name in listOf("sdk-shim", "rules")) {
            val layer = layers.first { it.layer == name }
            assertEquals("skipped", layer.status, "$name is not a gate a demo has")
            assertContains(layer.detail, "demo mode")
        }
        assertTrue(layers.none { it.status == "fail" }, "a demo doctor must never FAIL: $layers")
    }

    /** The console's first frame: the demo flag rides the hello, which every client already reads. */
    @Test fun theHelloFrameCarriesTheDemoFlag() {
        val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val demo = Events({ emptyList() }, { emptyList() }, scope, demo = true)
        assertContains(demo.helloFrame(), "\"demo\":true")
        val real = Events({ emptyList() }, { emptyList() }, scope)
        assertContains(real.helloFrame(), "\"demo\":false")
    }

    @Test fun demoStillAcceptsTheSandboxSafeKnobs() = withApi(demo = true) { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_SCREENSHOTS":"open"}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("open", EnvFile.read(DaemonConfig.defaultPath())["CORRAL_SCREENSHOTS"])
    }

    @Test fun demoRefusesAMixedBodyWholeSoNothingIsHalfWritten() = withApi(demo = true) { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_SCREENSHOTS":"open","CORRAL_ADB":"/usr/bin/adb"}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertFalse(Files.exists(DaemonConfig.defaultPath()), "a refused body must not write the accepted half either")
    }

    @Test fun aRealDaemonStillWritesTheSameKnobs() = withApi(demo = false) { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_UPDATE_CHECK":"off"}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("off", EnvFile.read(DaemonConfig.defaultPath())["CORRAL_UPDATE_CHECK"])
    }

    /** `CORRAL_DB` wants an absolute path; building it here keeps the row host-independent. */
    private fun Path_slash_tmp_db(): String =
        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "demo-test.db").toAbsolutePath().toString()
            .replace("\\", "\\\\")   // JSON-escape the Windows separators this row feeds a JSON body
}
