package dev.corral

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
