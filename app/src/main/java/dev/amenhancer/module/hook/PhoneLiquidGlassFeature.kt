package dev.amenhancer.module.hook

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomnavigation.BottomNavigationView
import eightbitlab.com.blurview.BlurView
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

private const val GLASS_BLUR_RADIUS = 10f
private const val GLASS_CORNER_RADIUS_DP = 28
private const val NAV_GLASS_HEIGHT_DP = 60
private const val NAV_SELECTION_HEIGHT_DP = 56
private const val NAV_SELECTION_MOTION_DURATION_MS = 480L

@Suppress("DEPRECATION")
internal class PhoneLiquidGlassStyler(context: Context) {

    private val selectionIndicator = LiquidSelectionIndicator(context)
    private var selectionAnimator: Animator? = null
    private var currentSelectionViewRef: WeakReference<View>? = null

    fun installBottomNavigation(nav: BottomNavigationView) {
        nav.setBackgroundColor(Color.TRANSPARENT)
        nav.elevation = 0f

        val surface = GlassSurface(context)

        // Android 12+: 使用 RenderEffect 原生模糊
        // Android 8-11: 继续使用 BlurView
        val glassView = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            View(context).apply {
                background = glassSurfaceDrawable(context, surface)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(
                            0, 0, view.width, view.height,
                            dp(context, GLASS_CORNER_RADIUS_DP).toFloat(),
                        )
                    }
                }
                clipToOutline = true
                elevation = dp(context, 16).toFloat()
            }
        } else {
            BlurView(context).apply {
                background = glassSurfaceDrawable(context, surface)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(
                            0, 0, view.width, view.height,
                            dp(context, GLASS_CORNER_RADIUS_DP).toFloat(),
                        )
                    }
                }
                clipToOutline = true
                elevation = dp(context, 16).toFloat()
            }
        }

        nav.addView(glassView, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, NAV_GLASS_HEIGHT_DP),
        ))

        // Android 12+: 设置 RenderEffect 模糊
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            glassView.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    GLASS_BLUR_RADIUS, GLASS_BLUR_RADIUS,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            )
        }

        // Remove divider
        nav.children.find {
            it.javaClass.simpleName == "NavigationBarItemView" ||
                it.javaClass.simpleName.contains("Divider")
        }?.let { divider ->
            (divider.parent as? ViewGroup)?.removeView(divider)
        }

        // Selection indicator
        nav.addView(selectionIndicator, FrameLayout.LayoutParams(
            dp(context, 64), dp(context, NAV_SELECTION_HEIGHT_DP), Gravity.CENTER,
        ))

        // Track selection
        nav.post {
            updateSelection(nav)
            nav.setOnItemSelectedListener { _ ->
                updateSelection(nav)
                true
            }
        }
    }

    fun installMiniPlayer(player: FrameLayout) {
        player.setBackgroundColor(Color.TRANSPARENT)
        player.elevation = 0f

        val surface = GlassSurface(context)

        // Android 12+: 使用 RenderEffect 原生模糊
        // Android 8-11: 继续使用 BlurView
        val glassView = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            View(context).apply {
                background = glassSurfaceDrawable(context, surface)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(
                            0, 0, view.width, view.height,
                            dp(context, GLASS_CORNER_RADIUS_DP).toFloat(),
                        )
                    }
                }
                clipToOutline = true
                elevation = dp(context, 16).toFloat()
            }
        } else {
            BlurView(context).apply {
                background = glassSurfaceDrawable(context, surface)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(
                            0, 0, view.width, view.height,
                            dp(context, GLASS_CORNER_RADIUS_DP).toFloat(),
                        )
                    }
                }
                clipToOutline = true
                elevation = dp(context, 16).toFloat()
            }
        }

        player.addView(glassView, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // Android 12+: 设置 RenderEffect 模糊
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            glassView.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    GLASS_BLUR_RADIUS, GLASS_BLUR_RADIUS,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            )
        }

        // Adjust content padding
        player.children.filter { it !== glassView }.forEach { child ->
            child.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = dp(context, 8)
                bottomMargin = dp(context, 8)
            }
        }
    }

    private fun updateSelection(nav: BottomNavigationView) {
        val selectedView = nav.children.filter {
            it.javaClass.simpleName == "NavigationBarItemView"
        }.find { it.isSelected } ?: return

        if (currentSelectionViewRef?.get() == selectedView) return
        currentSelectionViewRef = WeakReference(selectedView)

        selectionAnimator?.cancel()

        val targetX = selectedView.x + selectedView.width / 2f - selectionIndicator.width / 2f
        val currentX = selectionIndicator.translationX

        selectionAnimator = ValueAnimator.ofFloat(currentX, targetX).apply {
            duration = NAV_SELECTION_MOTION_DURATION_MS
            interpolator = ElasticOutInterpolator()
            addUpdateListener {
                selectionIndicator.translationX = it.animatedValue as Float
            }
            doOnEnd {
                selectionIndicator.animate()
                    .scaleX(1.05f)
                    .scaleY(1.05f)
                    .setDuration(150)
                    .withEndAction {
                        selectionIndicator.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(150)
                            .start()
                    }
                    .start()
            }
            start()
        }
    }
}

private fun glassSurfaceDrawable(context: Context, surface: GlassSurface): Drawable {
    val border = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, GLASS_CORNER_RADIUS_DP).toFloat()
        setStroke(dp(context, 1), Color.argb(64, 255, 255, 255))
    }

    val innerGlow = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, GLASS_CORNER_RADIUS_DP - 2).toFloat()
        setColor(Color.argb(32, 255, 255, 255))
    }

    // Enhanced highlight with more rainbow colors (AndroidLiquidGlass style)
    val sweep = SweepGradient(
        0f, 0f,
        intArrayOf(
            Color.TRANSPARENT,
            Color.argb(100, 255, 100, 100),   // Red
            Color.argb(100, 255, 255, 100),   // Yellow
            Color.argb(100, 100, 255, 100),   // Green
            Color.argb(100, 100, 255, 255),   // Cyan
            Color.argb(100, 100, 100, 255),   // Blue
            Color.argb(100, 255, 100, 255),   // Purple
            Color.TRANSPARENT,
        ),
        floatArrayOf(0f, 0.14f, 0.28f, 0.42f, 0.56f, 0.70f, 0.84f, 1f),
    )

    val highlight = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, GLASS_CORNER_RADIUS_DP).toFloat()
    }
    highlight.setGradient(sweep)

    return LayerDrawable(arrayOf(border, innerGlow, highlight)).apply {
        setLayerInset(1, dp(context, 1), dp(context, 1), dp(context, 1), dp(context, 1))
        setLayerInset(2, dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
    }
}

private class LiquidSelectionIndicator(context: Context) : View(context) {
    init {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 20).toFloat()
            setColor(Color.argb(48, 255, 255, 255))
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(context, 20).toFloat())
            }
        }
        clipToOutline = true
    }
}

private class GlassSurface(context: Context)

private fun dp(context: Context, dp: Int): Int =
    (dp * context.resources.displayMetrics.density).roundToInt()

private class ElasticOutInterpolator : android.view.animation.Interpolator {
    override fun getInterpolation(t: Float): Float {
        val p = 0.3f
        return (Math.pow(2.0, -10 * t.toDouble()) * Math.sin((t - p / 4) * (2 * Math.PI) / p) + 1).toFloat()
    }
}
