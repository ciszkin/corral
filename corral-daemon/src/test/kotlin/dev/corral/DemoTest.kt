package dev.corral

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * The supervisor's decisions, none of which may need a device, a port or a running daemon to test:
 * usage and refusal exit codes, the marker that is the only thing `--clean` trusts, the port scan,
 * the child environment, and the exit-code-shaped refusals behind `corral demo`.
 */
class DemoTest {
    private fun root(): Path = createTempDirectory("demohomes")

    @Test fun usageErrorsAreExit2AndNameTheProblem() {
        val cases = listOf(listOf("--nope"), listOf("--port"), listOf("--port", "99999"), listOf("--home"))
        for (args in cases) {
            val err = ByteArrayOutputStream()
            val saved = System.err
            System.setErr(PrintStream(err, true))
            val code = try { runDemo(args, PrintStream(ByteArrayOutputStream())) } finally { System.setErr(saved) }
            assertEquals(DEMO_EXIT_USAGE, code, "args=$args")
            assertTrue(err.toString().isNotBlank(), "a usage error must say what was wrong (args=$args)")
        }
    }

    @Test fun helpExits0AndNamesTheDemoContract() {
        val out = ByteArrayOutputStream()
        assertEquals(0, runDemo(listOf("--help"), PrintStream(out, true)))
        val text = out.toString()
        assertContains(text, "corral demo")
        assertContains(text, "No phones, no adb, no SDK, no network")
        assertContains(text, "--clean")
        assertContains(text, "--keep")
        for (flag in listOf("--speed", "--loop", "--no-script", "--plain")) assertContains(text, flag)
    }

    @Test fun theSpeedFlagAcceptsThePlansRangeAndNothingElse() {
        assertEquals(0.5, parseDemoSpeed("0.5"))
        assertEquals(1.0, parseDemoSpeed("1"))
        assertEquals(4.0, parseDemoSpeed(" 4 "))
        assertEquals(2.0, parseDemoSpeed("2.0"))
        assertNull(parseDemoSpeed("0.4"), "below the plan's floor")
        assertNull(parseDemoSpeed("4.1"), "above the plan's ceiling")
        assertNull(parseDemoSpeed("fast"))
        assertNull(parseDemoSpeed(""))
    }

    @Test fun anOutOfRangeSpeedIsUsageAndNotASilentlyClampedDemo() {
        for (args in listOf(listOf("--speed", "9"), listOf("--speed", "0"), listOf("--speed", "fast"), listOf("--speed"))) {
            val err = ByteArrayOutputStream()
            val saved = System.err
            System.setErr(PrintStream(err, true))
            val code = try { runDemo(args, PrintStream(ByteArrayOutputStream())) } finally { System.setErr(saved) }
            assertEquals(DEMO_EXIT_USAGE, code, "args=$args")
            assertTrue(err.toString().isNotBlank(), "a refused --speed must say why (args=$args)")
        }
    }

    @Test fun loopAndHomeCannotBeCombinedBecauseAKioskNeedsAFreshWorld() {
        val dir = createTempDirectory("loophome").resolve("demo")
        val err = ByteArrayOutputStream()
        val saved = System.err
        System.setErr(PrintStream(err, true))
        val code = try { runDemo(listOf("--loop", "--home", dir.toString()), PrintStream(ByteArrayOutputStream())) } finally {
            System.setErr(saved)
        }
        assertEquals(DEMO_EXIT_USAGE, code)
        assertContains(err.toString(), "--loop")
        assertFalse(Files.exists(dir), "the refusal must not create the home it refused")
    }

    @Test fun anUnmarkedHomeIsRefusedAndLeftAlone() {
        val dir = createTempDirectory("notourdemohome")
        Files.writeString(dir.resolve("precious.txt"), "keep me")
        val err = ByteArrayOutputStream()
        val saved = System.err
        System.setErr(PrintStream(err, true))
        val code = try { runDemo(listOf("--home", dir.toString()), PrintStream(ByteArrayOutputStream())) } finally {
            System.setErr(saved)
        }
        assertEquals(DEMO_EXIT_REFUSED, code)
        assertContains(err.toString(), "no demo marker")
        assertTrue(Files.exists(dir.resolve("precious.txt")), "a refused --home must not be touched")
    }

    @Test fun aHomeInsideTheRealCorralHomeIsRefused() {
        val real = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        for (target in listOf(real.resolve(".corral").toString(), real.resolve(".config/corral").toString())) {
            val err = ByteArrayOutputStream()
            val saved = System.err
            System.setErr(PrintStream(err, true))
            val code = try { runDemo(listOf("--home", target), PrintStream(ByteArrayOutputStream())) } finally {
                System.setErr(saved)
            }
            assertEquals(DEMO_EXIT_REFUSED, code, "target=$target")
            assertContains(err.toString(), "real Corral home")
        }
    }

    @Test fun theMarkerRoundTripsAndRejectsAnythingElse() {
        val marker = DemoMarker(supervisor = 11, daemon = 22, port = 8799, createdAt = 1_700_000_000_000)
        val parsed = DemoMarker.parse(marker.render("0.1.5"))
        assertEquals(marker, parsed)
        val text = marker.render("0.1.5")
        assertContains(text, "marker=corral-demo", message = "the marker is what proves provenance")
        assertNull(DemoMarker.parse("some other tool's file\nport=8799"))
        assertNull(DemoMarker.parse(""))
    }

    @Test fun aRunningDemosHomeIsRefusedRatherThanClobbered() {
        val dir = createTempDirectory("runningsdemohome")
        // this JVM is alive, so the marker's supervisor pid makes the home a live demo's
        Files.writeString(dir.resolve(DEMO_MARKER),
            DemoMarker(ProcessHandle.current().pid(), 0, 8799, System.currentTimeMillis()).render("0.1.5"))
        Files.writeString(dir.resolve("daemon.env"), "CORRAL_UPDATE_CHECK=off\n")
        val before = Files.readString(dir.resolve("daemon.env"))
        val err = ByteArrayOutputStream()
        val saved = System.err
        System.setErr(PrintStream(err, true))
        val code = try { runDemo(listOf("--home", dir.toString()), PrintStream(ByteArrayOutputStream())) } finally {
            System.setErr(saved)
        }
        assertEquals(DEMO_EXIT_REFUSED, code)
        assertContains(err.toString(), "running demo")
        assertContains(err.toString(), ProcessHandle.current().pid().toString(), message = "the refusal names the pid")
        assertEquals(before, Files.readString(dir.resolve("daemon.env")), "a refused --home must not be touched")
        assertTrue(Files.exists(dir.resolve(DEMO_MARKER)), "a refused --home keeps its provenance marker")
    }

    @Test fun cleanRemovesOnlyMarkedDeadHomesAndNamesWhatItLeaves() {
        val root = root()
        val dead = root.resolve("${DEMO_HOME_PREFIX}dead")
        Files.createDirectories(dead)
        Files.writeString(dead.resolve(DEMO_MARKER),
            DemoMarker(0, 0, 8799, System.currentTimeMillis()).render("0.1.5"))
        Files.writeString(dead.resolve("corral.db"), "x")

        val foreign = root.resolve("${DEMO_HOME_PREFIX}foreign")
        Files.createDirectories(foreign)
        Files.writeString(foreign.resolve("keep.txt"), "not ours")

        val running = root.resolve("${DEMO_HOME_PREFIX}running")
        Files.createDirectories(running)
        Files.writeString(running.resolve(DEMO_MARKER),
            DemoMarker(ProcessHandle.current().pid(), 0, 8799, System.currentTimeMillis()).render("0.1.5"))

        val out = ByteArrayOutputStream()
        assertEquals(0, cleanDemoHomes(PrintStream(out, true), root))
        val text = out.toString()
        assertFalse(Files.exists(dead), "a marked, dead home is removed")
        assertContains(text, "demo: removed $dead")
        assertTrue(Files.exists(foreign.resolve("keep.txt")), "a foreign directory is never removed")
        assertContains(text, "left alone $foreign")
        assertContains(text, "no demo marker")
        assertTrue(Files.exists(running), "a running demo home is left alone")
        assertContains(text, "still running")
    }

    @Test fun cleanOnAMissingRootIsQuietlyFine() {
        val out = ByteArrayOutputStream()
        assertEquals(0, cleanDemoHomes(PrintStream(out, true), root().resolve("absent")))
        assertContains(out.toString(), "no demo homes under")
    }

    /**
     * Review F1, the Windows half: sqlite-jdbc's native library lives in the child's
     * `java.io.tmpdir` (= `<home>/tmp`) and is a mapped image a delete can refuse. A residue that
     * cannot be emptied must keep its marker — an un-marked demo tree is one `--clean` can never
     * collect — and must then be collected by `--clean` once the handle drains.
     */
    @Test fun aHomeThatCannotBeEmptiedKeepsItsMarkerSoCleanCanStillCollectIt() {
        val home = root().resolve("${DEMO_HOME_PREFIX}stranded")
        Files.createDirectories(home.resolve("tmp"))
        Files.writeString(home.resolve(DEMO_MARKER),
            DemoMarker(0, 0, 8799, System.currentTimeMillis()).render("0.1.5"))
        val dll = home.resolve("tmp").resolve("sqlite-3.50.3.0-stranded-sqlitejdbc.dll")
        Files.writeString(dll, "the mapped native library")

        val removed = removeTree(home, attempts = 2, pauseMs = 1) { path ->
            if (path != dll) runCatching { Files.deleteIfExists(path) }
        }
        assertFalse(removed, "a home that cannot be emptied is not reported as removed")
        assertTrue(Files.exists(home.resolve(DEMO_MARKER)), "the marker stays, so the residue is still collectable (I10)")
        assertTrue(Files.exists(dll), "the file that refused to go is still there")

        val out = ByteArrayOutputStream()
        assertEquals(0, cleanDemoHomes(PrintStream(out, true), home.parent))
        assertFalse(Files.exists(home), "--clean collected the residue that kept its marker")
        assertContains(out.toString(), "demo: removed $home")
    }

    /** The other half of the fix: one retry is all the drained handle needs — no longer, no endless. */
    @Test fun aDeleteThatRefusesOnceIsRetriedUntilTheHomeIsGone() {
        val home = root().resolve("${DEMO_HOME_PREFIX}raced")
        Files.createDirectories(home.resolve("tmp"))
        Files.writeString(home.resolve(DEMO_MARKER),
            DemoMarker(0, 0, 8799, System.currentTimeMillis()).render("0.1.5"))
        val dll = home.resolve("tmp").resolve("sqlite-3.50.3.0-raced-sqlitejdbc.dll")
        Files.writeString(dll, "the mapped native library")
        var refusals = 1

        val removed = removeTree(home, attempts = 3, pauseMs = 1) { path ->
            if (path == dll && refusals > 0) refusals-- else runCatching { Files.deleteIfExists(path) }
        }
        assertTrue(removed, "one retry after the child's handles drain removes the whole home")
        assertFalse(Files.exists(home), "no residue, and no marker left behind")
    }

    @Test fun thePortScanTakesTheFirstFreePortInThePlanWindow() {
        assertEquals(8810, freeDemoPort(8810) { true })
        assertEquals(8812, freeDemoPort(8810) { it >= 8812 })
        assertNull(freeDemoPort(8810) { false }, "no free port means no demo, not a shared port")
        assertEquals(8811, freeDemoPort(8810) { it != 8810 }, "the window starts at the requested port")
    }

    @Test fun theChildEnvironmentPinsDemoAndDropsEveryRealPath() {
        val home = root()
        val env = demoEnv(home, 8899, 4242)
        assertEquals("1", env["CORRAL_DEMO"])
        assertEquals("8899", env["CORRAL_PORT"])
        assertEquals("off", env["CORRAL_UPDATE_CHECK"])
        assertEquals(home.resolve("corral.db").toString(), env["CORRAL_DB"])
        assertEquals(home.resolve("rules").toString(), env["CORRAL_RULES"])
        assertFalse("CORRAL_DEMO_HOME" in env, "the demo home is not a knob: the child gets -Duser.home and CORRAL_* paths")
        for (k in listOf("CORRAL_ADB", "CORRAL_EMULATOR", "CORRAL_REPO", "CORRAL_FAKE_DEVICES",
            "ANDROID_SERIAL", "ANDROID_HOME", "INVOCATION_ID")) {
            assertTrue(k in DEMO_UNSET, "$k must be dropped from the child's environment")
            assertFalse(k in env, "$k must not be set for the child")
        }
    }

    @Test fun theChildIsTheSameJarUnderItsOwnUserHome() {
        val cmd = demoCommand(Path.of("/jdk/bin/java"), Path.of("/opt/corral-daemon-all.jar"), Path.of("/tmp/demo"))
        val java = Path.of("/jdk/bin/java")
        val jar = Path.of("/opt/corral-daemon-all.jar")
        val home = Path.of("/tmp/demo")
        assertEquals(java.toString(), cmd[0])
        assertContains(cmd, "-Duser.home=$home")
        assertContains(cmd, "-Djava.io.tmpdir=${home.resolve("tmp")}", "the child's scratch files must die with the home")
        assertFalse(cmd.any { it.startsWith("-Dcorral.demo") }, "the demo flag is env-only: CORRAL_DEMO is the one source")
        assertEquals(listOf("-jar", jar.toString()), cmd.takeLast(2))
    }

    @Test fun theDemoUpdateSwitchIsOffWhateverTheFileOrEnvironmentSays() {
        val inheritedOn = DaemonConfig(fileValues = mapOf("CORRAL_UPDATE_CHECK" to "on"),
            inherited = mapOf("CORRAL_UPDATE_CHECK" to "on"))
        assertEquals("on", inheritedOn.get("CORRAL_UPDATE_CHECK"))
        assertEquals("off", demoUpdateOff().get("CORRAL_UPDATE_CHECK"), "a demo must not reach the feed (I5)")
        assertEquals("off", demoUpdateOff().get("CORRAL_UPDATE_CHECK", useFile = false))
    }

    @Test fun demoModeReadsTheFlagOnceAndOnlyTruthyValuesCount() {
        assertEquals(true, demoMode(DaemonConfig(fileValues = emptyMap(), inherited = mapOf("CORRAL_DEMO" to "1"))))
        assertEquals(true, demoMode(DaemonConfig(fileValues = emptyMap(), inherited = mapOf("CORRAL_DEMO" to "TRUE"))))
        assertEquals(false, demoMode(DaemonConfig(fileValues = emptyMap(), inherited = mapOf("CORRAL_DEMO" to "0"))))
        assertEquals(false, demoMode(DaemonConfig(fileValues = emptyMap(), inherited = emptyMap())))
    }

    @Test fun theWriteBarrierRefusesPathsAndTheUpdateSwitchOnly() {
        assertContains(demoSettingRefusal("CORRAL_ADB", "/usr/bin/adb") ?: "", "never runs adb")
        assertContains(demoSettingRefusal("CORRAL_EMULATOR", "/usr/bin/emulator") ?: "", "never starts an emulator")
        assertContains(demoSettingRefusal("CORRAL_DB", "/tmp/x.db") ?: "", "own database")
        assertContains(demoSettingRefusal("CORRAL_UPDATE_CHECK", "on") ?: "", "no network call")
        assertNull(demoSettingRefusal("CORRAL_UPDATE_CHECK", "off"), "turning it off is already the demo state")
        assertNull(demoSettingRefusal("CORRAL_SCREENSHOTS", "open"), "sandbox-safe knobs stay writable")
        assertNull(demoSettingRefusal("CORRAL_DEMO", "0"), "the flag is not a registered key; PUT refuses it as unknown")
    }

    @Test fun aDemoDeviceLineAlwaysSaysDemoAndItsReadiness() {
        val usb = Device("DEMO01234567", "device", model = "Pixel 7 (demo)", osVersion = "14")
        assertContains(demoDeviceLine(usb), "Pixel 7 (demo)")
        assertContains(demoDeviceLine(usb), "ready")
        val booting = Device("emulator-5554", "device", model = "sdk_gphone64 (demo)", avd = "DemoAVD", booted = false)
        assertContains(demoDeviceLine(booting), "DemoAVD")
        assertContains(demoDeviceLine(booting), "booting (not claimable yet)")
    }
}
