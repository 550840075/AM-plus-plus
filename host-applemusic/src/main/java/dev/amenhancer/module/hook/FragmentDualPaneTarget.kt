package dev.amenhancer.module.hook

import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method

/** Exact Fragment host for 1606. Native initialization and left-pane transactions remain intact. */
internal class FragmentDualPaneTarget(
    private val symbols: TargetSymbolResolver,
    private val targetBuild: TargetBuild,
) : DualPaneTarget {
    private val registration = HookRegistrationScope()
    private var installed: TargetCapabilityInstall? = null

    @Synchronized override fun install(): TargetCapabilityInstall {
        installed?.let { return it }
        val result = runCatching { installOnce() }.getOrElse {
            registration.close()
            TargetCapabilityInstall.Degraded("1606 Fragment dual pane contract failed: ${it.message}")
        }
        if (result is TargetCapabilityInstall.Active) registration.activate() else registration.close()
        installed = result
        return result
    }

    private fun installOnce(): TargetCapabilityInstall {
        if (targetBuild.packageName != "com.apple.android.music" || targetBuild.versionName != "7.0.0-beta" ||
            targetBuild.versionCode != 1606L
        ) return TargetCapabilityInstall.Unsupported("Fragment dual pane requires exact 7.0.0-beta/1606")
        val initialize = symbols.resolve(AppleMusicSymbols.PlayerControllerInitialize).valueOrNull()
            ?: return TargetCapabilityInstall.Degraded("1606 j1(BagConfig) unavailable")
        val create = symbols.resolve(AppleMusicSymbols.PlayerControllerCreateView).valueOrNull()
            ?: return TargetCapabilityInstall.Degraded("1606 PlayerMainFragment onCreateView unavailable")
        val select = symbols.resolve(AppleMusicSymbols.PlayerControllerSelectPane).valueOrNull()
            ?: return TargetCapabilityInstall.Degraded("1606 s1(State,Bundle) unavailable")
        val lyricsClass = symbols.resolve(AppleMusicSymbols.LyricsFragment).valueOrNull()
            ?: return TargetCapabilityInstall.Degraded("1606 PlayerLyricsViewFragment unavailable")
        val main = create.declaringClass
        check(main.name == "com.apple.android.music.player.fragment.PlayerMainFragment")
        check(initialize.name == "j1" && initialize.parameterTypes.map { it.name } == listOf("com.apple.android.music.storeapi.model.BagConfig"))
        check(select.name == "s1" && select.parameterTypes.map { it.name } == listOf("${main.name}\$l", "android.os.Bundle"))
        check(initialize.returnType == Void.TYPE && select.returnType == Void.TYPE)
        val native = FragmentDualPaneNativeContract(main, select.parameterTypes[0], lyricsClass)
        val visuals = BetaLyricsPaneRuntime(lyricsClass, checkNotNull(LyricsLayoutFieldProfiles.resolve(lyricsClass, targetBuild)))
        visuals.validate()
        val resume = main.getDeclaredMethod("onResume")
        val pause = main.getDeclaredMethod("onPause")
        val destroy = main.getDeclaredMethod("onDestroyView")
        val slideOwner = Class.forName("${main.name}\$i", false, main.classLoader)
        val slide = slideOwner.getDeclaredMethod("b", View::class.java, Float::class.javaPrimitiveType)
        val slideParent = checkNotNull(dualPaneField(slideOwner, "h")).also { check(it.type == main) }

        fun mount(controller: Any, root: View?): FragmentDualPaneState? {
            val group = root as? ViewGroup ?: return null
            val state = FragmentDualPaneViewMount.install(group) ?: return null
            if (state.artwork == null) state.artwork = FragmentDualPaneArtwork(controller, state)
            return state
        }
        fun reconcile(controller: Any, state: FragmentDualPaneState) {
            state.shell.requestLayout()
            val offset = native.slide(controller)
            state.artwork?.slide(offset)
            state.artwork?.refreshBinding()
            state.lyricsHost.alpha = FragmentDualPanePolicy.lyricsAlpha(offset)
            state.reconciler.reconcile(native.forController(controller), native.rightTag, TabletModeQualifier.isEligible(state.root.context))
        }
        hook(slide, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val callback = param.thisObject ?: return
                val offset = param.args[1] as? Float ?: return
                if (offset < 1f) guarded {
                    val controller = slideParent.get(callback) ?: return@guarded
                    FragmentDualPaneViewMount.state(native.root(controller))?.artwork?.slide(offset)
                }
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val callback = param.thisObject ?: return
                val offset = param.args[1] as? Float ?: return
                guarded {
                    val controller = slideParent.get(callback) ?: return@guarded
                    val state = FragmentDualPaneViewMount.state(native.root(controller)) ?: return@guarded
                    state.artwork?.slide(offset)
                    state.lyricsHost.alpha = FragmentDualPanePolicy.lyricsAlpha(offset)
                }
            }
        })
        hook(create, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val controller = param.thisObject ?: return
                guarded { mount(controller, param.result as? View)?.let { reconcile(controller, it) } }
            }
        })
        hook(initialize, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val controller = param.thisObject ?: return
                guarded {
                    val state = mount(controller, native.root(controller)) ?: return@guarded
                    if (TabletModeQualifier.isEligible(state.root.context)) native.normalizeInitialLeft(controller)
                }
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val controller = param.thisObject ?: return
                guarded { mount(controller, native.root(controller))?.let { reconcile(controller, it) } }
            }
        })
        hook(select, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val controller = param.thisObject ?: return
                val state = FragmentDualPaneViewMount.state(native.root(controller)) ?: return
                if (FragmentDualPanePolicy.suppressSelection(TabletModeQualifier.isEligible(state.root.context), (param.args[0] as? Enum<*>)?.name)) {
                    param.result = null
                } else {
                    // Relinquish our positional offset before Apple's SONG/QUEUE shared-element capture.
                    state.artwork?.restore()
                }
            }
        })
        hook(resume, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val controller = param.thisObject ?: return
                guarded { mount(controller, native.root(controller))?.let { it.artwork?.resume(); reconcile(controller, it) } }
            }
        })
        hook(pause, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.thisObject?.let { FragmentDualPaneViewMount.state(native.root(it))?.artwork?.pause() }
            }
        })
        hook(destroy, object : ModernMethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.thisObject?.let { controller ->
                    native.root(controller)?.let { root ->
                        FragmentDualPaneViewMount.state(root)?.close()
                        root.setTag(dev.amenhancer.host.applemusic.R.id.am_enhancer_dual_pane_state, null)
                    }
                }
            }
        })
        visuals.install(registration)
        return TargetCapabilityInstall.Active("1606 Fragment dual pane: native SONG/QUEUE left, persistent lyrics right; stable restore host")
    }

    private fun hook(method: Method, callback: ModernMethodHook) {
        check(ModernXposedRuntime.hookMethod(method, callback, registration)) { "Could not install ${method.name}" }
    }

    private inline fun guarded(action: () -> Unit) {
        runCatching(action).onFailure { ModernXposedRuntime.log("1606 dual pane callback failed", it) }
    }
}

/** All member names below are verified from .class descriptors in the 1606 DEX. */
internal class FragmentDualPaneNativeContract(main: Class<*>, stateClass: Class<*>, private val lyricsClass: Class<*>) {
    private val stateField = checkNotNull(dualPaneField(main, "a")).also { check(it.type == stateClass) }
    private val bindingField = checkNotNull(dualPaneField(main, "d"))
    private val fragmentViewField = checkNotNull(dualPaneField(main, "mView"))
    private val bindingRootField = checkNotNull(dualPaneField(bindingField.type, "d"))
    private val fragment = stateClass.getDeclaredMethod("a").apply { isAccessible = true }
    private val tag = stateClass.getDeclaredMethod("e").apply { isAccessible = true }.also { check(it.returnType == String::class.java) }
    private val songState = checkNotNull(stateClass.enumConstants).single { (it as Enum<*>).name == "SONG" }
    private val lyricsState = checkNotNull(stateClass.enumConstants).single { (it as Enum<*>).name == "LYRICS" }
    val rightTag: String = (tag.invoke(lyricsState) as String) + FragmentDualPanePolicy.RIGHT_TAG_SUFFIX
    private val managerClass = main.getMethod("getChildFragmentManager").returnType
    private val transactionClass = Class.forName("androidx.fragment.app.a", false, main.classLoader)
    private val constructor = transactionClass.getDeclaredConstructor(managerClass).apply { isAccessible = true }

    init {
        check(bindingField.type.name == "q8.a5")
        check(fragment.returnType.name == "com.apple.android.music.common.fragment.a")
        check(managerClass.name == "androidx.fragment.app.E")
        check(managerClass.getDeclaredMethod("Q").returnType == Boolean::class.javaPrimitiveType)
        check(managerClass.getDeclaredMethod("E", String::class.java).returnType.name == "androidx.fragment.app.m")
        val nativeFragment = Class.forName("androidx.fragment.app.m", false, main.classLoader)
        transactionClass.getMethod("e", Int::class.javaPrimitiveType, nativeFragment, String::class.java)
        transactionClass.getDeclaredMethod("m", nativeFragment)
        transactionClass.getMethod("f", Runnable::class.java)
        transactionClass.getDeclaredMethod("h", Boolean::class.javaPrimitiveType)
    }

    fun root(controller: Any): View? = runCatching {
        (fragmentViewField.get(controller) as? View) ?: bindingField.get(controller)?.let {
            bindingRootField.get(it) as? View
        }
    }.getOrNull()

    fun normalizeInitialLeft(controller: Any) {
        val current = stateField.get(controller) as? Enum<*>
        if (FragmentDualPanePolicy.initialLeftState(current?.name) == "SONG") stateField.set(controller, songState)
        // j1 itself owns the state LiveData Z, playback command p1 and native commit callbacks.
    }

    fun slide(controller: Any): Float = runCatching {
        val callback = checkNotNull(dualPaneField(controller.javaClass, "c0")?.get(controller))
        val offset = checkNotNull(dualPaneField(callback.javaClass, "f")).getFloat(callback)
        if (offset.isFinite() && offset >= 0f) offset else {
            val behavior = checkNotNull(dualPaneField(controller.javaClass, "c")?.get(controller))
            if (dualPaneField(behavior.javaClass, "p0")?.getInt(behavior) == 3) 1f else 0f
        }
    }.getOrDefault(0f)

    fun forController(controller: Any): FragmentDualPaneNative {
        val manager = checkNotNull(ModernXposedRuntime.callMethod(controller, "getChildFragmentManager"))
        return object : FragmentDualPaneNative {
            override fun stateSaved(): Boolean = ModernXposedRuntime.callMethod(manager, "Q") as Boolean
            override fun find(tag: String): Any? = ModernXposedRuntime.callMethod(manager, "E", tag)
            override fun isLyrics(fragment: Any): Boolean = lyricsClass.isInstance(fragment)
            override fun hostId(fragment: Any): Int = ModernXposedRuntime.callMethod(fragment, "getId") as Int
            override fun createLyrics(): Any = checkNotNull(fragment.invoke(lyricsState))
            override fun commit(remove: Any?, add: Any?, hostId: Int, tag: String, completed: () -> Unit) {
                val transaction = constructor.newInstance(manager)
                if (remove != null) ModernXposedRuntime.callMethod(transaction, "m", remove)
                if (add != null) ModernXposedRuntime.callMethod(transaction, "e", hostId, add, tag)
                ModernXposedRuntime.callMethod(transaction, "f", Runnable(completed))
                ModernXposedRuntime.callMethod(transaction, "h", false)
            }
            override fun initialize(fragment: Any): Boolean {
                if (ModernXposedRuntime.callMethod(fragment, "getView") == null) return false
                // Mirrors PlayerMainFragment$g.run: initialize native expanded-state handling and bind sheet.
                ModernXposedRuntime.callMethod(fragment, "D1")
                val actions = checkNotNull(dualPaneField(fragment.javaClass, "a")?.get(fragment))
                checkNotNull(dualPaneField(actions.javaClass, "V")).set(actions, dualPaneField(controller.javaClass, "c")?.get(controller))
                return true
            }
        }
    }
}
