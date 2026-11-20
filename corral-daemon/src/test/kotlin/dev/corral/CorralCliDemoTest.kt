package dev.corral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The CLI's own half of D9/I7: a visitor reading `corral devices|status|queue|check|doctor` must be
 * told the pool is a simulation, and `corral update` must say there is nothing to update. The rest
 * of the surface (the daemon's JSON, the console) is pinned by `DemoApiTest` and the UI suite.
 */
class CorralCliDemoTest {
    @Test fun theLabelSaysDemoAndSaysWhatThatMeans() {
        val line = demoCliLine()
        assertContains(line, "DEMO")
        assertContains(line, "simulated pool")
        assertContains(line, "no real device")
        assertContains(line, "no adb")
        assertContains(line, "no gates")
        assertFalse(line.contains("for real"), "the label must not read as an install hint: $line")
    }

    @Test fun theUpdateRefusalIsTheSameSentenceTheSuiteAsserts() {
        assertEquals("demo mode: updates are not applicable", demoUpdateRefusal())
    }

    @Test fun theDemoFlagIsReadFromStatusAndDefaultsToARealPool() {
        // absent / unparseable is a real daemon: a CLI must never invent a DEMO label
        assertFalse(corralJson.decodeFromString<StatusResponse>("""{"devices":[],"adb":"adb"}""").demo)
        assertTrue(corralJson.decodeFromString<StatusResponse>(
            """{"devices":[],"adb":"adb","demo":true}""").demo)
    }
}

private fun assertContains(text: String, part: String) =
    assertTrue(text.contains(part), "expected '$part' in: $text")
