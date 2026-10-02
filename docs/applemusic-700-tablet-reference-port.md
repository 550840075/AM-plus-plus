# 1606 平板参考实现迁移与验收

2026-10-02。参考分支 `codex/applemusic-7-tablet-glass / 595581b`；迁移提交 `c619a7a`。用户最终反馈：“没问题了，我试过了”。保留当前模块结构、配置 schema15、资产格式和原生设置页入口。

## 实现对应

| 参考实现 | 当前实现与职责 |
|---|---|
| TabletBetaGlassHost | app 的 FragmentTabletGlassSession：完整挂载、原生 z/插入顺序、预绘制就绪、采样交接、导航触摸转发、mini 可见性与渐隐 |
| TabletBetaGlassLayer/Press/Policy | app 的 FragmentTabletGlassLayer/Press、host-api 的 FragmentTabletGlassPolicy：独立测量、原生手势取消、坐标与可见性策略 |
| TabletBetaNavigationBridge | host-applemusic 的 FragmentChromeNavigation/Contract：M/J4.s1.a(MenuItem) 原生菜单动作、原生标签和库标题、抽屉动作 |
| NativeLayerAlpha/原生属性控制 | host-applemusic 的 FragmentTabletChromeBinding，经 FragmentTabletChromePort 暴露语义操作；INVISIBLE 导航、背景/运动层、祖先裁切和 mini 形变 |
| TabletBetaDualPaneSession/Runtime | host-applemusic 的 FragmentTabletDualPaneSession/Coordinator，经 DualPaneTarget 装配：参考容器、原生 SONG/QUEUE、原生 LYRICS 标签及 r1 绑定、提交与恢复 |
| 原生 d/e 与 G0.P hook | host-applemusic 的 Coordinator：区分展开/收起缩放和播放/暂停缩放，保留 native cache 与封面轨迹 |
| TabletBetaPlayerPolicy/CoverScaleState | core 的 FragmentTabletArtworkPolicy/CoverScaleState：相同封面帧与暂停/seek 规则，迁移参考行为测试 |
| GlassNavigation/NativeLiquidButton/Shader | 继续使用已迁移的共享渲染器和已声明 shader 补丁 |

初次 v4 只迁移玻璃会话和封面桥，仍混用原双栏协调器，造成歌词与队列回归；该临时包不作为最终交付。完整迁移后使用原生歌词标签并在提交后调用原生 r1，右侧歌词实际恢复；队列在左栏原生切换，右侧歌词保留。旧版模块标签恢复时只移除所属 Fragment，歌词资产不变。

保留的结构约束：宿主类、资源和反射仅在 host-applemusic；接口不暴露宿主 Compose/反射成员；纯策略在 core；app 负责模块会话和渲染。参考分支的独立设置/配置及侧栏 AM++ 入口没有迁移。固定右栏 ID 在 onCreateView.prepare 中先于子 Fragment view restore 创建；原生播放器使用 FrameLayout.LayoutParams（MarginLayoutParams 子类），维持启动修复要求。

## 验证与交付

- 自动验证：939 项，0 失败/错误/跳过；core278、app240、glass22、host-api30、host-applemusic367、hook-runtime2。Debug Lint、Release vital Lint、Release 构建通过。
- 静态验证：237 个 1606 原生检查；5 个精确 profile/314 个目标；模块边界与 32 个原始渲染文件/2 个声明补丁通过。Activity.dispatchTouchEvent 属于继承的 Android framework 方法，按当前 Activity 身份过滤，不错误登记为 APK 自己声明的成员。
- OPD2413 / Android16/API36 / 横屏3392×2400：完整包冷启动2405ms，PID19585；实际截图右侧英文及中文歌词显示，原生 QUEUE 在左、右歌词保持，返回 SONG 与 mini。用户确认当前版本无问题。未清除数据或修改队列项目。
- 录屏工具在设备的 /sdcard 与 /data/local/tmp 写入均被拒绝，未据此宣称帧耗时或完整性能矩阵通过。分屏/字体缩放/旧版本/手机等广泛矩阵仍按主适配文档待验收。

最终包为用户已验证、设备已安装的同一文件：

`.scratch/applemusic-700-beta-analysis/AMpp-1.6.2-112-1606-reference-full-v4.apk`

大小 **9,716,716 bytes**。SHA-256：`62a4fb6bf00693bfb17cb381fea2bceaefaeabbb20d2555a8e59eb9330d60672`。APK v2 签名通过，证书 SHA-256：`6ed7800187c3513562334319723498fd01772396b9848d4aeba50622910be0d4`。6 个 profile/index 打包字节与源码一致，入口存在，research 未打包。

本次平板参考迁移验收完成。原适配任务中已单独记录的元数据 bootstrap DEGRADED 仍需另行处理；本记录不把它或全设备矩阵标为通过。
