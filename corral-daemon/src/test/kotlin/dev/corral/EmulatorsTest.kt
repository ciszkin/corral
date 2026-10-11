package dev.corral

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmulatorsTest {
    private fun executable(p: Path): Path =
        p.also { Files.writeString(it, "#!/usr/bin/env bash\nexit 0\n") }.also { it.toFile().setExecutable(true) }

    private fun fakeEmulator(dir: Path, marker: Path): Path =
        dir.resolve("emulator").also {
            Files.writeString(it, """
                #!/usr/bin/env bash
                case "${'$'}1" in
                  -list-avds) printf 'Pixel6AVD\nSmallAVD\n' ;;
                  -avd) echo "${'$'}${'$'}" > '$marker'; exec sleep 300 ;;
                  *) exit 0 ;;
                esac
            """.trimIndent())
            it.toFile().setExecutable(true)
        }

    private fun scriptEmulator(dir: Path, body: String): Path =
        dir.resolve("emulator").also {
            Files.writeString(it, "#!/usr/bin/env bash\n$body\n")
            it.toFile().setExecutable(true)
        }

    private fun fakeAdb(dir: Path, log: Path): Path =
        dir.resolve("fake-adb").also {
            Files.writeString(it, """
                #!/usr/bin/env bash
                echo "${'$'}*" >> '$log'
                [ "${'$'}2" = badserial ] && { echo 'no emu' >&2; exit 3; }
                exit 0
            """.trimIndent())
            it.toFile().setExecutable(true)
        }

    private class FakeLauncher : EmulatorLauncher {
        val starts = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        var running = false
        var exit: Int? = null
        override fun start(bin: String, avd: String, log: Path) { starts += avd; running = true }
        override fun isRunning(avd: String): Boolean = running
        override fun stop(avd: String): String? { stopped += avd; running = false; return "unit $avd" }
        override fun lastExit(avd: String): Int? = exit
        override fun clearExit(avd: String) { exit = null }
    }

    private fun <T> withUserHome(home: Path, block: () -> T): T {
        val previous = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.toString())
            return block()
        } finally {
            System.setProperty("user.home", previous)
        }
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    private fun pidFrom(file: Path): Long {
        assertTrue(waitUntil(2_000) { Files.exists(file) }, "fake emulator never recorded its pid")
        return Files.readString(file).trim().toLong()
    }

    @Test fun listsAvds() {
        val dir = createTempDirectory("emu")
        val emu = fakeEmulator(dir, dir.resolve("started"))
        assertEquals(listOf("Pixel6AVD", "SmallAVD"), Emulators("adb", { emu.toString() }).list())
    }

    @Test fun listIsNullWhenTheBinaryIsMissing() {
        assertNull(Emulators("adb", { null }).list())
    }

    @Test fun listsEmptyWhenTheBinaryRunsAndHasNoAvds() {
        val dir = createTempDirectory("emu")
        val emu = scriptEmulator(dir, "exit 0")
        assertEquals(emptyList(), Emulators("adb", { emu.toString() }).list())
    }

    @Test fun listIsNullWhenTheBinaryFails() {
        val dir = createTempDirectory("emu")
        val emu = scriptEmulator(dir, "exit 3")
        assertNull(Emulators("adb", { emu.toString() }).list())
    }

    @Test fun listIsNullWhenTheBinaryTimesOut() {
        val dir = createTempDirectory("emu")
        val emu = scriptEmulator(dir, "sleep 3")
        assertNull(Emulators("adb", { emu.toString() }).list())
    }

    @Test fun startSpawnsAndLogs() {
        val dir = createTempDirectory("emu")
        val marker = dir.resolve("started")
        val emu = fakeEmulator(dir, marker)
        val home = createTempDirectory("home")
        val emulators = Emulators("adb", { emu.toString() }, DefaultLauncher())
        withUserHome(home) { emulators.start("Pixel6AVD") }
        try {
            assertTrue(waitUntil(2_000) { Files.exists(marker) }, "fake emulator never started")
            assertTrue(emulators.isRunning("Pixel6AVD"))
            assertTrue(Files.exists(home.resolve(".corral/emulator-Pixel6AVD.log")))
        } finally {
            if (Files.exists(marker)) Files.readString(marker).trim().toLongOrNull()
                ?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    @Test fun logSerialsReadsTheEmulatorConsoleSerial() {
        val home = createTempDirectory("home")
        val log = home.resolve(".corral/emulator-Pixel6AVD.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "INFO | Android emulator version 35.1.4\nemulator-5554: started\nemulator-5554: alive\n")
        withUserHome(home) {
            assertEquals(listOf("emulator-5554"), Emulators("adb", { null }).logSerials("Pixel6AVD"))
        }
    }

    @Test fun logSerialsIsEmptyWithoutALogOrAMatchingSerial() {
        val home = createTempDirectory("home")
        val log = home.resolve(".corral/emulator-Pixel6AVD.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "INFO | nothing addressable here\n")
        withUserHome(home) {
            val emulators = Emulators("adb", { null })
            assertTrue(emulators.logSerials("Pixel6AVD").isEmpty())
            assertTrue(emulators.logSerials("NoLogAVD").isEmpty())
        }
    }

    @Test fun startThrowsWithTheLogTailWhenTheEmulatorDiesImmediately() {
        val dir = createTempDirectory("emu")
        val emu = scriptEmulator(dir, "echo 'x86_64 emulation requires hardware acceleration' >&2; exit 1")
        val e = assertFailsWith<EmulatorException> {
            withUserHome(createTempDirectory("home")) {
                Emulators("adb", { emu.toString() }, DefaultLauncher()).start("Pixel6AVD")
            }
        }
        assertTrue("exited immediately" in (e.message ?: ""), e.message ?: "")
        assertTrue("hardware acceleration" in (e.message ?: ""), e.message ?: "")
    }

    @Test fun defaultLauncherReapsAnExitedProcess() {
        val dir = createTempDirectory("emu")
        val emu = scriptEmulator(dir, "exec sleep 1")
        val launcher = DefaultLauncher()
        launcher.start(emu.toString(), "Pixel6AVD", dir.resolve("out.log"))
        assertTrue(launcher.isRunning("Pixel6AVD"))
        assertTrue(waitUntil(2_000) { !launcher.isRunning("Pixel6AVD") }, "dead process still reported running")
    }

    @Test fun isRunningDelegatesToTheInjectedLauncher() {
        val fake = FakeLauncher()
        val emulators = Emulators("adb", { "/bin/true" }, fake)
        assertFalse(emulators.isRunning("Pixel6AVD"))
        withUserHome(createTempDirectory("home")) { emulators.start("Pixel6AVD") }
        assertEquals(listOf("Pixel6AVD"), fake.starts)
        assertTrue(emulators.isRunning("Pixel6AVD"))
    }

    @Test fun concurrentStartsSpawnOnce() {
        val launcher = object : EmulatorLauncher {
            val spawns = AtomicInteger()
            @Volatile var running = false
            override fun start(bin: String, avd: String, log: Path) {
                spawns.incrementAndGet()
                Thread.sleep(50)
                running = true
            }
            override fun isRunning(avd: String): Boolean = running
            override fun stop(avd: String): String? = null
            override fun lastExit(avd: String): Int? = null
        }
        val emulators = Emulators("adb", { "/bin/true" }, launcher)
        val spawned = AtomicInteger()
        withUserHome(createTempDirectory("home")) {
            val barrier = CyclicBarrier(8)
            (1..8).map { thread { barrier.await(); if (emulators.start("Pixel6AVD")) spawned.incrementAndGet() } }
                .forEach { it.join() }
        }
        assertEquals(1, spawned.get(), "exactly one caller should have spawned")
        assertEquals(1, launcher.spawns.get())
    }

    @Test fun startReturnsFalseWhenTheLauncherSaysAlreadyRunning() {
        val launcher = object : EmulatorLauncher {
            override fun start(bin: String, avd: String, log: Path) { throw EmulatorAlreadyRunning() }
            override fun isRunning(avd: String): Boolean = false
            override fun stop(avd: String): String? = null
            override fun lastExit(avd: String): Int? = null
        }
        assertFalse(withUserHome(createTempDirectory("home")) {
            Emulators("adb", { "/bin/true" }, launcher).start("Pixel6AVD")
        })
    }

    @Test fun lastErrorIsNullForACleanSelfExit() {
        val home = createTempDirectory("home")
        val log = home.resolve(".corral/emulator-Pixel6AVD.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "INFO | Emulator window closed by user\n")
        val launcher = FakeLauncher().apply { exit = 0 }
        withUserHome(home) {
            assertNull(Emulators("adb", { null }, launcher).lastError("Pixel6AVD"))
        }
    }

    @Test fun lastErrorReportsTheLogTailForANonZeroExit() {
        val home = createTempDirectory("home")
        val log = home.resolve(".corral/emulator-Pixel6AVD.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "booting\nERROR: acceleration missing\n")
        val launcher = FakeLauncher().apply { exit = 1 }
        withUserHome(home) {
            assertEquals("ERROR: acceleration missing",
                Emulators("adb", { null }, launcher).lastError("Pixel6AVD"))
        }
    }

    @Test fun lastErrorIsNullWhileRunning() {
        val home = createTempDirectory("home")
        val log = home.resolve(".corral/emulator-Pixel6AVD.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "ERROR: acceleration missing\n")
        val launcher = FakeLauncher().apply { running = true; exit = 1 }
        withUserHome(home) {
            assertNull(Emulators("adb", { null }, launcher).lastError("Pixel6AVD"))
        }
    }

    @Test fun lastErrorIsNullWhenThereIsNoExitSidecar() {
        val home = createTempDirectory("home")
        val log = home.resolve(".corral/emulator-Pixel6AVD.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "stale line from an unknown run\n")
        withUserHome(home) {
            assertNull(Emulators("adb", { null }, FakeLauncher()).lastError("Pixel6AVD"))
        }
    }

    @Test fun stopAvdRotatesTheLogAndClearsTheExitSidecar() {
        val home = createTempDirectory("home")
        val log = home.resolve(".corral/emulator-Pixel6AVD.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "INFO | Shutting down cleanly\n")
        val exitFile = home.resolve(".corral/emulator-Pixel6AVD.log.exit")
        Files.writeString(exitFile, "5\n")
        withUserHome(home) {
            val emulators = Emulators("adb", { null }, DefaultLauncher())
            assertEquals("INFO | Shutting down cleanly", emulators.logTail("Pixel6AVD"))
            assertNull(emulators.stopAvd("Pixel6AVD"))
            assertFalse(Files.exists(log))
            assertEquals("INFO | Shutting down cleanly\n",
                Files.readString(home.resolve(".corral/emulator-Pixel6AVD.log.1")))
            assertFalse(Files.exists(exitFile))
            assertNull(emulators.lastError("Pixel6AVD"))
        }
    }

    @Test fun startTruncatesAPreviousLogAndExitSidecar() {
        val dir = createTempDirectory("emu")
        val marker = dir.resolve("started")
        val emu = fakeEmulator(dir, marker)
        val home = createTempDirectory("home")
        val log = home.resolve(".corral/emulator-Pixel6AVD.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "old failure line\n")
        val rotated = home.resolve(".corral/emulator-Pixel6AVD.log.1")
        Files.writeString(rotated, "older failure line\n")
        val exit = home.resolve(".corral/emulator-Pixel6AVD.log.exit")
        Files.writeString(exit, "7\n")
        val emulators = Emulators("adb", { emu.toString() }, DefaultLauncher())
        withUserHome(home) { emulators.start("Pixel6AVD") }
        try {
            assertTrue(waitUntil(2_000) { Files.exists(marker) }, "fake emulator never started")
            assertFalse(Files.readString(log).contains("old failure line"), Files.readString(log))
            assertFalse(Files.exists(rotated))
            assertFalse(Files.exists(exit))
        } finally {
            if (Files.exists(marker)) Files.readString(marker).trim().toLongOrNull()
                ?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    @Test fun startTruncatesTheLogBeforeAnyLauncherSpawns() {
        val home = createTempDirectory("home")
        val log = home.resolve(".corral/emulator-Pixel6AVD.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "old failure line\n")
        Files.writeString(home.resolve(".corral/emulator-Pixel6AVD.log.exit"), "7\n")
        var contentAtStart: String? = null
        val launcher = object : EmulatorLauncher {
            override fun start(bin: String, avd: String, log: Path) {
                contentAtStart = if (Files.exists(log)) Files.readString(log) else null
            }
            override fun isRunning(avd: String): Boolean = false
            override fun stop(avd: String): String? = null
            override fun lastExit(avd: String): Int? = null
        }
        withUserHome(home) {
            assertTrue(Emulators("adb", { "/bin/true" }, launcher).start("Pixel6AVD"))
        }
        assertNull(contentAtStart)
    }

    @Test fun startDeletesTheRotatedLogAndClearsTheStatusBeforeAnyLauncherSpawns() {
        val home = createTempDirectory("home")
        val log = home.resolve(".corral/emulator-Pixel6AVD.log")
        Files.createDirectories(log.parent)
        Files.writeString(log, "old failure line\n")
        Files.writeString(log.resolveSibling("${log.fileName}.1"), "older failure line\n")
        var sawLog = true
        var sawRotated = true
        var cleared = false
        val launcher = object : EmulatorLauncher {
            override fun start(bin: String, avd: String, log: Path) {
                sawLog = Files.exists(log)
                sawRotated = Files.exists(log.resolveSibling("${log.fileName}.1"))
            }
            override fun isRunning(avd: String): Boolean = false
            override fun stop(avd: String): String? = null
            override fun lastExit(avd: String): Int? = null
            override fun clearExit(avd: String) { cleared = true }
        }
        withUserHome(home) {
            assertTrue(Emulators("adb", { "/bin/true" }, launcher).start("Pixel6AVD"))
        }
        assertFalse(sawLog)
        assertFalse(sawRotated)
        assertTrue(cleared)
    }

    @Test fun defaultLauncherRecordsACleanExitInTheSidecar() {
        val dir = createTempDirectory("emu")
        val emu = scriptEmulator(dir, "exit 0")
        val log = dir.resolve("out.log")
        val launcher = DefaultLauncher()
        runCatching { launcher.start(emu.toString(), "Pixel6AVD", log) }
        val sidecar = emulatorExitPath(log)
        assertTrue(waitUntil(2_000) { Files.exists(sidecar) && Files.readString(sidecar).trim().isNotEmpty() },
            "no exit sidecar written")
        assertEquals("0", Files.readString(sidecar).trim())
    }

    @Test fun systemdUnitNameSanitisesTheAvd() {
        assertTrue(Regex("corral-emulator-Pixel6AVD-[0-9a-f]{8}").matches(systemdUnitName("Pixel6AVD")))
        assertTrue(Regex("corral-emulator-Wide-AVD-[0-9a-f]{8}").matches(systemdUnitName("Wide AVD")))
        assertTrue(Regex("corral-emulator-a-b_c.d-e-[0-9a-f]{8}").matches(systemdUnitName("a/b_c.d e")))
    }

    @Test fun systemdUnitNameDistinguishesFoldedNames() {
        assertNotEquals(systemdUnitName("Pixel 6"), systemdUnitName("Pixel/6"))
    }

    @Test fun systemdRunForwardsOnlyAndroidEnvironment() {
        val cmd = systemdRunCommand("/sdk/emulator", "Pixel6AVD", Path.of("/tmp/emu.log"),
            mapOf("ANDROID_AVD_HOME" to "/x", "ANDROID_SDK_ROOT" to "/s", "OTHER" to "1", "PATH" to "/p"))
        assertTrue("--setenv=ANDROID_AVD_HOME=/x" in cmd, cmd.toString())
        assertTrue("--setenv=ANDROID_SDK_ROOT=/s" in cmd, cmd.toString())
        assertFalse(cmd.any { it.contains("OTHER") }, cmd.toString())
        assertFalse(cmd.any { it.contains("PATH") }, cmd.toString())
        assertTrue(cmd.any { it == "--property=StandardOutput=file:/tmp/emu.log" }, cmd.toString())
        assertTrue(cmd.any { it == "--property=StandardError=file:/tmp/emu.log" }, cmd.toString())
        assertFalse(cmd.any { it == "--collect" }, cmd.toString())
        assertFalse(cmd.any { it.contains("ExecStopPost") }, cmd.toString())
        assertEquals(listOf("/sdk/emulator", "-avd", "Pixel6AVD"), cmd.takeLast(3))
    }

    @Test fun systemdLastExitIsZeroForSuccess() {
        withUserHome(createTempDirectory("home")) {
            val launcher = SystemdLauncher(emptyMap()) { "ActiveState=inactive\nResult=success\nExecMainStatus=0\n" }
            assertEquals(0, launcher.lastExit("Pixel6AVD"))
            assertFalse(Files.exists(systemdExitPath("Pixel6AVD")))
        }
    }

    @Test fun systemdLastExitCarriesAnExitCode() {
        withUserHome(createTempDirectory("home")) {
            val launcher = SystemdLauncher(emptyMap(), resetFailed = {}) {
                "ActiveState=failed\nResult=exit-code\nExecMainCode=1\nExecMainStatus=3\n"
            }
            assertEquals(3, launcher.lastExit("Pixel6AVD"))
        }
    }

    @Test fun systemdLastExitMapsASignalTo128PlusTheSignal() {
        withUserHome(createTempDirectory("home")) {
            val launcher = SystemdLauncher(emptyMap(), resetFailed = {}) {
                "ActiveState=failed\nResult=signal\nExecMainCode=2\nExecMainStatus=9\n"
            }
            assertEquals(137, launcher.lastExit("Pixel6AVD"))
        }
    }

    @Test fun systemdLastExitIsNullWhenTheUnitIsNotLoaded() {
        val launcher = SystemdLauncher(emptyMap()) { null }
        assertNull(launcher.lastExit("Pixel6AVD"))
    }

    @Test fun systemdLastExitTreatsAnUnmodelledFailureAsNonZero() {
        withUserHome(createTempDirectory("home")) {
            val launcher = SystemdLauncher(emptyMap(), resetFailed = {}) {
                "ActiveState=failed\nResult=oom-kill\nExecMainStatus=0\n"
            }
            assertNotEquals(0, launcher.lastExit("Pixel6AVD"))
        }
    }

    @Test fun systemdLastExitReadsTheSidecarWhenPresent() {
        val home = createTempDirectory("home")
        withUserHome(home) {
            Files.createDirectories(home.resolve(".corral"))
            Files.writeString(systemdExitPath("Pixel6AVD"), "7\n")
            assertEquals(7, SystemdLauncher(emptyMap()) { null }.lastExit("Pixel6AVD"))
        }
    }

    @Test fun systemdFailedUnitWritesTheSidecarAndResetsTheUnit() {
        val home = createTempDirectory("home")
        withUserHome(home) {
            val reset = mutableListOf<String>()
            val launcher = SystemdLauncher(emptyMap(), resetFailed = { reset += it }) {
                "ActiveState=failed\nResult=exit-code\nExecMainStatus=3\n"
            }
            assertEquals(3, launcher.lastExit("Pixel6AVD"))
            assertEquals("3", Files.readString(systemdExitPath("Pixel6AVD")).trim())
            assertEquals(listOf(systemdUnitName("Pixel6AVD")), reset)
        }
    }

    @Test fun systemdSuccessDoesNotWriteASidecarOrReset() {
        val home = createTempDirectory("home")
        withUserHome(home) {
            val reset = mutableListOf<String>()
            val launcher = SystemdLauncher(emptyMap(), resetFailed = { reset += it }) {
                "ActiveState=inactive\nResult=success\nExecMainStatus=0\n"
            }
            assertEquals(0, launcher.lastExit("Pixel6AVD"))
            assertFalse(Files.exists(systemdExitPath("Pixel6AVD")))
            assertTrue(reset.isEmpty())
        }
    }

    @Test fun systemdNotLoadedDoesNotWriteASidecar() {
        val home = createTempDirectory("home")
        withUserHome(home) {
            assertNull(SystemdLauncher(emptyMap()) { null }.lastExit("Pixel6AVD"))
            assertFalse(Files.exists(systemdExitPath("Pixel6AVD")))
        }
    }

    @Test fun systemdRunningUnitDoesNotReadAStaleSidecar() {
        val home = createTempDirectory("home")
        withUserHome(home) {
            Files.createDirectories(home.resolve(".corral"))
            Files.writeString(systemdExitPath("Pixel6AVD"), "7\n")
            val launcher = SystemdLauncher(emptyMap()) { "ActiveState=active\nResult=success\nExecMainStatus=0\n" }
            assertNull(launcher.lastExit("Pixel6AVD"))
        }
    }

    @Test fun systemdClearExitDeletesTheSidecarAndResetsTheUnit() {
        val home = createTempDirectory("home")
        withUserHome(home) {
            val reset = mutableListOf<String>()
            val launcher = SystemdLauncher(emptyMap(), resetFailed = { reset += it }) { null }
            Files.createDirectories(home.resolve(".corral"))
            Files.writeString(systemdExitPath("Pixel6AVD"), "5\n")
            launcher.clearExit("Pixel6AVD")
            assertFalse(Files.exists(systemdExitPath("Pixel6AVD")))
            assertEquals(listOf(systemdUnitName("Pixel6AVD")), reset)
        }
    }

    @Test fun systemdReapFailedWritesSidecarsAndResetsFailedUnits() {
        val home = createTempDirectory("home")
        val unit = systemdUnitName("Pixel6AVD")
        withUserHome(home) {
            val reset = mutableListOf<String>()
            val launcher = SystemdLauncher(emptyMap(), resetFailed = { reset += it },
                listFailed = { listOf(unit) }) {
                "ActiveState=failed\nResult=signal\nExecMainStatus=9\n"
            }
            launcher.reapFailed()
            assertEquals("137", Files.readString(systemdExitPath("Pixel6AVD")).trim())
            assertEquals(listOf(unit), reset)
        }
    }

    @Test fun emulatorsReapFailedDelegatesToTheLauncher() {
        var reaped = false
        val launcher = object : EmulatorLauncher {
            override fun start(bin: String, avd: String, log: Path) {}
            override fun isRunning(avd: String): Boolean = false
            override fun stop(avd: String): String? = null
            override fun lastExit(avd: String): Int? = null
            override fun reapFailed() { reaped = true }
        }
        Emulators("adb", { null }, launcher).reapFailed()
        assertTrue(reaped)
    }

    @Test fun defaultLauncherDestroyAllEndsATrackedChild() {
        val dir = createTempDirectory("emu")
        val pidFile = dir.resolve("pid")
        val emu = scriptEmulator(dir, "echo \$\$ > '$pidFile'; exec sleep 300")
        val launcher = DefaultLauncher()
        launcher.start(emu.toString(), "Pixel6AVD", dir.resolve("out.log"))
        assertTrue(launcher.isRunning("Pixel6AVD"))
        val pid = pidFrom(pidFile)
        launcher.destroyAll()
        assertFalse(launcher.isRunning("Pixel6AVD"))
        assertTrue(waitUntil(2_000) { !ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
            "child $pid outlived destroyAll")
    }

    @Test fun defaultLauncherDestroyAllIsIdempotent() {
        val launcher = DefaultLauncher()
        launcher.destroyAll()
        launcher.destroyAll()
        assertFalse(launcher.isRunning("Pixel6AVD"))
    }

    @Test fun systemdDestroyAllIsANoOp() {
        SystemdLauncher(emptyMap()) { null }.destroyAll()
    }

    @Test fun emulatorsShutdownDelegatesToTheLauncher() {
        var destroyed = false
        val launcher = object : EmulatorLauncher {
            override fun start(bin: String, avd: String, log: Path) {}
            override fun isRunning(avd: String): Boolean = false
            override fun stop(avd: String): String? = null
            override fun lastExit(avd: String): Int? = null
            override fun destroyAll() { destroyed = true }
        }
        Emulators("adb", { null }, launcher).shutdown()
        assertTrue(destroyed)
    }

    @Test fun launcherForHonoursTheExplicitOverride() {
        assertTrue(launcherFor(mapOf("CORRAL_EMULATOR_LAUNCHER" to "systemd"), cgroup = { null }) is SystemdLauncher)
        assertTrue(launcherFor(mapOf("CORRAL_EMULATOR_LAUNCHER" to "direct"),
            cgroup = { "corral.service" }) is DefaultLauncher)
    }

    @Test fun launcherForHonoursTheOverrideArgument() {
        assertTrue(launcherFor(emptyMap(), cgroup = { null }, override = "systemd") is SystemdLauncher)
        assertTrue(launcherFor(emptyMap(), cgroup = { "corral.service" }, override = "direct") is DefaultLauncher)
        assertTrue(launcherFor(emptyMap(), cgroup = { null }, override = "") is DefaultLauncher)
        assertTrue(launcherFor(emptyMap(), cgroup = { null }, override = "SystemD") is SystemdLauncher)
        assertTrue(launcherFor(emptyMap(), cgroup = { "corral.service" }, override = "Direct") is DefaultLauncher)
    }

    @Test fun launcherForAutoDetectsTheCorralServiceCgroup() {
        assertTrue(launcherFor(emptyMap(), cgroup = { "1:name=systemd:/user.slice/corral.service" }) is SystemdLauncher)
        assertTrue(launcherFor(emptyMap(), cgroup = { null }) is DefaultLauncher)
        assertTrue(launcherFor(emptyMap(), cgroup = { "0::/user.slice/session.scope" }) is DefaultLauncher)
    }

    @Test fun defaultLauncherStopEndsTheTrackedProcess() {
        val dir = createTempDirectory("emu")
        val emu = scriptEmulator(dir, "exec sleep 300")
        val launcher = DefaultLauncher()
        launcher.start(emu.toString(), "Pixel6AVD", dir.resolve("out.log"))
        assertTrue(launcher.isRunning("Pixel6AVD"))
        launcher.stop("Pixel6AVD")
        assertFalse(launcher.isRunning("Pixel6AVD"))
    }

    @Test fun stopAvdDelegatesToTheLauncher() {
        val fake = FakeLauncher()
        assertEquals("unit Pixel6AVD", Emulators("adb", { "/bin/true" }, fake).stopAvd("Pixel6AVD"))
        assertEquals(listOf("Pixel6AVD"), fake.stopped)
    }

    @Test fun defaultLauncherStopReportsThePidItDestroyed() {
        val dir = createTempDirectory("emu")
        val emu = scriptEmulator(dir, "exec sleep 300")
        val launcher = DefaultLauncher()
        launcher.start(emu.toString(), "Pixel6AVD", dir.resolve("out.log"))
        val terminated = launcher.stop("Pixel6AVD")
        assertTrue(terminated != null && terminated.startsWith("pid "), terminated ?: "null")
        assertNull(launcher.stop("Pixel6AVD"))   // already gone
    }

    @Test fun defaultLauncherStopOfAnUntrackedAvdIsNull() {
        assertNull(DefaultLauncher().stop("Pixel6AVD"))
    }

    @Test fun startWithoutBinaryTeachesTheOverride() {
        val e = assertFailsWith<EmulatorException> { Emulators("adb", { null }).start("Pixel6AVD") }
        assertTrue("CORRAL_EMULATOR" in (e.message ?: ""))
    }

    @Test fun stopRunsEmuKill() {
        val dir = createTempDirectory("emu")
        val log = dir.resolve("invocations")
        Emulators(fakeAdb(dir, log).toString(), { null }).stop("emulator-5554")
        assertEquals(listOf("-s emulator-5554 emu kill"), Files.readAllLines(log))
    }

    @Test fun stopWithMissingAdbTeachesTheOverride() {
        val dir = createTempDirectory("emu")
        val e = assertFailsWith<EmulatorException> {
            Emulators(dir.resolve("no-such-adb").toString(), { null }).stop("emulator-5554") }
        assertTrue("CORRAL_ADB" in (e.message ?: ""))
    }

    @Test fun stopFailureCarriesStderr() {
        val dir = createTempDirectory("emu")
        val adb = fakeAdb(dir, dir.resolve("invocations"))
        val e = assertFailsWith<EmulatorException> { Emulators(adb.toString(), { null }).stop("badserial") }
        assertTrue("no emu" in (e.message ?: ""))
    }

    @Test fun resolutionOrderEnvSdkPath() {
        val sdk = createTempDirectory("sdk")
        val adb = executable(Files.createDirectories(sdk.resolve("platform-tools")).resolve("adb"))
        val sibling = executable(Files.createDirectories(sdk.resolve("emulator")).resolve("emulator"))
        val fromEnv = executable(Files.createTempFile("emulator", ""))
        assertEquals(fromEnv.toString(),
            Emulators.resolveEmulatorPath(mapOf("CORRAL_EMULATOR" to fromEnv.toString(), "PATH" to ""), adb.toString()))
        assertEquals(sibling.toString(),
            Emulators.resolveEmulatorPath(mapOf("PATH" to ""), adb.toString()))
        val pathDir = createTempDirectory("path")
        val onPath = executable(pathDir.resolve("emulator"))
        assertEquals(onPath.toString(),
            Emulators.resolveEmulatorPath(mapOf("PATH" to pathDir.toString()), null))
    }
}
