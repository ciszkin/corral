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

/**
 * Fetch the GitHub `releases/latest` body. The daemon's only outbound call: unauthenticated,
 * no token, no credentials. Every failure — 404 (the repo is private today, so this is the normal
 * answer), 403, timeout, DNS, TLS — collapses to null, i.e. "we could not learn anything".
 */
internal fun defaultUpdateFeed(): String? = runCatching {
    val repo = System.getenv("CORRAL_REPO")?.takeIf { it.isNotBlank() } ?: "ciszkin/corral"
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
    if (response.statusCode() == 200) response.body() else null
}.getOrNull()

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
    private val feed: () -> String? = ::defaultUpdateFeed,
    private val config: () -> DaemonConfig = { DaemonConfig() },
    private val version: String = VersionSource.version,
) {
    @Volatile private var cached: UpdateInfo? = null

    /**
     * The cached result without touching the network. Disabled always reports disabled; when the
     * switch is now enabled a cache recorded while disabled is ignored, so re-enabling is live and
     * needs neither a `?force` nor the next 24 h cycle.
     */
    fun current(): UpdateInfo {
        if (!checksEnabled()) return UpdateInfo(currentVersion = version, state = UpdateState.DISABLED)
        return cached?.takeIf { it.state != UpdateState.DISABLED }
            ?: UpdateInfo(currentVersion = version, state = UpdateState.UNKNOWN)
    }

    /** Run a fresh check (or report it disabled) and cache the result. Never throws. */
    fun checkNow(): UpdateInfo {
        if (!checksEnabled()) {
            return UpdateInfo(currentVersion = version, state = UpdateState.DISABLED).also { cached = it }
        }
        val release = parseRelease(feed())
        val verdict = release?.let { updateVerdict(version, it.tag) }
        val info = when {
            release == null || verdict == null ->
                UpdateInfo(currentVersion = version, state = UpdateState.UNKNOWN)
            else -> UpdateInfo(
                currentVersion = version,
                state = if (verdict) UpdateState.UPDATE_AVAILABLE else UpdateState.CURRENT,
                latestVersion = release.tag,
                releaseUrl = release.url)
        }
        cached = info
        return info
    }

    private fun checksEnabled(): Boolean =
        config().get("CORRAL_UPDATE_CHECK").trim().lowercase() != "off"
}
