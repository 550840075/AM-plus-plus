package dev.amenhancer.module.hook

import android.view.View
import android.view.ViewGroup
import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import java.util.concurrent.atomic.AtomicBoolean

/** Both registries can be installed before Application; dispatch is decided from each inflated root. */
internal object FragmentDualPaneResources {
    private val installed = AtomicBoolean()

    fun isLegacyRoot(root: View): Boolean = family(root) == "legacy-activity"
    private fun isFragmentRoot(root: View): Boolean = family(root) == "fragment-content"

    private fun family(root: View): String? = runCatching {
        val build = targetBuild(root.context)
        AppleMusicHostProfiles.find(build.packageName, build.versionName, build.versionCode)?.family
    }.getOrNull()

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        LayoutInflationRegistry.register("fragment_player_main") { root ->
            if (!isFragmentRoot(root)) return@register
            val build = targetBuild(root.context)
            if (build.packageName != "com.apple.android.music" || build.versionName != "7.0.0-beta" || build.versionCode != 1606L) return@register
            runCatching { FragmentDualPaneViewMount.install(root as? ViewGroup ?: return@register) }
                .onFailure { ModernXposedRuntime.log("1606 synchronous player host inflation failed", it) }
        }
        // Native flexbox masks cache line heights during binding. Set both lyric layers before
        // that first measurement, rather than changing text metrics only at RecyclerView attach.
        listOf("lyrics_line", "lyrics_word_karaoke").forEach { layout ->
            LayoutInflationRegistry.register(layout) { root ->
                if (isFragmentRoot(root) && TabletModeQualifier.isEligible(root.context)) {
                    TabletLyricTypography.applyToInflatedLayout(root)
                }
            }
        }
        // Sheet chrome and gradient changes remain scoped to the dedicated right Fragment.
    }
}
