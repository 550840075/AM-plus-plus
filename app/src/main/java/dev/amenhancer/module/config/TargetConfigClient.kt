package dev.amenhancer.module.config

import android.content.SharedPreferences
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.hook.ModernXposedRuntime
import dev.amenhancer.module.model.FeatureHealth
import dev.amenhancer.module.model.ModuleSettings

class TargetConfigClient(private val preferences: SharedPreferences) {
    init {
        active = this
    }
    fun settings(): ModuleSettings = ModuleSettings(
        dualPaneEnabled = preferences.getBoolean(ConfigContract.KEY_DUAL_PANE, true),
        disableEditorialVideoOnTablet = preferences.getBoolean(
            ConfigContract.KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET,
            true,
        ),
        phoneLiquidGlassEnabled = preferences.getBoolean(
            ConfigContract.KEY_PHONE_LIQUID_GLASS,
            false,
        ),
        futureBlurEnabled = preferences.getBoolean(ConfigContract.KEY_FUTURE_BLUR, true),
        schemaVersion = preferences.getInt(
            ConfigContract.KEY_SCHEMA_VERSION,
            ModuleConstants.CONFIG_SCHEMA_VERSION,
        ),
    )

    fun reportHealth(health: FeatureHealth) {
        ModernXposedRuntime.log(
            "${health.feature}: ${health.state} - ${health.message} [${health.targetVersion}]",
        )
    }

    companion object {
        @Volatile
        private var active: TargetConfigClient? = null

        fun currentSettings(): ModuleSettings = active?.settings()
            ?: ModuleSettings(phoneLiquidGlassEnabled = false)
    }
}
