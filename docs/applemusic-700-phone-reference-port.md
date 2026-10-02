# Apple Music 7.0/1606 手机液态玻璃参考迁移

本次按用户要求，以 `codex/applemusic-7-tablet-glass` / `595581b` 的手机实现为行为来源。保留 `codex/applemusic-700-adaptation` 当前模块结构，不合并旧分支的 HookEntry、实验配置、独立设置入口或反射工具。

## 实现对应

| 参考实现 | 当前实现 | 保留行为 |
|---|---|---|
| `BetaPhoneGlassSession` | `app/FragmentPhoneGlassSession` | 继承完整共享手机会话，不再使用独立 Fragment 几何实现 |
| `PhoneGlassSession` | `app/PhoneGlassSession` | 原有导航透镜、mini 内容形变、背景采样、材料展开与渐隐、原生播放器层交接 |
| `BetaPhoneGlassPolicy` | `host-api/FragmentPhoneGlassPolicy` | 原生抽屉资格检查；`(1-exp(-20*p))*extent` 底栏退出位置 |
| 资源、Fragment/Behavior 反射与导航调用 | `host-applemusic/FragmentPhoneChromeBinding` | 当前精确 1606 profile、缓存成员、原生 Menu 选择与重选、完整 native mini 控件 |
| 原生 alpha/padding/peek/BlurView hooks | `host-applemusic/FragmentChromeFactory` 和语义回调 | 按 Fragment root 和原生 Behavior 身份隔离；会话结束解除回调并恢复属性 |
| `PhoneMaterialIsland` | `app/FragmentPhoneMaterialIsland` | mini 材料在原生 `player_root` 内 index0，隔离玻璃重测，不插入受限 FragmentContainerView |

共享会话在重构时去掉的扩展接口已恢复，宿主绑定通过构造参数注入。6.5.x 继续使用原绑定。旧独立 `FragmentGlassSession` 不再被生产路由选择。

手机胶囊为 56dp 导航、43dp mini、16dp 两侧留白、8dp 两行间距，底部抬升和模糊仍读取当前 AM++ 配置。mini 从原生折叠位置展开：0～35% 材料展开、35～60% 渐隐并隐藏、60～85% 交接 motion 背景；回拉使用同一流程。按参考实现锁定折叠时的 mini 可见状态，避免原生 mini 提前隐藏让玻璃消失。

7.0 特有的 mini 内容背景、elevation、下一首内边距、按钮容器方向和 shadow 偏移由宿主适配器处理。关闭时使用当前重构的属性所有权规则，保留宿主后续写入。

## 平板底部渐变

所有平板会话都不再创建 AM++ `BottomScrim`。共享旧平板会话显式禁用该层；7.0 已验收的独立顶部导航和 mini 会话继续保持不创建该层。1606 的原生 `top_and_bottom_gradients` ComposeView 保留，既不隐藏也不重写。

本次不改变此前用户验收的 7.0 平板双栏、歌词、封面、SONG/QUEUE 切换及玻璃手势。

## 验证与验收范围

- 原 APKS 的 base 二进制 Manifest 和全部 DEX splits 校验：242 项通过，包括原生导航方法、Behavior `F/L` 和 `k0/e/f/p0` 字段类型。
- 原参考手机的资格、固定退出位置和中途回拉三项行为测试迁移至 `host-api`。
- 当前模块依赖方向、语义接口边界、旧冻结 profile 与渲染器来源校验保留。
- 当前 ADB 未连接设备，手机触摸、旋转、前后台、真实展开动画和平板底部效果仍待真机验收。自动验证不等同于视觉验收。

安装后建议检查：有／无 mini 的底栏位置；导航点击、重选和长按横滑；mini 播放／下一首和按压；展开／收起及中途反向；深浅色、横竖屏和关闭玻璃后的原生恢复。平板检查原生底部渐变只出现一次、双栏歌词和 QUEUE 仍正常。

最终实现提交：`25db0b6`。942 项测试通过（失败／错误／跳过均为0），app／glass／host-applemusic Debug Lint、Release vital Lint 和 Release 构建通过；242 项原包契约及20项手机资源绑定通过。六个打包 profile/index JSON 与源码逐字节一致，Xposed 入口存在；APK v2 签名有效、仍使用原 RSA4096 发布证书。

签名安装包：`.scratch/applemusic-700-beta-analysis/AMpp-1.6.2-112-1606-phone-reference-v5.apk`，9,717,228 字节，SHA-256：`d4e4faecefe21b008640806e70d3ae497f88924f090cd36a3931a2cd8e3b0655`。版本继续为1.6.2/112，配置 schema15 与资产格式不变。此前已验收平板 v4 APK 保留不覆盖。原完整适配的元数据启动契约缺口及其他设备矩阵继续单独记录。
