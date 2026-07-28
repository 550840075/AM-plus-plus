package dev.amenhancer.module.hook

import android.os.Build
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.FeatureState

/** Module setting and health adapter around the upstream AMLyricBlur core. */
internal class FutureLyricBlurFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_FUTURE_BLUR

    override fun isEnabled(context: HookContext): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.config.settings().futureBlurEnabled

    override fun install(context: HookContext) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            context.report(key, FeatureState.DISABLED, "Requires Android 12 or newer")
            return
        }

        OpenSourceLyricBlurPort().install(context.classLoader, context.application.applicationInfo.sourceDir)
        context.report(key, FeatureState.ACTIVE, "a23bc/amlyricblur core installed")
    }
}
