package dev.amenhancer.module.hook

/*
 * Ported from a23bc/amlyricblur, commit 3417e217d7692ae742bbae80d2bd51aadffcd59e.
 * Copyright (c) 2026 a23bc. Licensed under the MIT License.
 */

import android.animation.ValueAnimator
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dalvik.system.DexFile
import dev.amenhancer.module.hook.ModernMethodHook as XC_MethodHook
import java.lang.reflect.Method
import java.util.WeakHashMap

internal class OpenSourceLyricBlurPort {
    companion object {
        private const val TAG = "AMLyricBlur"
        private const val PKG = "com.apple.android.music"
        private const val BLUR_BASE = 12f
        private const val BLUR_STEP = 4f
        private const val BLUR_MAX = 20f
    }

    private val highlightedLineIds = mutableSetOf<Int>()
    private var previousHighlightIds = setOf<Int>()
    private val viewBlurValues = WeakHashMap<View, Float>()
    private val viewAnimators = WeakHashMap<View, ValueAnimator>()

    private var getChildCountMethod: Method? = null
    private var getChildAtMethod: Method? = null
    private var setRenderEffectMethod: Method? = null
    private var createBlurEffectMethod: Method? = null
    private var getAdapterPositionFromView: Method? = null

    private var recyclerView: Any? = null
    private var lyricsRootView: View? = null
    private var isUserScrolling = false
    private var highlightHookInstalled = false
    private var pendingBlurRunnable: Runnable? = null
    private val scrollHandler by lazy { Handler(Looper.getMainLooper()) }
    private var apkSourceDir: String? = null

    fun install(classLoader: ClassLoader, sourceDir: String) {
        Log.i(TAG, "handleLoadPackage: $PKG")
        apkSourceDir = sourceDir
        initReflectionCache(classLoader)
        hookHighlightCallback(classLoader)
        hookLyricsFragment(classLoader)
        hookViewModel(classLoader)
    }

    private fun initReflectionCache(cl: ClassLoader) {
        try {
            val rvClass = cl.loadClass("androidx.recyclerview.widget.RecyclerView")
            getChildCountMethod = ViewGroup::class.java.getMethod("getChildCount")
            getChildAtMethod = ViewGroup::class.java.getMethod("getChildAt", Int::class.javaPrimitiveType)
            for (m in rvClass.declaredMethods) {
                if (java.lang.reflect.Modifier.isStatic(m.modifiers)
                    && m.parameterTypes.size == 1
                    && m.parameterTypes[0] == View::class.java
                    && m.returnType == Int::class.javaPrimitiveType
                ) {
                    getAdapterPositionFromView = m
                    break
                }
            }
            setRenderEffectMethod = View::class.java.getMethod(
                "setRenderEffect",
                Class.forName("android.graphics.RenderEffect"),
            )
            createBlurEffectMethod = Class.forName("android.graphics.RenderEffect")
                .getMethod(
                    "createBlurEffect",
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Shader.TileMode::class.java,
                )
            Log.i(TAG, "Reflection OK")
        } catch (t: Throwable) {
            Log.e(TAG, "Reflection failed", t)
        }
    }

    private fun hookHighlightCallback(cl: ClassLoader) {
        if (highlightHookInstalled) return
        val sourceDir = apkSourceDir ?: return
        val vectorClass = try {
            cl.loadClass("com.apple.android.music.ttml.javanative.model.LyricsLineVector")
        } catch (e: Throwable) {
            Log.e(TAG, "LyricsLineVector NOT loadable: ${e.message}")
            return
        }
        try {
            val dexFile = DexFile(sourceDir)
            val entries = dexFile.entries()
            while (entries.hasMoreElements()) {
                val className = entries.nextElement()
                if (!className.startsWith("com.apple")) continue
                try {
                    val cls = cl.loadClass(className)
                    for (method in cls.declaredMethods) {
                        for (pt in method.parameterTypes) {
                            if (pt == vectorClass || vectorClass.isAssignableFrom(pt)) {
                                Log.i(TAG, "FOUND: $className.${method.name}")
                                installHighlightHook(method, vectorClass)
                                dexFile.close()
                                return
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            dexFile.close()
            Log.w(TAG, "No LyricsLineVector method found")
        } catch (t: Throwable) {
            Log.e(TAG, "DexFile scan failed", t)
        }
    }

    private fun installHighlightHook(method: Method, vectorClass: Class<*>) {
        try {
            ModernXposedRuntime.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val vector = param.args.firstOrNull { arg ->
                            arg != null && vectorClass.isInstance(arg)
                        } ?: return
                        val sizeMethod = vectorClass.getMethod("size")
                        val size = (sizeMethod.invoke(vector) as Long).toInt()
                        val getMethod = vectorClass.getMethod("get", Long::class.javaPrimitiveType)
                        val newIds = mutableSetOf<Int>()
                        for (i in 0 until size) {
                            try {
                                val ptr = getMethod.invoke(vector, i.toLong()) ?: continue
                                val nativeObj = ptr.javaClass.getMethod("get").invoke(ptr) ?: continue
                                val lineId = (
                                    nativeObj.javaClass.getMethod("getLineId").invoke(nativeObj) as Number
                                ).toInt()
                                newIds.add(lineId)
                            } catch (_: Exception) {
                            }
                        }
                        synchronized(highlightedLineIds) {
                            previousHighlightIds = highlightedLineIds.toSet()
                            highlightedLineIds.clear()
                            highlightedLineIds.addAll(newIds)
                        }
                        scheduleBlurUpdate()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Highlight hook error", t)
                    }
                }
            })
            highlightHookInstalled = true
            Log.i(TAG, "Highlight hook installed on ${method.name}")
            scheduleBlurUpdate()
        } catch (t: Throwable) {
            Log.e(TAG, "installHighlightHook failed", t)
        }
    }

    private fun hookLyricsFragment(cl: ClassLoader) {
        try {
            val cls = cl.loadClass("com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
            ModernXposedRuntime.hookAllMethods(cls, "onCreateView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val result = param.result as? View ?: return
                    lyricsRootView = result
                    Log.i(TAG, "onCreateView hooked")
                    Handler(Looper.getMainLooper()).postDelayed({ findRecyclerView(result) }, 500)
                }
            })
            Log.i(TAG, "Fragment hook installed")
        } catch (t: Throwable) {
            Log.w(TAG, "Fragment hook failed: ${t.message}")
        }
    }

    private fun hookViewModel(cl: ClassLoader) {
        try {
            val vmClass = cl.loadClass(
                "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
            )
            Log.i(TAG, "Found VM")

            for (m in vmClass.declaredMethods) {
                val p = m.parameterTypes
                if (p.size == 4 && p[0] == Int::class.javaPrimitiveType && p[3] == Boolean::class.javaPrimitiveType) {
                    ModernXposedRuntime.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val lineId = param.args[0] as Int
                            val isBg = param.args[3] as Boolean
                            if (!isBg && lineId > 0) {
                                synchronized(highlightedLineIds) {
                                    highlightedLineIds.add(lineId)
                                }
                                scheduleBlurUpdate()
                            }
                        }
                    })
                }
            }

            for (m in vmClass.declaredMethods) {
                val p = m.parameterTypes
                if (p.size == 1 && p[0] == Int::class.javaPrimitiveType && m.returnType == Void.TYPE) {
                    ModernXposedRuntime.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val lineId = param.args[0] as Int
                            if (lineId < 0) return
                            if (!highlightHookInstalled) {
                                synchronized(highlightedLineIds) {
                                    previousHighlightIds = highlightedLineIds.toSet()
                                    highlightedLineIds.clear()
                                    highlightedLineIds.add(lineId)
                                }
                                scheduleBlurUpdate()
                            }
                        }
                    })
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "VM hook failed: ${t.message}")
        }
    }

    private fun findRecyclerView(view: View) {
        if (recyclerView != null) return
        try {
            val rv = findRVInHierarchy(view)
            if (rv != null) {
                recyclerView = rv
                Log.i(TAG, "RV FOUND")
                attachScrollListener(rv)
            } else {
                Handler(Looper.getMainLooper()).postDelayed({ findRecyclerView(view) }, 1000)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "findRV error", t)
        }
    }

    private fun findRVInHierarchy(view: View): Any? {
        if (view.javaClass.name == "androidx.recyclerview.widget.RecyclerView") return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val result = findRVInHierarchy(view.getChildAt(i))
                if (result != null) return result
            }
        }
        return null
    }

    private fun scheduleBlurUpdate() {
        pendingBlurRunnable?.let { scrollHandler.removeCallbacks(it) }
        val r = Runnable {
            try {
                applyBlur()
            } catch (t: Throwable) {
                Log.e(TAG, "Blur failed", t)
            }
        }
        pendingBlurRunnable = r
        scrollHandler.post(r)
    }

    private fun attachScrollListener(rv: Any) {
        try {
            val view = rv as View
            view.setOnTouchListener { _, event ->
                isUserScrolling = event.action != MotionEvent.ACTION_CANCEL
                    && event.action != MotionEvent.ACTION_UP
                false
            }
            view.viewTreeObserver.addOnScrollChangedListener { onScrollDetected() }
            Log.i(TAG, "Scroll listener attached")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach scroll listener", t)
        }
    }

    private fun onScrollDetected() {
        if (isUserScrolling) clearAllBlur()
    }

    private fun clearAllBlur() {
        val rv = getRv() ?: return
        val gcm = getChildCountMethod ?: return
        val gca = getChildAtMethod ?: return
        val childCount = gcm.invoke(rv) as Int
        for (i in 0 until childCount) {
            val child = gca.invoke(rv, i) as? View ?: continue
            if (!isLyricsLine(child)) continue
            viewBlurValues[child] = 0f
            viewAnimators[child]?.cancel()
            setRenderEffectMethod?.invoke(child, null)
        }
    }

    private fun getRv(): Any? {
        val rv = recyclerView ?: return null
        val gcm = getChildCountMethod ?: return null
        val count = try {
            gcm.invoke(rv) as Int
        } catch (_: Throwable) {
            -1
        }
        if (count > 0) return rv
        val root = lyricsRootView ?: return null
        val fresh = findRVInHierarchy(root)
        if (fresh != null) {
            recyclerView = fresh
            return fresh
        }
        return null
    }

    private fun applyBlur() {
        val rv = getRv() ?: return
        val gcm = getChildCountMethod ?: return
        val gca = getChildAtMethod ?: return
        val childCount = gcm.invoke(rv) as Int
        val effectiveIds = synchronized(highlightedLineIds) { highlightedLineIds + previousHighlightIds }

        for (i in 0 until childCount) {
            val child = gca.invoke(rv, i) as? View ?: continue
            if (!isLyricsLine(child)) continue
            val adapterPos = getAdapterPosition(child)
            val isHighlighted = adapterPos in effectiveIds
            val targetBlur = if (effectiveIds.isEmpty()) {
                BLUR_MAX
            } else if (isHighlighted) {
                0f
            } else {
                val minDist = effectiveIds.minOf { Math.abs(adapterPos - it) }
                (BLUR_BASE + (minDist - 1) * BLUR_STEP).coerceAtMost(BLUR_MAX)
            }
            animateBlur(child, targetBlur)
        }
    }

    private fun isLyricsLine(view: View): Boolean {
        if (view !is ViewGroup) return false
        if (hasDescendantOfType(view, ImageView::class.java)) return false
        return true
    }

    private fun hasDescendantOfType(view: View, cls: Class<*>): Boolean {
        if (cls.isInstance(view)) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hasDescendantOfType(view.getChildAt(i), cls)) return true
            }
        }
        return false
    }

    private fun getAdapterPosition(child: View): Int {
        val method = getAdapterPositionFromView ?: return -1
        return try {
            method.invoke(null, child) as Int
        } catch (_: Throwable) {
            -1
        }
    }

    private fun animateBlur(view: View, targetBlur: Float) {
        val current = viewBlurValues[view] ?: 0f
        if (current == targetBlur) return
        viewAnimators[view]?.cancel()
        ValueAnimator.ofFloat(current, targetBlur).apply {
            duration = 300
            addUpdateListener { anim ->
                try {
                    val v = anim.animatedValue as Float
                    if (v <= 0f) {
                        setRenderEffectMethod?.invoke(view, null)
                    } else {
                        val effect = createBlurEffectMethod?.invoke(null, v, v, Shader.TileMode.MIRROR)
                        setRenderEffectMethod?.invoke(view, effect)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Render err: ${t.message}", t)
                }
            }
            viewBlurValues[view] = targetBlur
            viewAnimators[view] = this
            start()
        }
    }
}
