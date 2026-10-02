package dev.amenhancer.module.hook

import android.app.Application
import android.view.View
import dev.amenhancer.module.config.TargetConfigClient

/** Apple Music composition; the application supplies configuration and automatic-lyric services. */
object AppleMusicHostFactory {
    fun installDensity(application: Application, configuredDpi: Int): AppleMusicDpiOverrideStatus = AppleMusicDpiOverrideRuntime.install(application,configuredDpi)
    fun densityStatus(): AppleMusicDpiOverrideStatus = AppleMusicDpiOverrideRuntime.status

    private fun settingsNames(context: android.content.Context): org.json.JSONObject {
        val build=targetBuild(context)
        return checkNotNull(dev.amenhancer.host.applemusic.AppleMusicHostProfiles.find(build.packageName,build.versionName,build.versionCode)).document.getJSONObject("settings")
    }
    fun settingsActivityMatcher(context: android.content.Context, playerClass: Class<*>?): SettingsActivityMatcher =
        settingsNames(context).let { LegacySettingsActivityMatcher(playerClass,it.getString("playerActivity"),it.getString("mainActivity")) }
    fun settingsViewBridge(context: android.content.Context, onOpen: (android.app.Activity)->Unit): SettingsViewBridge =
        LegacySettingsViewBridge(context,onOpen)
    fun installSettingsEntry(context: android.content.Context, loader: ClassLoader, observer: SettingsEntryObserver) =
        LegacySettingsEntryInstaller(context).install(loader,observer)

    fun bindChrome(activity: android.app.Activity): ChromeHostBinding = LegacyChromeHostBinding(activity)
    fun installChromeHooks(loader: ClassLoader, build: TargetBuild, observer: ChromeHookObserver): HostSubscription =
        LegacyChromeHookInstaller.install(loader, build, observer)
    fun newTypefaceResources(): LyricsTypefaceResourceBinding = LyricsTypefaceSession()
    fun registerDualPaneResources() = DualPaneResourceHook.install()
    fun registerLyricAuxiliaryResources() = LyricCreditsRowResourceHook.install()
    fun installLayoutCallbacks() = LayoutInflationRegistry.install()
    fun registerChromeResources(observer: (View) -> Unit) {
        listOf("bottom_navigation", "mini_player").forEach { name ->
            LayoutInflationRegistry.register(name, observer)
        }
    }
    fun appleMusic(
        config: TargetConfigClient,
        application: Application,
        classLoader: ClassLoader,
        lyricsTypefaceSession: LyricsTypefaceResourceBinding,
        currentSong: CurrentSongIdentityCache = CurrentSongIdentityCache(),
        autoLyricsRuntime: AutoLyricsRuntime? = null,
    ): TargetAdaptation {
        val build = targetBuild(application)
        val resolver = IndexedTargetSymbolResolver(
            build = build,
            source = ApkTargetClassSource(application, classLoader),
        )
        val settings = config.settings()
        return TargetAdaptation(
            identity = build.displayName,
            build = build,
            currentSong = currentSong,
            dualPane = AppleMusicDualPaneTarget(resolver, build),
            editorialVideo = AppleMusicEditorialVideoTarget(application, resolver),
            cellularDataEntry = AppleMusicCellularDataEntryTarget(
                symbols = resolver,
                build = build,
                enabled = { config.settings().forceCellularDataEntryEnabled },
            ),
            bidirectionalLyricBlur = AppleMusicBidirectionalLyricBlurTarget(resolver),
            cjkKaraokeAnimation = AppleMusicCjkKaraokeAnimationTarget(resolver),
            lyricsTypeface = AppleMusicLyricsTypefaceTarget(
                symbols = resolver,
                session = lyricsTypefaceSession as LyricsTypefaceSession,
            ),
            customLyrics = AppleMusicCustomLyricsTarget(
                config = config,
                symbols = resolver,
                currentSong = currentSong,
                autoLyricsRuntime = autoLyricsRuntime,
            ),
            currentSongIdentity = AppleMusicCurrentSongIdentityTarget(
                resolver,
                currentSong,
            ),
            catalogLanguage = AppleMusicCatalogLanguageTarget(
                symbols = resolver,
                rawTargetLanguage = settings.titleCorrectionMode.catalogLanguage.orEmpty(),
            ),
            hleMetadata = HleMetadataTarget {
                val activeModule = ModernXposedRuntime.activeModule()
                    ?: return@HleMetadataTarget TargetCapabilityInstall.Degraded(
                        "Modern Xposed module was not attached",
                    )
                runCatching {
                    HleMetadataRuntime(
                        module = activeModule,
                        application = application,
                        classLoader = classLoader,
                        mode = settings.titleCorrectionMode,
                    ).install()
                }.getOrElse { error ->
                    ModernXposedRuntime.log("HLE metadata runtime install failed", error)
                    TargetCapabilityInstall.Degraded(
                        "HLE metadata runtime failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            },
        )
    }
}
