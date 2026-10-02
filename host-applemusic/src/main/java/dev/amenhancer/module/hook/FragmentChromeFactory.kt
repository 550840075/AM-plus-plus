package dev.amenhancer.module.hook

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import java.util.IdentityHashMap

/** Standalone install seam for the parent composition factory. Legacy chrome is independent. */
object FragmentChromeFactory {
    fun supports(build: TargetBuild): Boolean = AppleMusicHostProfiles.find(
        build.packageName, build.versionName, build.versionCode,
    )?.let { it.productionEnabled && it.capability("glass") && it.family == "fragment-content" && it.document.has("fragmentChrome") } == true

    fun registerResources(observer: (View) -> Unit) {
        // mini inflation happens during nested Fragment creation, before first onViewCreated.
        listOf("fragment_music_content", "mini_player").forEach { LayoutInflationRegistry.register(it, observer) }
    }

    fun install(loader: ClassLoader, build: TargetBuild, observer: FragmentPlayerSurfaceObserver): HostSubscription {
        val profile = checkNotNull(AppleMusicHostProfiles.find(build.packageName, build.versionName, build.versionCode))
        check(profile.family == "fragment-content") { "Fragment chrome cannot own ${profile.family}" }
        val contract = FragmentChromeContract(loader, profile.document.getJSONObject("fragmentChrome"))
        val scope = HookRegistrationScope()
        val bindings = IdentityHashMap<Any, FragmentSurfaceBinding>()
        val roots = IdentityHashMap<Any, ViewGroup>()
        val callbacks = IdentityHashMap<Any, IdentityHashMap<Any, Any>>()
        val activityOf = FragmentChromeContract.method(contract.content, "getActivity")
        fun destroy(owner: Any) {
            roots.remove(owner)
            callbacks.remove(owner)?.clear()
            bindings.remove(owner)?.let { binding ->
                observer.onDestroyed(binding.viewSessionIdentity)
                binding.close()
            }
        }
        fun fail(owner: Any?, error: Throwable, expectedRoot: ViewGroup? = null) {
            if (expectedRoot != null && roots[owner] !== expectedRoot) return
            val identity = owner?.let { bindings[it]?.viewSessionIdentity }
            if (owner != null) destroy(owner)
            observer.onFailure(identity, error)
        }
        fun bind(owner: Any, root: ViewGroup) {
            if (!scope.isActive || roots[owner] !== root) return
            if (!root.isAttachedToWindow) {
                root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.removeOnAttachStateChangeListener(this)
                        runCatching { bind(owner, root) }.onFailure { fail(owner, it, root) }
                    }
                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
                return
            }
            bindings[owner]?.takeIf { it.viewSessionIdentity === root }?.let { return }
            bindings.remove(owner)?.let { observer.onDestroyed(it.viewSessionIdentity); it.close() }
            val binding = FragmentSurfaceBinding(owner, activityOf.invoke(owner) as Activity, root, contract,
                callbacks.getOrPut(owner) { IdentityHashMap() }, { fail(owner, it, root) })
            bindings[owner] = binding
            observer.onCreated(binding)
        }
        fun hook(method: java.lang.reflect.Method, callback: ModernMethodHook) {
            check(ModernXposedRuntime.hookMethod(method, callback, scope)) { "Fragment chrome hook failed: $method" }
        }
        try {
            hook(FragmentChromeContract.method(contract.content, "onCreateView", LayoutInflater::class.java,
                ViewGroup::class.java, Bundle::class.java), object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val owner = param.thisObject ?: return
                    val root = param.result as? ViewGroup ?: return
                    roots[owner]?.takeIf { it !== root }?.let { destroy(owner) }
                    roots[owner] = root
                    root.post { runCatching { bind(owner, root) }.onFailure { fail(owner, it, root) } }
                }
            })
            hook(FragmentChromeContract.method(contract.content, "onViewCreated", View::class.java, Bundle::class.java),
                object : ModernMethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable != null) return
                        val owner = param.thisObject ?: return
                        if (!contract.content.isInstance(owner)) return
                        val root = param.args[0] as? ViewGroup ?: return
                        roots[owner] = root
                        root.post { runCatching { bind(owner, root) }.onFailure { fail(owner, it, root) } }
                    }
                })
            hook(FragmentChromeContract.method(contract.content, "onDestroyView"), object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.thisObject?.takeIf(contract.content::isInstance)?.let(::destroy)
                }
            })
            hook(contract.renderTab, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = param.thisObject ?: return
                    val model = param.args[0] ?: return
                    val action = param.args[2] ?: return
                    val actions = callbacks.getOrPut(owner) { IdentityHashMap() }
                    val kind = contract.modelKind.get(model)
                    actions.keys.removeAll { it !== model && contract.modelKind.get(it) == kind }
                    actions[model] = action
                }
            })
            hook(contract.slide, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    runCatching {
                        val player = contract.slidePlayer.get(param.thisObject)
                        val progress = param.args[1] as Float
                        bindings.values.forEach { if (it.isPlayer(player)) it.slide(progress) }
                    }.onFailure { observer.onFailure(null, it) }
                }
            })
            hook(ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java),
                object : ModernMethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        // Root dispatch observes one event before native child controls consume it.
                        bindings.values.forEach { binding ->
                            if (binding.isMiniTouchPanel(view)) {
                                runCatching { observer.onMiniTouch(binding.viewSessionIdentity, param.args[0] as MotionEvent) }
                                    .onFailure { fail(null, it) }
                            }
                        }
                    }
                })
            hook(View::class.java.getDeclaredMethod("setAlpha", java.lang.Float.TYPE), object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val original = param.args[0] as Float
                    bindings.values.firstNotNullOfOrNull { it.nativeAlphaWrite(view, original) }
                        ?.let { param.args[0] = it }
                }
            })
            scope.onClose {
                bindings.keys.toList().forEach(::destroy)
                callbacks.clear(); roots.clear()
            }
            scope.activate()
            return HostSubscription { scope.close() }
        } catch (error: Throwable) { scope.close(); throw error }
    }
}
