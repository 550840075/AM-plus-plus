package dev.amenhancer.module

import android.app.Application
import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

class ModuleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val preferences = getSharedPreferences(
            LOCAL_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        publish(XposedServiceSnapshot.local(preferences, filesDir))
    }

    companion object {
        private val serviceSnapshotReference =
            AtomicReference<XposedServiceSnapshot?>(null)

        internal val serviceSnapshot: XposedServiceSnapshot
            get() = serviceSnapshotReference.get()
                ?: error("ModuleApplication not initialized; serviceSnapshot accessed before onCreate()")

        internal fun isCurrentSnapshot(snapshot: XposedServiceSnapshot): Boolean = true

        private val listeners = CopyOnWriteArraySet<(XposedServiceSnapshot) -> Unit>()

        internal fun addServiceListener(listener: (XposedServiceSnapshot) -> Unit) {
            listeners += listener
        }

        internal fun removeServiceListener(listener: (XposedServiceSnapshot) -> Unit) {
            listeners -= listener
        }

        private fun publish(snapshot: XposedServiceSnapshot) {
            serviceSnapshotReference.set(snapshot)
            listeners.forEach { it(snapshot) }
        }

        private const val LOCAL_PREFERENCES_NAME = "module-settings"
    }
}