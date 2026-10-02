# Apple Music 1606 设置与后台返回时的玻璃交接

## 问题与原因

用户报告手机、平板从 Apple Music 原生设置返回主页时，先出现原版导航/mini，然后恢复液态玻璃；长时间后台返回也会有相同现象。

进程存活时，`FragmentChromeFactory` 的 Hook 注册保持有效，进入设置或后台不会主动卸载注册。7.0 改成 Fragment 宿主后，主页 View 的生命周期与旧版 Activity 宿主不同。原包 `MusicContentFragment.onDestroyView()` 会释放页面回调并清空原生导航/mini 高度等状态，重建的 View 不能继续使用旧的玻璃会话。系统若杀掉整个进程，则需要重新注入和注册，这是另一种正常的冷启动情况。

当前适配存在两个与用户现象吻合的代码缺口：

1. 新 root 的绑定通过 posted 任务进行。手机和平板的玻璃还要等待布局、原生菜单、Compose 和背景采样就绪。在这个阶段，原来的 pre-draw 路径允许原生界面先显示，随后才由玻璃接管。
2. `FragmentSurfaceBinding` 在 View 脱离窗口时独立关闭自己，上层 Factory 却仍按 root 身份缓存它。临时脱离再重挂同一 View 时，上层可能把关闭的绑定当作有效绑定，渲染会话和原生属性所有权也没有一起撤销。

旧版 `PhoneGlassRuntime` 以 Activity 为会话所有者，通常只在 Activity 真正销毁时关闭会话；设置返回可以复用已有玻璃。7.0 的恢复需要正确处理 View 替换和临时重挂。

## 修补

- `core/FragmentViewSessions` 区分准备、挂载、临时脱离和真正销毁。重复创建幂等，旧 root 的异步挂载/脱离不会影响新 root，创建失败不发布部分绑定。
- `host-applemusic/FragmentChromeFactory` 成为挂载监听的唯一所有者。临时脱离时同步撤销模块渲染会话和原生属性绑定，保留当前 root 和菜单动作以便重挂；真正销毁时清理 root、监听和动作。
- 尚未完成挂载的 root 脱离或销毁，也会通知上层清理它的等待状态。
- `host-api/FragmentPlayerSurfaceObserver.onPreparing` 在新 View 首次绘制前通知模块，包括同一 View 重新挂载的场景。接口没有混淆成员或宿主 Compose 类型。
- `app/FragmentGlassFirstDraw` 让布局、Compose 和采样继续执行，同时暂缓暴露尚未接管的主页帧。玻璃 ready 后放行；关闭开关、挂载失败或销毁立即放行；等待最多 750ms，超时后恢复原生绘制并移除监听。
- ready 回调绑定当次等待对象，旧会话的 ready 不会关闭新会话的等待。

背景采样本来就在 pre-draw 中通过 `source.draw()` 录制到模块 RenderNode，不依赖先把原版显示到屏幕，因此不会因为这段交接等待而无法首次采样。

保留重构后的模块边界、参考分支的渲染/交互、原生抽屉、封面暂停缩放和双栏布局。本次没有改手机封面控件或调查已经停止的封面问题，也没有修改配置 schema 15、依赖、工具链或版本支持范围。

## 验证与限制

- 全模块 965 项测试，失败/错误/跳过均为 0。新增 10 项覆盖脱离后重绑、重复创建、旧 root 回调、部分失败、销毁、异常清理、就绪交接、超时和关闭回退。
- app/glass/host-applemusic Debug Lint、Release vital Lint、Release 构建通过。
- 原始 1606 APKS 的 261 项契约校验通过。
- 架构、5 个精确 profile/316 个冻结目标、32 个渲染参考文件及 2 个声明补丁校验通过。
- 部分未变化的 Gradle 任务使用已有结果。

当前 ADB 没有设备，尚未确认真机上两种返回场景的闪回已消失。750ms 是避免等待无限持续的上限；如果某台设备的布局或渲染初始化超过该上限，会主动允许原生绘制。这需要实际画面和时间记录判断是否仍有降级。

验收重点：手机和平板原生设置返回、连续快速进出设置、短/长后台返回、同 root 重挂与 Activity/Fragment 重建；确认菜单和 mini 控件仍可操作、播放器展开/收起正常、关闭玻璃后原生恢复、无监听与会话累积。系统杀进程后的返回应与进程仍存活的恢复分别记录。

候选包：`.scratch/applemusic-700-beta-analysis/AMpp-1.6.2-112-1606-glass-return-v8.apk`，源码提交 `6fa5992`，版本 1.6.2/112，9,734,152 字节。本轮没有安装到设备。

SHA-256：`4323ccd58d0b7d08a08657bcbdfebbdd162b6dd0fc4510cbae41322c6d6535cf`。

APK v2 签名验证通过，沿用 RSA 4096 发布证书，证书 SHA-256：`6ed7800187c3513562334319723498fd01772396b9848d4aeba50622910be0d4`。6 个打包的 profile/index JSON 与源码逐字节一致，Xposed 入口和 1.6.2/112 版本信息验证通过。APK DEX 含新 `FragmentGlassFirstDraw / FragmentViewSessions / GlassFirstFrame`，与 v7 对照确认新增代码已打包。

此包包含此前 v7 的歌词宽度修补，v4/v5/v6/v7 安装包保留。安装后需要强制停止并重开 Apple Music，才能使现有进程加载新模块代码。
