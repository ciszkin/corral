package dev.corral

import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletionException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateApplierTest {

    private fun newWorkDir() = createTempDirectory("update-test")

    private fun applier(
        workDir: Path,
        rel: Relaunch = Relaunch.None,
        components: InstalledComponents = InstalledComponents(daemon = true, ui = true),
        refusal: String? = null,
        download: (URI, Path) -> Unit = { _, _ -> },
        execute: (List<String>) -> Int = { 0 },
        spawn: (List<String>) -> Unit = ::spawnProcess,
    ) = UpdateApplier(
        tag = "v1.2.3",
        repo = "ciszkin/corral",
        workDir = workDir,
        components = components,
        relaunch = rel,
        platformRefusal = refusal,
        download = download,
        execute = execute,
        spawn = spawn,
    )

    @Test fun commandsAreTheInstallerPipeline() {
        val workDir = Path.of("/tmp/corral-update")
        val relaunch = Relaunch.Console(listOf("java", "-cp", "/home/u/.corral/corral-ui-all.jar", "dev.corral.ui.CorralUiKt"))
        val applier = UpdateApplier(
            tag = "v1.2.3", repo = "ciszkin/corral", workDir = workDir,
            components = InstalledComponents(daemon = true, ui = true), relaunch = relaunch,
        )

        assertEquals(
            listOf(
                listOf("unzip", "-o", "/tmp/corral-update/corral-1.2.3.zip", "-d", "/tmp/corral-update/bundle"),
                listOf("bash", "/tmp/corral-update/bundle/install.sh", "--ui"),
                listOf("systemctl", "--user", "restart", "corral"),
                listOf("java", "-cp", "/home/u/.corral/corral-ui-all.jar", "dev.corral.ui.CorralUiKt"),
            ),
            applier.commands(),
        )
    }

    @Test fun checksumMismatchAbortsBeforeAnythingIsExtractedOrRun() {
        val workDir = newWorkDir()
        Files.write(workDir.resolve("corral-1.2.3.zip"), "tampered archive bytes".toByteArray())
        // a correct-looking 64-hex sidecar that names a different archive
        Files.writeString(workDir.resolve("corral-1.2.3.zip.sha256"), "0".repeat(64) + "  corral-1.2.3.zip\n")
        val actual = sha256Hex(workDir.resolve("corral-1.2.3.zip"))
        val ran = mutableListOf<List<String>>()
        val downloads = mutableListOf<URI>()

        val result = applier(workDir, download = { uri, _ -> downloads += uri }, execute = { ran += it; 0 }).run()

        assertTrue(result is ApplyResult.Failed, "expected a hard stop, got $result")
        val message = (result as ApplyResult.Failed).reason
        assertTrue("0".repeat(64) in message, "message must name the expected digest: $message")
        assertTrue(actual in message, "message must name the actual digest: $message")
        assertTrue("refusing to install" in message, message)
        // reached both downloads, then stopped at the gate before any command ran
        assertEquals(2, downloads.size, "both the zip and its .sha256 must be fetched")
        assertTrue(ran.isEmpty(), "no step may run after a checksum mismatch: $ran")
    }

    @Test fun aDownloadFailureIsAnHonestFailedResultAndHttpDownloadRaisesIOException() {
        // a port bound then closed: connecting to it is refused, the most ordinary failure there is
        val closedPort = ServerSocket(0).use { it.localPort }
        val refused = URI.create("http://127.0.0.1:$closedPort/corral-1.2.3.zip")

        val direct = runCatching { httpDownload(refused, newWorkDir().resolve("probe.zip")) }.exceptionOrNull()
        assertTrue(direct is java.io.IOException, "httpDownload must raise IOException, was $direct")
        assertTrue(direct !is CompletionException, "the async failure must not escape raw: $direct")

        val workDir = newWorkDir()
        val ran = mutableListOf<List<String>>()
        val result = applier(
            workDir,
            execute = { ran += it; 0 },
            download = { _, dest -> httpDownload(refused, dest) },
        ).run()

        assertTrue(result is ApplyResult.Failed, "offline must be a clean Failed, got $result")
        assertTrue("could not download" in (result as ApplyResult.Failed).reason, result.reason)
        assertTrue(ran.isEmpty(), "nothing may run after a failed download: $ran")
    }

    @Test fun missingChecksumFileAbortsTheSameWay() {
        val workDir = newWorkDir()
        Files.write(workDir.resolve("corral-1.2.3.zip"), "real bytes".toByteArray())
        val downloads = mutableListOf<URI>()
        val ran = mutableListOf<List<String>>()

        val result = applier(workDir, download = { uri, _ -> downloads += uri }, execute = { ran += it; 0 }).run()

        assertTrue(result is ApplyResult.Failed, "expected a hard stop, got $result")
        assertTrue("no .sha256" in (result as ApplyResult.Failed).reason, result.reason)
        assertTrue("refusing to install" in result.reason, result.reason)
        // reached both downloads, then stopped at the gate before any command ran
        assertEquals(2, downloads.size, "both the zip and its .sha256 must be fetched")
        assertTrue(ran.isEmpty(), "no step may run without a checksum: $ran")
    }

    @Test fun cliApplyWithAnUncreatableScratchDirFailsHonestly() {
        // A regular file where the scratch parent should be: creating the dir must fail, not throw.
        val blocked = Files.createTempFile("corral-blocked", ".tmp")
        val workDir = blocked.resolve("scratch")

        val result = UpdateApplier.forCli("v1.2.3", home = newWorkDir(), repo = "ciszkin/corral", workDir = workDir).run()

        assertTrue(result is ApplyResult.Failed, "expected a clean Failed, got $result")
        val reason = (result as ApplyResult.Failed).reason
        assertTrue("scratch" in reason, "the message must name what actually went wrong: $reason")
        assertFalse(reason.contains("daemon unreachable"), "the CLI must not blame the daemon: $reason")
    }

    @Test fun scratchDirIsAPurePathSoTheFactoryNeverTouchesTheFilesystem() {
        // The old eager Files.createTempDirectory threw at the call site, before run()'s try. The
        // factory now only picks a path; run() owns creating it and reporting a failure.
        val path = scratchDir()
        assertFalse(Files.exists(path), "provisioning the scratch path must not create anything: $path")
    }

    @Test fun componentsAreDerivedFromWhatIsInstalled() {
        val home = newWorkDir()
        val corralHome = home.resolve(".corral")
        Files.createDirectories(corralHome)

        Files.writeString(corralHome.resolve("corral-daemon-all.jar"), "daemon")
        val daemonOnly = InstalledComponents.detect(corralHome)
        assertEquals(InstalledComponents(daemon = true, ui = false), daemonOnly)
        assertEquals("--no-ui", applier(newWorkDir(), components = daemonOnly).commands()[1][2])

        val freshHome = newWorkDir().resolve(".corral")
        Files.createDirectories(freshHome)
        Files.writeString(freshHome.resolve("corral-ui-all.jar"), "ui")
        val uiOnly = InstalledComponents.detect(freshHome)
        assertEquals(InstalledComponents(daemon = false, ui = true), uiOnly)
        assertEquals("--ui", applier(newWorkDir(), components = uiOnly).commands()[1][2])

        Files.writeString(corralHome.resolve("corral-ui-all.jar"), "ui")
        val both = InstalledComponents.detect(corralHome)
        assertEquals(InstalledComponents(daemon = true, ui = true), both)
        assertEquals("--ui", applier(newWorkDir(), components = both).commands()[1][2])
    }

    @Test fun refusesOnAHostWithoutSystemd() {
        val remedy = systemdRefusal("Windows 11", hasSystemctl = false)
        assertTrue(remedy != null && "systemctl --user restart corral" in remedy, "remedy: $remedy")
        assertNull(systemdRefusal("Linux", hasSystemctl = true))

        val downloads = mutableListOf<URI>()
        val ran = mutableListOf<List<String>>()
        val result = applier(
            newWorkDir(),
            refusal = remedy,
            download = { uri, _ -> downloads += uri },
            execute = { ran += it; 0 },
        ).run()

        assertTrue(result is ApplyResult.Refused, "expected a refusal, got $result")
        assertTrue("systemctl --user restart corral" in (result as ApplyResult.Refused).reason, result.reason)
        assertTrue(downloads.isEmpty(), "a refused host must download nothing: $downloads")
        assertTrue(ran.isEmpty(), "a refused host must run nothing: $ran")
    }

    @Test fun relaunchIsDerivedFromTheRunningProcessOrTheFailureIsHonest() {
        assertEquals(
            listOf("java", "-cp", "/opt/corral-ui-all.jar", "dev.corral.ui.CorralUiKt"),
            relaunchArgv("java", listOf("-cp", "/opt/corral-ui-all.jar", "dev.corral.ui.CorralUiKt"), null),
        )
        assertEquals(
            listOf("java", "-cp", "/opt/corral-ui-all.jar", "dev.corral.ui.CorralUiKt"),
            relaunchArgv("java", null, "java -cp /opt/corral-ui-all.jar dev.corral.ui.CorralUiKt"),
        )
        assertNull(relaunchArgv(null, null, null))
        assertNull(relaunchArgv("java", listOf("-cp", "/opt/other.jar", "dev.other.Main"), null))
        assertTrue(CONSOLE_RELAUNCH_UNKNOWN.isNotBlank())
    }

    @Test fun aVerifiedBundleProceedsThroughExtractAndInstall() {
        val workDir = newWorkDir()
        val zip = workDir.resolve("corral-1.2.3.zip")
        Files.copy(zipWith("install.sh" to "#!/bin/sh\necho fake-install\n", "corral-daemon-all.jar" to "jar"), zip)
        Files.writeString(workDir.resolve("corral-1.2.3.zip.sha256"), sha256Hex(zip) + "  corral-1.2.3.zip\n")

        val ran = mutableListOf<List<String>>()
        var installShExistedAtInstall = false
        val result = applier(workDir, execute = { argv ->
            ran += argv
            when (argv.first()) {
                "unzip" -> ProcessBuilder(argv).inheritIO().start().waitFor()
                "bash" -> { installShExistedAtInstall = Files.exists(Path.of(argv[1])); 0 }
                else -> 0
            }
        }).run()

        assertTrue(result is ApplyResult.Completed, "expected completion, got $result")
        assertTrue(installShExistedAtInstall, "the bundle must be extracted before install.sh runs")
        assertEquals(listOf("unzip", "bash", "systemctl"), ran.map { it.first() })
    }

    private fun zipWith(vararg entries: Pair<String, String>): Path {
        val path = Files.createTempFile("bundle", ".zip")
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return path
    }
}
