package dev.corral

import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesFileTest {
    private val fixture = "# c\nreadonly=devices, help\nvalueflags=-P # inline\n"

    @Test fun parsesKeysAndStripsComments() {
        val r = RulesFile.parse(fixture)
        assertEquals(setOf("devices", "help"), r.readonly)
        assertEquals(setOf("-P"), r.valueFlags)
    }

    @Test fun missingKeysAreEmpty() = assertTrue(RulesFile.parse("readonly=devices").valueFlags.isEmpty())

    @Test fun healWritesDefaultWhenAbsent() {
        val path = createTempDirectory("rules").resolve("rules")
        val status = RulesFile.ensure(path)
        assertTrue(status!!.contains("restored"))
        assertTrue(path.readText().contains("readonly="))
    }

    @Test fun healLeavesUserFileAlone() {
        val path = createTempDirectory("rules").resolve("rules")
        Files.writeString(path, "readonly=mine\n")
        assertNull(RulesFile.ensure(path))
        assertEquals("readonly=mine\n", path.readText())
    }

    @Test fun loadOfMissingPathIsEmpty() =
        assertTrue(RulesFile.load(createTempDirectory("rules").resolve("nope")).readonly.isEmpty())

    @Test fun healRewritesWhitespaceOnlyFile() {
        val path = createTempDirectory("rules").resolve("rules")
        Files.writeString(path, "  \n\t\n")
        val status = RulesFile.ensure(path)
        assertTrue(status!!.contains("restored"))
        assertTrue(path.readText().contains("readonly="))
    }

    @Test fun healCreatesParentDirectories() {
        val path = createTempDirectory("rules").resolve("a/b/rules")
        assertTrue(RulesFile.ensure(path)!!.contains("restored"))
        assertTrue(path.readText().contains("readonly="))
    }

    @Test fun partialFileLeavesMissingKeyEmpty() =
        assertTrue(RulesFile.parse("valueflags=-P\n").readonly.isEmpty())

    @Test fun unreadablePathIsNotClobbered() {
        val path = createTempDirectory("rules").resolve("rules")
        Files.createDirectory(path)
        val status = RulesFile.ensure(path)
        assertTrue(status!!.contains("unreadable"))
        assertTrue(Files.isDirectory(path))
    }

    @Test fun identityKeysParseAndFallBack() {
        val r = RulesFile.parse("shell_skip=bash, cmd.exe\ninit_floor=systemd\n")
        assertEquals(setOf("bash", "cmd.exe"), r.shellSkip)
        assertEquals(setOf("systemd"), r.initFloor)
        // absent -> defaults; blank -> defaults (empty identity lists degrade attribution, see plan)
        assertEquals(DEFAULT_SHELL_SKIP, RulesFile.parse("readonly=devices\n").shellSkip)
        assertEquals(DEFAULT_INIT_FLOOR, RulesFile.parse("shell_skip=bash\ninit_floor=\n").initFloor)
    }

    @Test fun identityEntriesAreLowercasedOnParse() {
        // entries match the lowercased comm, so an uppercase entry must not be silently dead
        val r = RulesFile.parse("shell_skip=CMD.EXE, Bash\ninit_floor=SERVICES.EXE\n")
        assertEquals(setOf("cmd.exe", "bash"), r.shellSkip)
        assertEquals(setOf("services.exe"), r.initFloor)
    }

    @Test fun writeUpsertsKeysAndPreservesCommentsAndUnknowns() {
        val p = createTempDirectory("rules").resolve("rules")
        Files.writeString(p, "# header warning\nreadonly=devices\nMY_KEY=x\n")
        RulesFile.write(GateRules(setOf("devices", "help"), setOf("-P"), setOf("bash"), setOf("systemd")), p)
        val text = Files.readString(p)
        assertTrue("# header warning" in text && "MY_KEY=x" in text)
        assertTrue(Regex("readonly=devices,help|readonly=help,devices").containsMatchIn(text))
        val r = RulesFile.load(p)
        assertEquals(setOf("devices", "help"), r.readonly)
        assertEquals(setOf("-P"), r.valueFlags)
        assertEquals(setOf("bash"), r.shellSkip)
        assertEquals(setOf("systemd"), r.initFloor)
    }

    @Test fun writeGatePreservesIdentityLinesVerbatim() {
        val p = createTempDirectory("rules").resolve("rules")
        Files.writeString(p, "# header\nreadonly=devices\nvalueflags=-P\nshell_skip=python3,claude\ninit_floor=myinit\nUNKNOWN=x\n")
        RulesFile.writeGate(setOf("devices", "help"), setOf("-P", "-H"), p)
        val text = Files.readString(p)
        assertTrue("shell_skip=python3,claude" in text)
        assertTrue("init_floor=myinit" in text)
        assertTrue("UNKNOWN=x" in text)
        assertTrue(Regex("readonly=devices,help|readonly=help,devices").containsMatchIn(text))
        assertTrue(Regex("valueflags=-P,-H|valueflags=-H,-P").containsMatchIn(text))
        val r = RulesFile.load(p)
        assertEquals(setOf("devices", "help"), r.readonly)
        assertEquals(setOf("-P", "-H"), r.valueFlags)
        assertEquals(setOf("python3", "claude"), r.shellSkip)
        assertEquals(setOf("myinit"), r.initFloor)
    }

    @Test fun writeRoundTripsThroughParse() {
        val p = createTempDirectory("rules").resolve("rules")
        val r = GateRules(setOf("devices"), setOf("-H", "-P"), setOf("zsh"), setOf("init", "systemd"))
        RulesFile.write(r, p)
        assertEquals(r, RulesFile.load(p))
    }

    @Test fun writeLowercasesIdentityKeysButNotGateKeys() {
        val p = createTempDirectory("rules").resolve("rules")
        RulesFile.write(GateRules(setOf("Devices"), setOf("-P"), setOf("BASH"), setOf("SYSTEMD")), p)
        val text = Files.readString(p)
        assertTrue("readonly=Devices" in text)          // gate key value stays verbatim
        assertTrue("valueflags=-P" in text)
        assertTrue("shell_skip=bash" in text)           // identity entries lowercased
        assertTrue("init_floor=systemd" in text)
        val r = RulesFile.load(p)
        assertEquals(setOf("Devices"), r.readonly)
        assertEquals(setOf("-P"), r.valueFlags)
        assertEquals(setOf("bash"), r.shellSkip)
        assertEquals(setOf("systemd"), r.initFloor)
    }

    @Test fun writeTreatsSpacedKeysAsTheSameKey() {
        val p = createTempDirectory("rules").resolve("rules")
        Files.writeString(p, "readonly = devices\nNOT_A_PAIR\n")
        RulesFile.write(GateRules(setOf("devices", "help"), emptySet(), setOf("bash"), setOf("systemd")), p)
        val text = Files.readString(p)
        assertEquals(1, text.lines().count {
            it.substringBefore('#').substringBefore('=').trim() == "readonly" })
        assertTrue("NOT_A_PAIR" in text)
    }

    @Test fun writeFailsInsteadOfClobberingAnUnreadableTarget() {
        val p = createTempDirectory("rules").resolve("rules")
        Files.createDirectory(p)                 // present but not a readable file
        assertFailsWith<IOException> { RulesFile.write(GateRules(setOf("devices"), setOf("-P")), p) }
    }
}
