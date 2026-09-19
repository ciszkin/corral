package dev.corral

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
