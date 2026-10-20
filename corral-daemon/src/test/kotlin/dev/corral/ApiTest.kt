package dev.corral

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ApiTest {
    private val testDevices = {
        listOf(Device("dev1", "device", model = "Pixel_6", osVersion = "13"),
               Device("dev2", "device", model = "SM-S918B", osVersion = "13"))
    }

    /** Both ends: a stale file left by an earlier run must not decide what a config test writes. */
    @BeforeTest @AfterTest fun cleanConfigFixtures() {
        Files.deleteIfExists(RulesFile.defaultPath())
        Files.deleteIfExists(DaemonConfig.defaultPath())
    }

    /** L1 hermeticity: the config files the suite writes stay in the sandbox, never the developer's ~/.config. */
    @Test fun configFilesStayInsideTheTestHomeSandbox() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath()
        assertTrue(home.endsWith(Path.of("build", "test-home")),
            "tests must run with user.home at build/test-home, was $home")
        for (path in listOf(RulesFile.defaultPath(), DaemonConfig.defaultPath()))
            assertTrue(path.toAbsolutePath().startsWith(home), "$path escapes the test-home sandbox $home")
    }

    private fun ApplicationTestBuilder.corral(
        leases: LeaseManager = LeaseManager(testDevices, db = null),
        adbPath: String? = null,
    ) = application { corralModule(AppEnv(testDevices, leases, adbPath)) }

    private fun ApplicationTestBuilder.corralWithEvents(events: Events, leases: LeaseManager) =
        application { corralModule(AppEnv(testDevices, leases, events = events)) }

    private fun withApi(block: suspend CoroutineScope.(HttpClient) -> Unit) =
        testApplication { corral(); CoroutineScope(coroutineContext).block(client) }

    private fun withApi(leases: LeaseManager, block: suspend CoroutineScope.(HttpClient) -> Unit) =
        testApplication { corral(leases); CoroutineScope(coroutineContext).block(client) }

    private fun withApi(devices: List<Device>, block: suspend CoroutineScope.(HttpClient) -> Unit) =
        testApplication {
            application { corralModule(AppEnv({ devices }, LeaseManager({ devices }, db = null))) }
            CoroutineScope(coroutineContext).block(client)
        }

    private fun fakeScreenshotter(devices: List<Device>, fail: Boolean = false): Screenshotter {
        val dir = createTempDirectory("shot")
        val fakeAdb = dir.resolve("fake-adb")
        Files.writeString(fakeAdb, if (fail) "#!/usr/bin/env bash\necho boom >&2\nexit 3\n"
                                       else "#!/usr/bin/env bash\nprintf 'fake-screen'\n")
        fakeAdb.toFile().setExecutable(true)
        return Screenshotter({ devices }, fakeAdb.toString())
    }

    private fun withScreenshotApi(
        devices: List<Device> = testDevices(),
        policy: ScreenshotPolicy = ScreenshotPolicy("strict"),
        failAdb: Boolean = false,
        block: suspend CoroutineScope.(HttpClient) -> Unit,
    ) = testApplication {
        val leases = LeaseManager({ devices }, db = null)
        application {
            corralModule(AppEnv({ devices }, leases, screenshotter = fakeScreenshotter(devices, failAdb), policy = policy))
        }
        CoroutineScope(coroutineContext).block(client)
    }

    private fun withConfigApi(config: DaemonConfig, block: suspend CoroutineScope.(HttpClient) -> Unit) =
        testApplication {
            application {
                corralModule(AppEnv(testDevices, LeaseManager(testDevices, db = null), config = config))
            }
            CoroutineScope(coroutineContext).block(client)
        }

    private fun fakeEmulator(dir: Path, avds: String, exit: Int, avdScript: String): Path = dir.resolve("emulator").also {
        Files.writeString(it, """
            #!/usr/bin/env bash
            case "${'$'}1" in
              -list-avds) printf '$avds'; exit $exit ;;
              -avd) $avdScript ;;
              *) exit 0 ;;
            esac
        """.trimIndent())
        it.toFile().setExecutable(true)
    }

    private class RecordingLauncher : EmulatorLauncher {
        val stopped = mutableListOf<String>()
        var running = false
        override fun start(bin: String, avd: String, log: Path) {}
        override fun isRunning(avd: String): Boolean = running
        override fun stop(avd: String): String? { stopped += avd; return "unit ${systemdUnitName(avd)}" }
        override fun lastExit(avd: String): Int? = null
    }

    private fun exitLauncher(exit: Int) = object : EmulatorLauncher {
        override fun start(bin: String, avd: String, log: Path) {}
        override fun isRunning(avd: String): Boolean = false
        override fun stop(avd: String): String? = null
        override fun lastExit(avd: String): Int? = exit
    }

    private fun withEmulatorApi(
        devices: List<Device>,
        adbLog: Path? = null,
        binary: Boolean = true,
        avds: String = "Pixel6AVD\nSmallAVD\n",
        listExit: Int = 0,
        avdScript: String = "exec sleep 2",
        launcher: EmulatorLauncher = DefaultLauncher(),
        now: () -> Long = { System.currentTimeMillis() },
        block: suspend CoroutineScope.(HttpClient) -> Unit,
    ) = testApplication {
        val dir = createTempDirectory("emu")
        val emu = if (binary) fakeEmulator(dir, avds, listExit, avdScript) else null
        val adb = dir.resolve("fake-adb").also {
            Files.writeString(it, "#!/usr/bin/env bash\n" +
                (adbLog?.let { log -> "echo \"${'$'}*\" >> '$log'\n" } ?: "") + "exit 0\n")
            it.toFile().setExecutable(true)
        }
        val emulators = Emulators(adb.toString(), { emu?.toString() }, launcher, now)
        val pool = { emulators.liveDevices(devices) }
        application {
            corralModule(AppEnv(pool, LeaseManager(pool, db = null), emulators = emulators))
        }
        CoroutineScope(coroutineContext).block(client)
    }

    @Test fun configReportsEffectiveValuesDefaultsAndPaths() {
        val rules = GateRules(setOf("-s"), setOf("-p"), setOf("bash", "sh"), setOf("systemd"))
        val rulesPath = RulesFile.defaultPath()
        Files.createDirectories(rulesPath.parent)
        RulesFile.write(rules, rulesPath)

        val config = DaemonConfig(
            fileValues = mapOf("CORRAL_PORT" to "9000"),
            inherited = mapOf("CORRAL_SCREENSHOTS" to "open"))

        withConfigApi(config) { c ->
            val r = c.get("/api/config")
            assertEquals(HttpStatusCode.OK, r.status)
            val body = Json.decodeFromString<ConfigResponse>(r.bodyAsText())

            val rawRules = Json.parseToJsonElement(r.bodyAsText()).jsonObject["rules"]!!.jsonObject
            for (key in listOf("readonly", "valueflags", "shell_skip", "init_floor"))
                assertTrue(key in rawRules, "wire key '$key' missing from rules")

            val port = body.settings.first { it.key == "CORRAL_PORT" }
            assertEquals("9000", port.value)
            assertEquals("8798", port.default)
            assertTrue(port.restartRequired)

            val shots = body.settings.first { it.key == "CORRAL_SCREENSHOTS" }
            assertEquals("open", shots.value)
            assertEquals("strict", shots.default)

            val adb = body.settings.first { it.key == "CORRAL_ADB" }
            assertEquals("adb", adb.value)
            assertEquals("adb", adb.default)

            assertEquals(listOf("-s"), body.rules.readonly)
            assertEquals(listOf("-p"), body.rules.valueflags)
            assertEquals(listOf("bash", "sh"), body.rules.shell_skip)
            assertEquals(listOf("systemd"), body.rules.init_floor)
            assertEquals(rulesPath.toString(), body.rulesPath)
            assertEquals(DaemonConfig.defaultPath().toString(), body.envPath)
        }
    }

    @Test fun configReportsTheEffectiveValueWhenTheFileValueIsUnusable() {
        val config = DaemonConfig(
            fileValues = mapOf("CORRAL_PORT" to "22"),
            inherited = mapOf("CORRAL_PORT" to "9000"))

        withConfigApi(config) { c ->
            val body = Json.decodeFromString<ConfigResponse>(c.get("/api/config").bodyAsText())
            assertEquals("9000", body.settings.first { it.key == "CORRAL_PORT" }.value)
        }
    }

    @Test fun configEmitsRuleKeysEvenWhenAListIsEmpty() {
        val rulesPath = RulesFile.defaultPath()
        Files.createDirectories(rulesPath.parent)
        RulesFile.write(GateRules(setOf("devices"), emptySet()), rulesPath)

        withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
            val rawRules = Json.parseToJsonElement(c.get("/api/config").bodyAsText())
                .jsonObject["rules"]!!.jsonObject
            for (key in listOf("readonly", "valueflags", "shell_skip", "init_floor"))
                assertTrue(key in rawRules, "wire key '$key' missing from rules when its list is empty")
            assertEquals(0, rawRules["valueflags"]!!.jsonArray.size)
        }
    }

    @Test fun configUnwiredAnswers503() = withApi { c ->
        val r = c.get("/api/config")
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue(r.bodyAsText().contains("config not wired"))
    }

    private suspend fun HttpClient.putConfig(json: String) =
        put("/api/config") { contentType(ContentType.Application.Json); setBody(json) }

    @Test fun putWithoutJsonContentTypeTeachesTheHeader() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.put("/api/config") { setBody("""{"settings":{}}""") }   // no content-type -> text/plain
        assertEquals(HttpStatusCode.UnsupportedMediaType, r.status)
        val errs = Json.decodeFromString<ConfigErrors>(r.bodyAsText())
        assertTrue(errs.errors.any { it.contains("application/json") }, "expected the content-type teaching, got ${errs.errors}")
    }

    @Test fun putWithMalformedJsonIsA400WithABody() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.put("/api/config") { contentType(ContentType.Application.Json); setBody("{not json") }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val errs = Json.decodeFromString<ConfigErrors>(r.bodyAsText())
        assertTrue(errs.errors.any { it.startsWith("malformed JSON body") }, "got ${errs.errors}")
    }

    /** `{"key":…,"value":…}` — the first Windows-smoke body; it used to be a silent 200 that wrote nothing. */
    @Test fun putRejectsKeyValueBodyShape() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"key":"CORRAL_SCREENSHOTS","value":"open"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val err = Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.joinToString(" ")
        assertContains(err, "key")
        assertContains(err, "value")
        assertContains(err, """{"settings":{"CORRAL_SCREENSHOTS":"open"}}""")

        // the echoed keys are capped: first five, a "+N more" tail, then each key cut so one huge key can't balloon
        val eight = (1..8).joinToString(",") { "\"k$it\":1" }
        val many = Json.decodeFromString<ConfigErrors>(c.putConfig("""{$eight}""").bodyAsText()).errors.single()
        assertContains(many, "k1, k2, k3, k4, k5, +3 more")
        assertFalse(many.contains('\n'))

        val longKey = "Z".repeat(500)
        val huge = Json.decodeFromString<ConfigErrors>(c.putConfig("""{"$longKey":1}""").bodyAsText()).errors.single()
        assertContains(huge, "Z".repeat(40) + "…")
        assertFalse(huge.contains("Z".repeat(41)))
    }

    /** `{"CORRAL_SCREENSHOTS":"open"}` — the second Windows-smoke body; same silent 200. */
    @Test fun putRejectsFlatSettingBodyShape() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"CORRAL_SCREENSHOTS":"open"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val err = Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.joinToString(" ")
        assertContains(err, "CORRAL_SCREENSHOTS")
        assertContains(err, """{"settings":{"CORRAL_SCREENSHOTS":"open"}}""")
    }

    @Test fun putCorrectShapeWritesTheSetting() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_SCREENSHOTS":"open"}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(Json.decodeFromString<PutConfigResponse>(r.bodyAsText()).restartRequired)
        assertEquals("open", EnvFile.read(DaemonConfig.defaultPath())["CORRAL_SCREENSHOTS"])
    }

    @Test fun putEmptyObjectIsANoop200() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        // deliberate: an empty no-op save must not become a 400 — the UI may PUT {} when nothing changed
        val r = c.putConfig("{}")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(Json.decodeFromString<PutConfigResponse>(r.bodyAsText()).warnings.isEmpty())
        assertFalse(Files.exists(DaemonConfig.defaultPath()))
    }

    @Test fun putKeepsLeniencyForAnUnknownKeyAlongsideSettings() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_SCREENSHOTS":"open"},"someUnknownKey":1}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("open", EnvFile.read(DaemonConfig.defaultPath())["CORRAL_SCREENSHOTS"])
    }

    /** Valid JSON that is not a top-level object: the JsonObject decode fails and must teach a 400, not a 500. */
    @Test fun putRejectsAValidJsonNonObjectBody() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        for (raw in listOf("[1,2,3]", "\"x\"", "42")) {
            val r = c.putConfig(raw)
            assertEquals(HttpStatusCode.BadRequest, r.status, "body $raw")
            val errs = Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors
            assertTrue(errs.any { it.startsWith("malformed JSON body") }, "body $raw gave ${errs}")
            assertFalse(Files.exists(DaemonConfig.defaultPath()), "body $raw wrote the env file")
        }
    }

    @Test fun putWritesKnownSettingsAndRules() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_SCREENSHOTS":"open"},"rules":{"readonly":["devices"],"valueflags":["-p"]}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(Json.decodeFromString<PutConfigResponse>(r.bodyAsText()).restartRequired)
        val raw = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(JsonPrimitive(true), raw["restartRequired"])
        assertTrue(raw["warnings"] is JsonArray, "warnings key missing from the PUT wire response")
        assertEquals("open", EnvFile.read(DaemonConfig.defaultPath())["CORRAL_SCREENSHOTS"])
        val rules = RulesFile.load(RulesFile.defaultPath())
        assertEquals(setOf("devices"), rules.readonly)
        assertEquals(setOf("-p"), rules.valueFlags)
        assertEquals(DEFAULT_SHELL_SKIP, rules.shellSkip)
        assertEquals(DEFAULT_INIT_FLOOR, rules.initFloor)
    }

    @Test fun putInvalidPortIs400AndWritesNothing() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000", "KEEP" to "yes"))
        RulesFile.write(GateRules(setOf("-s"), setOf("-p")), rulesPath)
        val envBefore = Files.readString(envPath)
        val rulesBefore = Files.readString(rulesPath)

        val r = c.putConfig("""{"settings":{"CORRAL_PORT":"99999"}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val errs = Json.decodeFromString<ConfigErrors>(r.bodyAsText())
        assertTrue(errs.errors.any { it.contains("CORRAL_PORT") }, "expected a CORRAL_PORT error, got ${errs.errors}")
        assertEquals(envBefore, Files.readString(envPath))
        assertEquals(rulesBefore, Files.readString(rulesPath))
    }

    @Test fun putRejectsBadScreenshotsMode() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val bad = c.putConfig("""{"settings":{"CORRAL_SCREENSHOTS":"sometimes"}}""")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(bad.bodyAsText()).errors.any { it.contains("CORRAL_SCREENSHOTS") })

        val ok = c.putConfig("""{"settings":{"CORRAL_SCREENSHOTS":"OPEN"}}""")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("open", EnvFile.read(DaemonConfig.defaultPath())["CORRAL_SCREENSHOTS"])
    }

    @Test fun putRejectsABadEmulatorLauncherAndWritesNothing() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000", "KEEP" to "yes"))
        val before = Files.readString(envPath)

        val r = c.putConfig("""{"settings":{"CORRAL_EMULATOR_LAUNCHER":"auto"}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val errs = Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors
        assertTrue(errs.any { it.contains("CORRAL_EMULATOR_LAUNCHER") && it.contains("systemd") }, errs.toString())
        assertEquals(before, Files.readString(envPath))
    }

    @Test fun putAcceptsEmulatorLauncherValuesAndBlank() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        for (v in listOf("systemd", "direct")) {
            val r = c.putConfig("""{"settings":{"CORRAL_EMULATOR_LAUNCHER":"$v"}}""")
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals(v, EnvFile.read(DaemonConfig.defaultPath())["CORRAL_EMULATOR_LAUNCHER"])
        }
        val blank = c.putConfig("""{"settings":{"CORRAL_EMULATOR_LAUNCHER":""}}""")
        assertEquals(HttpStatusCode.OK, blank.status)
        assertEquals("", EnvFile.read(DaemonConfig.defaultPath())["CORRAL_EMULATOR_LAUNCHER"])
    }

    @Test fun putRejectsBlankPaths() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_ADB":"  "}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("CORRAL_ADB") })
    }

    @Test fun putRejectsRelativeDbPath() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000"))
        RulesFile.write(GateRules(setOf("-s"), setOf("-p")), rulesPath)
        val envBefore = Files.readString(envPath)
        val rulesBefore = Files.readString(rulesPath)

        val r = c.putConfig("""{"settings":{"CORRAL_DB":"relative/x.db"}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("CORRAL_DB") })
        assertEquals(envBefore, Files.readString(envPath))
        assertEquals(rulesBefore, Files.readString(rulesPath))
    }

    @Test fun putRejectsUnwritableDbParent() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000"))
        RulesFile.write(GateRules(setOf("-s"), setOf("-p")), rulesPath)
        val envBefore = Files.readString(envPath)
        val rulesBefore = Files.readString(rulesPath)

        val file = Files.createTempFile("corral-db-parent", ".file")
        try {
            val r = c.putConfig("""{"settings":{"CORRAL_DB":"$file/child/db"}}""")
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("CORRAL_DB") })
            assertEquals(envBefore, Files.readString(envPath))
            assertEquals(rulesBefore, Files.readString(rulesPath))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test fun putRejectsDbPathThatIsADirectory() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000"))
        RulesFile.write(GateRules(setOf("-s"), setOf("-p")), rulesPath)
        val envBefore = Files.readString(envPath)
        val rulesBefore = Files.readString(rulesPath)

        val dir = createTempDirectory("corral-db-dir")
        try {
            val r = c.putConfig("""{"settings":{"CORRAL_DB":"$dir"}}""")
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("CORRAL_DB") })
            assertEquals(envBefore, Files.readString(envPath))
            assertEquals(rulesBefore, Files.readString(rulesPath))
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    @Test fun putRejectsRelativeAdbPath() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000"))
        RulesFile.write(GateRules(setOf("-s"), setOf("-p")), rulesPath)
        val envBefore = Files.readString(envPath)
        val rulesBefore = Files.readString(rulesPath)

        val r = c.putConfig("""{"settings":{"CORRAL_ADB":"../x"}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("CORRAL_ADB") })
        assertEquals(envBefore, Files.readString(envPath))
        assertEquals(rulesBefore, Files.readString(rulesPath))
    }

    @Test fun putRejectsMissingAdbExecutable() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000"))
        RulesFile.write(GateRules(setOf("-s"), setOf("-p")), rulesPath)
        val envBefore = Files.readString(envPath)
        val rulesBefore = Files.readString(rulesPath)

        val r = c.putConfig("""{"settings":{"CORRAL_ADB":"/nonexistent/adb"}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("CORRAL_ADB") })
        assertEquals(envBefore, Files.readString(envPath))
        assertEquals(rulesBefore, Files.readString(rulesPath))
    }

    @Test fun putAcceptsValidAbsoluteDbAndAdb() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val db = createTempDirectory("corral-db").resolve("db/corral.db")
        val adb = Files.createTempFile("corral-adb", ".sh")
        adb.toFile().setExecutable(true)

        val r = c.putConfig("""{"settings":{"CORRAL_DB":"$db","CORRAL_ADB":"$adb"}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val env = EnvFile.read(DaemonConfig.defaultPath())
        assertEquals(db.toString(), env["CORRAL_DB"])
        assertEquals(adb.toString(), env["CORRAL_ADB"])
    }

    @Test fun putWarnsWhenBareAdbIsNotOnPath() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_ADB":"definitely-not-a-real-bin-xyz"}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(Json.decodeFromString<PutConfigResponse>(r.bodyAsText()).warnings.any {
            it.contains("definitely-not-a-real-bin-xyz") && it.contains("not on PATH")
        }, "a bare command not on PATH must warn")
    }

    @Test fun putRejectsPrivilegedPort() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000", "KEEP" to "yes"))
        RulesFile.write(GateRules(setOf("-s"), setOf("-p")), rulesPath)
        val envBefore = Files.readString(envPath)
        val rulesBefore = Files.readString(rulesPath)

        val r = c.putConfig("""{"settings":{"CORRAL_PORT":"22"}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("CORRAL_PORT") })
        assertEquals(envBefore, Files.readString(envPath))
        assertEquals(rulesBefore, Files.readString(rulesPath))
    }

    @Test fun putIgnoresUnknownSettingsKeys() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"settings":{"UNKNOWN":"x","CORRAL_PORT":"9001"}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val env = EnvFile.read(DaemonConfig.defaultPath())
        assertEquals("9001", env["CORRAL_PORT"])
        assertFalse(env.containsKey("UNKNOWN"))
    }

    @Test fun putRulesOnlyLeavesEnvFileUntouched() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000"))
        val envBefore = Files.readString(envPath)

        val r = c.putConfig("""{"rules":{"readonly":["devices"],"valueflags":[]}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(envBefore, Files.readString(envPath))
        assertEquals(setOf("devices"), RulesFile.load(rulesPath).readonly)

        val rulesBefore = Files.readString(rulesPath)
        val s = c.putConfig("""{"settings":{"CORRAL_PORT":"9001"}}""")
        assertEquals(HttpStatusCode.OK, s.status)
        assertEquals(rulesBefore, Files.readString(rulesPath))
        assertEquals("9001", EnvFile.read(envPath)["CORRAL_PORT"])
    }

    @Test fun putRefusesNonHostOnlyReadonly() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000"))
        RulesFile.write(GateRules(setOf("devices"), setOf("-p")), rulesPath)
        val envBefore = Files.readString(envPath)
        val rulesBefore = Files.readString(rulesPath)

        for (bad in listOf("install", "exec-in")) {
            val r = c.putConfig("""{"rules":{"readonly":["devices","$bad"],"valueflags":[]}}""")
            assertEquals(HttpStatusCode.BadRequest, r.status, "$bad must be refused")
            assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains(bad) },
                "error must name $bad")
            assertEquals(envBefore, Files.readString(envPath))
            assertEquals(rulesBefore, Files.readString(rulesPath))
        }

        val ok = c.putConfig("""{"rules":{"readonly":["devices","help"],"valueflags":[]}}""")
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    @Test fun putRejectsIdentityListChange() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val shellSkip = c.putConfig("""{"rules":{"readonly":["devices"],"valueflags":[],"shell_skip":["python3"]}}""")
        assertEquals(HttpStatusCode.BadRequest, shellSkip.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(shellSkip.bodyAsText()).errors.any { it.contains("shell_skip") })

        val initFloor = c.putConfig("""{"rules":{"readonly":["devices"],"valueflags":[],"init_floor":["systemd"]}}""")
        assertEquals(HttpStatusCode.BadRequest, initFloor.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(initFloor.bodyAsText()).errors.any { it.contains("init_floor") })
    }

    @Test fun putRefusesIdentityRevertToDefaults() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val rulesPath = RulesFile.defaultPath()
        Files.createDirectories(rulesPath.parent)
        RulesFile.write(GateRules(setOf("devices"), setOf("-P"), setOf("python3")), rulesPath)
        val before = Files.readString(rulesPath)

        val revert = c.putConfig("""{"rules":${
            Json.encodeToString(ConfigRules.serializer(), ConfigRules(
                readonly = listOf("devices"), valueflags = listOf("-P"),
                shell_skip = DEFAULT_SHELL_SKIP.toList()))
        }}""")
        assertEquals(HttpStatusCode.BadRequest, revert.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(revert.bodyAsText()).errors.any {
            it.contains("shell_skip") && it.contains("file-only")
        }, "a revert to the defaults must be refused as file-only, got ${revert.bodyAsText()}")
        assertEquals(before, Files.readString(rulesPath), "a refused revert must not touch the file")

        val echo = c.putConfig("""{"rules":{"readonly":["devices"],"valueflags":["-P"],"shell_skip":["python3"]}}""")
        assertEquals(HttpStatusCode.OK, echo.status)
        assertEquals(before, Files.readString(rulesPath), "an unchanged echo-back must not rewrite the file")
    }

    @Test fun putAcceptsUnchangedHandEditedReadonly() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val rulesPath = RulesFile.defaultPath()
        Files.createDirectories(rulesPath.parent)
        RulesFile.write(GateRules(setOf("devices", "exec-in"), setOf("-P")), rulesPath)

        val r = c.putConfig("""{"rules":{"readonly":["devices","exec-in"],"valueflags":["-P"]}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue("exec-in" in Files.readString(rulesPath), "an unchanged hand-edited readonly must survive")
    }

    @Test fun putAcceptsUnchangedHandEditedIdentity() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val rulesPath = RulesFile.defaultPath()
        Files.createDirectories(rulesPath.parent)
        RulesFile.write(GateRules(setOf("devices"), setOf("-P"), setOf("python3")), rulesPath)

        val r = c.putConfig("""{"rules":{"readonly":["devices"],"valueflags":["-P"],"shell_skip":["python3"]}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue("shell_skip=python3" in Files.readString(rulesPath), "the unchanged identity line must survive")
    }

    @Test fun putRulesEchoBackIsANoop() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val rulesPath = RulesFile.defaultPath()
        Files.createDirectories(rulesPath.parent)
        Files.writeString(rulesPath,
            "readonly=devices,exec-in\nvalueflags=-P\nshell_skip=python3,claude\ninit_floor=myinit\n# keep me\n")
        val before = Files.readString(rulesPath)

        val rules = Json.decodeFromString<ConfigResponse>(c.get("/api/config").bodyAsText()).rules
        val r = c.putConfig("""{"rules":${Json.encodeToString(ConfigRules.serializer(), rules)}}""")

        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(Json.decodeFromString<PutConfigResponse>(r.bodyAsText()).warnings.isEmpty())
        assertEquals(before, Files.readString(rulesPath), "an unchanged echo-back must not rewrite the file")
    }

    @Test fun putEmptyRulesListMeansNoChange() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val rulesPath = RulesFile.defaultPath()
        Files.createDirectories(rulesPath.parent)
        RulesFile.write(GateRules(setOf("devices"), setOf("-P")), rulesPath)
        val before = Files.readString(rulesPath)

        val r = c.putConfig("""{"rules":{"readonly":[]}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(setOf("devices"), RulesFile.load(rulesPath).readonly)
        assertEquals(before, Files.readString(rulesPath), "an empty list must not rewrite the file")
    }

    @Test fun putValueflagsRejectsThePin() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val rulesPath = RulesFile.defaultPath()
        Files.createDirectories(rulesPath.parent)
        RulesFile.write(GateRules(setOf("devices"), setOf("-P")), rulesPath)
        val before = Files.readString(rulesPath)

        for (pin in listOf("-s", "-sdirect")) {
            val r = c.putConfig("""{"rules":{"valueflags":["$pin","-P"]}}""")
            assertEquals(HttpStatusCode.BadRequest, r.status, "$pin must be refused")
            assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("-s is the pin") },
                "the error must name the pin, got ${r.bodyAsText()}")
        }
        assertEquals(before, Files.readString(rulesPath))

        val ok = c.putConfig("""{"rules":{"valueflags":["-P"]}}""")
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    @Test fun putStillRefusesAReadonlyChange() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val rulesPath = RulesFile.defaultPath()
        Files.createDirectories(rulesPath.parent)
        RulesFile.write(GateRules(setOf("devices"), setOf("-P")), rulesPath)

        val r = c.putConfig("""{"rules":{"readonly":["devices","exec-in"],"valueflags":["-P"]}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("exec-in") })
    }

    @Test fun putOmittedIdentityListsAreLeftUntouched() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val rulesPath = RulesFile.defaultPath()
        Files.createDirectories(rulesPath.parent)
        Files.writeString(rulesPath, "readonly=devices\nvalueflags=-P\nshell_skip=python3,claude\ninit_floor=myinit\n# keep me\n")

        val r = c.putConfig("""{"rules":{"readonly":["devices","help"],"valueflags":["-P"]}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val after = Files.readString(rulesPath)
        assertTrue("shell_skip=python3,claude" in after, "identity line must survive verbatim")
        assertTrue("init_floor=myinit" in after, "identity line must survive verbatim")
        assertTrue("# keep me" in after)
        val rules = RulesFile.load(rulesPath)
        assertEquals(setOf("devices", "help"), rules.readonly)
        assertEquals(setOf("-P"), rules.valueFlags)
        assertEquals(setOf("python3", "claude"), rules.shellSkip)
        assertEquals(setOf("myinit"), rules.initFloor)
    }

    @Test fun putRejectsRulesEntryWithComma() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000"))
        RulesFile.write(GateRules(setOf("-s"), setOf("-p")), rulesPath)
        val envBefore = Files.readString(envPath)
        val rulesBefore = Files.readString(rulesPath)

        val r = c.putConfig("""{"rules":{"readonly":["version,install"],"valueflags":[],"shell_skip":[],"init_floor":[]}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("readonly='version,install'") })
        assertEquals(envBefore, Files.readString(envPath))
        assertEquals(rulesBefore, Files.readString(rulesPath))
    }

    @Test fun putRejectsRulesEntryWithWhitespace() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"rules":{"readonly":["ver sion"],"valueflags":[],"shell_skip":[],"init_floor":[]}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("readonly='ver sion'") })
    }

    @Test fun putRejectsValueflagsWithoutDash() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000"))
        RulesFile.write(GateRules(setOf("-s"), setOf("-p")), rulesPath)
        val envBefore = Files.readString(envPath)
        val rulesBefore = Files.readString(rulesPath)

        val r = c.putConfig("""{"rules":{"readonly":["devices"],"valueflags":["shell"],"shell_skip":[],"init_floor":[]}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any {
            it.contains("valueflags") && it.contains("'shell'")
        })
        assertEquals(envBefore, Files.readString(envPath))
        assertEquals(rulesBefore, Files.readString(rulesPath))
    }

    @Test fun putRulesOnlyReportsRulesLiveWithoutRestart() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"rules":{"readonly":["devices"],"valueflags":[],"shell_skip":[],"init_floor":[]}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.decodeFromString<PutConfigResponse>(r.bodyAsText())
        assertTrue(body.rulesLive)
        assertFalse(body.restartRequired)
    }

    @Test fun putSettingsOnlyReportsRestartWithoutRulesLive() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_SCREENSHOTS":"open"}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.decodeFromString<PutConfigResponse>(r.bodyAsText())
        assertTrue(body.restartRequired)
        assertFalse(body.rulesLive)
    }

    @Test fun putOnlyTheLiveToggleNeedsNoRestart() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val live = c.putConfig("""{"settings":{"CORRAL_UPDATE_CHECK":"off"}}""")
        assertEquals(HttpStatusCode.OK, live.status)
        assertFalse(Json.decodeFromString<PutConfigResponse>(live.bodyAsText()).restartRequired)
        assertEquals("off", EnvFile.read(DaemonConfig.defaultPath())["CORRAL_UPDATE_CHECK"])

        // a restart-bound key still reports true; a mixed write is bound by the restart-bound key
        val shots = c.putConfig("""{"settings":{"CORRAL_SCREENSHOTS":"open"}}""")
        assertTrue(Json.decodeFromString<PutConfigResponse>(shots.bodyAsText()).restartRequired)
        val mixed = c.putConfig("""{"settings":{"CORRAL_UPDATE_CHECK":"on","CORRAL_SCREENSHOTS":"open"}}""")
        assertTrue(Json.decodeFromString<PutConfigResponse>(mixed.bodyAsText()).restartRequired)

        val env = EnvFile.read(DaemonConfig.defaultPath())
        assertEquals("on", env["CORRAL_UPDATE_CHECK"])
        assertEquals("open", env["CORRAL_SCREENSHOTS"])
    }

    @Test fun putRejectsABadUpdateCheckValue() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_UPDATE_CHECK":"maybe"}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("CORRAL_UPDATE_CHECK") })
    }

    @Test fun updateUnwiredReportsDisabled() = withApi { c ->
        val info = Json.decodeFromString<UpdateInfo>(c.get("/api/update").bodyAsText())
        assertEquals(UpdateState.DISABLED, info.state)
        assertFalse(info.checksEnabled)
        assertFalse(info.updateAvailable)
    }

    @Test fun updateRouteServesTheCacheAndForceRefreshes() = testApplication {
        var calls = 0
        var body = """{"tag_name":"v2.0.0","html_url":"https://example/2.0.0"}"""
        val check = UpdateCheck(
            feed = { calls++; FeedResult.Body(body) },
            config = { DaemonConfig(fileValues = mapOf("CORRAL_UPDATE_CHECK" to "on"), inherited = emptyMap()) },
            version = "1.0.0")
        application { corralModule(AppEnv(testDevices, LeaseManager(testDevices, db = null), updateCheck = check)) }
        val c = client

        val forced = Json.decodeFromString<UpdateInfo>(c.get("/api/update?force").bodyAsText())
        assertEquals(1, calls)
        assertEquals(UpdateState.UPDATE_AVAILABLE, forced.state)
        assertTrue(forced.updateAvailable)
        assertEquals("v2.0.0", forced.latestVersion)

        body = """{"tag_name":"v3.0.0","html_url":"https://example/3.0.0"}"""
        val cached = Json.decodeFromString<UpdateInfo>(c.get("/api/update").bodyAsText())
        assertEquals(1, calls, "the cached route must not hit the feed")
        assertEquals("v2.0.0", cached.latestVersion)

        val refreshed = Json.decodeFromString<UpdateInfo>(c.get("/api/update?force").bodyAsText())
        assertEquals(2, calls)
        assertEquals("v3.0.0", refreshed.latestVersion)
    }

    /** Defect 1 + P3 2 at the route: enabling the switch serves a fresh check, no ?force, no restart. */
    @Test fun enablingTheSwitchIsServedLiveWithoutForce() = testApplication {
        val file = createTempDirectory("update").resolve("daemon.env")
        EnvFile.write(file, mapOf("CORRAL_UPDATE_CHECK" to "off"))
        var calls = 0
        val check = UpdateCheck(
            feed = { calls++; FeedResult.Body("""{"tag_name":"v2.0.0","html_url":"https://example/2.0.0"}""") },
            config = { DaemonConfig(EnvFile.read(file), emptyMap()) },
            version = "1.0.0",
            dispatch = { it() })
        application { corralModule(AppEnv(testDevices, LeaseManager(testDevices, db = null), updateCheck = check)) }

        // a check cycle while disabled caches DISABLED (and never calls the feed)
        val off = Json.decodeFromString<UpdateInfo>(client.get("/api/update?force").bodyAsText())
        assertEquals(UpdateState.DISABLED, off.state)
        assertEquals(0, calls)

        EnvFile.write(file, mapOf("CORRAL_UPDATE_CHECK" to "on"))
        val live = Json.decodeFromString<UpdateInfo>(client.get("/api/update").bodyAsText())
        assertEquals(UpdateState.UPDATE_AVAILABLE, live.state)   // the transition ran the check itself
        assertTrue(live.checksEnabled)
        assertEquals(1, calls)

        client.get("/api/update")
        assertEquals(1, calls)                                   // and it does not re-kick on every poll
    }

    @Test fun putObserversTrimmedAndDeduped() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_SCREENSHOT_OBSERVERS":" a , a, b "}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("a,b", EnvFile.read(DaemonConfig.defaultPath())["CORRAL_SCREENSHOT_OBSERVERS"])
    }

    @Test fun putBlankObserversDisablesObservers() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_SCREENSHOT_OBSERVERS":""}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("", EnvFile.read(DaemonConfig.defaultPath())["CORRAL_SCREENSHOT_OBSERVERS"])
    }

    @Test fun putRejectsNewlineInjection() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000"))
        RulesFile.write(GateRules(setOf("-s"), setOf("-p")), rulesPath)
        val envBefore = Files.readString(envPath)
        val rulesBefore = Files.readString(rulesPath)

        val smuggled = c.putConfig("""{"settings":{"CORRAL_GRADLE_TASKS":"a\nCORRAL_ADB=/evil/adb"}}""")
        assertEquals(HttpStatusCode.BadRequest, smuggled.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(smuggled.bodyAsText()).errors.any { it.contains("control") })
        assertEquals(envBefore, Files.readString(envPath))
        assertEquals(rulesBefore, Files.readString(rulesPath))

        val rulesInjection = c.putConfig("""{"rules":{"readonly":["a\nCORRAL_ADB=/evil"],"valueflags":[],"shell_skip":[],"init_floor":[]}}""")
        assertEquals(HttpStatusCode.BadRequest, rulesInjection.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(rulesInjection.bodyAsText()).errors.any { it.contains("rules entries") })
        assertEquals(envBefore, Files.readString(envPath))
        assertEquals(rulesBefore, Files.readString(rulesPath))

        val ctrl = c.putConfig("""{"settings":{"CORRAL_GRADLE_TASKS":"a\u0007b"}}""")
        assertEquals(HttpStatusCode.BadRequest, ctrl.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(ctrl.bodyAsText()).errors.any { it.contains("control") })
        assertEquals(envBefore, Files.readString(envPath))
        assertEquals(rulesBefore, Files.readString(rulesPath))
    }

    @Test fun configPutRejectsForeignOrigin() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val body = """{"settings":{"CORRAL_SCREENSHOTS":"open"}}"""

        val foreign = c.put("/api/config") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "https://evil.example")
            setBody(body)
        }
        assertEquals(HttpStatusCode.Forbidden, foreign.status)

        val sameOrigin = c.put("/api/config") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "http://127.0.0.1:8798")
            header(HttpHeaders.Host, "127.0.0.1:8798")
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, sameOrigin.status)

        val otherPort = c.put("/api/config") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "http://127.0.0.1:3000")
            header(HttpHeaders.Host, "127.0.0.1:8798")
            setBody(body)
        }
        assertEquals(HttpStatusCode.Forbidden, otherPort.status)

        val missing = c.putConfig(body)
        assertEquals(HttpStatusCode.OK, missing.status)
    }

    @Test fun configEmulatorKeyAppearsWithItsDefault() = withConfigApi(
        DaemonConfig(fileValues = mapOf("CORRAL_EMULATOR" to "my-emulator"), inherited = emptyMap())
    ) { c ->
        val body = Json.decodeFromString<ConfigResponse>(c.get("/api/config").bodyAsText())
        val emu = body.settings.first { it.key == "CORRAL_EMULATOR" }
        assertEquals("my-emulator", emu.value)
        assertEquals("", emu.default)
        assertTrue(emu.restartRequired)
    }

    @Test fun putEmulatorAcceptsAPathAndClearsOnBlank() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val emu = createTempDirectory("emulator").resolve("emulator")
        Files.writeString(emu, "#!/bin/sh\n")
        emu.toFile().setExecutable(true)

        val ok = c.putConfig("""{"settings":{"CORRAL_EMULATOR":"$emu"}}""")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(emu.toString(), EnvFile.read(DaemonConfig.defaultPath())["CORRAL_EMULATOR"])

        val blank = c.putConfig("""{"settings":{"CORRAL_EMULATOR":""}}""")
        assertEquals(HttpStatusCode.OK, blank.status)
        assertEquals("", EnvFile.read(DaemonConfig.defaultPath())["CORRAL_EMULATOR"])
    }

    @Test fun putEmulatorRejectsAnInvalidPath() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_EMULATOR":"/no/such/emulator"}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("CORRAL_EMULATOR") })
    }

    @Test fun putRejectsTrailingBackslash() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000"))
        RulesFile.write(GateRules(setOf("-s"), setOf("-p")), rulesPath)
        val envBefore = Files.readString(envPath)
        val rulesBefore = Files.readString(rulesPath)

        val r = c.putConfig("""{"settings":{"CORRAL_GRADLE_TASKS":"assembleDebug\\"}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("CORRAL_GRADLE_TASKS") })
        assertEquals(envBefore, Files.readString(envPath))
        assertEquals(rulesBefore, Files.readString(rulesPath))
    }

    @Test fun putRejectsQuotedPathValue() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val db = createTempDirectory("corral-db").resolve("db/corral.db")
        val envPath = DaemonConfig.defaultPath()
        val rulesPath = RulesFile.defaultPath()
        EnvFile.write(envPath, mapOf("CORRAL_PORT" to "9000"))
        RulesFile.write(GateRules(setOf("-s"), setOf("-p")), rulesPath)
        val envBefore = Files.readString(envPath)
        val rulesBefore = Files.readString(rulesPath)

        val r = c.putConfig("""{"settings":{"CORRAL_DB":"\"$db\""}}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(Json.decodeFromString<ConfigErrors>(r.bodyAsText()).errors.any { it.contains("CORRAL_DB") })
        assertEquals(envBefore, Files.readString(envPath))
        assertEquals(rulesBefore, Files.readString(rulesPath))
    }

    @Test fun putUnwiredAnswers503() = withApi { c ->
        val r = c.putConfig("""{"settings":{"CORRAL_PORT":"9001"}}""")
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue(r.bodyAsText().contains("config not wired"))
    }

    @Test fun putNoWarningWhenReadonlyIsSafe() = withConfigApi(DaemonConfig(fileValues = emptyMap())) { c ->
        val r = c.putConfig("""{"rules":{"readonly":["devices"],"valueflags":[],"shell_skip":[],"init_floor":[]}}""")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(Json.decodeFromString<PutConfigResponse>(r.bodyAsText()).warnings.isEmpty())
    }

    @Test fun devicesListed() = withApi { c ->
        assertEquals(HttpStatusCode.OK, c.get("/api/devices").status)
    }

    @Test fun claimCheckFreeCycle() = withApi { c ->
        val claim = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.OK, claim.status)
        assertTrue(claim.bodyAsText().contains("\"serial\":\"dev1\""))

        val check = c.get("/api/check?serial=dev1&owner=agentA").bodyAsText()
        assertTrue(check.contains("\"allowed\":true"))
        val foreign = c.get("/api/check?serial=dev1&owner=agentB").bodyAsText()
        assertTrue(foreign.contains("\"allowed\":false"))

        assertEquals(HttpStatusCode.OK, c.delete("/api/leases/dev1") {
            contentType(ContentType.Application.Json); setBody("""{"owner":"agentA"}""") }.status)
    }

    @Test fun foreignFreeConflicts() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.Conflict, c.delete("/api/leases/dev1") {
            contentType(ContentType.Application.Json); setBody("""{"owner":"agentB"}""") }.status)
    }

    @Test fun busyClaimConflictsWithOwnerInfo() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA","ttlSeconds":100}""") }
        val r = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentB"}""") }
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("agentA"))
    }

    @Test fun checkUnknownSerialDenied() = withApi { c ->
        assertTrue(c.get("/api/check?serial=zzz&owner=a").bodyAsText().contains("\"allowed\":false"))
    }

    @Test fun renewExtendsLease() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val r = c.post("/api/leases/dev1/renew") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentA","ttlSeconds":100}""") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"expiresAt\""))
    }

    @Test fun renewRefusesForeigner() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val r = c.post("/api/leases/dev1/renew") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentB"}""") }
        assertEquals(HttpStatusCode.Conflict, r.status)
    }

    @Test fun mutatingRoutesRejectForeignOrigin() = withApi { c ->
        assertEquals(HttpStatusCode.Forbidden, c.post("/api/leases") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "https://evil.example")
            setBody("""{"selector":{"serial":"dev1"},"owner":"a"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, c.post("/api/leases/tickets/x/poll") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "https://evil.example")
            setBody("""{"owner":"a"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, c.delete("/api/leases/tickets/x") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "https://evil.example")
            setBody("""{"owner":"a"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, c.delete("/api/leases/dev1") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "https://evil.example")
            setBody("""{"owner":"a"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, c.post("/api/leases/dev1/renew") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "https://evil.example")
            setBody("""{"owner":"a"}""") }.status)
    }

    @Test fun mutatingRoutesAllowLoopbackOriginAndMissingOrigin() = withApi { c ->
        val same = c.post("/api/leases") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "http://127.0.0.1:8798")
            header(HttpHeaders.Host, "127.0.0.1:8798")
            setBody("""{"selector":{"serial":"dev1"},"owner":"a"}""") }
        assertEquals(HttpStatusCode.OK, same.status)

        val missing = c.post("/api/leases") {
            contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev2"},"owner":"a"}""") }
        assertEquals(HttpStatusCode.OK, missing.status)

        val otherPort = c.post("/api/leases") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "http://127.0.0.1:3000")
            header(HttpHeaders.Host, "127.0.0.1:8798")
            setBody("""{"selector":{"serial":"dev1"},"owner":"b"}""") }
        assertEquals(HttpStatusCode.Forbidden, otherPort.status)
    }

    @Test fun emulatorStartRejectsForeignOrigin() = withEmulatorApi(emptyList()) { c ->
        val foreign = c.post("/api/emulators/Pixel6AVD/start") {
            header(HttpHeaders.Origin, "https://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, foreign.status)

        val matched = c.post("/api/emulators/Pixel6AVD/start") {
            header(HttpHeaders.Origin, "http://127.0.0.1:8798")
            header(HttpHeaders.Host, "127.0.0.1:8798") }
        assertEquals(HttpStatusCode.Accepted, matched.status)
    }

    @Test fun statusReportsTheDaemonsAdbPath() {
        testApplication {
            corral(adbPath = "/custom/sdk/platform-tools/adb")
            val r = client.get("/api/status")
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("\"adb\":\"/custom/sdk/platform-tools/adb\""))
        }
    }

    @Test fun statusCarriesBooted() = withApi(
        listOf(Device("emulator-5554", "device", booted = false), Device("dev1", "device"))
    ) { c ->
        val devices = Json.parseToJsonElement(c.get("/api/status").bodyAsText())
            .jsonObject["devices"]!!.jsonArray
        val emu = devices.first { it.jsonObject["serial"]!!.jsonPrimitive.content == "emulator-5554" }
        assertFalse(emu.jsonObject["booted"]!!.jsonPrimitive.boolean)
    }

    @Test fun doctorReportsThreeLayersAndAdb() {
        val dir = createTempDirectory("doctor")
        val realAdb = dir.resolve("platform-tools/adb.real")
        Files.createDirectories(realAdb.parent)
        Files.writeString(realAdb, "REAL ADB v1")
        realAdb.toFile().setExecutable(true)
        Files.writeString(realAdb.resolveSibling("adb"), "#!/usr/bin/env bash\n# corral adb wrapper: enforces leasing.\n")

        val rulesPath = RulesFile.defaultPath()
        Files.createDirectories(rulesPath.parent)
        RulesFile.write(GateRules(setOf("devices"), setOf("-P")), rulesPath)

        testApplication {
            application {
                corralModule(AppEnv(testDevices, LeaseManager(testDevices, db = null), adbPath = realAdb.toString()))
            }
            val r = client.get("/api/doctor")
            assertEquals(HttpStatusCode.OK, r.status)
            val body = Json.decodeFromString<DoctorResponse>(r.bodyAsText())
            assertEquals(listOf("daemon", "sdk-shim", "rules"), body.layers.map { it.layer })
            assertEquals("ok", body.layers.first { it.layer == "daemon" }.status)
            assertEquals("ok", body.layers.first { it.layer == "sdk-shim" }.status)
            assertEquals("ok", body.layers.first { it.layer == "rules" }.status)
            assertEquals(realAdb.toString(), body.adb)
        }
    }

    @Test fun doctorReportsRulesFailAndShimSkipped() {
        Files.deleteIfExists(RulesFile.defaultPath())

        testApplication {
            application {
                corralModule(AppEnv(testDevices, LeaseManager(testDevices, db = null), adbPath = null))
            }
            val r = client.get("/api/doctor")
            assertEquals(HttpStatusCode.OK, r.status)
            val body = Json.decodeFromString<DoctorResponse>(r.bodyAsText())
            assertEquals(listOf("daemon", "sdk-shim", "rules"), body.layers.map { it.layer })
            assertEquals("ok", body.layers.first { it.layer == "daemon" }.status)
            assertEquals("skipped", body.layers.first { it.layer == "sdk-shim" }.status)
            assertEquals("fail", body.layers.first { it.layer == "rules" }.status)
        }
    }

    @Test fun doctorRulesFailWhenValueflagsEmpty() {
        // a present rules file with readonly but no valueflags must fail, matching `corral doctor`
        RulesFile.write(GateRules(setOf("devices"), emptySet()), RulesFile.defaultPath())
        testApplication {
            application {
                corralModule(AppEnv(testDevices, LeaseManager(testDevices, db = null), adbPath = null))
            }
            val body = Json.decodeFromString<DoctorResponse>(client.get("/api/doctor").bodyAsText())
            assertEquals("fail", body.layers.first { it.layer == "rules" }.status)
        }
    }

    @Test fun doctorWarnsOnDeviceTouchingReadonly() {
        RulesFile.write(GateRules(setOf("devices", "exec-in"), setOf("-P")), RulesFile.defaultPath())
        testApplication {
            application {
                corralModule(AppEnv(testDevices, LeaseManager(testDevices, db = null), adbPath = null))
            }
            val body = Json.decodeFromString<DoctorResponse>(client.get("/api/doctor").bodyAsText())
            val rules = body.layers.first { it.layer == "rules" }
            assertEquals("warn", rules.status)
            assertTrue(rules.detail.contains("exec-in"), "detail must name the non-host-only command: ${rules.detail}")
        }
    }

    @Test fun doctorWarnsOnIdentityListDivergence() {
        RulesFile.write(GateRules(setOf("devices"), setOf("-P"), setOf("bash"), DEFAULT_INIT_FLOOR), RulesFile.defaultPath())
        testApplication {
            application {
                corralModule(AppEnv(testDevices, LeaseManager(testDevices, db = null), adbPath = null))
            }
            val body = Json.decodeFromString<DoctorResponse>(client.get("/api/doctor").bodyAsText())
            val rules = body.layers.first { it.layer == "rules" }
            assertEquals("warn", rules.status)
            assertTrue(rules.detail.contains("identity"), "detail must mention the identity divergence: ${rules.detail}")
        }
    }

    @Test fun statusListsLeases() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val r = c.get("/api/status")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"serial\":\"dev1\""))
    }

    @Test fun statusCarriesAVersion() = withApi { c ->
        val body = Json.decodeFromString<StatusResponse>(c.get("/api/status").bodyAsText())
        assertTrue(body.version.isNotBlank(), "the status version must be non-blank")
        assertTrue(Regex("""\d+\.\d+\.\d+.*""").matches(body.version),
            "the status version must be version-shaped, got: ${body.version}")
    }

    @Test fun freeAllOnlyOwned() = withApi(
        listOf(Device("dev1", "device"), Device("dev2", "device"), Device("dev3", "device"))
    ) { c ->
        suspend fun claim(serial: String, owner: String) = c.post("/api/leases") {
            contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"$serial"},"owner":"$owner"}""") }
        suspend fun status() = Json.decodeFromString<StatusResponse>(c.get("/api/status").bodyAsText())

        claim("dev1", "agentA"); claim("dev2", "agentA"); claim("dev3", "agentB")

        // mirrors the CLI `free --all` loop: read status, DELETE every serial the caller owns
        val owned = status().devices.filter { it.lease?.owner == "agentA" }.map { it.serial }
        owned.forEach { serial ->
            c.delete("/api/leases/$serial") {
                contentType(ContentType.Application.Json); setBody("""{"owner":"agentA"}""") }
        }

        val after = status()
        assertNull(after.devices.first { it.serial == "dev1" }.lease)
        assertNull(after.devices.first { it.serial == "dev2" }.lease)
        assertEquals("agentB", after.devices.first { it.serial == "dev3" }.lease?.owner)
    }

    @Test fun modelSelectorClaimsMatchingDevice() = withApi { c ->
        val r = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"model":"Pixel_6"},"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"serial\":\"dev1\""))
    }

    @Test fun noMatchSelectorReportsMatchingZero() = withApi { c ->
        val r = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"model":"iPhone"},"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("\"matching\":0"))
    }

    @Test fun waitEnqueuesTicketAndPollOffers() = withApi { c ->
        val enq = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA","waitSeconds":60}""") }
        assertEquals(HttpStatusCode.Accepted, enq.status)
        assertTrue(enq.bodyAsText().contains("\"retryAfterMs\":1000"))
        val ticket = enq.bodyAsText().ticketOf()
        val poll = c.post("/api/leases/tickets/$ticket/poll") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentA","pid":1234}""") }
        assertEquals(HttpStatusCode.OK, poll.status)
        assertTrue(poll.bodyAsText().contains("\"serial\":\"dev1\""))
        assertTrue(poll.bodyAsText().contains("\"owner\":\"agentA\""))
        assertTrue(poll.bodyAsText().contains("\"pid\":1234"))
    }

    @Test fun claimTicketEchoesClampedClientCadence() = withApi { c ->
        suspend fun enqueue(body: String) = c.post("/api/leases") {
            contentType(ContentType.Application.Json); setBody(body) }

        val small = enqueue("""{"selector":{"serial":"dev1"},"owner":"agentA","waitSeconds":60,"retryAfterMs":500}""")
        assertTrue(small.bodyAsText().contains("\"retryAfterMs\":1000"))
        val big = enqueue("""{"selector":{"serial":"dev1"},"owner":"agentB","waitSeconds":60,"retryAfterMs":999999}""")
        assertTrue(big.bodyAsText().contains("\"retryAfterMs\":10000"))
        val absent = enqueue("""{"selector":{"serial":"dev1"},"owner":"agentC","waitSeconds":60}""")
        assertTrue(absent.bodyAsText().contains("\"retryAfterMs\":1000"))
    }

    @Test fun queuedTicketResolvedByFree() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val enq = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentB","waitSeconds":60}""") }
        assertEquals(HttpStatusCode.Accepted, enq.status)
        val ticket = enq.bodyAsText().ticketOf()

        val queued = c.post("/api/leases/tickets/$ticket/poll") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentB"}""") }
        assertEquals(HttpStatusCode.Accepted, queued.status)
        assertTrue(queued.bodyAsText().contains("\"state\":\"queued\""))
        assertTrue(queued.bodyAsText().contains("\"retryAfterMs\":1000"))

        c.delete("/api/leases/dev1") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentA"}""") }
        val offered = c.post("/api/leases/tickets/$ticket/poll") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentB"}""") }
        assertEquals(HttpStatusCode.OK, offered.status)
        assertTrue(offered.bodyAsText().contains("\"serial\":\"dev1\""))
        assertTrue(offered.bodyAsText().contains("\"owner\":\"agentB\""))
    }

    @Test fun unknownOrExpiredTicketPollGone() {
        val now = AtomicLong(1500)
        val leases = LeaseManager(testDevices, db = null, clock = { now.get() })
        withApi(leases) { c ->
            val unknown = c.post("/api/leases/tickets/nope/poll") { contentType(ContentType.Application.Json)
                setBody("""{"owner":"agentA"}""") }
            assertEquals(HttpStatusCode.Gone, unknown.status)
            assertTrue(unknown.bodyAsText().contains("ticket expired or unknown"))

            val expiredEnq = c.post("/api/leases") { contentType(ContentType.Application.Json)
                setBody("""{"selector":{"serial":"dev1"},"owner":"agentA","waitSeconds":5}""") }
            val expiredTicket = expiredEnq.bodyAsText().ticketOf()
            now.addAndGet(6)
            val expired = c.post("/api/leases/tickets/$expiredTicket/poll") { contentType(ContentType.Application.Json)
                setBody("""{"owner":"agentA"}""") }
            assertEquals(HttpStatusCode.Gone, expired.status)
            assertTrue(expired.bodyAsText().contains("ticket deadline expired"))

            val silentEnq = c.post("/api/leases") { contentType(ContentType.Application.Json)
                setBody("""{"selector":{"serial":"dev1"},"owner":"agentA","waitSeconds":600}""") }
            val silentTicket = silentEnq.bodyAsText().ticketOf()
            now.addAndGet(POLL_SILENCE_S + 1)
            val silent = c.post("/api/leases/tickets/$silentTicket/poll") { contentType(ContentType.Application.Json)
                setBody("""{"owner":"agentA"}""") }
            assertEquals(HttpStatusCode.Gone, silent.status)
            assertTrue(silent.bodyAsText().contains("ticket dropped: not polled in time"))

            val ghostEnq = c.post("/api/leases") { contentType(ContentType.Application.Json)
                setBody("""{"selector":{"serial":"dev2"},"owner":"ghost","pid":2000000000,"waitSeconds":60}""") }
            val ghostTicket = ghostEnq.bodyAsText().ticketOf()
            val swept = c.post("/api/leases/tickets/$ghostTicket/poll") { contentType(ContentType.Application.Json)
                setBody("""{"owner":"ghost"}""") }
            assertEquals(HttpStatusCode.Gone, swept.status)
            assertTrue(swept.bodyAsText().contains("ticket expired or unknown"))
        }
    }

    @Test fun cancelTicketRemovesIt() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val enq = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentB","waitSeconds":60}""") }
        val ticket = enq.bodyAsText().ticketOf()

        val cancelled = c.delete("/api/leases/tickets/$ticket") {
            contentType(ContentType.Application.Json); setBody("""{"owner":"agentB"}""") }
        assertEquals(HttpStatusCode.OK, cancelled.status)
        assertTrue(cancelled.bodyAsText().contains("\"cancelled\":true"))
        val gone = c.post("/api/leases/tickets/$ticket/poll") {
            contentType(ContentType.Application.Json); setBody("""{"owner":"agentB"}""") }
        assertEquals(HttpStatusCode.Gone, gone.status)
        assertTrue(gone.bodyAsText().contains("ticket expired or unknown"))
    }

    @Test fun cancelTicketWithWrongOwnerForbidden() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val enq = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentB","waitSeconds":60}""") }
        val ticket = enq.bodyAsText().ticketOf()

        val r = c.delete("/api/leases/tickets/$ticket") {
            contentType(ContentType.Application.Json); setBody("""{"owner":"agentC"}""") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test fun cancelUnknownTicketNotFound() = withApi { c ->
        val r = c.delete("/api/leases/tickets/nope") {
            contentType(ContentType.Application.Json); setBody("""{"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test fun cancelTicketQueryFallbackStillWorks() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        val enq = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentB","waitSeconds":60}""") }
        val ticket = enq.bodyAsText().ticketOf()

        assertEquals(HttpStatusCode.OK, c.delete("/api/leases/tickets/$ticket?owner=agentB").status)
    }

    @Test fun cancelTicketWithoutOwnerBadRequest() = withApi { c ->
        val r = c.delete("/api/leases/tickets/nope") {
            contentType(ContentType.Application.Json); setBody("""{"notOwner":true}""") }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test fun waitReturnsTicketWithoutParkingWaiter() = withApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"agentA"}""") }
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev2"},"owner":"agentA"}""") }
        val enq = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{},"owner":"waiter","waitSeconds":60}""") }
        assertEquals(HttpStatusCode.Accepted, enq.status)
        assertTrue(c.get("/api/queue").bodyAsText().contains("\"owner\":\"waiter\""))
    }

    @Test fun malformedJsonIsClientError() = withApi { c ->
        val r = c.post("/api/leases") { contentType(ContentType.Application.Json); setBody("{not json") }
        assertTrue(r.status.value in 400..499, "malformed JSON must be a 4xx, got ${r.status}")
    }

    @Test fun unknownSerialClaimFreeRenew() = withApi { c ->
        val claim = c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"zzz"},"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.Conflict, claim.status)
        assertTrue(claim.bodyAsText().contains("\"matching\":0"))

        val free = c.delete("/api/leases/zzz") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.OK, free.status)
        assertTrue(free.bodyAsText().contains("\"freed\":true"))

        val renew = c.post("/api/leases/zzz/renew") { contentType(ContentType.Application.Json)
            setBody("""{"owner":"agentA"}""") }
        assertEquals(HttpStatusCode.Conflict, renew.status)
        assertTrue(renew.bodyAsText().contains("no lease on zzz"))
    }

    @Test fun routeShapesStable() = withApi { c ->
        val devices = Json.parseToJsonElement(c.get("/api/devices").bodyAsText()).jsonArray
        val dev = devices.first().jsonObject
        assertTrue(dev["serial"] is JsonPrimitive && dev["serial"]!!.jsonPrimitive.isString)
        assertTrue(dev["state"] is JsonPrimitive && dev["state"]!!.jsonPrimitive.isString)

        val status = Json.parseToJsonElement(c.get("/api/status").bodyAsText()).jsonObject
        assertTrue(status["devices"] is JsonArray)
        assertTrue(status["adb"] is JsonPrimitive && status["adb"]!!.jsonPrimitive.isString)
        val ds = status["devices"]!!.jsonArray.first().jsonObject
        assertTrue(ds["serial"] is JsonPrimitive && ds["serial"]!!.jsonPrimitive.isString)
        assertTrue(ds.containsKey("lease"))

        // enqueue one ticket so the queue entry shape is pinned, not just "empty array"
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{},"owner":"waiter","waitSeconds":60}""") }
        val queue = Json.parseToJsonElement(c.get("/api/queue").bodyAsText()).jsonArray
        val q = queue.first().jsonObject
        assertTrue(q["position"] is JsonPrimitive && q["position"]!!.jsonPrimitive.content.toLongOrNull() != null)
        assertTrue(q["owner"] is JsonPrimitive && q["owner"]!!.jsonPrimitive.isString)
        assertTrue(q["selector"] is JsonPrimitive && q["selector"]!!.jsonPrimitive.isString)
        assertTrue(q["waitedSeconds"] is JsonPrimitive && q["waitedSeconds"]!!.jsonPrimitive.content.toLongOrNull() != null)
        assertTrue(q["deadlineIn"] is JsonPrimitive && q["deadlineIn"]!!.jsonPrimitive.content.toLongOrNull() != null)

        val check = Json.parseToJsonElement(c.get("/api/check?serial=dev1&owner=nobody").bodyAsText()).jsonObject
        assertEquals("false", check["allowed"]!!.jsonPrimitive.content)
        assertTrue(check["reason"] is JsonPrimitive && check["reason"]!!.jsonPrimitive.isString)
    }

    @Test fun checkIndependentOfQueueState() {
        val leases = LeaseManager(testDevices, db = null)
        withApi(leases) { c ->
            suspend fun post(body: String) = c.post("/api/leases") {
                contentType(ContentType.Application.Json); setBody(body) }
            post("""{"selector":{"serial":"dev1"},"owner":"holder"}""")
            post("""{"selector":{"serial":"dev1"},"owner":"waiter","waitSeconds":60}""")
            c.delete("/api/leases/dev1") { contentType(ContentType.Application.Json)
                setBody("""{"owner":"holder"}""") }

            assertTrue(leases.snapshot["dev1"]!!.pendingAccept)   // the queued ticket holds dev1

            val foreign = Json.parseToJsonElement(
                c.get("/api/check?serial=dev1&owner=someone-else").bodyAsText()).jsonObject
            assertEquals("false", foreign["allowed"]!!.jsonPrimitive.content)
            assertTrue(foreign["reason"]!!.jsonPrimitive.content.contains("waiter"))
            assertTrue(leases.snapshot["dev1"]!!.pendingAccept)   // a foreign check does not touch the offer
        }
    }

    @Test fun screenshotServesPngBytes() = withScreenshotApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"me"}""") }
        val r = c.get("/api/devices/dev1/screenshot?owner=me")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(ContentType.Image.PNG, r.contentType())
        assertEquals("fake-screen", r.bodyAsText())
    }

    @Test fun screenshotUnwiredIs503() = withApi { c ->
        assertEquals(HttpStatusCode.ServiceUnavailable,
            c.get("/api/devices/dev1/screenshot?owner=me").status)
    }

    @Test fun screenshotStrictDeniesStranger() = withScreenshotApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"me"}""") }
        val r = c.get("/api/devices/dev1/screenshot?owner=stranger")
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("held by me"))
        assertTrue(r.bodyAsText().contains("CORRAL_SCREENSHOTS=open"))
    }

    @Test fun screenshotStrictAllowsHolder() = withScreenshotApi { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"dev1"},"owner":"me"}""") }
        assertEquals(HttpStatusCode.OK, c.get("/api/devices/dev1/screenshot?owner=me").status)
    }

    @Test fun screenshotStrictAllowsObserver() =
        withScreenshotApi(policy = ScreenshotPolicy("strict", "corral-ui")) { c ->
            assertEquals(HttpStatusCode.OK, c.get("/api/devices/dev1/screenshot?owner=corral-ui").status)
        }

    @Test fun screenshotStrictUnknownSerialIs403Not404() =
        withScreenshotApi(policy = ScreenshotPolicy("strict", null)) { c ->
            val r = c.get("/api/devices/zzz/screenshot?owner=me")
            assertEquals(HttpStatusCode.Forbidden, r.status)          // policy first: no existence leak
            assertTrue(r.bodyAsText().contains("not leased"))
        }

    @Test fun screenshotUnknownSerialIs404() = withScreenshotApi(policy = ScreenshotPolicy("open")) { c ->
        assertEquals(HttpStatusCode.NotFound, c.get("/api/devices/zzz/screenshot?owner=me").status)
    }

    @Test fun screenshotUnusableDeviceIs404() = withScreenshotApi(
        devices = listOf(Device("dev1", "offline")), policy = ScreenshotPolicy("open")
    ) { c ->
        val r = c.get("/api/devices/dev1/screenshot?owner=me")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("offline"))
    }

    @Test fun screenshotAdbFailureIs502() =
        withScreenshotApi(policy = ScreenshotPolicy("open"), failAdb = true) { c ->
            assertEquals(HttpStatusCode.BadGateway, c.get("/api/devices/dev1/screenshot?owner=me").status)
        }

    @Test fun emulatorsListsWithRunningSerials() = withEmulatorApi(
        listOf(Device("emulator-5554", "device", avd = "Pixel6AVD"))
    ) { c ->
        val r = c.get("/api/emulators")
        assertEquals(HttpStatusCode.OK, r.status)
        val list = Json.parseToJsonElement(r.bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals(listOf("Pixel6AVD", "SmallAVD"), list.map { it["avd"]!!.jsonPrimitive.content })
        val running = list.first { it["avd"]!!.jsonPrimitive.content == "Pixel6AVD" }["runningSerials"]!!
        assertEquals(listOf("emulator-5554"), running.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(list.first { it["avd"]!!.jsonPrimitive.content == "SmallAVD" }["runningSerials"]!!
            .jsonArray.isEmpty())
    }

    @Test fun emulatorsReconcileAnOfflineInstanceFromItsLog() {
        val adbLog = createTempDirectory("emu").resolve("invocations")
        withEmulatorApi(listOf(Device("emulator-5554", "offline")), adbLog = adbLog) { c ->
            val home = createTempDirectory("home")
            Files.createDirectories(home.resolve(".corral"))
            Files.writeString(home.resolve(".corral/emulator-Pixel6AVD.log"), "INFO | console emulator-5554\n")
            val previous = System.getProperty("user.home")
            System.setProperty("user.home", home.toString())
            try {
                val list = Json.parseToJsonElement(c.get("/api/emulators").bodyAsText()).jsonArray.map { it.jsonObject }
                val entry = list.first { it["avd"]!!.jsonPrimitive.content == "Pixel6AVD" }
                assertEquals(listOf("emulator-5554"),
                    entry["runningSerials"]!!.jsonArray.map { it.jsonPrimitive.content })

                val start = c.post("/api/emulators/Pixel6AVD/start")
                assertEquals(HttpStatusCode.OK, start.status, start.bodyAsText())
                assertTrue(start.bodyAsText().contains("\"alreadyRunning\":true"), start.bodyAsText())

                val stop = c.post("/api/emulators/Pixel6AVD/stop") {
                    contentType(ContentType.Application.Json); setBody("{}") }
                assertEquals(HttpStatusCode.OK, stop.status, stop.bodyAsText())
                assertTrue(stop.bodyAsText().contains("\"stopped\":[\"emulator-5554\"]"), stop.bodyAsText())
                assertEquals(listOf("-s emulator-5554 emu kill"), Files.readAllLines(adbLog))
            } finally {
                System.setProperty("user.home", previous)
            }
        }
    }

    @Test fun startSpawnsUnknown404ListsAvds() = withEmulatorApi(emptyList()) { c ->
        val unknown = c.post("/api/emulators/Nope/start")
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertTrue(unknown.bodyAsText().contains("Pixel6AVD"))
        val previous = System.getProperty("user.home")
        System.setProperty("user.home", createTempDirectory("home").toString())
        try {
            val started = c.post("/api/emulators/Pixel6AVD/start")
            assertEquals(HttpStatusCode.Accepted, started.status)
            assertTrue(started.bodyAsText().contains("\"started\":true"))
        } finally {
            System.setProperty("user.home", previous)
        }
    }

    @Test fun startIsIdempotentWhileRunningOrBooting() = withEmulatorApi(
        listOf(Device("emulator-5554", "device", avd = "Pixel6AVD", booted = false))
    ) { c ->
        val r = c.post("/api/emulators/Pixel6AVD/start")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"alreadyRunning\":true"))
    }

    @Test fun startAnswersAlreadyRunningWhenALiveSpawnIsNotInThePool() {
        val live = object : EmulatorLauncher {
            override fun start(bin: String, avd: String, log: Path) {}
            override fun isRunning(avd: String): Boolean = true
            override fun stop(avd: String): String? = null
            override fun lastExit(avd: String): Int? = null
        }
        withEmulatorApi(emptyList(), launcher = live) { c ->
            val r = c.post("/api/emulators/Pixel6AVD/start")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            assertTrue(r.bodyAsText().contains("\"alreadyRunning\":true"))
        }
    }

    @Test fun startAnswersAlreadyRunningWhenTheLauncherRaces() {
        val racy = object : EmulatorLauncher {
            override fun start(bin: String, avd: String, log: Path) { throw EmulatorAlreadyRunning() }
            override fun isRunning(avd: String): Boolean = false
            override fun stop(avd: String): String? = null
            override fun lastExit(avd: String): Int? = null
        }
        withEmulatorApi(emptyList(), launcher = racy) { c ->
            val r = c.post("/api/emulators/Pixel6AVD/start")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            assertTrue(r.bodyAsText().contains("\"alreadyRunning\":true"))
        }
    }

    @Test fun startIsIdempotentWhileASpawnIsLiveButNotYetInThePool() = withEmulatorApi(emptyList()) { c ->
        val previous = System.getProperty("user.home")
        System.setProperty("user.home", createTempDirectory("home").toString())
        try {
            val first = c.post("/api/emulators/Pixel6AVD/start")
            assertEquals(HttpStatusCode.Accepted, first.status)
            assertTrue(first.bodyAsText().contains("\"alreadyRunning\":false"))
            val second = c.post("/api/emulators/Pixel6AVD/start")
            assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
            assertTrue(second.bodyAsText().contains("\"alreadyRunning\":true"), second.bodyAsText())
        } finally {
            System.setProperty("user.home", previous)
        }
    }

    @Test fun startDeadOnArrivalIs502WithTheLogTail() = withEmulatorApi(
        emptyList(), avdScript = "echo 'requires hardware acceleration' >&2; exit 1"
    ) { c ->
        val previous = System.getProperty("user.home")
        val r = try {
            System.setProperty("user.home", createTempDirectory("home").toString())
            c.post("/api/emulators/Pixel6AVD/start")
        } finally {
            System.setProperty("user.home", previous)
        }
        assertEquals(HttpStatusCode.BadGateway, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("exited immediately"), r.bodyAsText())
        assertTrue(r.bodyAsText().contains("hardware acceleration"), r.bodyAsText())
    }

    @Test fun stopForceFreesTheLeaseItKills() = withEmulatorApi(
        listOf(Device("emulator-5554", "device", avd = "Pixel6AVD", booted = true))
    ) { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"emulator-5554"},"owner":"a"}""") }
        val r = c.post("/api/emulators/Pixel6AVD/stop") {
            contentType(ContentType.Application.Json); setBody("""{"force":true}""") }
        assertEquals(HttpStatusCode.OK, r.status)
        val status = c.get("/api/status").bodyAsText()
        assertTrue(!status.contains("\"owner\":\"a\""), "lease survived the stop: $status")
    }

    @Test fun stopKillsAllSerialsOfTheAvd() {
        val log = createTempDirectory("emu").resolve("invocations")
        withEmulatorApi(
            listOf(Device("emulator-5554", "device", avd = "Pixel6AVD", booted = true)), adbLog = log
        ) { c ->
            val r = c.post("/api/emulators/Pixel6AVD/stop") {
                contentType(ContentType.Application.Json); setBody("{}") }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("\"stopped\":[\"emulator-5554\"]"))
            assertEquals(listOf("-s emulator-5554 emu kill"), Files.readAllLines(log))
        }
    }

    @Test fun stopRefusedWhileLeased() = withEmulatorApi(
        listOf(Device("emulator-5554", "device", avd = "Pixel6AVD", booted = true))
    ) { c ->
        c.post("/api/leases") { contentType(ContentType.Application.Json)
            setBody("""{"selector":{"serial":"emulator-5554"},"owner":"a"}""") }
        val r = c.post("/api/emulators/Pixel6AVD/stop") {
            contentType(ContentType.Application.Json); setBody("""{"force":false}""") }
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("leased by a"))
        assertTrue(r.bodyAsText().contains("force"))
    }

    @Test fun stopForceOverrides() {
        val launcher = RecordingLauncher()
        withEmulatorApi(
            listOf(Device("emulator-5554", "device", avd = "Pixel6AVD", booted = true)), launcher = launcher
        ) { c ->
            c.post("/api/leases") { contentType(ContentType.Application.Json)
                setBody("""{"selector":{"serial":"emulator-5554"},"owner":"a"}""") }
            val r = c.post("/api/emulators/Pixel6AVD/stop") {
                contentType(ContentType.Application.Json); setBody("""{"force":true}""") }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("\"stopped\":[\"emulator-5554\"]"))
            assertEquals(listOf("Pixel6AVD"), launcher.stopped)
        }
    }

    @Test fun stopEndsTheUnitEvenWhenAdbDoesNotListIt() {
        val launcher = RecordingLauncher()
        withEmulatorApi(emptyList(), launcher = launcher) { c ->
            val r = c.post("/api/emulators/SmallAVD/stop") {
                contentType(ContentType.Application.Json); setBody("{}") }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("\"stopped\":[]"), r.bodyAsText())
            assertTrue(r.bodyAsText().contains("\"terminated\":\"unit ${systemdUnitName("SmallAVD")}\""), r.bodyAsText())
            assertEquals(listOf("SmallAVD"), launcher.stopped)
        }
    }

    @Test fun stopDropsTheEmulatorFromThePoolAndKeepsItOutAcrossARepoll() {
        var t = 1_000_000L
        val pool = mutableListOf(Device("emulator-5554", "device", avd = "Pixel6AVD", booted = true))
        withEmulatorApi(pool, launcher = RecordingLauncher(), now = { t }) { c ->
            assertTrue(c.get("/api/devices").bodyAsText().contains("emulator-5554"))   // BEFORE
            val stop = c.post("/api/emulators/Pixel6AVD/stop") {
                contentType(ContentType.Application.Json); setBody("{}") }
            assertEquals(HttpStatusCode.OK, stop.status, stop.bodyAsText())
            assertFalse(c.get("/api/devices").bodyAsText().contains("emulator-5554"))  // immediately AFTER

            // the monitor's next poll still gets the killed serial from adb: it must not re-add it
            pool.clear(); pool.add(Device("emulator-5554", "device", avd = "Pixel6AVD", booted = true))
            assertFalse(c.get("/api/devices").bodyAsText().contains("emulator-5554"))

            // and it stays out while adb keeps listing it, inside the suppression window
            t += 60_000
            assertFalse(c.get("/api/devices").bodyAsText().contains("emulator-5554"))
        }
    }

    @Test fun repeatStopOfACorralTerminatedAvdIsTruthfulAndNeverSaysWedged() {
        val launched = object : EmulatorLauncher {
            @Volatile var running = true
            override fun start(bin: String, avd: String, log: Path) {}
            override fun isRunning(avd: String): Boolean = running
            override fun stop(avd: String): String? = if (running) { running = false; "pid 34472" } else null
            override fun lastExit(avd: String): Int? = null
        }
        val adbLog = createTempDirectory("emu").resolve("invocations")
        val pool = mutableListOf(Device("emulator-5554", "device", avd = "Pixel6AVD", booted = true))
        withEmulatorApi(pool, adbLog = adbLog, launcher = launched) { c ->
            val before = c.get("/api/devices").bodyAsText()
            val first = c.post("/api/emulators/Pixel6AVD/stop") {
                contentType(ContentType.Application.Json); setBody("{}") }
            assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
            val after = c.get("/api/devices").bodyAsText()
            pool.clear(); pool.add(Device("emulator-5554", "device", avd = "Pixel6AVD", booted = true))
            val repoll = c.get("/api/devices").bodyAsText()

            val second = c.post("/api/emulators/Pixel6AVD/stop") {
                contentType(ContentType.Application.Json); setBody("{}") }
            assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
            assertFalse(second.bodyAsText().contains("wedged"), second.bodyAsText())
            assertTrue(second.bodyAsText().contains("\"stopped\":[]"), second.bodyAsText())
            assertFalse(second.bodyAsText().contains("\"terminated\""), second.bodyAsText())
            // the second stop must not have tried to kill the already-dead serial again
            assertEquals(listOf("-s emulator-5554 emu kill"), Files.readAllLines(adbLog))
            assertTrue(before.contains("emulator-5554"))
            assertFalse(after.contains("emulator-5554"))    // gone before the monitor's next poll
            assertFalse(repoll.contains("emulator-5554"))   // and not re-added by it
        }
    }

    @Test fun aFailedKillIsStillReportedAndIsNotSuppressedFromThePool() {
        val launcher = RecordingLauncher().apply { running = true }
        val dir = createTempDirectory("emu")
        val adb = dir.resolve("fake-adb").also {
            Files.writeString(it, "#!/usr/bin/env bash\necho 'no emu' >&2\nexit 3\n")
            it.toFile().setExecutable(true)
        }
        val devs = listOf(Device("emulator-5554", "device", avd = "Pixel6AVD", booted = true))
        val emulators = Emulators(adb.toString(), { null }, launcher)
        testApplication {
            application { corralModule(AppEnv({ devs }, LeaseManager({ devs }, db = null), emulators = emulators)) }
            val r = client.post("/api/emulators/Pixel6AVD/stop") {
                contentType(ContentType.Application.Json); setBody("""{"force":true}""") }
            assertEquals(HttpStatusCode.BadGateway, r.status, r.bodyAsText())
            assertTrue(r.bodyAsText().contains("no emu"), r.bodyAsText())
            // the process is still alive, so the pool must still list it
            assertTrue(client.get("/api/devices").bodyAsText().contains("emulator-5554"))
        }
    }

    @Test fun aCorralTerminatedEmulatorNeverSurfacesAWedgeOrDoctorLayer() {
        var t = 1_000_000L
        val launcher = RecordingLauncher()
        val pool = mutableListOf<Device>()
        withEmulatorApi(pool, launcher = launcher, now = { t }) { c ->
            val home = createTempDirectory("home")
            Files.createDirectories(home.resolve(".corral"))
            val previous = System.getProperty("user.home")
            System.setProperty("user.home", home.toString())
            try {
                assertEquals(HttpStatusCode.Accepted, c.post("/api/emulators/Pixel6AVD/start").status)
                launcher.running = true
                Files.writeString(home.resolve(".corral/emulator-Pixel6AVD.log"), "INFO | console emulator-5554\n")
                pool.add(Device("emulator-5554", "offline"))
                t += MAX_WAIT_S * 1000
                assertTrue(c.get("/api/devices").bodyAsText().contains("\"wedged\":true"))   // wedged before the stop

                val stop = c.post("/api/emulators/Pixel6AVD/stop") {
                    contentType(ContentType.Application.Json); setBody("{}") }
                assertEquals(HttpStatusCode.OK, stop.status, stop.bodyAsText())

                // even a monitor re-poll that re-lists the serial shows no wedge flag and no doctor layer
                pool.clear(); pool.add(Device("emulator-5554", "offline"))
                assertFalse(c.get("/api/devices").bodyAsText().contains("\"wedged\":true"))
                assertFalse(Json.decodeFromString<DoctorResponse>(c.get("/api/doctor").bodyAsText())
                    .layers.any { it.layer == "emulators" })
            } finally {
                System.setProperty("user.home", previous)
            }
        }
    }

    @Test fun emulatorsShowsTheLogTailAfterANonZeroExit() {
        val crashed = exitLauncher(1)
        withEmulatorApi(emptyList(), launcher = crashed) { c ->
            val home = createTempDirectory("home")
            Files.createDirectories(home.resolve(".corral"))
            Files.writeString(home.resolve(".corral/emulator-Pixel6AVD.log"),
                "loading\nERROR: x86_64 emulation requires hardware acceleration\n")
            val previous = System.getProperty("user.home")
            System.setProperty("user.home", home.toString())
            try {
                val body = c.get("/api/emulators").bodyAsText()
                assertTrue(body.contains("\"error\":\"ERROR: x86_64 emulation requires hardware acceleration\""), body)
            } finally {
                System.setProperty("user.home", previous)
            }
        }
    }

    @Test fun emulatorsHidesTheLogTailAfterACleanSelfExit() {
        withEmulatorApi(emptyList(), launcher = exitLauncher(0)) { c ->
            val home = createTempDirectory("home")
            Files.createDirectories(home.resolve(".corral"))
            Files.writeString(home.resolve(".corral/emulator-Pixel6AVD.log"),
                "INFO | Emulator window closed by user\n")
            val previous = System.getProperty("user.home")
            System.setProperty("user.home", home.toString())
            try {
                assertFalse(c.get("/api/emulators").bodyAsText().contains("\"error\""))
            } finally {
                System.setProperty("user.home", previous)
            }
        }
    }

    @Test fun emulatorsHidesTheLogTailWithoutAnExitSidecar() {
        withEmulatorApi(emptyList()) { c ->
            val home = createTempDirectory("home")
            Files.createDirectories(home.resolve(".corral"))
            Files.writeString(home.resolve(".corral/emulator-Pixel6AVD.log"), "stale line from an unknown run\n")
            val previous = System.getProperty("user.home")
            System.setProperty("user.home", home.toString())
            try {
                assertFalse(c.get("/api/emulators").bodyAsText().contains("\"error\""))
            } finally {
                System.setProperty("user.home", previous)
            }
        }
    }

    @Test fun emulatorsHidesTheLogTailWhileRunning() {
        withEmulatorApi(listOf(Device("emulator-5554", "device", avd = "Pixel6AVD"))) { c ->
            val home = createTempDirectory("home")
            Files.createDirectories(home.resolve(".corral"))
            Files.writeString(home.resolve(".corral/emulator-Pixel6AVD.log"), "ERROR stale\n")
            val previous = System.getProperty("user.home")
            System.setProperty("user.home", home.toString())
            try {
                val body = c.get("/api/emulators").bodyAsText()
                assertFalse(body.contains("\"error\""), body)
            } finally {
                System.setProperty("user.home", previous)
            }
        }
    }

    @Test fun stopEndsTheUnitWhenEmuKillFails() {
        val launcher = RecordingLauncher()
        val dir = createTempDirectory("emu")
        val adb = dir.resolve("fake-adb").also {
            Files.writeString(it, "#!/usr/bin/env bash\necho 'no emu' >&2\nexit 3\n")
            it.toFile().setExecutable(true)
        }
        val devs = listOf(Device("emulator-5554", "device", avd = "Pixel6AVD", booted = true))
        val emulators = Emulators(adb.toString(), { null }, launcher)
        testApplication {
            application { corralModule(AppEnv({ devs }, LeaseManager({ devs }, db = null), emulators = emulators)) }
            val r = client.post("/api/emulators/Pixel6AVD/stop") {
                contentType(ContentType.Application.Json); setBody("""{"force":true}""") }
            assertEquals(HttpStatusCode.BadGateway, r.status)
            assertEquals(listOf("Pixel6AVD"), launcher.stopped)   // unit ended despite the failed kill
        }
    }

    @Test fun emulatorsHidesAStaleErrorWhileASpawnIsLive() {
        val live = object : EmulatorLauncher {
            override fun start(bin: String, avd: String, log: Path) {}
            override fun isRunning(avd: String): Boolean = true
            override fun stop(avd: String): String? = null
            override fun lastExit(avd: String): Int? = null
        }
        withEmulatorApi(emptyList(), launcher = live) { c ->
            val home = createTempDirectory("home")
            Files.createDirectories(home.resolve(".corral"))
            Files.writeString(home.resolve(".corral/emulator-Pixel6AVD.log"), "ERROR stale\n")
            val previous = System.getProperty("user.home")
            System.setProperty("user.home", home.toString())
            try {
                assertFalse(c.get("/api/emulators").bodyAsText().contains("\"error\""))
            } finally {
                System.setProperty("user.home", previous)
            }
        }
    }

    /** The emulator entry of a `/api/devices` body. */
    private fun wedgeOf(body: String) =
        Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
            .firstOrNull { it["serial"]!!.jsonPrimitive.content.startsWith("emulator-") }

    /**
     * The Windows smoke's H2: the daemon launched the AVD, the process is alive, and the serial
     * never reached device+booted past the boot deadline. Three cases over one virtual clock —
     * wedged, still inside the deadline, and recovered.
     */
    @Test fun wedgedEmulatorIsFlaggedOnlyPastTheBootDeadlineAndUnflaggedOnceItBoots() {
        var t = 1_000_000L
        val launcher = RecordingLauncher()
        val pool = mutableListOf<Device>()
        withEmulatorApi(pool, launcher = launcher, now = { t }) { c ->
            val home = createTempDirectory("home")
            Files.createDirectories(home.resolve(".corral"))
            val previous = System.getProperty("user.home")
            System.setProperty("user.home", home.toString())
            try {
                // the daemon launches it; adb then lists the console serial, offline, forever
                val started = c.post("/api/emulators/Pixel6AVD/start")
                assertEquals(HttpStatusCode.Accepted, started.status, started.bodyAsText())
                launcher.running = true
                Files.writeString(home.resolve(".corral/emulator-Pixel6AVD.log"), "INFO | console emulator-5554\n")
                pool.add(Device("emulator-5554", "offline"))

                // inside the deadline: booting is still the honest reading
                t += MAX_WAIT_S * 1000 - 1
                assertFalse(wedgeOf(c.get("/api/devices").bodyAsText())?.get("wedged")?.jsonPrimitive?.boolean
                    ?: false, "flagged inside the boot deadline")

                // past the deadline, alive, never listed as device+booted -> wedged
                t += 1
                val wedged = wedgeOf(c.get("/api/devices").bodyAsText())!!
                assertEquals(true, wedged["wedged"]!!.jsonPrimitive.boolean)
                val reason = wedged["wedgeReason"]!!.jsonPrimitive.content
                assertTrue(reason.contains("without registering"), reason)
                assertTrue(reason.contains("corral avd stop Pixel6AVD"), wedged.toString())
                val doctor = Json.decodeFromString<DoctorResponse>(c.get("/api/doctor").bodyAsText())
                val layer = doctor.layers.single { it.layer == "emulators" }
                assertEquals("fail", layer.status)
                assertTrue(layer.detail.contains("corral avd stop Pixel6AVD"), layer.detail)

                // recovered: now device+booted -> the flag and the doctor entry are gone
                pool.clear(); pool.add(Device("emulator-5554", "device", booted = true))
                assertEquals(false, c.get("/api/devices").bodyAsText().contains("\"wedged\":true"))
                assertFalse(Json.decodeFromString<DoctorResponse>(c.get("/api/doctor").bodyAsText())
                    .layers.any { it.layer == "emulators" })
            } finally {
                System.setProperty("user.home", previous)
            }
        }
    }

    @Test fun wedgeReasonSaysRegisteredWhenASerialBootedThenDroppedOffline() {
        var t = 1_000_000L
        val launcher = RecordingLauncher()
        val pool = mutableListOf<Device>()
        withEmulatorApi(pool, launcher = launcher, now = { t }) { c ->
            val home = createTempDirectory("home")
            Files.createDirectories(home.resolve(".corral"))
            val previous = System.getProperty("user.home")
            System.setProperty("user.home", home.toString())
            try {
                c.post("/api/emulators/Pixel6AVD/start")
                launcher.running = true
                Files.writeString(home.resolve(".corral/emulator-Pixel6AVD.log"), "INFO | console emulator-5554\n")
                pool.add(Device("emulator-5554", "offline", booted = true))
                t += MAX_WAIT_S * 1000

                val wedged = wedgeOf(c.get("/api/devices").bodyAsText())!!
                assertEquals(true, wedged["wedged"]!!.jsonPrimitive.boolean)
                val reason = wedged["wedgeReason"]!!.jsonPrimitive.content
                assertFalse(reason.contains("without registering"), reason)
                assertTrue(reason.contains("registered"), reason)
                assertTrue(reason.contains("corral avd stop Pixel6AVD"), reason)
            } finally {
                System.setProperty("user.home", previous)
            }
        }
    }

    /**
     * THE GAP: the daemon launched the AVD and it wedged before adb ever listed a serial, so the
     * pool stays empty and no device can carry the wedge flag. The pool-derived CLI path printed
     * nothing while /api/doctor failed; the CLI's derivation now reads the daemon's own layer.
     */
    @Test fun cliDoctorDerivationSurfacesAWedgeWithNoPoolSerial() {
        var t = 1_000_000L
        val launcher = RecordingLauncher()
        withEmulatorApi(emptyList(), launcher = launcher, now = { t }) { c ->
            val home = createTempDirectory("home")
            Files.createDirectories(home.resolve(".corral"))
            val previous = System.getProperty("user.home")
            System.setProperty("user.home", home.toString())
            try {
                assertEquals(HttpStatusCode.Accepted, c.post("/api/emulators/Pixel6AVD/start").status)
                launcher.running = true
                t += MAX_WAIT_S * 1000   // alive past the deadline, never registered -> wedged

                // the pool cannot know: no serial was ever listed
                assertFalse(c.get("/api/devices").bodyAsText().contains("\"wedged\":true"))
                val lines = emulatorProblems(decodeDoctorResponse(c.get("/api/doctor").bodyAsText()))
                assertEquals(1, lines.size)
                assertTrue(lines.single().contains("Pixel6AVD"), lines.toString())
                assertTrue(lines.single().contains("without registering"), lines.toString())
            } finally {
                System.setProperty("user.home", previous)
            }
        }
    }

    /** A wedge that DOES own a pool serial is still exactly one problem line — no pool/doctor duplicate. */
    @Test fun cliDoctorDerivationReportsAPooledWedgeExactlyOnce() {
        var t = 1_000_000L
        val launcher = RecordingLauncher()
        val pool = mutableListOf<Device>()
        withEmulatorApi(pool, launcher = launcher, now = { t }) { c ->
            val home = createTempDirectory("home")
            Files.createDirectories(home.resolve(".corral"))
            val previous = System.getProperty("user.home")
            System.setProperty("user.home", home.toString())
            try {
                assertEquals(HttpStatusCode.Accepted, c.post("/api/emulators/Pixel6AVD/start").status)
                launcher.running = true
                Files.writeString(home.resolve(".corral/emulator-Pixel6AVD.log"), "INFO | console emulator-5554\n")
                pool.add(Device("emulator-5554", "offline"))
                t += MAX_WAIT_S * 1000

                assertTrue(c.get("/api/devices").bodyAsText().contains("\"wedged\":true"))
                val lines = emulatorProblems(decodeDoctorResponse(c.get("/api/doctor").bodyAsText()))
                assertEquals(1, lines.size)
                assertTrue(lines.single().contains("Pixel6AVD"), lines.toString())
            } finally {
                System.setProperty("user.home", previous)
            }
        }
    }

    @Test fun stopNotRunningIsNoOp() = withEmulatorApi(emptyList()) { c ->
        val r = c.post("/api/emulators/SmallAVD/stop") {
            contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"stopped\":[]"))
        assertFalse(r.bodyAsText().contains("\"terminated\""), r.bodyAsText())
    }

    @Test fun startWithoutBinaryIs502() = withEmulatorApi(emptyList(), binary = false) { c ->
        val r = c.post("/api/emulators/Pixel6AVD/start")
        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertTrue(r.bodyAsText().contains("CORRAL_EMULATOR"))
    }

    @Test fun listWithoutBinaryTeachesInsteadOfEmpty() = withEmulatorApi(emptyList(), binary = false) { c ->
        // a missing binary is 502 + the CORRAL_EMULATOR teaching, never "[]" (the Windows smoke read that lie)
        val r = c.get("/api/emulators")
        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertTrue(r.bodyAsText().contains("CORRAL_EMULATOR"), "body was: ${r.bodyAsText()}")
    }

    @Test fun startWithBrokenBinaryIs502NotUnknown() = withEmulatorApi(
        emptyList(), avds = "", listExit = 3
    ) { c ->
        val r = c.post("/api/emulators/Pixel6AVD/start")
        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertTrue(r.bodyAsText().contains("CORRAL_EMULATOR"))
        assertTrue(!r.bodyAsText().contains("unknown avd"))
    }

    @Test fun startAcceptsASpacedAvdName() = withEmulatorApi(
        emptyList(), avds = "Pixel6AVD\nWide AVD\n"
    ) { c ->
        val previous = System.getProperty("user.home")
        System.setProperty("user.home", createTempDirectory("home").toString())
        try {
            val r = c.post("/api/emulators/Wide%20AVD/start")
            assertEquals(HttpStatusCode.Accepted, r.status, r.bodyAsText())
            assertTrue(r.bodyAsText().contains("\"started\":true"), r.bodyAsText())
        } finally {
            System.setProperty("user.home", previous)
        }
    }

    @Test fun stopAcceptsAFormEncodedSpace() {
        val log = createTempDirectory("emu").resolve("invocations")
        withEmulatorApi(
            listOf(Device("emulator-5554", "device", avd = "Wide AVD", booted = true)), adbLog = log
        ) { c ->
            val r = c.post("/api/emulators/Wide+AVD/stop") {
                contentType(ContentType.Application.Json); setBody("{}") }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.bodyAsText().contains("\"stopped\":[\"emulator-5554\"]"))
            assertEquals(listOf("-s emulator-5554 emu kill"), Files.readAllLines(log))
        }
    }

    @Test fun startKeepsALiteralPlusInTheAvdName() = withEmulatorApi(
        emptyList(), avds = "a+b\n"
    ) { c ->
        val previous = System.getProperty("user.home")
        System.setProperty("user.home", createTempDirectory("home").toString())
        try {
            val r = c.post("/api/emulators/a%2Bb/start")
            assertEquals(HttpStatusCode.Accepted, r.status)
        } finally {
            System.setProperty("user.home", previous)
        }
    }

    @Test fun wsEventsSendsHelloThenNudges() = testApplication {
        val leases = LeaseManager(testDevices, db = null)
        val eventsScope = CoroutineScope(Job())
        val events = Events(
            { testDevices().map { d -> DeviceStatus(d.serial, leases.snapshot[d.serial]) } },
            { leases.queueSnapshot() },
            eventsScope,
            tickMs = 100,
        )
        corralWithEvents(events, leases)
        events.start()
        delay(500)                                       // let the baseline tick prime before mutating
        try {
            val ws = createClient { install(WebSockets) }
            ws.webSocket("/ws/events") {
                val hello = Json.parseToJsonElement(nextText()).jsonObject
                assertEquals("hello", hello["type"]?.jsonPrimitive?.content)
                assertEquals("devices", hello["resources"]!!.jsonArray.first().jsonPrimitive.content)

                leases.requestClaim(Selector(serial = "dev1"), "me", ttl = 100)
                val changed = Json.parseToJsonElement(nextText()).jsonObject
                assertEquals("state.changed", changed["type"]?.jsonPrimitive?.content)
                assertTrue(changed["resources"]!!.jsonArray.any { it.jsonPrimitive.content == "devices" })

                send(Frame.Text("not a real frame"))
                leases.free("dev1", "me")
                val next = Json.parseToJsonElement(nextText()).jsonObject
                assertEquals("state.changed", next["type"]?.jsonPrimitive?.content)
                assertTrue(next["resources"]!!.jsonArray.any { it.jsonPrimitive.content == "devices" })
            }
        } finally {
            eventsScope.cancel()
        }
    }

    @Test fun wsForeignOriginIsClosed() = testApplication {
        val leases = LeaseManager(testDevices, db = null)
        val events = Events({ emptyList() }, { emptyList() }, CoroutineScope(coroutineContext))
        corralWithEvents(events, leases)

        val ws = createClient { install(WebSockets) }
        val session = ws.webSocketSession("/ws/events") { header(HttpHeaders.Origin, "https://evil.example") }
        val reason: CloseReason? = withTimeout(5000) { session.closeReason.await() }
        assertEquals(CloseReason.Codes.VIOLATED_POLICY, reason?.knownReason)
        assertEquals("corral: local only", reason?.message)
    }

    @Test fun wsIpv6LoopbackOriginIsAccepted() = testApplication {
        val leases = LeaseManager(testDevices, db = null)
        val events = Events({ emptyList() }, { emptyList() }, CoroutineScope(coroutineContext))
        corralWithEvents(events, leases)

        val ws = createClient { install(WebSockets) }
        ws.webSocket("/ws/events", { header(HttpHeaders.Origin, "http://[::1]:3000") }) {
            val hello = Json.parseToJsonElement(nextText()).jsonObject
            assertEquals("hello", hello["type"]?.jsonPrimitive?.content)
        }
    }

    @Test fun wsUnconfiguredEventsIsClosed() = testApplication {
        corral()

        val ws = createClient { install(WebSockets) }
        val session = ws.webSocketSession("/ws/events")
        val reason: CloseReason? = withTimeout(5000) { session.closeReason.await() }
        assertEquals(CloseReason.Codes.VIOLATED_POLICY, reason?.knownReason)
        assertEquals("corral: events not configured", reason?.message)
    }

    private suspend fun WebSocketSession.nextText() =
        withTimeout(5000) { (incoming.receive() as Frame.Text).readText() }

    private fun String.ticketOf() = substringAfter("\"ticket\":\"").substringBefore("\"")
}
