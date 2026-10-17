package dev.corral

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val DOWNLOAD_CONNECT_TIMEOUT_SECONDS = 15L
private const val DOWNLOAD_TIMEOUT_SECONDS = 180L
private const val DEFAULT_REPO = "ciszkin/corral"

/** The console jar must be on the running process's command line, or relaunching would restore the wrong thing. */
private const val CONSOLE_JAR_MARKER = "corral-ui"

/**
 * What the update applier reports back. Refused is a deliberate stop before anything was touched
 * (an unsupported host); Failed is a step that ran and did not finish; Completed is the whole
 * pipeline, including the console relaunch (the process is normally replaced at that point).
 */
sealed interface ApplyResult {
    data class Completed(val commands: List<List<String>>) : ApplyResult
    data class Refused(val reason: String) : ApplyResult
    data class Failed(val reason: String) : ApplyResult

    val ok: Boolean get() = this is Completed
}

/** A download that verified as neither, or a `.sha256` that was missing or unreadable. */
class ChecksumException(message: String) : IOException(message)

/** The honest failure when the console's own command line cannot be re-derived. */
const val CONSOLE_RELAUNCH_UNKNOWN: String =
    "could not determine how to relaunch this console — start it again from its launcher after the update"

/**
 * Which corral components are installed under the install root. `install.sh` takes only a UI switch
 * (`--ui` / `--no-ui`) because the daemon is always part of a release bundle, so the console's
 * presence is the one flag the bundle needs told.
 */
data class InstalledComponents(val daemon: Boolean, val ui: Boolean) {
    val uiFlag: String get() = if (ui) "--ui" else "--no-ui"

    companion object {
        /** A jar sitting in `~/.corral` is an installed component; that is the whole detection. */
        fun detect(corralHome: Path): InstalledComponents = InstalledComponents(
            daemon = Files.isRegularFile(corralHome.resolve("corral-daemon-all.jar")),
            ui = Files.isRegularFile(corralHome.resolve("corral-ui-all.jar")),
        )
    }
}

/** Whether the apply relaunches a console, or is the CLI applying on behalf of the host. */
sealed interface Relaunch {
    /** Re-running exactly these argv restores this console window. */
    data class Console(val argv: List<String>) : Relaunch

    /** The CLI has no console window to restore. */
    data object None : Relaunch
}

/**
 * Applies one released bundle. The command list is a pure function so a reviewer can read the whole
 * update path without running one; [run] is the only part that touches the network or the host.
 *
 * Order of operations in [run]: download the zip and its `.sha256`, VERIFY THE CHECKSUM, and only
 * then execute [commands] (extract to a scratch dir, run the bundle's `install.sh` with the
 * installed component set, restart the daemon, relaunch the console). The check is the gate: a
 * mismatch, a missing `.sha256`, or an unreadable one stops before any file is extracted, and the
 * message names both digests. There is no warn-and-continue path.
 *
 * Every step is waited on EXCEPT the console relaunch, which is fire-and-forget: the old console
 * must not hang showing "installing…" for the whole lifetime of the new one. Firing it and leaving
 * lets this process exit while the replacement takes over.
 */
class UpdateApplier(
    private val tag: String,
    private val repo: String,
    private val workDir: Path,
    private val components: InstalledComponents,
    private val relaunch: Relaunch,
    private val restart: List<String> = listOf("systemctl", "--user", "restart", "corral"),
    /** Non-null on a host that cannot restart the user unit; [run] refuses before anything runs. */
    private val platformRefusal: String? = null,
    private val download: (URI, Path) -> Unit = ::httpDownload,
    private val execute: (List<String>) -> Int = ::runProcess,
    /** Starts the console replacement and returns at once — [run] never waits on the relaunch. */
    private val spawn: (List<String>) -> Unit = ::spawnProcess,
    /** Set when the applier could not even be prepared: [run] reports it instead of running. */
    private val setupError: String? = null,
) {
    private val version = tag.trim().removePrefix("v").removePrefix("V")
    private val zip: Path = workDir.resolve("corral-$version.zip")
    private val sha: Path = workDir.resolve("corral-$version.zip.sha256")
    private val extractDir: Path = workDir.resolve("bundle")
    private val consoleRelaunchArgv: List<String>? = (relaunch as? Relaunch.Console)?.argv

    /** The argv the apply performs, in order. Side-effect free; [run] verifies the checksum first. */
    fun commands(): List<List<String>> = buildList {
        add(listOf("unzip", "-o", zip.toString(), "-d", extractDir.toString()))
        add(listOf("bash", extractDir.resolve("install.sh").toString(), components.uiFlag))
        add(restart)
        consoleRelaunchArgv?.let { add(it) }
    }

    fun run(): ApplyResult {
        // Preparing the applier can fail before run() is ever entered; report that, never throw it.
        setupError?.let { return ApplyResult.Failed(it) }
        // A host that cannot restart the unit must refuse, not half-apply: name the remedy first.
        platformRefusal?.let { return ApplyResult.Refused(it) }
        return try {
            createScratchDir()
            val zipUri = releaseZipUrl(repo, tag)
            download(zipUri, zip)
            download(URI.create("$zipUri.sha256"), sha)
            // The gate: nothing below runs until the archive matches its published digest.
            verifyChecksum(sha, zip)
            Files.createDirectories(extractDir)
            val pipeline = commands()
            for (argv in pipeline) {
                // The relaunch replaces this process: fire it and return Completed without waiting.
                if (argv == consoleRelaunchArgv) {
                    spawn(argv)
                    continue
                }
                val code = execute(argv)
                if (code != 0) {
                    return ApplyResult.Failed("update step failed (exit $code): ${argv.joinToString(" ")}")
                }
            }
            ApplyResult.Completed(pipeline)
        } catch (e: ChecksumException) {
            ApplyResult.Failed(e.message ?: "checksum verification failed")
        } catch (e: Exception) {
            // The failure must never escape: an offline host is ordinary, not an uncaught crash.
            ApplyResult.Failed("update failed: ${e.message ?: e::class.simpleName ?: "unknown error"}")
        } finally {
            // the scratch tree (downloads + extracted bundle) is never the install; drop it either way
            runCatching { workDir.toFile().deleteRecursively() }
        }
    }

    /**
     * The scratch dir is created HERE, inside [run]'s contract: an unwritable or full temp filesystem
     * is reported as a [ApplyResult.Failed] naming the directory, not a throw before run() is entered.
     */
    private fun createScratchDir() {
        try {
            Files.createDirectories(workDir)
        } catch (e: Exception) {
            throw IOException(
                "could not create the update scratch directory at $workDir (${e.message ?: e::class.simpleName ?: "unknown error"})",
                e,
            )
        }
    }

    companion object {
        /** The CLI applies for the host; it has no console window to relaunch. */
        fun forCli(
            tag: String,
            home: Path = userHome(),
            repo: String = defaultRepo(),
            workDir: Path = scratchDir(),
        ): UpdateApplier = build(tag, home, repo, workDir, Relaunch.None)

        /** The console applies and then relaunches itself; null when its own argv cannot be re-derived. */
        fun forConsole(
            tag: String,
            home: Path = userHome(),
            repo: String = defaultRepo(),
            workDir: Path = scratchDir(),
        ): UpdateApplier? {
            val info = ProcessHandle.current().info()
            val argv = relaunchArgv(
                info.command().orElse(null),
                info.arguments().orElse(null)?.toList(),
                info.commandLine().orElse(null),
            ) ?: return null
            return build(tag, home, repo, workDir, Relaunch.Console(argv))
        }

        /**
         * Everything a factory does besides pick the scratch path touches the filesystem (detecting
         * what is installed, resolving the platform refusal), and none of it may escape as an
         * exception: a caller that only understands [ApplyResult] must never get a throw before
         * [run]. On failure, hand back an applier whose [run] reports the honest Failed instead.
         */
        private fun build(tag: String, home: Path, repo: String, workDir: Path, relaunch: Relaunch): UpdateApplier =
            runCatching {
                UpdateApplier(
                    tag = tag, repo = repo, workDir = workDir,
                    components = InstalledComponents.detect(corralHome(home)),
                    relaunch = relaunch,
                    platformRefusal = defaultPlatformRefusal(),
                )
            }.getOrElse { e ->
                UpdateApplier(
                    tag = tag, repo = repo, workDir = workDir,
                    components = InstalledComponents(daemon = false, ui = false),
                    relaunch = relaunch,
                    setupError = "could not prepare the update: ${e.message ?: e::class.simpleName ?: "unknown error"}",
                )
            }
    }
}

/** `https://github.com/<repo>/releases/download/v<version>/corral-<version>.zip`, tag's `v` restored. */
internal fun releaseZipUrl(repo: String, tag: String): URI {
    val version = tag.trim().removePrefix("v").removePrefix("V")
    return URI.create("https://github.com/$repo/releases/download/v$version/corral-$version.zip")
}

internal fun defaultRepo(env: (String) -> String? = System::getenv): String =
    env("CORRAL_REPO")?.takeIf { it.isNotBlank() } ?: DEFAULT_REPO

internal fun userHome(): Path = Path.of(System.getProperty("user.home"))

internal fun corralHome(home: Path = userHome()): Path = home.resolve(".corral")

/**
 * A unique scratch path; it is NOT created here. [UpdateApplier.run] creates it inside its try, so a
 * full or unwritable temp filesystem becomes a reported apply failure instead of a throw at the call
 * site (where a default argument would evaluate it before run()).
 */
internal fun scratchDir(): Path = Path.of(
    System.getProperty("java.io.tmpdir") ?: System.getProperty("user.dir") ?: ".",
    "corral-update-${UUID.randomUUID()}",
)

/** The platform refusal, worded with the remedy; null when this host can restart a user unit. */
internal fun systemdRefusal(osName: String, hasSystemctl: Boolean): String? =
    if (osName.startsWith("Linux", ignoreCase = true) && hasSystemctl) null
    else "corral update needs a systemd user session to restart the daemon " +
        "(systemctl --user restart corral) — this host has none; unpack the release zip and run ./install.sh by hand"

internal fun defaultPlatformRefusal(): String? =
    systemdRefusal(System.getProperty("os.name"), hasCommandOnPath("systemctl"))

internal fun hasCommandOnPath(name: String, path: String? = System.getenv("PATH")): Boolean =
    path.orEmpty().split(File.pathSeparator).any { it.isNotBlank() && Files.isExecutable(Path.of(it, name)) }

/**
 * The argv that restores THIS console, taken from the running process itself. [command] and
 * [arguments] are the JDK's own tokenisation (no quoting to guess); [commandLine] is the fallback
 * when arguments are unavailable. Returns null when the line does not contain the console jar —
 * re-running anything else would not restore this console, and guessing is worse than saying so.
 */
internal fun relaunchArgv(command: String?, arguments: List<String>?, commandLine: String?): List<String>? {
    val args = when {
        !arguments.isNullOrEmpty() -> arguments
        !commandLine.isNullOrBlank() && !command.isNullOrBlank() ->
            commandLine.removePrefix(command).trim().split(WHITESPACE).filter { it.isNotEmpty() }
        !commandLine.isNullOrBlank() -> commandLine.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        else -> return null
    }
    val argv = if (command.isNullOrBlank()) args else listOf(command) + args
    if (argv.isEmpty() || argv.none { it.contains(CONSOLE_JAR_MARKER) }) return null
    return argv
}

private val WHITESPACE = Regex("\\s+")

/**
 * The expected digest, read from the first token of the `.sha256` (`sha256sum`'s `<hex>  <name>`),
 * or a [ChecksumException] naming the file when it is missing, empty, or not a SHA-256 digest.
 */
internal fun readExpectedDigest(shaFile: Path): String {
    if (!Files.isRegularFile(shaFile)) {
        throw ChecksumException("no .sha256 checksum beside the archive (looked for $shaFile) — refusing to install")
    }
    val token = runCatching { Files.readString(shaFile).trim().split(WHITESPACE).firstOrNull() }.getOrNull()
    if (token.isNullOrEmpty() || !token.matches(SHA256_HEX)) {
        throw ChecksumException("unreadable .sha256 checksum at $shaFile — refusing to install")
    }
    return token.lowercase()
}

private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

/** The archive's SHA-256, streamed so a large zip is never held in memory. */
internal fun sha256Hex(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The hard gate: verify [zip] against [shaFile] BEFORE it is unpacked or run. A mismatch names both
 * the expected and the actual digest; a missing/unreadable sidecar is the same hard stop.
 */
internal fun verifyChecksum(shaFile: Path, zip: Path) {
    val expected = readExpectedDigest(shaFile)
    val actual = sha256Hex(zip)
    if (!expected.equals(actual, ignoreCase = true)) {
        throw ChecksumException(
            "checksum mismatch for ${zip.fileName}: expected sha256 $expected but the archive is $actual — refusing to install",
        )
    }
}

/**
 * One outbound download over the JDK client the daemon already uses, unauthenticated, with a real
 * timeout. The response body is streamed to [dest]; it is never read into memory or logged whole.
 *
 * EVERY failure — DNS, connection refused, timeout — surfaces as an [IOException] with a teaching
 * message, mirroring the daemon's UpdateCheck feed. A raw CompletionException
 * from `.join()` would escape [run]'s contract and brick the console, so it is wrapped here.
 */
internal fun httpDownload(uri: URI, dest: Path): Unit {
    dest.parent?.let { Files.createDirectories(it) }
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(DOWNLOAD_CONNECT_TIMEOUT_SECONDS))
        .build()
    val request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
        .GET()
        .build()
    val response = runCatching {
        client.sendAsync(request, HttpResponse.BodyHandlers.ofFile(dest))
            // HttpRequest.timeout only bounds time-to-headers; orTimeout also bounds the body read.
            .orTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .join()
    }.getOrElse { e ->
        Files.deleteIfExists(dest)
        throw IOException("could not download ${uri.path.substringAfterLast('/')} from ${uri.host} (${e.message ?: e::class.simpleName})")
    }
    if (response.statusCode() != 200) {
        Files.deleteIfExists(dest)
        throw IOException("could not download ${uri.path.substringAfterLast('/')} (HTTP ${response.statusCode()})")
    }
}

/** Start a process and return at once; used only for the console relaunch, which must not be awaited. */
internal fun spawnProcess(argv: List<String>) {
    runCatching { ProcessBuilder(argv).inheritIO().start() }
}

/** Run a process and wait for it to finish; 127 when it cannot even start. */
internal fun runProcess(argv: List<String>): Int =
    runCatching { ProcessBuilder(argv).inheritIO().start().waitFor() }.getOrDefault(127)
