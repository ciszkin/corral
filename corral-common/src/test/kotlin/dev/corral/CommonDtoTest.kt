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
