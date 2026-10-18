plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.shadow) apply false
}

// One git tag becomes the single source of truth: the release workflow passes CORRAL_VERSION,
// a dev tree falls back to `git describe`, and a tree with no usable version (or no git) gets
// the dev placeholder. A bare `--always` commit hash, a typo, or a blank value fails this shape.
val versionShape = Regex("""\d+\.\d+\.\d+.*""")

// Both sources go through the same normalisation — trim, strip the tag's leading `v`, then the
// shape check — so the env override and `git describe` cannot drift apart. `v1.2.3` yields `1.2.3`
// on either path; a value that fails the check returns null and the next source is tried.
fun normalizedVersion(raw: String?): String? =
    raw?.trim()?.removePrefix("v")?.takeIf { it.matches(versionShape) }

val corralVersion: String = normalizedVersion(System.getenv("CORRAL_VERSION"))
    ?: normalizedVersion(runCatching {
        providers.exec {
            commandLine("git", "describe", "--tags", "--match", "v*", "--always")
        }.standardOutput.asText.get()
    }.getOrNull())
    ?: "0.0.0-dev"

allprojects {
    version = corralVersion
}

// L1 hermeticity for every module's test JVM: no test may read or write the developer's real home.
// `user.home` is a mutable global that the daemon resolves config/db/emulator paths from, and a
// spawned emulator's reaper resolves it asynchronously — after a per-test override has restored it —
// so a per-test override alone leaks to the real home. Point it at each module's build/test-home
// (the same directory corral-daemon's CORRAL_RULES/CORRAL_ENV_FILE already use) and create it before
// the test worker boots. corral-daemon's own tasks.test block still adds its env-file overrides.
subprojects {
    tasks.withType<Test>().configureEach {
        val testHome = layout.buildDirectory.dir("test-home")
        doFirst { testHome.get().asFile.mkdirs() }
        systemProperty("user.home", testHome.get().asFile.absolutePath)
    }
}
