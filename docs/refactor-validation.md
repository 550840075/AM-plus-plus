# 重构验证与剩余验收

日期：2026-10-02。基线：`97c4b69`，分支：`codex/Refactor`。所有命令在本地离线依赖环境运行。未安装新版宿主或模块到设备，未改配置 schema/用户资产。

## 已执行

| 检查 | 结果与范围 |
|---|---|
| 全模块 `gradlew test` | 835 项全部通过：core 267、app 239、glass 14、host-applemusic 313、hook-runtime 2；保留基线 824 项，新增 11 项 |
| app / glass Debug Lint、app Release vital Lint | 通过；未升级依赖、工具链或 APK 版本 |
| host-applemusic Debug Lint | 通过 |
| Debug / Release APK | 构建成功；Release 沿用当前项目签名配置 |
| JSON profile 与冻结快照 | 四个 tuple，249 个原目标、索引映射及候选顺序一致；Chrome/设置/布局变体新增资料也在统一 profile 中 |
| 架构规则 | core 无 Android/宿主依赖；依赖无反向或循环；API 不导入反射/宿主 AndroidX；功能和 UI 不发现宿主成员；UI 不导入网络客户端 |
| 玻璃参考源码 | 33 个原 Backdrop 文件与固定上游一致，1 份已声明补丁 hash 一致 |
| 6.5.3/1599 宿主静态校验 | 本地 `.scratch/decompile-1599/com.apple.android.music.apk`，51 项，0 失败；二进制 Manifest 精确为 6.5.3/1599；包含玻璃入口 |
| APK 内容/签名 | 四份生产/参考 profile 随 APK 打包，HookEntry 元数据和许可资产存在；research 夹具未打包；Release v2 签名通过，证书为当前 AM Plus Plus Release |
| 版本伪装负例 | beta APKS 声称 6.5.3/1599 被二进制 Manifest 校验拒绝（实际 7.0.0-beta/1606） |

安装报告与实际就绪分离：字体后台完成后更新健康状态；玻璃实际挂载后报告 ACTIVE。原生注册失败时逻辑作用域 close，旧回调透传。歌词 pointer 的地址/sections/Adam ID 检查及原生 I2/后台重新应用测试保留。

## 尚未执行，不能视为通过

- 6.5.1/1583 和 6.5.2/1586 的当前安装包静态重验：本次工作区缺少这两个包。JSON 冻结映射与原单元夹具已验证，不能替代实际包取证。
- 三个旧版本的真机 UI/生命周期/SAF/歌词/字体/双栏/玻璃/蜂窝/DPI 组合验收：`adb devices -l` 当前没有设备。
- 同设备、同宿主、同配置下基线与重构 APK 各至少五次冷启动/拖动帧时间对比，以及旋转/页面重建后的任务和监听数量：需要连接设备。
- 两处既有 1599 降级修复：独立状态见 `refactor-degradations.md`。
- 7.x 正式支持：仅完成接口、Fragment 会话实现边界、beta 研究夹具和测试；1606/未知正式版没有生产资格。

## 真机执行顺序

1. 保存设备型号/API、宿主 binary Manifest/SHA、模块 SHA、设置和歌词/字体备份。保持账户及用户数据，使用现有设备校验脚本与人工录屏。
2. 1583 验收默认设置和现有能力，玻璃/蜂窝明确跳过；1586/1599 验收单开关和组合。包括 SONG/QUEUE、独立右栏歌词、歌词滚动恢复/CJK、导入字体/TTML、禁用 ID、备份冲突、标题各展示表面、导航选择/重选、mini 按压拖动取消、DPI/旋转/重建/后台返回。
3. 比较相同场景的基线与重构，每个性能场景至少五次；持续超过 10% 的退化先定位解决。检查多次重建后会话、订阅、View 和任务是否累积。
4. 分开登记基线既有降级和新退化。新增退化停止后续适配并回退对应提交；数据格式不变，无需反向迁移。

因此，本次自动验证可以支持继续开发和真机回归，尚不能作“旧版功能已全部真机保全”或“整个发布验收完成”的声明。

最终 Release APK：`app/build/outputs/apk/release/app-release.apk`，9,557,932 bytes；SHA-256：`7d034cbac0467ba890e1748e1ec791d23daeade4d62eff67a3d8413b56f5c2f4`。Xposed 入口仍为 `dev.amenhancer.module.hook.HookEntry`，生产 index 顺序仍为 1599 → 1586 → 1583 → 1580 参考。
