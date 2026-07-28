package dev.amenhancer.module.config

import android.content.Context
import android.content.SharedPreferences
import dev.amenhancer.module.ModuleApplication
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.ModuleSettings

class ConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val legacyPreferences = appContext.getSharedPreferences(
        ConfigContract.LEGACY_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    val isRemoteAvailable: Boolean get() = ModuleApplication.remotePreferences != null

    fun settings(): ModuleSettings = readSettings(ModuleApplication.remotePreferences ?: legacyPreferences)

    fun saveSettings(settings: ModuleSettings): Boolean {
        val preferences = ModuleApplication.remotePreferences ?: return false
        preferences.edit()
            .putBoolean(ConfigContract.KEY_DUAL_PANE, settings.dualPaneEnabled)
            .putBoolean(
                ConfigContract.KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET,
                settings.disableEditorialVideoOnTablet,
            )
            .putBoolean(ConfigContract.KEY_PHONE_LIQUID_GLASS, settings.phoneLiquidGlassEnabled)
            .putBoolean(ConfigContract.KEY_FUTURE_BLUR, settings.futureBlurEnabled)
            .putInt(ConfigContract.KEY_SCHEMA_VERSION, ModuleConstants.CONFIG_SCHEMA_VERSION)
            .apply()
        return true
    }

    companion object {
        private fun readSettings(preferences: SharedPreferences): ModuleSettings = ModuleSettings(
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

        fun migrateLegacyPreferences(context: Context, destination: SharedPreferences) {
            if (destination.contains(ConfigContract.KEY_SCHEMA_VERSION)) return
            val legacy = context.getSharedPreferences(
                ConfigContract.LEGACY_PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            val settings = readSettings(legacy)
            destination.edit()
                .putBoolean(ConfigContract.KEY_DUAL_PANE, settings.dualPaneEnabled)
                .putBoolean(
                    ConfigContract.KEY_DISABLE_EDITORIAL_VIDEO_ON_TABLET,
                    settings.disableEditorialVideoOnTablet,
                )
                .putBoolean(ConfigContract.KEY_PHONE_LIQUID_GLASS, settings.phoneLiquidGlassEnabled)
                .putBoolean(ConfigContract.KEY_FUTURE_BLUR, settings.futureBlurEnabled)
                .putInt(ConfigContract.KEY_SCHEMA_VERSION, ModuleConstants.CONFIG_SCHEMA_VERSION)
                .commit()
        }
    }
}
