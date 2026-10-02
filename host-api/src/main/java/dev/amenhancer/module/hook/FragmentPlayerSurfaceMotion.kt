package dev.amenhancer.module.hook

import kotlin.math.abs

data class FragmentSurfaceBounds(val left: Float, val top: Float, val width: Float, val height: Float)
data class FragmentMiniMaterial(val bounds: FragmentSurfaceBounds, val cornerExpansion: Float, val alpha: Float)

/** Native bounds remain the source of truth for compact and every wide mini variant. */
object FragmentPlayerSurfaceMotion {
    fun material(native: FragmentSurfaceBounds, sheetWidth: Float, sheetHeight: Float,
                 expansion: Float, nativeAlpha: Float): FragmentMiniMaterial = material(native,
        FragmentSurfaceBounds(0f, 0f, sheetWidth, sheetHeight), expansion, nativeAlpha)

    fun material(native: FragmentSurfaceBounds, player: FragmentSurfaceBounds,
                 expansion: Float, nativeAlpha: Float): FragmentMiniMaterial {
        if (listOf(native.left, native.top, native.width, native.height, player.left, player.top, player.width, player.height,
                expansion, nativeAlpha).any { !it.isFinite() } || native.width <= 0 || native.height <= 0)
            return FragmentMiniMaterial(native, 0f, 0f)
        val p = expansion.coerceIn(0f, 1f)
        val t = smooth(p)
        fun mix(a: Float, b: Float) = a + (b - a) * t
        return FragmentMiniMaterial(FragmentSurfaceBounds(mix(native.left, player.left), mix(native.top, player.top),
            mix(native.width, player.width), mix(native.height, player.height)), t,
            nativeAlpha.coerceIn(0f, 1f) * (1 - smooth((p - .85f) / .15f)))
    }
    private fun smooth(value: Float) = value.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
}

data class FragmentContentTransform(val scaleX: Float, val scaleY: Float, val translationX: Float, val translationY: Float)

/** Parent-content deformation composes with native values and never edits artwork/button children. */
class FragmentContentTransformOwner(
    private val read: () -> FragmentContentTransform,
    private val write: (FragmentContentTransform) -> Unit,
) : AutoCloseable {
    private var native = read()
    private var last: FragmentContentTransform? = null
    fun apply(scaleX: Float, scaleY: Float, translationX: Float, translationY: Float) {
        val current = read()
        val previous = last
        native = FragmentContentTransform(
            if (previous == null || current.scaleX != previous.scaleX) current.scaleX else native.scaleX,
            if (previous == null || current.scaleY != previous.scaleY) current.scaleY else native.scaleY,
            if (previous == null || current.translationX != previous.translationX) current.translationX else native.translationX,
            if (previous == null || current.translationY != previous.translationY) current.translationY else native.translationY)
        val next = FragmentContentTransform(native.scaleX * scaleX, native.scaleY * scaleY,
            native.translationX + translationX, native.translationY + translationY)
        last = next
        write(next)
    }
    override fun close() {
        val previous = last ?: return
        val current = read()
        write(FragmentContentTransform(
            if (current.scaleX == previous.scaleX) native.scaleX else current.scaleX,
            if (current.scaleY == previous.scaleY) native.scaleY else current.scaleY,
            if (current.translationX == previous.translationX) native.translationX else current.translationX,
            if (current.translationY == previous.translationY) native.translationY else current.translationY))
        last = null
    }
}

/** Vertical player dragging cancels press illumination but never claims the native gesture. */
class FragmentMiniPress(private val slop: Float) {
    private var downX = 0f
    private var downY = 0f
    var pointerId: Int? = null
        private set
    fun start(pointer: Int, x: Float, y: Float) {
        pointerId = pointer; downX = x; downY = y
    }
    fun move(x: Float, y: Float): Boolean {
        if (pointerId == null) return false
        val dx = abs(x - downX); val dy = abs(y - downY)
        if (!x.isFinite() || !y.isFinite() || (dy > slop && dy > dx)) { cancel(); return false }
        return true
    }
    fun pointerUp(pointer: Int): Boolean {
        if (pointerId != pointer) return false
        cancel(); return true
    }
    fun cancel() { pointerId = null }
}
