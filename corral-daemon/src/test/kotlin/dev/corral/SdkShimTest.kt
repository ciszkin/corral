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

    companion object { const val MARKER_CONTENT = "corral adb wrapper" }
}