package dev.amenhancer.module.hook

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import java.util.WeakHashMap
import java.util.IdentityHashMap

/** Uses only verified 1606 contracts; the legacy dual-pane installer remains closed to beta. */
internal object FragmentTabletDualPaneCoordinator {
    private val sessions = WeakHashMap<Any, FragmentTabletDualPaneSession>()
    private val roots = WeakHashMap<Any, ViewGroup>()
    private val artworkOwners = IdentityHashMap<View, FragmentTabletDualPaneSession>()
    private val playbackTargets = WeakHashMap<View, Float>()
    private val failed = WeakHashMap<Any, Boolean>()
    private class WindowGate(val width: Int, val height: Int, val density: Int, val drawer: Boolean)
    private val windows = WeakHashMap<Resources, WindowGate>()

    private val progressValues = WeakHashMap<Any, Float>()
    fun progress(owner: Any): Float? = progressValues[owner]
    fun eligible(context: Context): Boolean {
        val c = context.resources.configuration
        if (!dev.amenhancer.module.config.TargetConfigClient.currentSettings().dualPaneEnabled) return false
        val drawer = context.resources.getIdentifier("useNavigationDrawer", "bool", context.packageName)
        return c.screenWidthDp >= 600 && c.screenWidthDp > c.screenHeightDp && drawer != 0 && context.resources.getBoolean(drawer)
    }
    fun install(loader: ClassLoader, registration: HookRegistrationScope) {
        this.registration = registration
        registration.onClose { sessions.values.toList().forEach { it.destroy() }; sessions.clear(); roots.clear(); artworkOwners.clear(); failed.clear(); progressValues.clear() }
        val lyricsClass = loader.loadClass("com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
        val build = TargetBuild("com.apple.android.music", "7.0.0-beta", 1606L)
        val visual = BetaLyricsPaneRuntime(lyricsClass, checkNotNull(LyricsLayoutFieldProfiles.resolve(lyricsClass, build)))
        visual.validate(); visual.install(registration)
        installArtworkHooks(loader)
        val controller = loader.loadClass("com.apple.android.music.player.fragment.PlayerMainFragment")
        val state = loader.loadClass("com.apple.android.music.player.fragment.PlayerMainFragment\$l")
        val bag = loader.loadClass("com.apple.android.music.storeapi.model.BagConfig")
        hook(controller.getDeclaredMethod("j1", bag), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                val context = ModernXposedRuntime.callMethod(owner, "getContext") as? Context ?: return
                if (eligible(context)) controller.getDeclaredField("a").apply { isAccessible = true }
                    .set(owner, checkNotNull(state.enumConstants).first { (it as Enum<*>).name == "SONG" })
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                roots[owner]?.post { reconcile(owner) }
            }
        })
        hook(controller.getDeclaredMethod("onCreateView", LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java), object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                val root = param.result as? ViewGroup ?: return
                roots[owner] = root
                // Saved right-pane fragments must find the same container before view restoration.
                if (eligible(root.context) || param.args.getOrNull(2) != null) guarded(owner) {
                    sessions.getOrPut(owner) { FragmentTabletDualPaneSession(owner, root) }.prepare()
                }
                root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
                    if (r - l != oldR - oldL || b - t != oldB - oldT) root.post { reconcile(owner) }
                }
                root.post { reconcile(owner) }
            }
        })
        hook(controller.getDeclaredMethod("s1", state, Bundle::class.java), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                val session = sessions[owner]?.takeIf { it.installed && eligible(it.root.context) } ?: return
                val requested = (param.args[0] as? Enum<*>)?.name ?: return
                // Match the established dual-pane player: only lyrics are fixed on the right.
                // SONG/QUEUE retain Apple's state, shared-element transitions and left controls.
                if (!FragmentTabletArtworkPolicy.keepsDedicatedLyrics(requested)) return
                param.result = null
            }
        })
        hook(controller.getDeclaredMethod("onResume"), object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { param.thisObject?.let { owner -> roots[owner]?.post { reconcile(owner) } } }
        })
        hook(controller.getDeclaredMethod("onDestroyView"), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = param.thisObject ?: return
                sessions.remove(owner)?.destroy()
                roots.remove(owner)
                failed.remove(owner)
        progressValues.remove(owner)
            }
        })
    }

    private lateinit var registration: HookRegistrationScope
    private fun hook(method: java.lang.reflect.Method, callback: ModernMethodHook) {
        check(ModernXposedRuntime.hookMethod(method, callback, registration)) { "Reference dual-pane hook failed: $method" }
    }

    private fun installArtworkHooks(loader: ClassLoader) {
        val callback = loader.loadClass("com.apple.android.music.player.fragment.PlayerMainFragment\$i")
        val slide = callback.getDeclaredMethod("b", View::class.java, Float::class.javaPrimitiveType)
        val artwork = callback.getDeclaredMethod("d", Float::class.javaPrimitiveType)
        val owner = callback.getDeclaredField("h").apply { isAccessible = true }
        hook(artwork, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                owner.get(param.thisObject)?.let(FragmentTabletDualPaneCoordinator::beginArtwork)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val controller = owner.get(param.thisObject) ?: return
                val progress = (param.args[0] as? Number)?.toFloat() ?: return
                progressValues[controller] = progress.coerceIn(0f, 1f)
                FragmentTabletDualPaneCoordinator.onArtwork(controller, progress)
            }
        })
        hook(callback.getDeclaredMethod("e"), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                owner.get(param.thisObject)?.let(FragmentTabletDualPaneCoordinator::beginArtwork)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                owner.get(param.thisObject)?.let { FragmentTabletDualPaneCoordinator.endArtwork(it, null) }
            }
        })
        for (x in listOf(true, false)) hook(
            View::class.java.getDeclaredMethod(if (x) "setScaleX" else "setScaleY", Float::class.javaPrimitiveType),
            object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val scale = (param.args[0] as? Number)?.toFloat() ?: return
                    FragmentTabletDualPaneCoordinator.redirectArtworkScale(view, x, scale)?.let { param.args[0] = it }
                }
            },
        )
        hook(loader.loadClass("com.apple.android.music.player.G0")
            .getDeclaredMethod("P", View::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType), object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.args[0] as? View ?: return
                FragmentTabletDualPaneCoordinator.beforePlaybackAnimation(view, param.args[1] as Int, param.args[2] as Boolean)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.args[0] as? View)?.let(FragmentTabletDualPaneCoordinator::afterPlaybackAnimation)
            }
        })
    }

    private fun guarded(owner: Any, block: () -> Unit) {
        runCatching(block).onFailure { error -> fail(owner, error) }
    }

    fun fail(owner: Any, error: Throwable) {
        if (failed.put(owner, true) == true) return
        ModernXposedRuntime.log("7.0 tablet dual pane failed; restoring native player", error)
        roots[owner]?.post { runCatching { sessions[owner]?.restore() } }
    }

    private fun reconcile(owner: Any) {
        val root = roots[owner] ?: return
        if (!root.isAttachedToWindow) return
        guarded(owner) {
            if (!eligible(root.context) || failed[owner] == true) {
                sessions[owner]?.restore()
            } else {
                val session = sessions.getOrPut(owner) { FragmentTabletDualPaneSession(owner, root) }
                session.install()
            }
        }
    }

    fun refreshSettings() {
        failed.clear()
        roots.keys.toList().forEach { owner -> roots[owner]?.post { reconcile(owner) } }
    }

    fun onSlide(sheet: View, progress: Float) {
        sessions.values.toList().filter { it.root.parent === sheet && failed[it.controller] != true }.forEach { session -> guarded(session.controller) { session.onSlide(progress) } }
    }

    fun onRestored(owner: Any) { roots[owner]?.post { reconcile(owner) } }
    fun beginArtwork(controller: Any) {
        if (failed[controller] != true) sessions[controller]?.takeIf { eligible(it.root.context) }?.let { session -> guarded(controller) { session.beginNativeArtwork() } }
    }
    fun endArtwork(controller: Any, progress: Float?) {
        sessions[controller]?.let { session ->
            session.endNativeArtwork()
            if (progress != null && failed[controller] != true) guarded(controller) { session.onSlide(progress) }
        }
    }
    fun onArtwork(controller: Any, progress: Float) = endArtwork(controller, progress)
    fun ownArtwork(view: View, session: FragmentTabletDualPaneSession) { artworkOwners[view] = session }
    fun releaseArtwork(view: View?) { if (view != null) artworkOwners.remove(view) }
    private fun artworkOwner(view: View) = artworkOwners[view]?.takeIf { failed[it.controller] != true && eligible(it.root.context) }
    fun redirectArtworkScale(view: View, x: Boolean, scale: Float): Float? = artworkOwner(view)?.nativeScale(x, scale)
    fun playbackTarget(view: View): Float? = playbackTargets[view]
    fun beforePlaybackAnimation(view: View, state: Int, seeking: Boolean) {
        // Binding may publish a paused state before the dual-pane observer claims its cover.
        playbackTargets[view] = FragmentTabletArtworkPolicy.playbackScale(state, seeking)
        artworkOwner(view)?.let { session -> guarded(session.controller) { session.beforePlaybackAnimation() } }
    }
    fun afterPlaybackAnimation(view: View) { artworkOwner(view)?.let { session -> guarded(session.controller) { session.afterPlaybackAnimation() } } }
    fun active(player: View?): Boolean = sessions.values.any { it.installed && it.player === player && failed[it.controller] != true && eligible(it.root.context) }
    fun coverReady(player: View?): Boolean = sessions.values.any { it.installed && it.player === player && it.coverAligned && failed[it.controller] != true && eligible(it.root.context) }
}
