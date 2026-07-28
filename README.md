# AM++

AM++ 是面向 libxposed API 102 的 Apple Music 增强模块。目前主要针对 Apple Music `6.5.0 (1580)` 开发并完成真机验证。

## 功能

| 功能 | 默认状态 | 生效范围 | 稳定性 |
| --- | --- | --- | --- |
| 平板双栏播放器 | 开启 | Apple Music 官方 `is_tablet=true` 且横屏 | 可用 |
| 平板动态视频抑制 | 开启 | 平板横屏的 Editorial Video | 可用 |
| 未来歌词模糊 | 开启 | Android 12 及以上 | 可用 |
| 手机液态玻璃底栏 | 关闭 | Apple Music 官方 `is_tablet=false` | **WIP** |

手机液态玻璃会为底部导航栏和迷你播放器增加实时背景模糊、半透明材质与选中项胶囊。该功能仍可能在冷启动或全屏播放器收回时出现单帧闪烁，因此默认关闭并统一标记为 WIP。

未来歌词模糊方案移植并适配自 [a23bc/amlyricblur](https://github.com/a23bc/amlyricblur)，参考提交为 [`3417e217d7692ae742bbae80d2bd51aadffcd59e`](https://github.com/a23bc/amlyricblur/commit/3417e217d7692ae742bbae80d2bd51aadffcd59e)。本项目保留了其核心歌词行识别、滚动期间清除模糊、按高亮位置重新计算以及模糊动画方案，并针对当前 libxposed API 102 模块结构进行了接入适配。原项目采用 MIT License，完整版权与许可文本见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 液态玻璃演示（WIP）

<p align="center">
  <img src="docs/images/liquid-glass-demo.jpg" alt="AM++ 手机液态玻璃底栏演示效果" width="420">
</p>

> [!WARNING]
> 当前液态玻璃功能是完整意义上的半成品，不代表最终质量，也不建议把它当作稳定功能使用。现阶段仍存在冷启动或播放器收回时偶发单帧闪烁、不同设备和系统版本表现不一致、生命周期处理不完整等问题，因此默认关闭。

非常欢迎大家提交 Issue 和 Pull Request，共同完善液态玻璃功能。尤其欢迎针对以下方向的贡献：

- 消除冷启动、播放器展开和收回过程中的底部闪烁；
- 改善 BlurView/RenderNode 的生命周期、背景采样和性能；
- 调整材质、描边、阴影、选中胶囊和动画效果；
- 补充不同 Android 版本、屏幕尺寸和 ROM 的兼容性验证；
- 为关键行为增加可重复的自动化或真机回归检查。

提交 PR 前请至少运行 `test`、`lintVitalRelease` 和 `assembleRelease`，并在涉及界面行为时附上设备、系统版本和验证截图或录屏。

## 环境要求

- Android 8.0（API 26）或更高版本；
- 实际支持 libxposed API 102 和 remote preferences 的 Xposed 框架；
- Apple Music。当前验证版本为 `6.5.0 (1580)`，其他版本不保证兼容。

旧版 LSPosed 1.9.x 的 API 100 运行时无法加载本模块。判断兼容性时应以框架核心报告的 API 版本为准，而不是只看 Manager 应用版本。

## 安装

1. 安装 release APK。
2. 在 LSPosed 中启用 **AM++**。
3. 仅选择 Apple Music（`com.apple.android.music`）作为作用域。
4. 强制停止并重新打开 Apple Music。
5. 从桌面打开模块设置页，根据需要调整功能。

设置修改后需要强制停止并重新打开 Apple Music。设置页显示“已连接 LSPosed API 102”后才能写入框架托管的 remote preferences。

## 从源码构建

需要 JDK 17、Android SDK 37 和 Build Tools 37.0.0。项目自带 Gradle Wrapper：

```powershell
.\gradlew.bat test lintVitalRelease assembleRelease
```

Linux 或 macOS：

```bash
chmod +x gradlew
./gradlew test lintVitalRelease assembleRelease
```

生成的 APK 位于：

```text
app/build/outputs/apk/release/app-release.apk
```

默认 release 构建使用 Android debug 证书，仅适合本地测试。正式发布前请在 `app/build.gradle.kts` 中配置自己的稳定签名；更换签名后，已安装的不同签名版本需要先卸载。

## 实现说明

- 模块入口使用 libxposed `XposedModule/onPackageReady`。
- 配置由 `libxposed/service` 写入 remote preferences，Apple Music 注入进程只读。
- API 102 不再提供旧式资源 Hook，因此布局接入通过受限的 `LayoutInflater.inflate` 拦截和布局根节点识别完成。
- 各项功能独立定位、独立降级；某一组 Hook 定位失败时不应阻止其他功能加载。
- 平板判定直接读取 Apple Music 的 `bool/is_tablet`，不使用模块自定义屏幕宽度阈值。
- 未来歌词模糊核心来自 [a23bc/amlyricblur](https://github.com/a23bc/amlyricblur)，模块只负责版本定位、配置开关和 libxposed API 102 接入。

## 隐私与安全

模块不请求网络、存储或通知权限，也不包含分析服务。设置保存在 Xposed 框架管理的 remote preferences 中；模块不自行暴露配置 Provider、广播接收器或跨应用写入接口。`libxposed/service` 依赖会注册其框架连接所需的 `XposedProvider`。

## 项目结构

```text
app/src/main/java/      模块入口、配置与功能实现
app/src/main/resources/ libxposed 模块元数据和作用域
app/src/test/           JVM 与结构回归测试
docs/adr/               当前架构决策记录
scripts/                可选的真机回归与录屏分析脚本
```

`scripts/` 中的真机脚本需要 ADB，部分液态玻璃录屏检查还需要 root、Python、OpenCV，并针对 1080 × 2376 的验证设备坐标编写。运行前通过 `-Serial`/`-Device` 参数或 `ANDROID_SERIAL` 环境变量指定设备。

## 已知限制

- Apple Music 内部类和资源会随版本混淆、调整，升级 Apple Music 后可能需要重新适配。
- 手机液态玻璃为 WIP，不应视为稳定功能。
- 功能开关不会热卸载已安装到当前 Apple Music Activity 的界面修改；修改后应重启目标应用。

第三方代码与依赖许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 开源许可

本项目以 [MIT License](LICENSE) 开源。第三方代码与依赖仍分别遵循其原始许可。
