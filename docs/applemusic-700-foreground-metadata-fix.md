# 1606 前台恢复 mini 叠层与歌名修正修复

日期：2026-10-03。基线为手机参考迁移 v5（`25db0b6`），继续使用重构后的模块结构。

## mini 原生材质重新出现

用户提供的手机布局截图显示玻璃 mini 下方残留原生胶囊，返回前台后偶发，重载进程恢复。

v5 手机接入只通过 `BlurView.draw` 拦截替换原生材质，没有调用现有 `FragmentPlayerSurfacePort` 的材质就绪接口。Android 恢复绘制时可以复用已经录制的 RenderNode；原生恢复代码也会重新调用 `setAlpha(1)`。这两条路径均可绕过仅靠 draw 拦截的保护，导致原生材质与玻璃同时出现。此为源码支持的原因，当前无设备连接，尚未直接录制用户的触发过程。

修复复用现有宿主透明度和背景所有权：手机会话在每次 pre-draw 和回到前台时同步导航／mini 替换状态；原生后续透明度写入被乘以替换因子，隐藏期间仍保留原值，关闭玻璃时恢复原生最新状态。`FragmentNativeAlphaLease` 从原宿主内部实现等价提取到 `host-api`，增加针对恢复、绕过 setter、原生隐藏和解除所有权的回归测试。

共享手机会话在原生全局布局变化后重新检查 mini，内层 content 更换时也重建材料及形变目标；7.0 材料 island 的原生 player parent 更换时同步迁移。前台恢复会清理缓存再检查层级，避免一直操作已经被替换的 View。

## 歌名修正初始化中断

此前 1606 实测日志已出现 `LOCAL_MEDIA_PLAYER_INDEX_CHANGED unresolved`。严格 profile 没有这个必需的回调，`ApplePlaybackMetadataHooks.installHooks()` 因此抛出异常，后续内容 getter 和界面元数据桥没有完成初始化。歌曲状态契约也为空，即使只填换歌回调，当前歌曲 ID 的读取仍会失败。

原包 smali 确认：

| 入口 | 完整契约与语义 |
|---|---|
| `LocalMediaPlayerController.onPlaybackIndexChanged` | `(MediaPlayer,int,int)void`；读取旧／新队列项并发送原生0x16事件 |
| `LocalMediaPlayerController.onPlaybackStateChanged` | `(MediaPlayer,int,int)void`；发送原生0x13状态事件 |
| `MediaPlayer.getCurrentItem` | 返回 `PlayerQueueItem` |
| `PlayerQueueItem.getItem/getPlaybackQueueId` | 返回 `PlayerMediaItem/long` |
| `PlayerMediaItem` 六个 getter | 标题、艺人、曲风、时长、订阅商店 ID、persistent ID |

这些目标写入精确 1606 profile，保留未知版本拒绝和歧义拒绝。静态验证增加十一项原包断言，共253项通过。完整 playback bootstrap 先解析元数据／换歌／状态回调与九个 getter 名，再注册；原生恢复播放仅发送状态事件时，也能建立当前播放器和歌曲身份。既有 CN／JP／原名解析、缓存和界面应用流程继续复用。

静态 profile 检查现已要求已启用 Fragment profile 提供完整元数据 bootstrap 和九个 getter，防止空目标再次漏过检查。旧版本冻结目标及候选顺序未改动。

## 验证范围

新增五项原生材质透明度回归、四项 1606 metadata bootstrap／换歌读取测试。当前完整测试、Debug Lint、Release vital Lint 和构建通过；原包253项契约、profile 冻结、模块架构、渲染器来源校验通过。

当前 ADB 未连接设备，前台恢复视觉效果和实际地区查询结果待用户测试。建议在同一首歌下连续切出／切回，展开／收起后重复，再检查歌名修正各模式及快速换歌。已有平板双栏、歌词、封面、队列和原生底部渐变逻辑保留。

实现提交：`fc5c086`。最终951项测试通过（core278、app240、glass22、host-api38、host-applemusic371、hook-runtime2），失败／错误／跳过均为0。最终 Debug Lint、Release vital Lint 和 Release 构建通过。原包253项契约、5个精确 profile／316个目标冻结检查、模块架构及32个原始渲染文件＋2个声明补丁的哈希校验通过。

签名 APK：`.scratch/applemusic-700-beta-analysis/AMpp-1.6.2-112-1606-foreground-metadata-v6.apk`，9,717,560字节，SHA-256：`d90d6359b171b0b49dfdfc43f731dfbf2fc43f30c12508e52c79d0c594bb9e7d`。APK v2／RSA4096签名有效，证书仍为`6ed7800187c3513562334319723498fd01772396b9848d4aeba50622910be0d4`。六个打包 profile/index JSON 与源码逐字节相同，新 metadata bootstrap 和 Xposed 入口均存在。schema15及资产格式不变，旧 v4/v5 安装包保留。

覆盖安装后需要彻底停止 Apple Music 再打开，以加载新的进程 Hook；当前未进行设备安装或验收。
