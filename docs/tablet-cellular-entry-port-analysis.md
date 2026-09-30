# HyperLyrics-Enhanced 强制蜂窝数据入口实现与 AM++ 移植分析

分析日期：2026-09-30。源仓库 HEAD：`38cf511`；AM++ HEAD：`4891b30`。

前十节记录最初的源码分析、目标反编译核对与移植设计。随后用户授权实现，第十一节记录移植状态。HEAD 用于定位分析时的基线，结论依据实际工作区文件。

## 1. 结论

这个功能可以移植到 AM++，核心是三个方法 Hook，而不是添加一个设置菜单。

1. 在 Apple Music 设置页面构建期间，让数据分组的 SIM 判断通过，保留原生“数据”分组。
2. 对 Apple Music 的 `FuseConnectivityChecker.isCellularAvailable()` 查询返回 `true`，让播放层继续使用用户选择的蜂窝数据偏好。
3. 上述行为受独立开关控制，缺省关闭；设置页 SIM 判断只在构建作用域中放行一次，其他调用保持原值。

推荐在 AM++ 的 `FeatureHook → TargetAdaptation → AppleMusicSymbols` 架构中增加独立功能，同时复用已经移植的 `AppleHookRegistrar`。不需要装配完整 HLE 元数据系统，也不需要新的 Hook 框架。

首轮可靠支持范围应为 Apple Music **6.5.2/1586 和 6.5.3/1599**。AM++ 内置入口还支持 6.5.1/1583，但本次没有足够证据为其填写蜂窝功能的版本映射。

## 2. 源功能在哪里

| 层次 | 位置 | 作用 |
| --- | --- | --- |
| 设置 UI | `AppleMusicOptimizationPage.kt:55,525` | 初始化状态并保存 Boolean 开关 |
| 设置定义 | `RootConstants.kt:303,365` | key 为 `key_hook_apple_music_force_cellular_data_entry`，默认 false |
| 偏好桥接 | `XposedLyricSettingPageScaffold.kt:43,51` | 写本地 SharedPreferences，再经 PrefsBridge 发布配置 |
| 目标进程配置 | `AppleOrchestratorContentUiLanguage.kt:19` | 获取远程偏好，注入目录组装群和体验优化总开关 |
| Hook 注册入口 | `AppleOrchestratorHookModules.kt:224,233` | 分别注册设置分组与全局蜂窝可用性模块 |
| 核心业务 | `AppleCellularDataSettingsHooks.kt:17` | 两组 Hook 及作用域状态 |
| 版本映射 | `AppleMusicHookProfiles.kt:509,714` | 1586、1599 的准确二进制目标 |
| 回调语义 | `internal/HookCallbacks.kt:42,103` | finally 清理作用域，先原调用再覆盖返回值 |
| 基础注册 | `hooks/AppleHookRegistrar.kt:90` | 总开关包装、deoptimize、注册与安装失败后的修复重试 |
| 单测 | `AppleCellularDataSettingsTest.kt` | 默认关闭、单次放行、线程/嵌套隔离、描述符及返回值策略 |

核心源码：[AppleCellularDataSettingsHooks.kt](https://github.com/juren233/HyperLyrics-Enhanced/blob/38cf511ef438bacedf0d7336f58f6f2f24fc561f/app/src/main/java/io/github/proify/lyricon/amprovider/xposed/hooks/AppleCellularDataSettingsHooks.kt#L17)。

源 UI 显示“强制平板显示蜂窝数据入口”。但核心 Hook 中没有平板、横屏、屏幕宽度或厂商判断：开启后，它针对 Apple Music 目标进程生效。这个区别直接影响移植时的门控选择。

## 3. Apple Music 为什么隐藏入口

### 3.1 设置页面有自己的 SIM 门槛

1586 的映射及源码注释指出：`SettingsFragment.t1()` 调用 `La.c.e(Context)`；该方法检查 SIM 状态，状态 0、1 返回 false，进而删除 `KEY_CATEGORY_DATA`。

本次额外直接检查了工作区的 1599 反编译结果：

- `SettingsFragment.smali:3308`：`public final t1()V`。
- `:3632`：调用 `Oa.c.e(Context)Z`。
- `:3640`：结果 true 跳过删除分组分支。
- `:3660`：结果 false 时调用 `B1(Preference)` 删除分组。
- `:3666`：随后才执行 `x1()`，完成进一步设置初始化。
- `Oa/c.smali:371`：`public static e(Context)Z`，直接读取 `TelephonyManager.getSimState()`，0、1 返回 false。

原生 XML 中“数据”分组的 key 为 `key_data_category`：`res/xml/settings_preference.xml:28` 使用 `KEY_CATEGORY_DATA`，资源 ID 是 `0x7f13002e`。分组中本来就有蜂窝数据入口和蜂窝省流量开关。

因此，保留这个分组之后，原生标题、跳转、监听器和偏好处理继续由 Apple Music 完成，移植方无需重造设置页面。

证据：1599 SettingsFragment、1599 SIM 判断。`.scratch` 是本机反编译材料，未纳入 Git。

### 3.2 播放层还有独立判断

1599 的 `FuseConnectivityChecker.isCellularAvailable()Z` 同样直接读取 SIM 状态，未调用上述 `Oa.c.e(Context)`。

播放层 `MediaPlaybackPreferences.getUseCellularData(Context)` 的简化逻辑为：

```kotlin
if (connectivityChecker != null && !connectivityChecker.isCellularAvailable()) {
    return false
}
return preferences.getBoolean("key_use_cellular_data", true)
```

所以只恢复 UI 分组，播放层仍可能忽略蜂窝使用偏好。源功能额外把 `isCellularAvailable()` 的正常返回值覆盖为 true，让播放层继续读取用户自己的开关。

这里不会把 `key_use_cellular_data` 强制写成 true：用户设置为 false 时，它仍然为 false。也没有修改 `isWifiConnected()`、`isMobileConnected()`、网络类型或系统 SIM 状态。

该 Hook 可以改变应用的能力判断与播放策略，但不能给没有蜂窝硬件的设备创建真实移动网络。是否满足某个特殊网络环境的播放需求，仍需设备验证。

证据：FuseConnectivityChecker、播放偏好消费位置。

## 4. 三个 Hook 的精确目标

| Hook | 6.5.2 / 1586 | 6.5.3 / 1599 | 完整契约 |
| --- | --- | --- | --- |
| 设置构建 | `com.apple.android.music.settings.fragment.SettingsFragment.t1` | 相同 | 实例、无参、返回 void |
| 设置 SIM 判断 | `La.c.e` | `Oa.c.e` | static、参数 `android.content.Context`、返回 primitive boolean |
| 全局蜂窝可用性 | `com.apple.android.music.playback.connectivity.FuseConnectivityChecker.isCellularAvailable` | 相同 | 实例、无参、返回 primitive boolean |

使用 DEX 描述符表示：

```text
Lcom/apple/android/music/settings/fragment/SettingsFragment;->t1()V
LLa/c;->e(Landroid/content/Context;)Z          # 1586
LOa/c;->e(Landroid/content/Context;)Z          # 1599
Lcom/apple/android/music/playback/connectivity/FuseConnectivityChecker;->isCellularAvailable()Z
```

必须保留 `La.c`、`Oa.c` 的大小写，不能使用 JADX 的碰撞别名、`defpackage` 路径或一律转小写。源注释明确指出，1599 的 `La.c` 已转作日期格式器。

必须 Hook 具体的 `FuseConnectivityChecker` 实现，不能把 `ConnectivityChecker` 接口声明当作等价安装目标。需要验证非 synthetic、非 bridge、static 标志、参数类型和返回类型。

源解析器先尝试版本候选并验证签名/契约，再尝试 DexKit；这不等于任意新版本已适配。当前提供明确二进制证据的是上述两版。

证据：[源 1586 profile](https://github.com/juren233/HyperLyrics-Enhanced/blob/38cf511ef438bacedf0d7336f58f6f2f24fc561f/app/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleMusicHookProfiles.kt#L509)、[源 1599 profile](https://github.com/juren233/HyperLyrics-Enhanced/blob/38cf511ef438bacedf0d7336f58f6f2f24fc561f/app/src/main/java/io/github/proify/lyricon/amprovider/xposed/AppleMusicHookProfiles.kt#L714)。

## 5. 为什么设置 SIM Hook 必须限定作用域并只放行一次

源代码不对 SIM helper 永久返回 true，而是维护 `ThreadLocal<ArrayDeque<Boolean>>`：

```text
t1 进入：push(当前开关)
  helper 第一次正常返回：若栈顶为 true，将栈顶改为 false，覆盖结果为 true
  helper 后续正常返回：保持原结果
t1 结束或抛异常：finally pop；栈空时 ThreadLocal.remove()
```

三个设计点都不能省略：

- **动态作用域**：只有 t1 的同步调用期间能放行，设置页外的 helper 调用保持原结果。
- **单次消费**：源注释指出数据分组门槛位于后续初始化之前，仅放行首个检查，避免后面的省流量初始化或网络检查继承强制值。
- **栈与线程隔离**：嵌套调用可以各自拥有状态。内层关闭时必须压入 false，屏蔽外层 true；其他线程完全不继承授权。

示例：`enter(true) → enter(false) → consume=false → exit → consume=true`。用一个全局 Boolean 或 ThreadLocal<Boolean> 无法正确保持这种嵌套语义。

`ScopedCallbackHook` 在 `finally` 中执行退出，即原生 t1 抛出异常也清理。`ResultOverrideHook` 先 `chain.proceed()`，然后覆盖正常返回值；原方法异常会传播，不会被强行转换为 true。

这实际上依赖“构建期间首个 helper 调用就是数据分组门槛”。它不是精确到 DEX call-site 的 Hook。如果新版在前面新增同一 helper 调用，就需要重新核对顺序。

全局 availability Hook 不使用上述作用域，每次查询读取当前开关；设置作用域则在 t1 进入时读取开关并固定本次 allowance。

## 6. 设置保存、生效时机与总开关

源配置链路是：

```text
Compose SwitchPreference
  → 本地偏好 + PrefsBridge.putBoolean
  → 目标进程 getRemotePreferences
  → catalogLanguage.contentUiLanguagePrefs
  → Hook 回调读取当前偏好
```

两个功能模块都不属于 debugOnly，也没有根据初始蜂窝开关提前跳过安装。因此默认关闭时已有 Hook，可以在配置更新后改变回调行为。

此外，源 registrar 默认把功能 Hook 包装在 `EntryGatedHooker` 中；体验优化总入口关闭时直接执行原生链路。因此源行为同时受总入口和蜂窝子开关控制。

这项开关没有单独的“设置页面立即重建”配置监听分支。原生 t1 还会用实例字段缓存页面构建状态。配置查询可实时变化，不代表已被删除的数据分组会自动恢复。可靠做法是重新创建设置 Fragment，必要时重启 Apple Music。

AM++ 应沿用自己的配置链路，不移植源 `PrefsBridge` 或 HLE 总入口。若希望忠实保留实时查询语义，新 feature 默认关闭时也不能简单跳过 Hook 安装。

## 7. AM++ 已有设施与缺口

| AM++ 当前设施 | 可复用部分 | 移植注意点 |
| --- | --- | --- |
| `HookEntry` | Application 完成后初始化 feature，持有真实 XposedModule | 当前入口是 embedded-only，不要假设需要新增独立设置 App |
| `EmbeddedBootstrap` | 主进程和精确版本白名单 | 支持 1583/1586/1599，蜂窝 capability 的验证范围可以更窄 |
| `FeatureInstallation` | 安装顺序、进程内幂等、单功能异常隔离和健康记录 | 增加一个独立 plan，避免散落在 HookEntry |
| `TargetAdaptation` | feature 专属 capability、统一 target factory | 新增 cellularDataEntry，与标题修正解耦 |
| `TargetSymbols` | 精确类/方法映射及 Missing/Ambiguous 诊断 | 需要新增三个目标及签名契约 |
| `TargetConfigClient.settings()` | 通过现有 reader 读取普通配置 | 回调中调用，避免捕获初始化时的 Boolean |
| `HostPrivateEmbeddedStorage` | `ampp-embedded-settings` 内的私有偏好 | 不需要另建偏好文件或跨应用访问机制 |
| `EmbeddedSettingsHost` | 功能卡片、Boolean row、普通设置保存 | 在当前设置页添加开关即可 |
| HLE `AppleHookRegistrar` | result override、scope、deoptimize | 已足够实现核心三个 Hook，不需要新运行时 |
| HLE profiles | 元数据系统版本解析 | 当前没有三个蜂窝枚举/目标，整文件覆盖会影响已有适配 |

AM++ 的 `HleMetadataRuntime` 是完整元数据装配，包含语言请求与元数据表面 Hook；它的构造发生在标题修正 capability 内。把蜂窝 Hook 塞进去，会让关闭标题修正时蜂窝功能也缺失。

`ModernXposedRuntime.hookMethod()` 当前没有调用 deoptimize；若改用它实现，应显式保留反优化步骤。直接复用 `AppleHookRegistrar` 可以保留源行为中的步骤和 `finally` 清理。

依据：[TargetAdaptation](../app/src/main/java/dev/amenhancer/module/hook/TargetAdaptation.kt#L88)、[FeatureInstallation](../app/src/main/java/dev/amenhancer/module/hook/FeatureInstallation.kt#L256)、[已有 registrar](../app/src/main/java/io/github/proify/lyricon/amprovider/xposed/hooks/AppleHookRegistrar.kt#L53)、[现有设置 UI](../app/src/main/java/dev/amenhancer/module/ui/EmbeddedSettingsHost.kt#L2155)。

## 8. 推荐的具体移植方案

### 8.1 新增配置与 UI

建议字段：`ModuleSettings.forceCellularDataEntryEnabled: Boolean = false`。

建议 AM++ 存储 key：`force_cellular_data_entry_enabled`。源 HLE key 是另外一个应用配置系统的 key，名称相同并不会自动迁移其值。

| 文件 | 具体改动 |
| --- | --- |
| `model/ModuleModels.kt` | 新增缺省 false 的 Boolean 字段 |
| `config/ModuleSettingsSchema.kt` | 在 decode、encodeOrdinarySettings、settingKeys 中加入新配置 |
| `ModuleConstants.kt` | 增加 `FEATURE_CELLULAR_DATA_ENTRY` 标识 |
| `ui/EmbeddedSettingsHost.kt` | renderEmbeddedMainPage 中新增功能开关，使用 settings.copy 写入 |

现在 schema 版本是 14。可按项目约定升级到 15，并更新相关迁移断言；单独增加默认 false 的字段，在解码层也可以兼容缺少 key 的旧存储。不要为一个 Boolean 建立新的迁移/远程存储系统。

UI 建议说明：“恢复 Apple Music 原生蜂窝数据设置；重新创建设置页面或重启后显示。”功能与双栏、DPI、液态玻璃和标题修正独立。

### 8.2 新增 capability 和版本符号

建议新增 `CellularDataEntryFeature.kt`、`AppleMusicCellularDataEntryTarget.kt`。在 TargetAdaptation 中增加 `CellularDataEntryTarget` capability，并把 feature 加入统一安装列表。

TargetSymbols 中添加设置 Fragment、SIM helper、Fuse checker 的 owner 和 method ID，分别在 1586、1599 profile 填入第 4 节映射。采用精确目标、完整描述符校验、禁用模糊结构扫描；不能仅以 static Boolean(Context) 在全 APK 中寻找候选。

注意 `EXACT_REQUIRED` 的现有实现：只有已有 profile 匹配时才阻止向 fallback 继续解析。因此 target 本身还应明确检查 `versionName + versionCode` 是否属于 1586/1599，不能把 EXACT_REQUIRED 当作通用版本白名单。

若需 1583 支持，先核对其原始 APK 中 t1、SIM helper 及调用顺序，再增加该版映射。版本不支持返回 unsupported；目标缺失或歧义返回 degraded。

### 8.3 保留核心调用语义

以下是安装结构示意，属于设计稿，尚未加入项目或编译：

```kotlin
// 方法均已经解析并验证，三处 Hook 都成功后才允许修改行为。
val ready = AtomicBoolean(false)
val scope = CellularDataSettingsScope()
fun enabledNow(): Boolean = ready.get() &&
    config.settings().forceCellularDataEntryEnabled

registrar.installResultOverrideHook(simCheck) { _, original ->
    if (scope.consume()) true else original
}
registrar.installScopedHook(
    buildMethod,
    enter = {
        scope.enter(enabledNow()) // false 也必须压栈，以隔离嵌套调用
        true                     // 已压栈，必须退出清理
    },
    after = { _, _ -> },
    exit = scope::exit,
)
registrar.installResultOverrideHook(availabilityMethod) { _, original ->
    if (enabledNow()) true else original
}
ready.set(true)
```

作用域实现可以复用源 `AppleCellularDataSettingsScope` 的算法，保留版权和来源记录。原类依赖 RootConstants/SharedPreferences；若直接搬其 class，建议把配置接口改为 `enabled: () -> Boolean`，由 AM++ 注入。

三处 Method 应全部解析并校验后再注册。`ready` 是建议增强：注册期间发生异常时，已注册的 Hook 仍透传，避免安装一半就开始全局改变蜂窝判断。源代码只在设置分组两个方法解析完成后注册；它并没有三处注册的事务回滚，不能误认为已有原子安装保证。

默认关闭时仍安装通过验证的 Hook，回调透传；这样同进程开启配置后可以生效。避免复制 DualPaneFeature 的“开关 false 就直接 return disabled”安装门控，否则首次开启要重启进程才能安装。

健康信息应区分“目标成功安装”和“当前开关是否启用”，并记录版本与三个目标描述符。现有 FeatureInstallation 的快照是安装时状态，不会随着偏好变化自动刷新。

### 8.4 不接入平板横屏限定

不要复用 `TabletModeQualifier.isEligible()` 或双栏 feature 的条件。该功能在竖屏设置页也有意义，源实现没有横屏限定。

若产品最终选择只让真正平板用户使用，应另设与方向无关的设备策略；这是额外行为变更，需要单独定义，而非机械复用双栏条件。

### 8.5 另一条可行路径

也可以继续使用 HLE AppleMusicHookResolver：补三个 HookPoint 枚举、两版 profile、必要的匹配分支，独立创建轻量 `AppleMusicProviderRuntime` 并 attach，再移植原 hooks 类并替换配置 supplier。

这条路径可以保留 HLE resolver 的契约、基线和 DexKit 修复能力。但需要验证新增枚举的所有分支，而且多一个 runtime 与另一套版本符号维护。若团队计划把更多 HLE 功能集中在共享基础 runtime 中，它有价值；当前只移植这个小功能时，AM++ 原生 TargetSymbols 路径更直接。

两条路径都不应复用“必须开启标题修正才能创建”的 HleMetadataRuntime，也不应以源文件整包覆盖代替局部合并。

## 9. 容易造成行为偏差的实现

| 实现偏差 | 后果 |
| --- | --- |
| 仅增加 AM++ UI 开关 | 没有目标方法行为变化 |
| 仅保留设置分组 | 播放层仍可能因 availability=false 忽略蜂窝偏好 |
| 仅 Hook availability | 设置分组的独立 SIM 判断仍会删除入口 |
| 永久让 SIM helper 返回 true | 改变构建页外及后续同 helper 查询 |
| 把 getSimState 全局改成“有 SIM” | 修改范围远大于源功能，影响目标应用其他调用 |
| 用全局 flag 替代 ThreadLocal 栈 | 跨线程、嵌套、异常退出发生状态串扰 |
| 用 before hook 直接短路 true | 丢失原调用副作用与异常语义，和源实现不同 |
| 捕获启动时 settings Boolean | 用户后续开关更新不能反映到回调 |
| 默认为 false 就不安装 | 首次打开开关必须重新启动 Hook 安装链路 |
| 在已删除分组后手工追加 UI | 原生跳转、监听器、初始化与缓存需要再实现 |
| 复用平板横屏/标题修正门控 | 竖屏或关闭标题修正时意外失效 |
| 1599 仍绑定 La.c 或使用小写 | 目标不符，存在错类/找不到方法风险 |

此外，三处 Hook 成功注册只证明安装完成，不证明 ART 优化后的所有实际调用都已进入回调；需要设备确认首个回调及门槛放行日志。两套模块同时 Hook 时的优先级/覆写顺序也需实际验证。

## 10. 验证方案及本轮验证范围

### 已完成

阅读并对照两个仓库的实现、安装入口、配置读写、版本映射和已有测试；直接核对 1599 本机反编译材料，并通过以下七项静态断言：

1. t1 中恰有一次已知 SIM helper 调用。
2. 该调用位于数据分组删除之前，删除位于 x1 初始化之前。
3. SIM helper 直接读取真实 SIM 状态。
4. Fuse availability 独立读取真实 SIM 状态，未调用设置 SIM helper。
5. 播放偏好查询 availability。
6. 播放偏好仍保留 key_use_cellular_data 用户配置。
7. 原生 XML 具备数据分组。

1586 的目标来自源 profile 与 descriptor 单测，没有在本轮独立提取 1586 APK 作二进制核对。上述静态断言不替代设备验证。

### 实现时应补的行为测试

- 未进入 scope、开关关闭、scope 退出后均不覆盖原值。
- 同一 scope 仅第一次消费 true，重复构建重新取得一次 allowance。
- 内层关闭屏蔽外层 allowance；其他线程不继承；异常 finally 后无泄漏。
- availability 开关打开返回 true；关闭时保留原值；运行中变化能生效。
- 三处目标的版本与完整签名契约；1599 不回退到旧 La.c；未知版本不安装。
- 三处 Hook 安装失败的故障注入：ready 不置 true、已装回调透传，不影响其他 feature。
- settings encode/decode、缺省 false、普通设置写入和嵌入式存储往返。

### 设备验收

| 场景 | 验收重点 |
| --- | --- |
| 1586/1599，无 SIM 或 Wi-Fi 平板，开关关 | 保持原生入口及网络行为 |
| 同设备，开关开，创建新设置页 | 数据分组、蜂窝入口、省流量控件出现且可操作 |
| 竖屏/横屏切换 | 功能不受双栏与横屏条件影响 |
| 标题修正关闭、双栏关闭 | 蜂窝功能仍可独立使用 |
| 运行中 false→true→false | availability 随配置变化；页面以重建后的状态验证 |
| 用户原生蜂窝开关设 false | 不因 availability 覆盖而被强制启用 |
| 连续打开/关闭设置及异常路径 | 无状态残留、重复 Hook 或线程串扰 |
| 设置页以外查询 SIM/移动连接 | 未受到设置 SIM scope 放行影响 |
| 启用其他同时 Hook Apple Music 的模块 | 排查同一方法优先级与相互覆盖 |

分析阶段尚未实际移植；后续实现与验证状态见下一节。

## 11. 后续移植状态

已按推荐路径加入独立 CellularDataEntryFeature/AppleMusicCellularDataEntryTarget：

- 新增默认 false 的配置 `force_cellular_data_entry_enabled`，schema 升级为 15，嵌入式设置页提供开关。
- TargetSymbols 中加入 1586/1599 的三个精确目标。1583 和不匹配的版本返回 UNSUPPORTED；目标缺失返回 DEGRADED。
- 配置由 TargetConfigClient 在回调时读取，默认关闭仍安装透传回调。
- 设置 SIM helper 仅在当前线程的构建 scope 中放行一次，保留嵌套和退出清理；availability 查询独立读取实时开关。
- 三处注册全部成功后 ready 才开启；安装过程失败时已注册部分保持透传。
- 与双栏、标题修正、方向判断独立。生产实现复用现有 AppleHookRegistrar 的原调用、finally 清理和 deoptimize。
- 新增 19 项行为、符号和 schema 测试，并扩充嵌入式存储断言；配置读取失败会压入关闭的 scope frame，防止嵌套构建继承外层放行。

最终验证：`testDebugUnitTest` 共 807 项全部通过（0 失败、0 错误、0 跳过）；`assembleDebug`、`assembleRelease` 和 release vital lint 通过；`git diff --check` 通过。验证命令使用项目 Gradle wrapper 和离线缓存。

Release 模块产物：`app/build/outputs/apk/release/app-release.apk`。这是 AM++ 模块 APK，本轮没有重新打包 Apple Music 嵌入版，也没有发布或安装到设备。

尚未进行真机验证；静态适配及 JVM 测试不证明设备上的 ART 回调与设置显示均已通过。
