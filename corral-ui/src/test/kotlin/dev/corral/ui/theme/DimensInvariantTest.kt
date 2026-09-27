package dev.corral.ui.theme

import java.nio.file.Path
import kotlin.io.extension
import kotlin.io.readText
import kotlin.io.walkTopDown
import kotlin.test.Test
import kotlin.test.assertTrue

class DimensInvariantTest {

    @Test fun hardcodedDimensionsStayInDimens() {
        val sourceRoot = Path.of("src/main/kotlin/dev/corral/ui")
        val literal = Regex("""\d+\.(dp|sp)""")
        val allowed = setOf("theme/Dimens.kt", "icons/Lucide.kt")
        val offenders = sourceRoot.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { literal.containsMatchIn(it.readText()) }
            .map { sourceRoot.relativize(it.toPath()).toString() }
            .filter { it !in allowed }
            .toList()
        assertTrue(offenders.isEmpty(), "hardcoded dimensions outside Dimens: $offenders")
    }
}
