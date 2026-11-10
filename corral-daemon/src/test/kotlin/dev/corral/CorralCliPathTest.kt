package dev.corral

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorralCliPathTest {
    @Test fun percentEncodesSpaces() {
        assertEquals("Wide%20AVD", pathSegment("Wide AVD"))
    }

    @Test fun leavesSafeNamesAlone() {
        assertEquals("Pixel_6", pathSegment("Pixel_6"))
    }

    @Test fun encodesLiteralPlus() {
        assertEquals("a%2Bb", pathSegment("a+b"))
    }

    @Test fun encodesSlash() {
        assertEquals("a%2Fb", pathSegment("a/b"))
    }

    @Test fun deviceDecodeToleratesFieldsANewerDaemonAdds() {
        val devices = decodeDeviceList("""[{"serial":"emulator-5554","state":"offline","futureField":1}]""")
        assertEquals("emulator-5554", devices.single().serial)
    }

    @Test fun repoFlagAcceptsOnlyOwnerSlashName() {
        assertTrue(validRepo("ciszkin/corral"))
        assertFalse(validRepo("ciszkin"))
        assertFalse(validRepo("a/b/c"))
        assertFalse(validRepo("cisz kin/corral"))
        assertFalse(validRepo("ciszkin/"))
        assertFalse(validRepo("/corral"))
    }

    @Test fun repoFlagAcceptsSpaceAndEqualsFormsAndTheLastWins() {
        assertEquals(RepoFlag(false, null), parseRepoFlag(listOf("--apply")))
        assertEquals(RepoFlag(true, "a/b"), parseRepoFlag(listOf("--repo", "a/b")))
        assertEquals(RepoFlag(true, "a/b"), parseRepoFlag(listOf("--repo=a/b")))
        assertEquals(RepoFlag(true, "c/d"), parseRepoFlag(listOf("--repo", "a/b", "--repo=c/d")))
        assertEquals(RepoFlag(true, ""), parseRepoFlag(listOf("--repo=")))
        assertEquals(RepoFlag(true, null), parseRepoFlag(listOf("--repo")))
    }
}
