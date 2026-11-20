package dev.corral

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommonDtoTest {
    @Test fun dtosRoundTripThroughCorralJson() {
        val d = Device("emulator-5554", "device", model = "Pixel_6", osVersion = "13", avd = "Pixel6AVD")
        assertEquals(d, corralJson.decodeFromString(Device.serializer(), corralJson.encodeToString(Device.serializer(), d)))
        val l = Lease("dev1", "proc:claude:1:2", 3, expiresAt = 99)
        assertEquals(l, corralJson.decodeFromString(Lease.serializer(), corralJson.encodeToString(Lease.serializer(), l)))
        val q = QueueEntry(1, "w", "[any]", 5, 600)
        assertEquals(q, corralJson.decodeFromString(QueueEntry.serializer(), corralJson.encodeToString(QueueEntry.serializer(), q)))
        val f = EventFrame("state.changed", listOf("devices"), 7)
        assertEquals(f, corralJson.decodeFromString(EventFrame.serializer(), corralJson.encodeToString(EventFrame.serializer(), f)))
    }

    @Test fun updateInfoRoundTripsAndStateDefaults() {
        val u = UpdateInfo("1.0.0", UpdateState.UPDATE_AVAILABLE, "2.0.0",
            releaseUrl = "https://example/2.0.0")
        assertEquals(u, corralJson.decodeFromString(UpdateInfo.serializer(), corralJson.encodeToString(UpdateInfo.serializer(), u)))
        assertTrue(u.updateAvailable)
        assertTrue(u.checksEnabled)

        val unknown = corralJson.decodeFromString(UpdateInfo.serializer(), """{"currentVersion":"1.0.0"}""")
        assertEquals(UpdateState.UNKNOWN, unknown.state)
        assertNull(unknown.latestVersion)
        assertNull(unknown.reason)          // an older daemon's payload carries no reason, and must not blow up
        assertFalse(unknown.updateAvailable)
        assertTrue(unknown.checksEnabled)

        // P3 3: an UPDATE_AVAILABLE payload with no releaseUrl would render "(null)" — the wire refuses it
        val truncated = assertFails {
            corralJson.decodeFromString(UpdateInfo.serializer(),
                """{"currentVersion":"1.0.0","state":"UPDATE_AVAILABLE","latestVersion":"2.0.0"}""")
        }
        assertTrue(truncated is IllegalArgumentException || truncated is SerializationException, "was $truncated")

        // a wire payload whose fields disagree with its state is refused by the type, not trusted
        val thrown = assertFails {
            corralJson.decodeFromString(UpdateInfo.serializer(),
                """{"currentVersion":"1.0.0","state":"UNKNOWN","latestVersion":"x"}""")
        }
        assertTrue(thrown is IllegalArgumentException || thrown is SerializationException, "was $thrown")
    }

    @Test fun updateReasonRoundTripsAdditively() {
        val u = UpdateInfo("1.0.0", UpdateState.UNKNOWN, reason = UpdateReason.HTTP_ERROR, httpStatus = 404)
        assertEquals(u, corralJson.decodeFromString(UpdateInfo.serializer(), corralJson.encodeToString(UpdateInfo.serializer(), u)))
    }

    /**
     * D9 — the demo label is additive and defaulted in **both** directions: a payload from a daemon
     * that predates the field decodes as a real one (never a crash, never a false DEMO), a payload
     * that carries it decodes as a demo one, and the label is only on the wire when it is true — so
     * a real daemon's JSON stays byte-identical to the pre-demo shape.
     */
    @Test fun demoLabelsDecodeFromLegacyPayloadsAndAreOnlyEmittedWhenSet() {
        val oldDevice = corralJson.decodeFromString(Device.serializer(), """{"serial":"d1","state":"device"}""")
        assertFalse(oldDevice.demo, "a payload with no demo field is a real device")
        val demoDevice = corralJson.decodeFromString(Device.serializer(),
            """{"serial":"d1","state":"device","demo":true}""")
        assertTrue(demoDevice.demo)
        assertTrue(corralJson.encodeToString(Device.serializer(), demoDevice).contains("\"demo\":true"))
        assertFalse(corralJson.encodeToString(Device.serializer(), oldDevice).contains("\"demo\""),
            "a real device must not gain a demo key on the wire")

        val oldStatus = corralJson.decodeFromString(StatusResponse.serializer(), """{"devices":[],"adb":"adb"}""")
        assertFalse(oldStatus.demo)
        assertTrue(corralJson.decodeFromString(StatusResponse.serializer(),
            """{"devices":[],"adb":"adb","demo":true}""").demo)

        val oldHello = corralJson.decodeFromString(EventFrame.serializer(), """{"type":"hello","seq":0}""")
        assertFalse(oldHello.demo, "an old daemon's hello frame is a real pool")
        assertTrue(corralJson.decodeFromString(EventFrame.serializer(),
            """{"type":"hello","resources":["devices"],"seq":0,"demo":true}""").demo)
    }

    @Test fun jsonIgnoresUnknownKeys() {
        val d = corralJson.decodeFromString(Device.serializer(), """{"serial":"d1","state":"device","futureField":1}""")
        assertEquals("d1", d.serial)
    }

    @Test fun commonHasNoJvmImports() {
        // KMP-readiness contract (spec): grep the main source set for java.* imports
        val src = Path.of("src/main/kotlin").let { p ->
            Files.walk(p).filter { it.toString().endsWith(".kt") }
                .flatMap { Files.readAllLines(it).stream() }.toList()
        }
        assertTrue(src.none { it.trimStart().startsWith("import java.") }, "corral-common must stay JVM-free")
    }
}
