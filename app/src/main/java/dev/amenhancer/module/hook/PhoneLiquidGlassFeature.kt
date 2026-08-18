package com.example.amplusplus.feature.phone

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import com.example.amplusplus.util.dp
import com.example.amplusplus.util.findFieldByType
import com.example.amplusplus.util.getField
import com.example.amplusplus.util.hookAfter
import com.example.amplusplus.util.setField
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class PhoneLiquidGlassFeature(private val lpparam: XC_LoadPackage.LoadPackageParam) {

    companion object {
        private const val GLASS_BLUR_RADIUS = 22f
        private const val GLASS_CORNER_RADIUS = 24f
    }

    fun run() {
        runCatching {
            val tabBarClass = XposedHelpers.findClass(
                "com.apple.android.music.ui.tab.TabBarView",
                lpparam.classLoader
            )

            tabBarClass.hookAfter("onFinishInflate") { param ->
                val tabBarView = param.thisObject as ViewGroup
                setupFrostedGlassBar(tabBarView)
            }
        }.onFailure {
            android.util.Log.e("AM++", "Frosted glass init failed", it)
        }
    }

    private fun setupFrostedGlassBar(tabBarView: ViewGroup) {
        val context = tabBarView.context

        // 底栏容器背景：磨砂玻璃主层
        val glassSurfaceDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = GLASS_CORNER_RADIUS.dp(context)
            setColor(Color.argb(48, 238, 238, 244))
            setStroke(1.dp(context), Color.argb(35, 255, 255, 255))
        }

        // 夜间模式暗色版本
        val glassSurfaceDarkDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = GLASS_CORNER_RADIUS.dp(context)
            setColor(Color.argb(55, 18, 18, 22))
            setStroke(1.dp(context), Color.argb(28, 255, 255, 255))
        }

        // 背景模糊设置
        ViewCompat.setBackground(tabBarView, glassSurfaceDrawable)
        ViewCompat.setElevation(tabBarView, 6f.dp(context))
        tabBarView.clipToOutline = true

        // 启用渲染模糊
        tabBarView.viewTreeObserver.addOnGlobalLayoutListener {
            tabBarView.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    GLASS_BLUR_RADIUS,
                    GLASS_BLUR_RADIUS,
                    android.graphics.Shader.TileMode.CLAMP
                )
            )
        }

        // 调整底栏内边距
        tabBarView.updatePadding(
            left = 8.dp(context),
            right = 8.dp(context),
            top = 6.dp(context),
            bottom = 6.dp(context)
        )

        // 处理子项：选中指示器改为磨砂哑光风格
        runCatching {
            val tabContainer = tabBarView.getField("mTabContainer") as? LinearLayout
            tabContainer?.let { container ->
                for (i in 0 until container.childCount) {
                    val tabItem = container.getChildAt(i)
                    setupFrostedTabIndicator(tabItem)
                }
            }
        }
    }

    private fun setupFrostedTabIndicator(tabItem: View) {
        val context = tabItem.context

        // 磨砂选中指示器：均匀哑光白，无色散彩虹边缘
        val indicatorDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 18f.dp(context)
            setColor(Color.argb(140, 255, 255, 255))
            setStroke(1.dp(context), Color.argb(50, 255, 255, 255))
        }

        // 水波纹效果
        val rippleDrawable = RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(30, 0, 0, 0)),
            indicatorDrawable,
            null
        )

        ViewCompat.setBackground(tabItem, rippleDrawable)
        ViewCompat.setElevation(tabItem, 7f.dp(context))

        // 移除弹性挤压动画，仅保留平滑位移
        tabItem.animate().setDuration(220).setInterpolator(
            android.view.animation.AccelerateDecelerateInterpolator()
        )
    }
}
