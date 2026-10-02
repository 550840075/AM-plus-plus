# Apple Music 1606 平板恢复闪退分析与修补

## 已观察到的事实

2026-10-03，用户报告平板从后台返回 Apple Music 时闪退，暂停状态也会发生。ADB 连接的是 OPD2413、Android 16/API 36，序列号 7065756b。设备已安装的 AM++ 哈希与 v6 一致。

崩溃缓冲区最新一条 Apple Music Java 异常发生于 01:16:40，PID 8145：

```text
IllegalArgumentException: mKaraokeLineEffectiveWidth not yet initialized!
com.apple.android.music.player.A.n0
com.apple.android.music.player.A.b0
com.apple.android.music.player.A.k / l
androidx.recyclerview.widget.RecyclerView
```

相同堆栈也存在于此前的记录，不能仅凭时间把它归因于 v6 的元数据修复。调查期间，尚未安装任何新包，用户已表示问题不再复现；暂停状态的暖启动返回、展开/收起、一次冷启动和最近任务返回也未产生新 Java 异常。期间部分进程被用户移除任务或系统停止，exit-info 与异常退出不同，不计作闪退。

## 代码中能够确认的缺口

原始 7.0.0-beta/1606 APK 的 DEX 有以下路径：

1. 歌词适配器 `A` 的构造函数把 `S:int`（karaoke 有效行宽）设为 `-1`。
2. `A.m(ViewGroup, int)` 在创建 viewType 3 的歌词行时，如果 `S <= 0`，取当前歌词 RecyclerView 的宽度，减去新行的内部左右 margin/padding，写入 `S`。新行尚未挂载。
3. `A.k(RecyclerView$D, int)` 绑定歌词行时，通过 `b0 -> n0` 使用 `S`，不会补做初始化。
4. `A.n0(Resources, int)` 在 `S <= 0` 时直接抛出日志中的异常。

因此，创建时 RecyclerView 还没有有效宽度，或者适配器尚未完成创建流程就绑定了复用行，都可能使绑定路径拿到无效的 `S`。复用行绕过 `m` 的路径与该堆栈相符，但没有再次捕获发生异常那一轮的完整状态，不能断言哪一种顺序是本次唯一触发条件。

当前 AM++ 独立右侧歌词的 `onResume` 还会同步调用尺寸刷新；刷新最终使 RecyclerView 装饰失效并重新布局。这是可能放大时序缺口的模块路径。参考分支 `codex/applemusic-7-tablet-glass` 的 `TabletBetaLyricsBridge` 使用 `scheduleMetrics()` 延后刷新。本次恢复这一时序。

进入一次完整播放器后，原生创建路径可能已经把 `S` 设为正数，从而使之后的返回暂时正常。这能解释用户观察，但仍属于代码推断；修补前不复现不能算修补已经奏效。

## 修补边界与模块结构

- `host-applemusic/FragmentKaraokeWidthContract` 从精确 1606 profile 一次性解析适配器、绑定方法、行绑定、itemView 和 flexbox 成员。
- `BetaLyricsPaneRuntime` 只在自己的独立右侧歌词会话中，在原生绑定前检查未初始化的宽度。
- `core/FragmentKaraokeWidthPolicy` 用当前 RecyclerView 宽度减去行内部左右 margin/padding。复用行可能已经挂载，因此遍历在 `holder.itemView` 处停止，避免把播放器、RecyclerView 或窗口边距算作行内边距。
- 已初始化的正宽度保留；未测量、扣除边距后非正数或溢出的结果不写入。没有吞掉原生异常、分配假行或使用固定的一像素替代值。
- 右侧歌词恢复时改用已有的可取消、可合并的 posted 刷新；会话关闭时删除回调。

保留重构后的 `host-applemusic / core / host-api / app / glass` 边界。没有迁回参考分支的旧启动或配置架构，也没有改变已验收的封面、玻璃、SONG/QUEUE 或歌词布局几何。配置 schema 15、签名和版本支持范围保持一致。

## 验证与限制

- 全模块 955 项测试，失败/错误/跳过均为 0，包含 4 项新增宽度策略回归；部分未变化任务使用 Gradle 已有结果。
- Debug Lint（app、glass、host-applemusic）、Release vital Lint、Release 构建通过。
- 原始 APKS 的 261 项签名/字段/资源契约通过。
- 架构、5 个精确 profile/316 个冻结目标、32 个渲染参考文件及 2 个声明补丁校验通过。

本次提交是有日志和 DEX 支持的针对性修补。由于 v6 在修补前已不再复现，尚不能证明它消除了用户最初的稳定触发；需要用 v7 在原来的后台返回场景继续验收。尤其是未测量宽度为零且没有可用行几何的情况，此修补保留原生行为，需要新的异常现场才能进一步定位。

候选安装包：`.scratch/applemusic-700-beta-analysis/AMpp-1.6.2-112-1606-tablet-resume-v7.apk`。由源码提交 `0822451` 打包，版本 1.6.2/112，9,734,152 字节；本轮仅分析代码并生成候选包，没有安装到设备。

SHA-256：`dddeb723130e292097eb52e15d10ea0240793743efc4d6c18de48b57e3946546`。

APK v2 签名验证通过，沿用 RSA 4096 发布证书，证书 SHA-256：`6ed7800187c3513562334319723498fd01772396b9848d4aeba50622910be0d4`。6 个打包的 profile/index JSON 与源码逐字节一致，Xposed 入口存在。v4/v5/v6 安装包保留。
