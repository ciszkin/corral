package dev.corral

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private const val FEED_TIMEOUT_SECONDS = 10L
private const val DEFAULT_UPDATE_REPO = "ciszkin/corral"

/** The one update-repository resolver: check and config API share it, so the reported value cannot drift. */
internal fun updateRepo(env: (String) -> String? = System::getenv): String =
    env("CORRAL_REPO")?.takeIf { it.isNotBlank() } ?: DEFAULT_UPDATE_REPO

/**
 * What the one outbound call observed: a 200 body, a non-200 status, or no response at all. The
 * distinction is the whole point — "GitHub answered 404" and "GitHub was unreachable" are different
 * operator problems, and collapsing them into null is what let doctor assert the wrong cause.
 */
sealed interface FeedResult {
    data class Body(val json: String) : FeedResult
    data class Http(val status: Int) : FeedResult
    data object Unreachable : FeedResult
}

/**
 * Fetch the GitHub `releases/latest` body. The daemon's only outbound call: unauthenticated,
 * no token, no credentials. A 404 (the repo is private today, so this is the normal answer) is an
 * observed HTTP status, while a timeout, DNS, or TLS failure is [FeedResult.Unreachable].
 */
internal fun defaultUpdateFeed(): FeedResult = runCatching {
    val repo = updateRepo()
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(FEED_TIMEOUT_SECONDS)).build()
    val request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/$repo/releases/latest"))
        // HttpRequest.timeout only bounds time-to-headers; orTimeout below bounds the whole exchange
        // (headers + body read), so a slow-drip body cannot outrun it.
        .timeout(Duration.ofSeconds(FEED_TIMEOUT_SECONDS))
        .header("Accept", "application/vnd.github+json")
        .GET()
        .build()
    val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .orTimeout(FEED_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .join()
    if (response.statusCode() == 200) FeedResult.Body(response.body()) else FeedResult.Http(response.statusCode())
}.getOrElse { FeedResult.Unreachable }

private val updateJson = Json { ignoreUnknownKeys = true }

/** A release tag and the page to open for it; both are required, so a partial object is unknown. */
internal data class Release(val tag: String, val url: String)

/** Defensive: a missing/wrongly-typed field, a JSON array, or garbage all yield null, never a throw. */
internal fun parseRelease(body: String?): Release? {
    val text = body?.takeIf { it.isNotBlank() } ?: return null
    val obj = runCatching { updateJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val tag = (obj["tag_name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?.takeIf { it.isNotBlank() } ?: return null
    val url = (obj["html_url"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?.takeIf { it.isNotBlank() } ?: return null
    return Release(tag, url)
}

private data class Semver(val major: Int, val minor: Int, val patch: Int, val pre: String?)

/**
 * `v0.10.0-rc1+build` -> (0, 10, 0, "rc1"). The core must be exactly three numeric segments: a
 * short (`1.2`), long (`1.2.3.4`), or non-numeric (`nightly`) tag is unparseable (null), so the
 * caller reports unknown rather than silently truncating or calling an incomparable tag "current".
 */
private fun parseSemver(raw: String): Semver? {
    val cleaned = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
    val pre = cleaned.substringAfter('-', "").takeIf { it.isNotEmpty() }
    val parts = cleaned.substringBefore('-').split('.')
    if (parts.size != 3) return null
    val major = parts[0].toIntOrNull() ?: return null
    val minor = parts[1].toIntOrNull() ?: return null
    val patch = parts[2].toIntOrNull() ?: return null
    return Semver(major, minor, patch, pre)
}

/** SemVer precedence, numeric per component; a pre-release sorts below its release and per identifier. */
private fun compareSemver(a: Semver, b: Semver): Int {
    val core = compareValuesBy(a, b, Semver::major, Semver::minor, Semver::patch)
    if (core != 0) return core
    return when {
        a.pre == null && b.pre == null -> 0
        a.pre == null -> 1
        b.pre == null -> -1
        else -> comparePre(a.pre, b.pre)
    }
}

private fun comparePre(a: String, b: String): Int {
    val xs = a.split('.')
    val ys = b.split('.')
    for (i in 0 until maxOf(xs.size, ys.size)) {
        val x = xs.getOrNull(i) ?: return -1
        val y = ys.getOrNull(i) ?: return 1
        val xn = x.toIntOrNull()
        val yn = y.toIntOrNull()
        val c = when {
            xn != null && yn != null -> xn.compareTo(yn)
            xn != null -> -1   // numeric identifiers sort below alphanumeric ones (SemVer)
            yn != null -> 1
            else -> x.compareTo(y)
        }
        if (c != 0) return c
    }
    return 0
}

/** True when [latest] is a strictly newer SemVer than [current]; null when either side is not comparable. */
internal fun updateVerdict(current: String, latest: String): Boolean? {
    val c = parseSemver(current) ?: return null
    val l = parseSemver(latest) ?: return null
    return compareSemver(l, c) > 0
}

/**
 * The daemon's daily update check. The feed is injected so tests never open a socket; [config] is a
 * supplier so the [CORRAL_UPDATE_CHECK] kill-switch is re-read every cycle and flips live (no restart).
 *
 * A failed or unreadable check caches [UpdateState.UNKNOWN], not the previous answer: a stale
 * "update available" would assert something about a feed we just failed to read.
 */
class UpdateCheck(
    private val feed: () -> FeedResult = ::defaultUpdateFeed,
    private val config: () -> DaemonConfig = { DaemonConfig() },
    private val version: String = VersionSource.version,
    /** Runs a kicked check off the caller's thread; tests inject a synchronous runner. */
    private val dispatch: (() -> Unit) -> Unit = { task ->
        Thread(task, "corral-update-check").apply { isDaemon = true }.start()
    },
) {
    @Volatile private var cached: UpdateInfo? = null

    /** The switch value at the last check cycle; null until the first cycle has run. */
    @Volatile private var lastEnabled: Boolean? = null

    /**
     * The cached result without touching the network. Disabled always reports disabled; when the
     * switch is now enabled a cache recorded while disabled is ignored, so re-enabling is live and
     * needs neither a `?force` nor the next 24 h cycle.
     *
     * A switch that has changed since the last recorded cycle kicks exactly one check (off the
     * caller's thread, so this never blocks on the network). It runs at most once per transition —
     * the same cadence guard the 24 h loop relies on — so a flip cannot become a retry storm; the
     * caller sees UNKNOWN/NOT_CHECKED for the moment the check is in flight.
     */
    fun current(): UpdateInfo {
        if (!checksEnabled()) {
            lastEnabled = false
            return UpdateInfo(currentVersion = version, state = UpdateState.DISABLED)
        }
        if (lastEnabled != true) {
            lastEnabled = true   // claim the transition before dispatching so it is kicked exactly once
            dispatch { runCatching { checkNow() } }
        }
        return cached?.takeIf { it.state != UpdateState.DISABLED }
            ?: UpdateInfo(currentVersion = version, state = UpdateState.UNKNOWN,
                reason = UpdateReason.NOT_CHECKED)
    }

    /** Run a fresh check (or report it disabled) and cache the result. Never throws. */
    fun checkNow(): UpdateInfo {
        val enabled = checksEnabled()
        lastEnabled = enabled
        if (!enabled) {
            return UpdateInfo(currentVersion = version, state = UpdateState.DISABLED).also { cached = it }
        }
        val info = when (val result = feed()) {
            is FeedResult.Body -> {
                val release = parseRelease(result.json)
                val verdict = release?.let { updateVerdict(version, it.tag) }
                when {
                    release == null || verdict == null -> UpdateInfo(currentVersion = version,
                        state = UpdateState.UNKNOWN, reason = UpdateReason.UNPARSEABLE)
                    verdict -> UpdateInfo(currentVersion = version, state = UpdateState.UPDATE_AVAILABLE,
                        latestVersion = release.tag, releaseUrl = release.url)
                    else -> UpdateInfo(currentVersion = version, state = UpdateState.CURRENT,
                        latestVersion = release.tag)
                }
            }
            is FeedResult.Http -> UpdateInfo(currentVersion = version, state = UpdateState.UNKNOWN,
                reason = UpdateReason.HTTP_ERROR, httpStatus = result.status)
            FeedResult.Unreachable -> UpdateInfo(currentVersion = version, state = UpdateState.UNKNOWN,
                reason = UpdateReason.UNREACHABLE)
        }
        cached = info
        return info
    }

    private fun checksEnabled(): Boolean =
        config().get("CORRAL_UPDATE_CHECK").trim().lowercase() != "off"
}
