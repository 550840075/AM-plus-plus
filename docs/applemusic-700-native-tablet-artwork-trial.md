# 1606 平板原生封面动画与动态封面试用

## 本次变化

按用户 2026-10-03 的要求，7.0.0-beta/1606 平板双栏改用 Apple Music 原生 mini 到播放器的封面动画，并保留原生视频动态封面。重构后的模块结构、双栏歌词、原生 SONG/QUEUE、静态封面布局和玻璃渐隐继续保留。

原包已确认 `PlayerMainFragment$i.d(float)` 读取 mini 与当前封面的尺寸，通过 `offsetDescendantRectToMyCoords` 计算相对坐标，写入缩放、位移、圆角和视频 TextureView 矩阵。原生 `f(View)` 保存休止缩放/圆角，`e()` 恢复并清理缓存。代码具备基于实际 View 位置计算动画的路径；是否在当前模块双栏布局中表现完整，需要真机验收。

## 动画接管移除

- `FragmentTabletDualPaneSession` 不再计算或写入完整封面每帧的 scaleX/scaleY/translationX/translationY。
- 移除模块对原生缩放缓存 b/c 的写入、封面所有权表及播放状态目标表。
- 移除 `View.setScaleX/setScaleY` 拦截、`G0.P` 播放/暂停动画拦截和原生 `e()` 前后接管。
- 只在原生 `d(float)` 完成后观察进度，用于独立右侧歌词和玻璃材质显隐；不替换原生动画结果或参数。
- 通过原生 `f1(PlayerMainFragment):View` 获取 SONG/QUEUE 当前封面，只检查挂载和测量完成状态，供玻璃层决定内容可见性。
- 静态双栏布局仍由模块调整；退出双栏恢复布局后调用原生 e/d 重新应用原生状态，不直接设置完整封面变换。

历史参考插值纯函数和冻结用例继续保留作回归资料，1606 生产会话不再调用它们。它们通过测试不等于新原生动画已通过真机视觉验收。

## 视频动态封面

此前 `AppleMusicEditorialVideoTarget` 会在官方平板横屏时把 Editorial Video URL 置空，7.0 双栏也沿用了这个限制。

现在按精确 profile 的页面族分派：

- 7.0 `fragment-content` 使用 `FragmentEditorialVideoTarget`，保留原生 URL、资源选择、播放条件、视频 Surface 和生命周期，不安装 URL 抑制 Hook。
- 6.5.x `legacy-activity` 继续使用原有抑制逻辑、静态预览和独立 Music Video 路径。
- 未确认的页面族拒绝分派。

7.0 设置中的双栏说明更新为“保留原生动态封面”。没有新增配置开关、修改 schema 15、升级依赖或工具链。

## 自动验证

- 972 项测试，失败/错误/跳过均为 0。其中新增 4 项验证原生静态封面选择器的完整签名，3 项验证 Editorial 页面族分派与旧版保留。
- app/glass/host-applemusic Debug Lint、Release vital Lint、Release 构建通过；部分未变化任务使用 Gradle 已有结果。
- 原始 APKS 262 项契约检查通过，新增原生 f1 精确签名。
- 5 个精确 profile/316 个冻结目标、模块架构、32 个渲染参考文件及 2 个声明补丁校验通过。

目前只有手机连接 ADB，没有平板上的动画或视频播放验收。本次交付为试用包，不能写成原生双栏视频已实测通过。

## 平板验收

选择有原生动态封面的歌曲，检查：播放和暂停；mini 展开/收起及中途反向拖动；完整封面、圆角与视频 Surface 连续性；SONG/QUEUE 切换与右侧歌词；横竖屏、分屏和后台返回；关闭双栏后的原生恢复。同时核对玻璃大面积渐隐和 v8 设置返回交接。

候选包：`.scratch/applemusic-700-beta-analysis/AMpp-1.6.2-112-1606-native-tablet-artwork-v9.apk`。包含此前 v7/v8 修补，v8 APK 保留可作对照。最终包哈希和签名信息在打包后补充。
