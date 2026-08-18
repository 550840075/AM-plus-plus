package dev.amenhancer.module.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.RenderScriptBlur
import dev.amenhancer.module.config.TargetConfigClient
import dev.amenhancer.module.model.ModuleSettings

internal object PhoneLiquidGlassQualifier {
    fun isEligible(context: Context, settings: ModuleSettings): Boolean =
        settings.phoneLiquidGlassEnabled
}

internal class PhoneLiquidGlassFeature : Feature {
    override fun isEligible(context: Context): Boolean {
        return PhoneLiquidGlassQualifier.isEligible(context, TargetConfigClient.currentSettings())
    }
}

internal class PhoneLiquidGlassResourceHook {

    fun install() {
        val bottomNavId = findId("bottom_navigation")
        val miniPlayerId = findId("mini_player")

        if (bottomNavId != 0) {
            ModernXposedRuntime.hookLayout(bottomNavId) { view ->
                installBottomNavGlass(view)
            }
        }

        if (miniPlayerId != 0) {
            ModernXposedRuntime.hookLayout(miniPlayerId) { view ->
                installMiniPlayerGlass(view)
            }
        }
    }

    private fun installBottomNavGlass(view: View) {
        val settings = TargetConfigClient.currentSettings()
        if (!settings.phoneLiquidGlassEnabled) return

        val context = view.context
        val parent = view.parent as? ViewGroup ?: return

        val blurView = BlurView(context)
        blurView.layoutParams = view.layoutParams

        blurView.setupWith(parent, RenderScriptBlur(context))
            .setBlurRadius(18f)
            .setBlurAutoUpdate(true)

        val maskColor = if (isSystemDarkMode(context)) {
            Color.argb(140, 0, 0, 0)
        } else {
            Color.argb(120, 255, 255, 255)
        }
        blurView.setOverlayColor(maskColor)

        val index = parent.indexOfChild(view)
        parent.removeView(view)
        parent.addView(blurView, index)
        blurView.addView(view)

        view.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun installMiniPlayerGlass(view: View) {
        val settings = TargetConfigClient.currentSettings()
        if (!settings.phoneLiquidGlassEnabled) return

        val context = view.context
        val parent = view.parent as? ViewGroup ?: return

        val blurView = BlurView(context)
        blurView.layoutParams = view.layoutParams

        blurView.setupWith(parent, RenderScriptBlur(context))
            .setBlurRadius(18f)
            .setBlurAutoUpdate(true)

        val maskColor = if (isSystemDarkMode(context)) {
            Color.argb(130, 0, 0, 0)
        } else {
            Color.argb(110, 255, 255, 255)
        }
        blurView.setOverlayColor(maskColor)

        val index = parent.indexOfChild(view)
        parent.removeView(view)
        parent.addView(blurView, index)
        blurView.addView(view)

        view.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun isSystemDarkMode(context: Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun findId(name: String): Int {
        return ModernXposedRuntime.getIdentifier(name, "id")
    }
}
