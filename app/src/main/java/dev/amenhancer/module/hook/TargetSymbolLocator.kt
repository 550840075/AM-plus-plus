package dev.amenhancer.module.hook

import android.content.Context
import android.view.View
import dalvik.system.DexFile
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

internal class TargetSymbolLocator(
    private val context: Context,
    private val classLoader: ClassLoader,
) {
    private val names: List<String> by lazy(::readClassNames)

    fun findExact(name: String): Class<*>? = load(name)

    fun findFirst(predicate: (String) -> Boolean): Class<*>? = names.asSequence()
        .filter(predicate)
        .mapNotNull(::load)
        .firstOrNull()

    fun findAll(predicate: (String) -> Boolean, limit: Int = 40): List<Class<*>> = names.asSequence()
        .filter(predicate)
        .take(limit)
        .mapNotNull(::load)
        .toList()

    fun playerControllerCandidates(): List<Class<*>> {
        val known = load("com.apple.android.music.player.fragment.w0")
        val dynamic = findAll(
            predicate = { name ->
                name.startsWith("com.apple.android.music.player.fragment.") &&
                    name.substringAfterLast('.').length <= 3
            },
            limit = 32,
        )
        return listOfNotNull(known).plus(dynamic).distinctBy { it.name }
    }

    /**
     * The modified 6.5.0 APK changes w0.w1/F1 specifically.  Prefer the
     * stable known name, then use the same method-shape as a no-version-gate
     * fallback rather than hooking every short class in the fragment package.
     */
    fun playerController(): Class<*>? = load("com.apple.android.music.player.fragment.w0")
        ?: names.asSequence()
            .filter { it.startsWith("com.apple.android.music.player.fragment.") }
            .mapNotNull(::load)
            .firstOrNull { candidate ->
                candidate.declaredMethods.any { method ->
                    method.name == "w1" &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.singleOrNull()?.name?.endsWith(".BagConfig") == true
                }
            }

    fun playerActivity(): Class<*>? = load("com.apple.android.music.common.activity.PlayerActivity")
        ?: findFirst { it.endsWith(".common.activity.PlayerActivity") }

    fun editorialVideoUrlSelector(): Method? {
        load("com.apple.android.music.player.c1")?.editorialVideoUrlSelector()?.let { return it }
        return names.asSequence()
            .filter { name ->
                name.startsWith("com.apple.android.music.player.") &&
                    name.substringAfterLast('.').substringBefore('$').length <= 3
            }
            .mapNotNull(::load)
            .mapNotNull { it.editorialVideoUrlSelector() }
            .firstOrNull()
    }

    fun lyricsFragment(): Class<*>? = load("com.apple.android.music.player.fragment.PlayerLyricsViewFragment")
        ?: findFirst { it.endsWith(".PlayerLyricsViewFragment") }

    /**
     * The modified 6.5.0 APK adds a landscape guard to e.a2(int, int[]):
     * for PlayerLyricsViewFragment it hides f2() and returns before the
     * stock chrome animation. Prefer the known class, then resolve the same
     * method shape from the lyrics fragment's inheritance chain.
     */
    fun lyricsChromeFragment(): Class<*>? {
        val known = load("com.apple.android.music.player.fragment.e")
        if (known?.hasLyricsChromeContract() == true) return known
        return generateSequence(lyricsFragment()?.superclass) { it.superclass }
            .firstOrNull { it.hasLyricsChromeContract() }
            ?: names.asSequence()
                .filter { it.startsWith("com.apple.android.music.player.fragment.") }
                .mapNotNull(::load)
                .firstOrNull { it.hasLyricsChromeContract() }
    }

    fun lyricsViewModelCandidates(): List<Class<*>> = findAll(
        predicate = { it.contains("PlayerLyricsViewModel", ignoreCase = true) },
        limit = 8,
    )

    private fun load(name: String): Class<*>? = runCatching {
        Class.forName(name, false, classLoader)
    }.getOrNull()

    private fun Class<*>.hasLyricsChromeContract(): Boolean =
        declaredMethods.any { method ->
            method.name == "a2" &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.parameterTypes[1] == IntArray::class.java
        } && declaredMethods.any { method ->
            method.name == "f2" &&
                View::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.isEmpty()
        }

    private fun Class<*>.editorialVideoUrlSelector(): Method? = declaredMethods.firstOrNull { method ->
        Modifier.isStatic(method.modifiers) &&
            method.returnType == String::class.java &&
            method.parameterTypes.size == 3 &&
            method.parameterTypes[0].name == "com.apple.android.music.model.Song" &&
            method.parameterTypes[1] == Float::class.javaPrimitiveType &&
            method.parameterTypes[2].isArray &&
            method.parameterTypes[2].componentType?.name ==
            "com.apple.android.music.mediaapi.models.internals.EditorialVideo\$Flavor"
    }

    private fun readClassNames(): List<String> {
        val appInfo = context.applicationInfo
        val paths = buildList {
            add(appInfo.sourceDir)
            appInfo.splitSourceDirs?.let(::addAll)
        }.distinct()
        return paths.flatMap { path ->
            runCatching {
                val dex = DexFile(path)
                try {
                    Collections.list(dex.entries()).filter { it.startsWith("com.apple.android.music.") }
                } finally {
                    dex.close()
                }
            }.getOrDefault(emptyList())
        }.distinct()
    }
}

internal interface FeatureHook {
    val key: String
    fun isEnabled(context: HookContext): Boolean
    fun install(context: HookContext)
}
