package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the right-only landscape resource overlay extracted from the modified
 * Apple Music 6.5.0 APK. The device test verifies geometry; this source test
 * makes the intended mutation boundary explicit without needing target APK
 * classes on the JVM classpath.
 */
class RightLyricsPaneStructuralRegressionTest {
    private val source: String by lazy {
        sequenceOf(
            File("src/main/java/dev/amenhancer/module/hook/DualPaneFeature.kt"),
            File("app/src/main/java/dev/amenhancer/module/hook/DualPaneFeature.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("DualPaneFeature.kt was not found from the unit-test working directory")
    }

    @Test
    fun `mirrors the modified right lyrics sheet resource at its inflation boundary`() {
        assertTrue(source.contains("\"fragment_player_lyrics_sheet\""))
        assertTrue(source.contains("RightLyricsPaneLayout::apply"))
        assertTrue(source.contains("\"current_player_item\""))
        assertTrue(source.contains("\"recycler_view_gradients\""))
        assertTrue(source.contains("\"controls\""))
        assertTrue(source.contains("\"controls_tap_target\""))
        assertTrue(source.contains("rootParams.topMargin = 0"))
        assertTrue(source.contains("anchorTopToParent"))
        assertTrue(source.contains("clearGradientEdges"))
    }

    @Test
    fun `limits the resource overlay to the official tablet landscape predicate`() {
        assertTrue(source.contains("TabletModeQualifier.isEligible(root.context)"))
        assertTrue(source.contains("right lyrics pane landscape resource installed"))
    }
}
