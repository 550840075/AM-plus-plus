package dev.amenhancer.module.hook

import android.app.Application
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.config.TargetConfigClient
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File

class HookEntry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        ModernXposedRuntime.attach(this)
        log(
            Log.INFO,
            "AppleMusicEnhancer",
            "loaded in ${param.processName}; framework=$frameworkName API=$apiVersion",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != ModuleConstants.TARGET_PACKAGE || !param.isFirstPackage) return
        ModernXposedRuntime.attach(this)
        FeatureInstallation.install(
            configProvider = ::createConfig,
            targetClassLoader = param.classLoader,
        )
    }

    private fun createConfig(application: Application): TargetConfigClient {
        val moduleContext = runCatching {
            application.createPackageContext(
                ModuleConstants.MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
            )
        }.getOrElse { error ->
            ModernXposedRuntime.log("createPackageContext failed, falling back to direct file read", error)
            null
        }
        val preferences = if (moduleContext != null) {
            moduleContext.getSharedPreferences(LOCAL_PREFERENCES_NAME, Context.MODE_PRIVATE)
        } else {
            // Fallback: read the module's SharedPreferences XML directly from its data dir.
            val prefsFile = File(
                "/data/data/${ModuleConstants.MODULE_PACKAGE}/shared_prefs/$LOCAL_PREFERENCES_NAME.xml",
            )
            SharedPreferencesXmlReader.read(prefsFile)
        }
        val filesDir = moduleContext?.filesDir
            ?: File("/data/data/${ModuleConstants.MODULE_PACKAGE}/files")
        return TargetConfigClient(
            preferences = preferences,
            remoteFileOpener = { name ->
                runCatching {
                    val file = File(filesDir, name)
                    if (file.exists()) {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    } else null
                }.getOrNull()
            },
        )
    }

    companion object {
        private const val LOCAL_PREFERENCES_NAME = "module-settings"
    }
}