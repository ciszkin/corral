package dev.corral

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SdkShimTest {
    private val wrapper = "#!/usr/bin/env bash\n# corral adb wrapper: enforces leasing at exec time.\n"
    private lateinit var dir: Path
    private val realAdb get() = dir.resolve("platform-tools/adb.real")
    private val shim get() = dir.resolve("platform-tools/adb")
    private val wrapperSource get() = dir.resolve("wrapper-source")

    @BeforeTest
    fun setup() {
        dir = createTempDirectory("sdkshim")
        Files.createDirectories(shim.parent)
        Files.writeString(wrapperSource, wrapper)
    }

    private fun writeShim(content: String) { Files.writeString(shim, content) }

    /** Where [SdkShim] parks an `adb.real` it is about to replace. */
    private val stash get() = dir.resolve("platform-tools/adb.real.corral-backup")

    /** The planted foreign bytes, searched for under every name the repair might have moved them to. */
    private fun assertPreserved(planted: String, vararg candidates: Path) {
        val survivors = candidates.filter { Files.exists(it) }.map { it.readText() }
        assertTrue(planted in survivors, "planted adb.real bytes were destroyed; found instead: $survivors")
    }

    @Test
    fun `foreign adb real is stashed, not destroyed, when repairing via the shim path`() {
        val planted = "FOREIGN REAL BINARY - these bytes are the user's"
        writeShim("FOREIGN ADB BINARY")          // not our wrapper, no marker
        Files.writeString(realAdb, planted)
        val status = SdkShim.ensure(shim, wrapperSource)
        assertPreserved(planted, stash, realAdb)
        assertTrue(status!!.contains("repaired"), "status was: $status")
        assertTrue(status.contains(stash.toString()), "the summary must name the stash: $status")
        assertTrue(shim.readText().contains(MARKER_CONTENT))
        assertEquals("FOREIGN ADB BINARY", realAdb.readText())   // the newer binary is the one adopted
    }

    @Test
    fun `foreign adb real is stashed, not destroyed, when repairing via the real path`() {
        val planted = "FOREIGN REAL BINARY - these bytes are the user's"
        writeShim("FOREIGN ADB BINARY")
        Files.writeString(realAdb, planted)
        val status = SdkShim.ensure(realAdb, wrapperSource)
        assertPreserved(planted, stash, realAdb)
        assertTrue(status!!.contains("repaired"), "status was: $status")
        assertEquals("FOREIGN ADB BINARY", realAdb.readText())
    }

    @Test
    fun `a repair never overwrites an earlier stash`() {
        val planted = "FOREIGN REAL BINARY v2"
        writeShim("FOREIGN ADB BINARY")
        Files.writeString(realAdb, planted)
        Files.writeString(stash, "EARLIER STASH - must survive too")
        SdkShim.ensure(shim, wrapperSource)
        assertEquals("EARLIER STASH - must survive too", stash.readText())
        assertPreserved(planted, dir.resolve("platform-tools/adb.real.corral-backup.1"), realAdb)
    }

    @Test
    fun `a corral shim pair is left untouched and creates no stash`() {
        writeShim(wrapper)
        Files.writeString(realAdb, "REAL ADB v1")
        assertEquals(null, SdkShim.ensure(shim, wrapperSource))
        assertEquals(null, SdkShim.ensure(realAdb, wrapperSource))
        assertEquals(wrapper, shim.readText())
        assertEquals("REAL ADB v1", realAdb.readText())
        assertTrue(!Files.exists(stash), "a healthy pair must not be stashed")
    }

    @Test
    fun `repairing by the real path with no shim installs the wrapper and keeps the real binary`() {
        Files.writeString(realAdb, "REAL ADB v1")   // .real spelling, adb absent
        val status = SdkShim.ensure(realAdb, wrapperSource)
        assertTrue(status!!.contains("repaired"), "status was: $status")
        assertEquals("REAL ADB v1", realAdb.readText())
        assertTrue(shim.readText().contains(MARKER_CONTENT))
        assertTrue(!Files.exists(stash), "nothing was replaced, so nothing should be stashed")
    }

    @Test
    fun `first install stashes the real binary and installs the wrapper`() {
        writeShim("REAL ADB v1")
        val status = SdkShim.ensure(realAdb, wrapperSource)
        assertTrue(status!!.contains("repaired"))
        assertTrue(shim.readText().contains(MARKER_CONTENT))
        assertEquals("REAL ADB v1", realAdb.readText())
    }

    @Test
    fun `rerun is idempotent`() {
        writeShim("REAL ADB v1")
        SdkShim.ensure(realAdb, wrapperSource)
        val status = SdkShim.ensure(realAdb, wrapperSource)
        assertEquals(null, status)
        assertEquals("REAL ADB v1", realAdb.readText())
    }

    @Test
    fun `sdk update preserves the new binary`() {
        writeShim("REAL ADB v1")
        SdkShim.ensure(realAdb, wrapperSource)
        writeShim("REAL ADB v2") // the SDK just replaced the shim with a fresh binary
        val status = SdkShim.ensure(realAdb, wrapperSource)
        assertTrue(status!!.contains("repaired"))
        assertEquals("REAL ADB v2", realAdb.readText())
        assertTrue(shim.readText().contains(MARKER_CONTENT))
        assertPreserved("REAL ADB v1", stash)   // the superseded real binary is stashed, not destroyed
    }

    @Test
    fun `wrapper without adb real warns`() {
        Files.writeString(shim, wrapper)
        val status = SdkShim.ensure(realAdb, wrapperSource)
        assertTrue(status!!.contains("is missing"))
    }

    @Test
    fun `no wrapper source warns and leaves the file alone`() {
        writeShim("REAL ADB v3")
        val status = SdkShim.ensure(realAdb, wrapperSource = null)
        assertTrue(status!!.contains("not the corral wrapper"))
        assertEquals("REAL ADB v3", Files.readString(shim))
    }

    @Test
    fun `relative or non-real adb path is unmanaged`() {
        assertEquals(null, SdkShim.ensure(Path.of("adb"), wrapperSource))
        assertEquals(null, SdkShim.ensure(dir.resolve("platform-tools/adb"), wrapperSource))
    }

    @Test
    fun `ensure spelled as the shim repairs the SDK-updated binary`() {
        writeShim("REAL ADB v1")
        SdkShim.ensure(realAdb, wrapperSource)
        writeShim("REAL ADB v2") // the SDK just replaced the shim with a fresh binary
        val status = SdkShim.ensure(shim, wrapperSource)
        assertTrue(status!!.contains("repaired"))
        assertEquals("REAL ADB v2", realAdb.readText())
        assertTrue(shim.readText().contains(MARKER_CONTENT))
    }

    @Test
    fun `ensure leaves an unmanaged adb binary alone`() {
        writeShim("REAL ADB v1") // exists, no .real sibling, not the wrapper
        assertEquals(null, SdkShim.ensure(shim, wrapperSource))
        assertEquals("REAL ADB v1", shim.readText())
    }

    @Test
    fun probeOkWhenWrapperAndRealPresent() {
        writeShim(wrapper)
        Files.writeString(realAdb, "REAL ADB v1")
        realAdb.toFile().setExecutable(true)
        assertEquals("ok", SdkShim.probe(realAdb).first)
    }

    @Test
    fun `probe ok when given the shim path`() {
        writeShim(wrapper)
        Files.writeString(realAdb, "REAL ADB v1")
        realAdb.toFile().setExecutable(true)
        val (status, detail) = SdkShim.probe(shim)
        assertEquals("ok", status)
        assertEquals("$shim -> adb.real", detail)
    }

    @Test
    fun `probe fails when given the shim and adb real is missing`() {
        writeShim(wrapper)
        val (status, detail) = SdkShim.probe(shim)
        assertEquals("fail", status)
        assertTrue(detail.contains(realAdb.toString()), "detail must name the missing real binary: $detail")
    }

    @Test
    fun probeFailsWhenRealMissing() {
        writeShim(wrapper)
        val (status, detail) = SdkShim.probe(realAdb)
        assertEquals("fail", status)
        assertTrue(detail.contains(realAdb.toString()), "detail must name the missing real binary: $detail")
    }

    @Test
    fun probeFailsWhenRealNotExecutable() {
        writeShim(wrapper)
        Files.writeString(realAdb, "REAL ADB v1")
        realAdb.toFile().setExecutable(false)
        if (realAdb.toFile().canExecute()) return   // platform cannot express non-executable: nothing to assert
        val (status, detail) = SdkShim.probe(realAdb)
        assertEquals("fail", status)
        assertTrue(detail.contains(realAdb.toString()), "detail must name the non-executable binary: $detail")
    }

    @Test
    fun probeFailsWhenNotTheWrapper() {
        writeShim("REAL ADB v1")
        Files.writeString(realAdb, "REAL ADB v1")
        val (status, detail) = SdkShim.probe(realAdb)
        assertEquals("fail", status)
        assertTrue(detail.contains("not the corral wrapper"), "detail was: $detail")
    }

    @Test
    fun probeSkipsUnmanagedAdb() {
        val existing = dir.resolve("platform-tools/adb")
        existing.parent.toFile().mkdirs()
        existing.toFile().writeText("#!/bin/sh\n")          // unmanaged but present -> skipped
        val (status, detail) = SdkShim.probe(existing)
        assertEquals("skipped", status)
        assertTrue(detail.contains("unmanaged adb path"), "detail was: $detail")
    }

    @Test
    fun probeFailsOnAMissingAbsoluteUnmanagedAdb() {
        val (status, detail) = SdkShim.probe(dir.resolve("gone/adb"))   // configured, absent -> discovery broken
        assertEquals("fail", status)
        assertTrue(detail.contains("does not exist") && detail.contains("discovery"), "detail was: $detail")
    }

    @Test
    fun `shimPathOf maps a real path to its sibling shim and passes others through`() {
        assertEquals("/sdk/platform-tools/adb", shimPathOf("/sdk/platform-tools/adb.real"))
        assertEquals("/sdk/platform-tools/adb", shimPathOf("/sdk/platform-tools/adb"))
        assertEquals("""C:\sdk\platform-tools\adb""", shimPathOf("""C:\sdk\platform-tools\adb.real"""))
    }

    companion object { const val MARKER_CONTENT = "corral adb wrapper" }
}