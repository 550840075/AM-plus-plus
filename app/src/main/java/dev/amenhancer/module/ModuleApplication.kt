package dev.amenhancer.module

import android.app.Application
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class ModuleApplication : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        val supportsRemote = service.apiVersion >= 102 &&
            service.frameworkProperties.and(XposedService.PROP_CAP_REMOTE) != 0L
        if (!supportsRemote) {
            serviceStatus = "${service.frameworkName} API ${service.apiVersion} 不支持 API 102 remote preferences"
            remotePreferences = null
            listeners.forEach { it(null) }
            return
        }
        val preferences = service.getRemotePreferences(ModuleConstants.REMOTE_PREFERENCES_GROUP)
        dev.amenhancer.module.config.ConfigStore.migrateLegacyPreferences(this, preferences)
        remotePreferences = preferences
        serviceStatus = "已连接 ${service.frameworkName} API ${service.apiVersion}"
        listeners.forEach { it(preferences) }
    }

    override fun onServiceDied(service: XposedService) {
        remotePreferences = null
        serviceStatus = "libxposed 服务连接已断开"
        listeners.forEach { it(null) }
    }

    companion object {
        @Volatile
        var remotePreferences: SharedPreferences? = null
            private set

        @Volatile
        var serviceStatus: String = "等待 libxposed API 102 服务"
            private set

        private val listeners = CopyOnWriteArraySet<(SharedPreferences?) -> Unit>()

        fun addServiceListener(listener: (SharedPreferences?) -> Unit) {
            listeners += listener
            listener(remotePreferences)
        }

        fun removeServiceListener(listener: (SharedPreferences?) -> Unit) {
            listeners -= listener
        }
    }
}
