package dev.amenhancer.module.hook

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the blur implementation to a direct port of a23bc/amlyricblur. */
class FutureLyricBlurStructuralRegressionTest {
    private val featureSource: String by lazy {
        sourceFile("FutureLyricBlurFeature.kt").readText()
    }
    private val portSource: String by lazy {
        sequenceOf(
            File("src/main/java/dev/amenhancer/module/hook/OpenSourceLyricBlurPort.kt"),
            File("app/src/main/java/dev/amenhancer/module/hook/OpenSourceLyricBlurPort.kt"),
        ).firstOrNull(File::isFile)?.readText().orEmpty()
    }
    private val typographySource: String by lazy {
        sequenceOf(
            File("src/main/java/dev/amenhancer/module/hook/TabletLyricTypography.kt"),
            File("app/src/main/java/dev/amenhancer/module/hook/TabletLyricTypography.kt"),
            sourceFile("FutureLyricBlurFeature.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Tablet lyric typography source was not found")
    }
    private val dualPaneSource: String by lazy {
        sourceFile("DualPaneFeature.kt").readText()
    }

    @Test
    fun `feature is only the module adapter for the upstream port`() {
        assertTrue(featureSource.contains("OpenSourceLyricBlurPort"))
        assertTrue(
            featureSource.contains(
                "install(context.classLoader, context.application.applicationInfo.sourceDir)",
            ),
        )
        assertFalse(featureSource.contains("FutureBlurController"))
        assertFalse(featureSource.contains("FutureLyricBlurTouchStateMachine"))
        assertFalse(featureSource.contains("FutureLyricBlurRowPolicy"))
        assertFalse(featureSource.contains("USER_SCROLL_PAUSE_MS"))
    }

    @Test
    fun `port keeps the upstream singleton state and blur constants`() {
        assertTrue(portSource.contains("private const val TAG = \"AMLyricBlur\""))
        assertTrue(portSource.contains("private const val BLUR_BASE = 12f"))
        assertTrue(portSource.contains("private const val BLUR_STEP = 4f"))
        assertTrue(portSource.contains("private const val BLUR_MAX = 20f"))
        assertTrue(portSource.contains("private val highlightedLineIds = mutableSetOf<Int>()"))
        assertTrue(portSource.contains("private var previousHighlightIds = setOf<Int>()"))
        assertTrue(portSource.contains("private val viewBlurValues = WeakHashMap<View, Float>()"))
        assertTrue(portSource.contains("private val viewAnimators = WeakHashMap<View, ValueAnimator>()"))
        assertTrue(portSource.contains("private var recyclerView: Any? = null"))
        assertTrue(portSource.contains("private var lyricsRootView: View? = null"))
        assertTrue(portSource.contains("private var isUserScrolling = false"))
    }

    @Test
    fun `port keeps the complete upstream private method inventory`() {
        val expected = setOf(
            "initReflectionCache",
            "hookHighlightCallback",
            "installHighlightHook",
            "hookLyricsFragment",
            "hookViewModel",
            "findRecyclerView",
            "findRVInHierarchy",
            "scheduleBlurUpdate",
            "attachScrollListener",
            "onScrollDetected",
            "clearAllBlur",
            "getRv",
            "applyBlur",
            "isLyricsLine",
            "hasDescendantOfType",
            "getAdapterPosition",
            "animateBlur",
        )
        val actual = Regex("""private fun (\w+)\(""")
            .findAll(portSource)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(expected, actual)
    }

    @Test
    fun `scroll clearing matches upstream without a recovery timer`() {
        assertTrue(portSource.contains("view.setOnTouchListener"))
        assertTrue(portSource.contains("event.action != MotionEvent.ACTION_CANCEL"))
        assertTrue(portSource.contains("event.action != MotionEvent.ACTION_UP"))
        assertTrue(portSource.contains("addOnScrollChangedListener { onScrollDetected() }"))
        assertTrue(portSource.contains("if (isUserScrolling) clearAllBlur()"))
        assertFalse(portSource.contains("postDelayed(resume"))
        assertFalse(portSource.contains("USER_SCROLL_PAUSE_MS"))
        assertFalse(portSource.contains("touch-end-schedule"))
    }

    @Test
    fun `blur calculation and animation match upstream`() {
        assertTrue(portSource.contains("highlightedLineIds + previousHighlightIds"))
        assertTrue(portSource.contains("val minDist = effectiveIds.minOf"))
        assertTrue(
            portSource.contains(
                "(BLUR_BASE + (minDist - 1) * BLUR_STEP).coerceAtMost(BLUR_MAX)",
            ),
        )
        assertTrue(portSource.contains("ValueAnimator.ofFloat(current, targetBlur)"))
        assertTrue(portSource.contains("duration = 300"))
        assertTrue(portSource.contains("Shader.TileMode.MIRROR"))
        assertTrue(portSource.contains("hasDescendantOfType(view, ImageView::class.java)"))
    }

    @Test
    fun `native vector scanner is installed as documented upstream`() {
        assertEquals(2, Regex("""hookHighlightCallback\(""").findAll(portSource).count())
        assertTrue(portSource.contains("hookHighlightCallback(classLoader)"))
        assertTrue(portSource.contains("LyricsLineVector"))
        assertTrue(portSource.contains("getLineId"))
    }

    @Test
    fun `tablet lyric typography remains independent of blur`() {
        assertTrue(typographySource.contains("TABLET_LANDSCAPE_LYRICS_TEXT_SIZE_SP = 35f"))
        assertTrue(typographySource.contains("\"song_lyrics_line\""))
        assertTrue(typographySource.contains("\"song_lyrics_word\""))
        assertTrue(typographySource.contains("TabletModeQualifier.isEligible"))
        assertTrue(dualPaneSource.contains("TabletLyricTypography::applyToInflatedLayout"))
        assertTrue(
            dualPaneSource.contains("TabletLyricTypography::attach") ||
                dualPaneSource.contains("TabletLyricTypography.attach"),
        )
    }

    private fun sourceFile(name: String): File = sequenceOf(
        File("src/main/java/dev/amenhancer/module/hook/$name"),
        File("app/src/main/java/dev/amenhancer/module/hook/$name"),
    ).firstOrNull(File::isFile) ?: error("$name was not found from the unit-test working directory")
}
