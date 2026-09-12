package dev.corral

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.exitProcess

/**
 * corral CLI — all logic in the jar (portable), launched by 3-line shims.
 * claim: JVM exits right after printing -> no pid recorded -> TTL-bound lease (MVP `adb-lease claim` semantics).
 * lease: JVM outlives the child -> pid recorded -> instant crash recovery (MVP `withdev` semantics).
 */
object CorralCli {
    private val url = System.getenv("CORRAL_URL")?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1:8798"
    // Resolved once per process so claim/free in one invocation use the same owner.
    private val owner: String by lazy { resolveOwner() }

    /**
     * Lease owner identity — per-agent-session (see agent-session-identity.md):
     * CORRAL_OWNER > claude:$CLAUDE_CODE_SESSION_ID > opencode:$OPENCODE_PID
     * > proc:<comm>:<pid>:<starttime> (process ancestry, universal) > user:$USER
     */
    private fun resolveOwner(): String {
        System.getenv("CORRAL_OWNER")?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv("CLAUDE_CODE_SESSION_ID")?.let { return "claude:$it" }
        System.getenv("OPENCODE_PID")?.let { return "opencode:$it" }
        processOwner()?.let { return it }
        return "user:" + (System.getenv("USER") ?: System.getProperty("user.name"))
    }

    private val shellSkip = setOf(
        "bash", "sh", "zsh", "fish", "dash", "ksh", "env", "nohup",
        "timeout", "xargs", "sudo", "setsid", "stdbuf", "script")

    // CORRAL_ADB first (the only SDK pointer the daemon unit sets; install.sh computed and
    // trusts it) — it points at the real binary (adb.real), the shim is its sibling "adb".
    // Then the same detection order as install.sh: ANDROID_HOME, ANDROID_SDK_ROOT, default path.
    private fun findSdkAdb(): String? {
        val fromCorral = System.getenv("CORRAL_ADB")?.let { p ->
            val path = Path.of(p)
            if (path.fileName?.toString()?.endsWith(".real") == true) path.resolveSibling("adb").toString() else p
        }
        val candidates = buildList {
            fromCorral?.let { add(it) }
            System.getenv("ANDROID_HOME")?.let { add("$it/platform-tools/adb") }
            System.getenv("ANDROID_SDK_ROOT")?.let { add("$it/platform-tools/adb") }
            add(System.getProperty("user.home") + "/Android/Sdk/platform-tools/adb")
        }
        return candidates.firstOrNull { File(it).exists() } ?: candidates.firstOrNull()
    }

    // Walk up from the JVM's parent (like the wrapper starts at $PPID); the first
    // non-shell ancestor is the agent. Read /proc/<pid>/stat directly so comm and
    // starttime match the bash wrapper byte for byte.
    private fun processOwner(): String? {
        var p = ProcessHandle.current().parent()
        while (p.isPresent) {
            val h = p.get()
            val pid = h.pid()
            if (pid <= 1) break
            val stat = statOf(pid) ?: return null
            if (stat.first !in shellSkip) return "proc:${stat.first}:$pid:${stat.second}"
            p = h.parent()
        }
        return null
    }

    private fun statOf(pid: Long): Pair<String, Long>? = try {
        val text = File("/proc/$pid/stat").readText()
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close < 0) null
        else {
            val comm = text.substring(open + 1, close)
            val start = text.substring(close + 1).trim().split(Regex("\\s+"))
                .getOrNull(19)?.toLongOrNull()
            if (start == null) null else comm to start
        }
    } catch (_: Exception) { null }

    private val http = HttpClient.newHttpClient()

    private fun get(path: String) = http.send(
        HttpRequest.newBuilder(URI.create("$url$path")).GET().build(),
        HttpResponse.BodyHandlers.ofString()).let(::ok).body()

    private fun send(method: String, path: String, body: String) = http.send(
        HttpRequest.newBuilder(URI.create("$url$path"))
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").build(),
        HttpResponse.BodyHandlers.ofString()).let(::ok).body()

    private fun trySend(method: String, path: String, body: String) = http.send(
        HttpRequest.newBuilder(URI.create("$url$path"))
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").build(),
        HttpResponse.BodyHandlers.ofString())

    private fun ok(r: HttpResponse<String>) = r.also {
        if (it.statusCode() >= 400) { System.err.println(it.body()); exitProcess(1) }
    }

    private fun release(serial: String) {
        runCatching {
            val r = http.send(HttpRequest.newBuilder(URI.create("$url/api/leases/$serial"))
                .method("DELETE", HttpRequest.BodyPublishers.ofString(Json.encodeToString(FreeRequest(owner = owner))))
                .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString())
            if (r.statusCode() >= 400) System.err.println(r.body())
        }
    }

    /** Claim one free device; retries on a raced busy (409 {"busy":...}), null when none free. */
    private fun claimFree(owner: String, pid: Long): String? {
        repeat(5) {
            val r = trySend("POST", "/api/leases",
                Json.encodeToString(ClaimRequest(owner = owner, pid = pid, ttlSeconds = 1800)))
            if (r.statusCode() == 200)
                return Json.parseToJsonElement(r.body()).jsonObject["serial"]!!.jsonPrimitive.content
            if (r.body().contains("\"busy\"")) return@repeat // raced; try again
            return null // 409 no-free-device (or other) -> stop
        }
        return null
    }

    private fun String.str(field: String) =
        Json.parseToJsonElement(this).jsonObject[field]!!.jsonPrimitive.content

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (e: IOException) {
            System.err.println("corral: daemon unreachable at $url (${e.message})")
            exitProcess(1)
        }
    }

    private fun run(args: Array<String>) {
        val pid = ProcessHandle.current().pid()
        when (args.firstOrNull() ?: "help") {
            "devices" -> println(get("/api/devices"))
            "status" -> println(get("/api/status"))
            "owner" -> println(owner)
            "doctor" -> {
                var problems = 0
                val devices = try {
                    val r = http.send(HttpRequest.newBuilder(URI.create("$url/api/devices")).GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                    if (r.statusCode() == 200) Json.parseToJsonElement(r.body()).jsonArray.size else -1
                } catch (_: Exception) { -1 }
                if (devices >= 0) println("daemon: ok ($url, $devices device(s))")
                else { println("daemon: FAIL - unreachable at $url (is it running? systemctl --user status corral)"); problems++ }

                val sdkAdb = findSdkAdb()
                when {
                    sdkAdb == null -> println("sdk adb shim: skipped (no SDK platform-tools/adb found; set ANDROID_HOME)")
                    else -> {
                        val shim = File(sdkAdb)
                        val real = File("$sdkAdb.real")
                        val isWrapper = shim.exists() && shim.readText().contains("corral adb wrapper")
                        when {
                            !shim.exists() -> { println("sdk adb shim: FAIL - $sdkAdb missing"); problems++ }
                            !isWrapper -> { println("sdk adb shim: FAIL - $sdkAdb is not the corral wrapper (re-run install.sh)"); problems++ }
                            !real.canExecute() -> { println("sdk adb shim: FAIL - $real missing (re-run install.sh)"); problems++ }
                            else -> println("sdk adb shim: ok ($sdkAdb -> adb.real)")
                        }
                    }
                }
                if (problems > 0) exitProcess(1)
            }
            "check" -> {
                val checkOwner: String
                val serials: List<String>
                if (args.getOrNull(1) == "--owner") {
                    checkOwner = args.getOrNull(2) ?: usage(2)
                    serials = args.drop(3)
                } else {
                    checkOwner = owner
                    serials = args.drop(1)
                }
                val targets = if (serials.isEmpty())
                    Json.parseToJsonElement(get("/api/devices")).jsonArray
                        .filter { it.jsonObject["state"]!!.jsonPrimitive.content == "device" }
                        .map { it.jsonObject["serial"]!!.jsonPrimitive.content }
                else serials
                val denied = targets.mapNotNull { s ->
                    val obj = Json.parseToJsonElement(get("/api/check?serial=${URLEncoder.encode(s, StandardCharsets.UTF_8)}&owner=${URLEncoder.encode(checkOwner, StandardCharsets.UTF_8)}")).jsonObject
                    if (obj["allowed"]!!.jsonPrimitive.content == "true") null
                    else "  $s: ${obj["reason"]!!.jsonPrimitive.content}"
                }
                if (denied.isEmpty()) println("ok: ${targets.size} device(s) leased by $checkOwner")
                else {
                    System.err.println("corral: devices not leased by $checkOwner:")
                    denied.forEach(System.err::println)
                    exitProcess(1)
                }
            }
            "claim" -> {
                when (args.getOrNull(1)) {
                    "--all" -> {
                        var claimed = 0
                        while (true) {
                            val s = claimFree(owner, 0) ?: break
                            println(s)
                            claimed++
                        }
                        if (claimed == 0) { System.err.println("corral: no free device"); exitProcess(1) }
                    }
                    null -> println(send("POST", "/api/leases",
                        Json.encodeToString(ClaimRequest(owner = owner, ttlSeconds = 1800))).str("serial"))
                    else -> println(send("POST", "/api/leases",
                        Json.encodeToString(ClaimRequest(selector = Selector(serial = args[1]), owner = owner, ttlSeconds = 1800))).str("serial"))
                }
            }
            "free" -> {
                if (args.getOrNull(1) == "--all") {
                    val owned = Json.parseToJsonElement(get("/api/status")).jsonObject["devices"]!!.jsonArray
                        .filter { (it.jsonObject["lease"] as? JsonObject)?.get("owner")?.jsonPrimitive?.content == owner }
                        .map { it.jsonObject["serial"]!!.jsonPrimitive.content }
                    if (owned.isEmpty()) println("no leases owned by $owner")
                    else {
                        owned.forEach { send("DELETE", "/api/leases/$it", Json.encodeToString(FreeRequest(owner = owner))) }
                        println("freed: ${owned.joinToString(", ")}")
                    }
                } else {
                    val serial = args.getOrNull(1) ?: usage(2)
                    send("DELETE", "/api/leases/$serial", Json.encodeToString(FreeRequest(owner = owner)))
                    println("freed $serial")
                }
            }
            "renew" -> {
                val serial = args.getOrNull(1) ?: usage(2)
                send("POST", "/api/leases/$serial/renew", Json.encodeToString(RenewRequest(owner = owner)))
                println("renewed $serial")
            }
            "lease" -> {
                val all = args.getOrNull(1) == "--all"
                val cmd = args.drop(if (all) 2 else 1)
                    .let { if (it.firstOrNull() == "--") it.drop(1) else it }
                if (cmd.isEmpty()) { System.err.println("usage: corral lease [--all] -- <cmd>"); exitProcess(2) }
                val isGradle = cmd.firstOrNull()?.let { File(it).name } in setOf("gradle", "gradlew")
                if (all) {
                    val claimed = mutableListOf<String>()
                    while (true) {
                        val s = claimFree(owner, pid) ?: break
                        claimed += s
                    }
                    if (claimed.isEmpty()) { System.err.println("corral: no free device"); exitProcess(1) }
                    val gradleFlag = if (isGradle) listOf("-PcorralOwner=$owner") else emptyList()
                    val rc = try {
                        ProcessBuilder(cmd + gradleFlag).apply {
                            environment()["CORRAL_OWNER"] = owner
                        }.inheritIO().start().waitFor()
                    } finally {
                        claimed.forEach { runCatching { release(it) } }
                    }
                    exitProcess(rc)
                } else {
                    val serial = send("POST", "/api/leases",
                        Json.encodeToString(ClaimRequest(owner = owner, pid = pid, ttlSeconds = 1800))).str("serial")
                    val rc = try {
                        ProcessBuilder(cmd).apply {
                            environment()["ANDROID_SERIAL"] = serial
                            environment()["CORRAL_OWNER"] = owner
                        }.inheritIO().start().waitFor()
                    } finally {
                        release(serial)
                    }
                    exitProcess(rc)
                }
            }
            "help" -> usage(0)
            else -> usage(2)
        }
    }

    private fun usage(code: Int): Nothing {
        println("usage: corral devices|status|owner|doctor|check [--owner <o>] [<serial>...]|claim [--all|<serial>]|free [--all|<serial>]|renew <serial>|lease [--all] -- <cmd>")
        exitProcess(code)
    }
}
