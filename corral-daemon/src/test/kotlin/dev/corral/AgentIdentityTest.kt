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

    @Test fun processInfoOfReadsCurrentJvm() {
        // exercises the ProcessHandle path directly (not /proc): command basename + a real start
        val info = processInfoOf(ProcessHandle.current().pid())
        assertNotNull(info, "ProcessHandle.info() should describe the current JVM")
        assertTrue(info.first.contains("java"), "comm was ${info.first}")
        assertTrue(info.second > 0, "start was ${info.second}")
    }
}
