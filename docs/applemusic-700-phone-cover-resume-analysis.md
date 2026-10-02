# 1606 手机后台恢复后封面变小：现场分析

## 结论及证据范围

目前更倾向于 Apple Music 7.0 原生封面尺寸恢复不完整。现场已经排除播放器父层被整体缩小，以及手机液态玻璃直接写入全屏封面尺寸/缩放的路径。AM++ 是否间接影响原生第一次测量的时序，仍没有足够证据排除，不能据此断言在完全无模块环境中也必然发生。

用户强调这是偶发问题。因此，临时关闭玻璃并重启一次后正常，也不能证明归因。本轮保留异常页面，没有关闭玻璃、切换页面、播放音乐、重启进程或安装新包。生产代码没有修改。

## 2026-10-03 现场

- 设备：PJD110 / 3b0bd02a，Android 16/API 36。
- Apple Music：7.0.0-beta/1606。
- AM++：1.6.2/112，APK SHA-256 与 v6 `d90d6359b171b0b49dfdfc43f731dfbf2fc43f30c12508e52c79d0c594bb9e7d` 一致；不是 v7。
- 当前进程：PID 32523，暂停的 WALK ON WATER，进度 0:51。
- 应用实际配置：360dp 宽、792dp 高、480dpi、字体缩放 1.0；显示覆盖为 1080×2376/480dpi。
- 异常前的进程在 01:28:44 被系统停止，当前进程在 01:28:46 初始化。当前玻璃在 01:56:58 再次挂载；这说明恢复期间发生了玻璃会话重建，但并不能独立证明它造成了封面问题。

通过 ADB 截图、Apple Music activity 层级，以及系统 `dump-visible-window-views` 的当前进程 View 属性得到：

| 控件 | 当前布局尺寸 | scaleX / scaleY |
|---|---:|---:|
| player_sheet_container | 1080×2376 | 1 / 1 |
| player_root | 1080×2376 | 1 / 1 |
| player_fragments_host | 1080×2328 | 1 / 1 |
| player_container | 1080×2112 | 1 / 1 |
| artwork_container | 936×936 | 1 / 1 |
| fullplayerSongImage | 648×648 | 0.85 / 0.85 |
| artwork_image | 648×648 | 1 / 1 |
| 原生全屏 video_surface | 648×648 | 1 / 1 |
| mini_player_content | 984×129 | 1 / 1 |

封面可见宽度 `648 × 0.85 = 550.8px`，与用户图片和原始截图一致。原生 `G0.P(View, int, boolean)` 在暂停状态 2 使用绝对缩放 `0.85`；寻址时为 `0.95`，正常播放为 `1.0`。现场的 0.85 符合原生暂停语义，额外变小来自 648px 的内部布局尺寸，而非连续重复乘暂停缩放。全屏封面父层的缩放全部为 1。

## 原生尺寸链路

原始 1606 DEX 和 `res/layout/music_player.xml` 显示：

1. `artwork_container` 通过约束形成正方形；内部 `fullplayerSongImage` 是居中的 `wrap_content` Card，图像与视频子控件使用匹配父级尺寸。
2. `PlayerSongViewFragment.h0:Size` 保存封面目标区域。`b5.f` 取已经测量的容器尺寸；若尚未有效测量，注册 `L0.onGlobalLayout()`。
3. `L0` 一旦见到正宽高就移除自己的监听，保存那一轮尺寸到 `h0`，后续区域变大不会由这个监听自动更新。
4. `PlayerSongViewFragment.p1()` 返回 `h0`。基础 fragment `l.M1()` 使用它，通过 `l.E1(boolean, Size)` 改变全屏图像和视频的 LayoutParams；Card 随子控件测量。
5. `l.E1()` 在入口先比较上次的模式 `L` 和目标尺寸 `Q`；如果相同就直接返回，实际子控件宽高检查在这个返回之后。因此，缓存目标尺寸和实际控件失配时，重复相同请求不会修复它。

以上提供两种代码上可行的路径：首次正尺寸被缓存成较小区域、后来容器扩大；或者尺寸动画/控件恢复后实际尺寸与已缓存目标不一致。现场没有读取到 `h0/Q` 的值，也没有捕获发生变小那一刻的写入堆栈，因此暂时不能在两者之间作唯一选择。

进一步核对发现 `l.onPause()` 会清理视频 Surface 并通过 `i1.e()` 清除 View layer；没有直接取消尺寸 AnimatorSet `V`。`E1` 内的取消发生在目标切换路径，不能把“后台直接取消尺寸动画”当成已确认原因。

## AM++ 修改边界

- `FragmentPhoneGlassSession` 复用 `PhoneGlassSession`，尺寸及按压形变写入的是 `mini_player_content` 和其按钮、封面缩略图。
- 手机会话还调整导航容器、玻璃材质 sibling、原生层透明度、底部留白和 BottomSheet peek；这些是可能影响首次布局时序的间接接入点。
- 手机接入没有获取或直接修改 `fullplayerSongImage`、`artwork_image` 的布局尺寸或缩放。
- `FragmentTabletDualPaneCoordinator` 的全屏封面缩放接管需要双栏会话的封面所有权，且要求至少 600dp、横屏和原生抽屉布局。现场为 360dp 竖屏，没有进入该所有权条件。
- 全屏封面当前是原生标准暂停缩放，所有祖先缩放正常。这与模块手机 mini 的形变残留不同。

因此，本轮能够定位到“原生封面内部尺寸未恢复”的状态；归因更偏向原生尺寸缓存/恢复路径，尚不足以完全排除模块的间接时序影响。

## 保留的现场资料

仅在本地忽略目录保存：

- `.scratch/applemusic-700-beta-analysis/phone-small-cover-before.png`
- `.scratch/applemusic-700-beta-analysis/phone-small-cover-activity-focused.txt`
- `.scratch/applemusic-700-beta-analysis/phone-small-cover-view-properties.json`
- `.scratch/applemusic-700-beta-analysis/phone-small-cover-logcat.txt`
- `.scratch/applemusic-700-beta-analysis/phone-small-cover-module.txt`
- 原包对应 `L0 / l.E1 / l.M1 / G0.P` 的方法摘录。

没有用一次不复现的重启对照宣称问题消除。后续如修补，应先观测首次测量尺寸、当前目标缓存与实际子控件尺寸，再决定同步哪个原生状态；不直接强制封面 scale=1，以免破坏暂停缩放和原生展开动画。
