package dev.corral.ui.config

import dev.corral.ApplyResult
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Starts the CLI's gated apply as a detached process. The console never downloads or installs in
 * its own JVM: closing the window must not abort an apply half-way.
 */
internal object UpdateLaunch {
    fun start(tag: String, repo: String?): ApplyResult {
        val home = Path.of(System.getProperty("user.home"))
        val cli = cliArgv(home) ?: return ApplyResult.Failed(
            "could not find the corral CLI — expected ${home.resolve("bin/corral")} " +
                "or ${home.resolve(".corral/corral-daemon-all.jar")}"
        )
        val argv = buildList {
            // A terminal hangup must not take the apply down; setsid is POSIX-only.
            if (onPath("setsid")) add("setsid")
            addAll(cli)
            add("update")
            add("--apply")
            if (repo != null) {
                add("--repo")
                add(repo)
            }
        }
        val log = home.resolve(".corral").resolve("update-${safeName(tag)}.log")
        return runCatching {
            Files.createDirectories(log.parent)
            ProcessBuilder(argv)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start()
            ApplyResult.Completed(listOf(argv))
        }.getOrElse { e ->
            ApplyResult.Failed("could not start the update: ${e.message ?: e::class.simpleName ?: "unknown error"}")
        }
    }

    /** A launcher's PATH may not contain `~/bin`, so fall back to this JVM running the installed jar. */
    private fun cliArgv(home: Path): List<String>? {
        val shim = home.resolve("bin/corral")
        if (Files.isExecutable(shim)) return listOf(shim.toString())
        val jar = home.resolve(".corral/corral-daemon-all.jar")
        if (!Files.isRegularFile(jar)) return null
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        return listOf(java.toString(), "-cp", jar.toString(), "dev.corral.CorralCli")
    }

    private fun onPath(name: String, path: String? = System.getenv("PATH")): Boolean =
        path.orEmpty().split(File.pathSeparator).any { it.isNotBlank() && Files.isExecutable(Path.of(it, name)) }

    private fun safeName(tag: String): String =
        tag.trim().map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("")
            .ifBlank { "unknown" }
}

/**
 * The repository named by a GitHub release page URL, or null when the URL is missing or not one.
 * The console passes it to `corral update --repo` so the apply follows the repo the daemon checked.
 */
internal fun repoFromReleaseUrl(url: String?): String? {
    val match = url?.trim()?.let { GITHUB_RELEASE.find(it) } ?: return null
    val owner = match.groupValues[1]
    val name = match.groupValues[2].removeSuffix(".git")
    return if (owner.isNotBlank() && name.isNotBlank()) "$owner/$name" else null
}

private val GITHUB_RELEASE = Regex("""^https?://(?:www\.)?github\.com/([^/\s]+)/([^/\s]+)/releases(?:/.*)?$""")
