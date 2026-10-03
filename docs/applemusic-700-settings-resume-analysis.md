# 7.0 设置页后台恢复黑屏分析

目标：Apple Music 7.0.0-beta / 1606。现象为长时间后台后进入原生设置页，出现持续黑屏，重启应用恢复。用户进一步说明是界面停止渲染的卡死黑屏，并非绘制了一张黑色界面。最初仅静态分析；随后已采集手机现场、线程堆栈和系统 ANR。下文区分初始假设、运行时证据和针对已发现风险的修正。

## 2026-10-03 手机现场与窗口恢复

设备 PJD110 / Android 16，进程 24471，安装包 SHA-256 与 20% 首轮候选 `84e65be104e17cab858647427d165eb5b505c776fa0c9392d0d99c03b1d7ced2` 完全一致。

- SettingsFragment 已创建并恢复到 RESUMED，View 存在且尺寸正常。进入设置页时 MusicContentFragment 的 View 已销毁，当前层级中没有模块玻璃视图。
- 两次本地线程快照显示主线程位于 MessageQueue / epoll；系统读取 Activity 状态能返回。没有发现 Java FATAL、模块设置回调的锁等待或玻璃采样超时记录。
- 19:15:00 系统报告 `Input dispatching timed out (Application does not have a focused window)`。WindowManager 的 ANR 快照显示 `currentFocus=null`、AM 主窗口 `mViewVisibility=0x4`、`HAS_DRAWN`、Surface 未显示。随后系统以用户关闭 ANR 为由结束进程。它证明窗口被隐藏 / 没有输入焦点，而非证明主线程死锁。
- 已保存系统退出存档中的 ANR Java 堆栈及窗口快照。重新启动同一候选后，主页、原生设置页和 AM++ 入口正常。
- 用户补充：后台约几分钟，中间打开过 AM++ 设置页；关闭 AM++ 后暂未复现。一次未复现不能证明根因，也不能据此归为宿主原生问题。

代码检查发现 `PhoneGlassSession.allowGlassOverflow` 为了允许阴影溢出，会沿祖先链一直到 DecorView 调用 `save`。该 `NativeViewState` 保存 / 恢复的是完整属性组，包括 visibility、alpha、尺寸和边距，但溢出功能实际上只修改 clipChildren 和 clipToPadding。`attachAvailableViews` 在下一次原生属性观察前执行 `captureOwned`，可以把后台恢复产生的原生属性变化记为模块最后写入；如果基线仍是旧的隐藏状态，退出玻璃会把可见性回滚为 INVISIBLE。这是源码能够构造出的风险路径，与 ANR 的隐藏窗口相符，但现场没有抓到具体 setVisibility 调用栈，仍需候选验证。

修正使用独立 `OwnedHostClipping`，祖先链只登记两项裁剪属性，不再放入完整 View 快照。退出先释放功能自身的 View 状态，再释放裁剪租约。背景恢复后最新的窗口可见性、alpha 和布局不属于这个租约，因此不会被它还原。7.0 平板宿主适配器此前已经使用裁剪专属的属性租约；本次不修改该适配器或原生 Compose 设置回调。core 保存纯属性所有权逻辑，app 绑定实际 View，保持现有模块边界。

新增回归覆盖后台恢复改变窗口可见性 / alpha / 尺寸后再退出，以及裁剪被原生接管后的释放与重复关闭。修正后 core 304 项与 app 240 项，共 544 项测试通过，失败 / 错误 / 跳过均为 0；Debug Lint、Release vital Lint、Release 构建及架构 / profile / 玻璃源码检查通过。当前没有连接 ADB，进一步长期真机回归仍待完成。20% 覆盖保持不变。

用户随后试用窗口恢复候选，反馈“暂时没问题了”，并要求提交留档。该反馈作为当前候选的试用结果记录；持续后台、多轮窗口恢复及最终因果确认仍未完成。

PR #78 审查另发现 mini 层级替换时，旧根节点及独有祖先的裁剪租约仍保留到整个会话关闭。现在先恢复旧根节点的普通属性，再按当前导航栏和新 mini 的祖先身份保留有效租约，立即关闭并删除旧分支的租约。共用祖先与同根节点仅替换内容的情况保留原租约。新增回归覆盖共享祖先、节点身份、连续 20 次重建和最终释放。玻璃覆盖最终按用户要求恢复为 40%；前述 20% 指当时的窗口恢复候选。

## 设置入口

`FragmentSettingsRuntime` 仅绑定 SettingsFragment 的 ViewModel，在原生 `getPreferenceItems` 返回后插入一个宿主类型的 AM++ 分类。Fragment / ViewModel 使用弱引用；销毁 View 时撤销会话，旧入口回调失效。构造参数和回调来自宿主类加载器。绑定和转换没有网络、文件读写或等待任务；模块设置界面仅在点击 AM++ 时打开。

1606 DEX 证据：`SettingsViewModel.getPreferenceItems` 读取 `_preferencesStateFlow.value`，值为 null 时立即返回 `ci.w.a` 空列表；有状态时才构造分类和版本行。之前模块仍把 AM++ 分类插入该空列表，改变了原生未就绪结果。现在空列表按原对象透传，后续有原生设置项时再插入入口。增加了初始加载 → 就绪 → 再次未就绪 → 恢复的回归测试，同时保留去重和旧回调撤销测试。

进一步检查 `Rb.k2.a → Rb.Z1.invoke → Rb.e2.invoke → Rb.n2.a`：AM++ 的 action 分支不要求非空偏好状态。因此“提前插入入口”是契约问题，但单凭它不能证明黑屏根因，也没有证据证明是设置入口线程死锁。

## 窗口绘制

`PhoneGlassSession.onPreDraw` 和 `FragmentTabletGlassSession.onPreDraw` 在玻璃可见且背景采样未就绪时返回 false。这会取消所属窗口这一帧的绘制，影响同一 Activity 中的原生设置页。`ViewBackdrop.onPreDraw` 在采样源未附着、尺寸为零等情况下不产生新采样；如果恢复时就绪条件持续不满足，之前的恢复等待没有时间上限。它比入口行更能解释“设置页卡在黑色画面”的表现，但实际触发条件仍未获得运行时证据。

恢复采样等待现在使用 core 的 `GlassCaptureWait`，连续等待达到 750ms 后进入现有玻璃失败恢复路径并允许窗口继续绘制。正常采样完成、玻璃隐藏、进入后台或重建材质时清除计时，下一次恢复重新计时。等待期间请求下一帧，避免停在无后续失效通知的状态。已有首次挂载的 `GlassFirstFrame` 超时机制保持不变。

这项改动限制的是异常采样对原生窗口的影响。用户已报告首轮候选仍然黑屏；后续现场不支持把这次问题归结为采样等待，所以它不是已证实的黑屏修复。超时恢复会记录玻璃降级原因。

## 验证范围

- 测试：原生空列表透传、就绪后唯一入口、恢复后入口身份、ViewModel 切换和旧回调撤销；采样超时、超时不重新开始、成功 / 隐藏后重新计时。
- core JVM 302 项、host-applemusic 385 项、app 240 项，共 927 项，失败 / 错误 / 跳过均为 0。Debug Lint、Release vital Lint、Release 构建和架构 / profile / 玻璃源码校验通过；部分无改动任务使用 Gradle 结果缓存。
- 试装后仍需检查手机和平板：长时间后台 → 原生设置页 → 返回主页；观察是否黑屏、AM++ 入口是否出现、玻璃是否被记录为超时降级。
- 若仍卡住，保留进程并连接 ADB，采集主线程堆栈、Compose / AndroidRuntime 错误和模块恢复日志，以区分宿主设置加载、设置 Hook 与窗口绘制问题。

本次另将手机和平板共享导航 / mini 材质的常驻覆盖调整为 20%；按压高光、模糊、折射和动画不变。仍保持重构后的模块职责。
