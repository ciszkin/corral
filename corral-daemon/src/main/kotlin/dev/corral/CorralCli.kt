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
import java.time.Duration
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
     * Lease owner identity — the declared corral identifier wins, else process ancestry:
     * CORRAL_OWNER > proc:<comm>:<pid>:<starttime> (universal) > user:$USER
     */
    private fun resolveOwner(): String {
        System.getenv("CORRAL_OWNER")?.takeIf { it.isNotBlank() }?.let { return it }
        processOwner()?.let { return it }
        return "user:" + (System.getenv("USER") ?: System.getProperty("user.name"))
    }

    private val shellSkip = setOf(
        "bash", "sh", "zsh", "fish", "dash", "ksh", "env", "nohup",
        "timeout", "xargs", "sudo", "setsid", "stdbuf", "script")

    // The floor of the ancestry walk: the init system is never an agent. An orphaned (reparented)
    // client's first non-shell ancestor is `systemd --user`, a shared and immortal principal — if
    // returned, every orphan would own each other's leases and never be reclaimed by pid liveness.
    // Stop here and fall back to `user:$USER` instead.
    private val initSkip = setOf("systemd", "init")

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
    private fun processOwner(): String? = agentAncestor()?.let { "proc:${it.first}:${it.second}:${it.third}" }

    /** The agent's pid from the ancestry walk (see processOwner) — the holder that outlives this CLI. */
    private fun agentPid(): Long = agentAncestor()?.second ?: 0

    /**
     * The pid to record on a lease as a liveness hint, or 0 for TTL-bound.
     * Session id is the identity; a pid is only a valid liveness signal when the process
     * actually spans the work. A declared `CORRAL_OWNER` is a durable identity — the process
     * is not the session (Air starts a fresh process per prompt) — so pinning the lease to
     * the pid would break the hold; send 0 and lean on the TTL. Only `proc:` owners (process
     * ancestry, no declared identifier) get pid liveness, because there the process is the session.
     */
    private fun holderPid(): Long =
        if (owner.startsWith("proc:")) agentPid() else 0

    /** First non-shell ancestor as (comm, pid, starttime), or null if none found (→ user:$USER). */
    private fun agentAncestor(): Triple<String, Long, Long>? {
        var p = ProcessHandle.current().parent()
        while (p.isPresent) {
            val h = p.get()
            val pid = h.pid()
            if (pid <= 1) return null
            val stat = statOf(pid) ?: return null
            if (stat.first in initSkip) return null   // reparented onto init — no agent above us
            if (stat.first !in shellSkip) return Triple(stat.first, pid, stat.second)
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

    private fun send(method: String, path: String, body: String, timeoutSeconds: Long = 0) = http.send(
        HttpRequest.newBuilder(URI.create("$url$path"))
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .let { b -> if (timeoutSeconds > 0) b.timeout(Duration.ofSeconds(timeoutSeconds)) else b }
            .build(),
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
    private fun claimFree(owner: String, pid: Long, selector: Selector = Selector()): String? {
        repeat(5) {
            val r = trySend("POST", "/api/leases",
                Json.encodeToString(ClaimRequest(selector = selector, owner = owner, pid = pid, ttlSeconds = 1800)))
            if (r.statusCode() == 200)
                return Json.parseToJsonElement(r.body()).jsonObject["serial"]!!.jsonPrimitive.content
            if (r.body().contains("\"busy\"")) return@repeat // raced; try again
            return null // 409 no-free-device (or other) -> stop
        }
        return null
    }

    /** Claim one device now; on 409 print a plain message (not the raw busy JSON) and exit 1. */
    private fun claimOne(selector: Selector, pid: Long): String {
        val r = trySend("POST", "/api/leases",
            Json.encodeToString(ClaimRequest(selector = selector, owner = owner, pid = pid, ttlSeconds = 1800)))
        if (r.statusCode() == 200)
            return Json.parseToJsonElement(r.body()).jsonObject["serial"]!!.jsonPrimitive.content
        val matching = runCatching {
            Json.parseToJsonElement(r.body()).jsonObject["matching"]!!.jsonPrimitive.content.toInt()
        }.getOrNull()
        if (matching == 0) System.err.println("corral: no connected device matches $selector")
        else System.err.println("corral: no free device matches $selector (${matching ?: "some"} connected, all leased) — corral wait to queue, corral status for holders")
        exitProcess(1)
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
                val (sel, rest) = parseSelector(args.drop(1).toTypedArray())
                when {
                    rest.firstOrNull() == "--all" -> {
                        var claimed = 0
                        while (true) {
                            val s = claimFree(owner, holderPid(), selector = sel) ?: break
                            println(s); claimed++
                        }
                        if (claimed == 0) { System.err.println("corral: no free device"); exitProcess(1) }
                    }
                    else -> {
                        val selector = rest.firstOrNull()?.let { sel.copy(serial = it) } ?: sel
                        println(claimOne(selector, holderPid()))
                    }
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
                val (sel, rest0) = parseSelector(args.drop(1).toTypedArray())
                val all = rest0.firstOrNull() == "--all"
                val cmd = rest0.drop(if (all) 1 else 0).let { if (it.firstOrNull() == "--") it.drop(1) else it }
                if (cmd.isEmpty()) { System.err.println("usage: corral lease [--all] -- <cmd>"); exitProcess(2) }
                val isGradle = cmd.firstOrNull()?.let { File(it).name } in setOf("gradle", "gradlew")
                if (all) {
                    val claimed = mutableListOf<String>()
                    while (true) {
                        val s = claimFree(owner, pid, selector = sel) ?: break
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
                        Json.encodeToString(ClaimRequest(selector = sel, owner = owner, pid = pid, ttlSeconds = 1800))).str("serial")
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
            "wait" -> {
                val (sel, rest) = parseSelector(args.drop(1).toTypedArray())
                val i = rest.indexOf("--timeout")
                val waitSeconds = if (i >= 0) rest.getOrNull(i + 1)?.toLongOrNull() ?: usage(2) else 600L
                if (waitSeconds <= 0) { println(claimOne(sel, holderPid())); return }
                // Own pid: killing this CLI frees the ticket at once (the daemon skips dead pids).
                val ticket = send("POST", "/api/leases",
                    Json.encodeToString(ClaimRequest(selector = sel, owner = owner,
                        pid = pid, ttlSeconds = 1800, waitSeconds = waitSeconds))).str("ticket")
                Runtime.getRuntime().addShutdownHook(Thread {
                    runCatching {
                        http.send(HttpRequest.newBuilder(URI.create("$url/api/leases/tickets/$ticket"))
                            .timeout(Duration.ofSeconds(2))
                            .method("DELETE", HttpRequest.BodyPublishers.ofString(
                                Json.encodeToString(TicketCancelRequest(owner = owner))))
                            .header("Content-Type", "application/json")
                            .build(), HttpResponse.BodyHandlers.ofString())
                    }
                })
                while (true) {
                    val r = trySend("POST", "/api/leases/tickets/$ticket/poll",
                        Json.encodeToString(TicketPollRequest(owner = owner, pid = holderPid())))
                    when (r.statusCode()) {
                        200 -> { println(r.body().str("serial")); return }
                        202 -> Thread.sleep(runCatching { r.body().str("retryAfterMs").toLong() }.getOrDefault(1000L))
                        else -> { System.err.println("corral: no free device matches $sel"); exitProcess(1) }
                    }
                }
            }
            "queue" -> println(get("/api/queue"))
            "help" -> usage(0)
            else -> usage(2)
        }
    }

    /** Pulls --serial/--model/--os/--type/--avd out of args; returns (selector, remaining positional args). */
    private fun parseSelector(args: Array<String>): Pair<Selector, List<String>> {
        var sel = Selector(); val rest = mutableListOf<String>(); var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--serial" -> sel = sel.copy(serial = args.getOrNull(++i) ?: usage(2))
                "--model" -> sel = sel.copy(model = args.getOrNull(++i) ?: usage(2))
                "--os" -> sel = sel.copy(minOs = args.getOrNull(++i) ?: usage(2))
                "--type" -> sel = sel.copy(transport = args.getOrNull(++i) ?: usage(2))
                "--avd" -> sel = sel.copy(avd = args.getOrNull(++i) ?: usage(2))
                "--" -> { rest.addAll(args.drop(i)); i = args.size }
                else -> rest += args[i]
            }
            i++
        }
        return sel to rest
    }

    private fun usage(code: Int): Nothing {
        println("usage: corral devices|status|queue|owner|doctor|check [--owner <o>] [<serial>...]" +
            "|claim [--all|<serial>] [selector]|free [--all|<serial>]|renew <serial>" +
            "|wait [--timeout S (max 600)] [selector]|lease [--all] [selector] -- <cmd>" +
            "\n  selector: --serial S --model M --os V(min) --type usb|wifi|emulator --avd A")
        exitProcess(code)
    }
}
