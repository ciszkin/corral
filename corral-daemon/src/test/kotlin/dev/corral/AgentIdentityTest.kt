package dev.corral

import java.lang.ProcessHandle
import kotlin.test.*

class AgentIdentityTest {
    // parentOf: pid -> parent pid; infoOf: pid -> (comm, start) or null when unreadable
    @Test fun picksFirstNonShellAncestor() {
        val parent = mapOf(100L to 50L, 50L to 30L, 30L to 1L)
        val info = mapOf(50L to ("bash" to 1L), 30L to ("claude" to 2L))
        assertEquals(Triple("claude", 30L, 2L), pickAgentAncestor(100, parent::get, info::get))
    }

    @Test fun windowsShellIsSkipped() {
        val parent = mapOf(100L to 50L, 50L to 30L)
        val info = mapOf(50L to ("cmd.exe" to 1L), 30L to ("claude.exe" to 2L))
        assertEquals(Triple("claude.exe", 30L, 2L), pickAgentAncestor(100, parent::get, info::get))
    }

    @Test fun skipListsMatchCaseInsensitivelyButIdentityKeepsCase() {
        // Windows preserves on-disk case: a renamed CMD.EXE is still a shell, while the
        // identity string carries the comm verbatim
        val parent = mapOf(100L to 50L, 50L to 30L)
        val info = mapOf(50L to ("CMD.EXE" to 1L), 30L to ("Agent.exe" to 2L))
        assertEquals(Triple("Agent.exe", 30L, 2L), pickAgentAncestor(100, parent::get, info::get))
    }

    @Test fun stopsAtInitFloor() {
        val parent = mapOf(100L to 30L, 30L to 1L)
        assertNull(pickAgentAncestor(100, parent::get, { "systemd" to 5L }))
    }

    @Test fun windowsRootIsFloor() {
        val parent = mapOf(100L to 30L)
        assertNull(pickAgentAncestor(100, parent::get, { "services.exe" to 3L }))
    }

    @Test fun cyclicChainStopsAtTheDepthCap() =
        // a self-parenting fake would spin forever without the cap; null -> user:$USER
        assertNull(pickAgentAncestor(100, { 50L }, { "bash" to 1L }))

    @Test fun unreadableChainIsNull() =
        assertNull(pickAgentAncestor(100, { 50L }, { null }))

    @Test fun noParentIsNull() =
        assertNull(pickAgentAncestor(100, { null }, { null }))

    @Test fun listsAreInjectable() {
        val parent = mapOf(100L to 50L, 50L to 30L)
        val info = mapOf(50L to ("mywrapper" to 1L), 30L to ("agent" to 2L))
        // custom skip list steps over mywrapper; empty floor still stops at pid 1 by the loop guard
        assertEquals(Triple("agent", 30L, 2L),
            pickAgentAncestor(100, parent::get, info::get, shellSkip = setOf("mywrapper")))
        assertEquals(Triple("mywrapper", 50L, 1L),
            pickAgentAncestor(100, parent::get, info::get, shellSkip = emptySet()))
    }

    @Test fun commOfTakesBasenameAcrossPlatforms() {
        assertEquals("claude.exe", commOf("""C:\Users\v\bin\claude.exe"""))
        assertEquals("claude", commOf("/usr/bin/claude"))
    }

    // --- Windows identity walk (row 32): the lists are written per program, the OS reports `.exe`.
    // Each of these fails on a verbatim-membership walk; see row32-identity-research.md §6.3.

    @Test fun windowsGitBashChainIsWalkedToTheAgent() {
        // the measured chain: agent <- bash.exe <- bash.exe <- the CLI's fork copy
        val parent = mapOf(100L to 101L, 101L to 102L, 102L to 103L, 103L to 104L)
        val info = mapOf(
            101L to ("bash.exe" to 1L),
            102L to ("bash.exe" to 2L),
            103L to ("opencode.exe" to 3L))
        assertEquals(Triple("opencode.exe", 103L, 3L),
            pickAgentAncestor(100, parent::get, info::get))
    }

    @Test fun bareEntriesSkipDotExeComms() {
        // the default/POSIX spelling must skip the Windows image name
        val parent = mapOf(100L to 50L, 50L to 30L)
        val info = mapOf(50L to ("bash.exe" to 1L), 30L to ("agent.exe" to 2L))
        assertEquals(Triple("agent.exe", 30L, 2L),
            pickAgentAncestor(100, parent::get, info::get, shellSkip = setOf("bash")))
    }

    @Test fun explicitDotExeEntriesAreNotDeadEntries() {
        // the mirror direction: a list (or a user's hand edit) that spells `cmd.exe` must still
        // skip a bare `cmd` — the shipped lists carry both spellings, so either must work
        val parent = mapOf(100L to 50L, 50L to 30L)
        val info = mapOf(50L to ("cmd" to 1L), 30L to ("agent" to 2L))
        assertEquals(Triple("agent", 30L, 2L),
            pickAgentAncestor(100, parent::get, info::get, shellSkip = setOf("cmd.exe")))
    }

    @Test fun dotExeFloorEntriesAreNotDeadEntries() {
        val parent = mapOf(100L to 30L)
        assertNull(pickAgentAncestor(100, parent::get, { "services" to 3L },
            initFloor = setOf("services.exe")))
        assertNull(pickAgentAncestor(100, parent::get, { "services.exe" to 3L },
            initFloor = setOf("services")))
    }

    @Test fun agentExeIsNotSkipped() {
        // the fix must not swallow every `.exe`: an agent image named `opencode.exe` is a principal
        val parent = mapOf(100L to 50L, 50L to 30L)
        val info = mapOf(50L to ("bash.exe" to 1L), 30L to ("opencode.exe" to 2L))
        assertEquals(Triple("opencode.exe", 30L, 2L),
            pickAgentAncestor(100, parent::get, info::get))
    }

    @Test fun aListThatDeliberatelyOmitsBashIsStillHonoured() {
        // the safety direction: normalising must not hard-code bash — a user who removes `bash`
        // from shell_skip gets the old (stopping) behaviour, verbatim
        val parent = mapOf(100L to 50L, 50L to 30L)
        val info = mapOf(50L to ("bash.exe" to 1L), 30L to ("agent.exe" to 2L))
        assertEquals(Triple("bash.exe", 50L, 1L),
            pickAgentAncestor(100, parent::get, info::get, shellSkip = DEFAULT_SHELL_SKIP - "bash"))
    }

    @Test fun emptySkipReturnsFirstAncestor() =
        assertEquals(Triple("bash", 50L, 1L),
            pickAgentAncestor(100, { 50L }, { "bash" to 1L }, shellSkip = emptySet()))

    @Test fun commKeyStripsOneDotExeAndLowercases() {
        assertEquals("bash", commKey("bash.exe"))
        assertEquals("bash", commKey("BASH.EXE"))
        assertEquals("my.sh", commKey("my.sh"))          // only `.exe` is special
        assertEquals("", commKey(".exe"))
        assertEquals("claude", commKey("claude"))
    }

    @Test fun processInfoOfReadsCurrentJvm() {
        // exercises the ProcessHandle path directly (not /proc): command basename + a real start
        val info = processInfoOf(ProcessHandle.current().pid())
        assertNotNull(info, "ProcessHandle.info() should describe the current JVM")
        assertTrue(info.first.contains("java"), "comm was ${info.first}")
        assertTrue(info.second > 0, "start was ${info.second}")
    }
}
