# 1606 双栏原生封面收起对齐修补

## 现象与代码证据

用户试用 v9 后反馈：不开双栏时原生动画可用，开启双栏后封面收起不能对齐 mini。本轮聚焦这个现象，保留原生动画和动态封面。

1606 原包 `PlayerMainFragment$i.d(float)` 通过 `player_root.offsetDescendantRectToMyCoords` 读取完整封面和 mini 封面的布局矩形，再计算并写入封面自身的位移、缩放、圆角和 TextureView 矩阵。

v9 双栏虽已删除自定义封面插值，仍在 `FragmentTabletDualPaneSession.styleArtwork()` 中通过 `artwork_container.translationY` 实现静态居中。该变换发生在封面的祖先容器上，原生读取的布局矩形没有包括这段变换，因此收起终点会残留父容器位移。Android 16 的 [AOSP ViewGroup 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/java/android/view/ViewGroup.java)确认 `offsetRectBetweenParentAndChild` 逐层累加 `mLeft/mTop` 并减去 scroll，没有累加祖先的 translation 或 Matrix。

这是已确认的代码坐标冲突，能解释用户反馈。当前没有 ADB 设备，尚未在平板上验证修补后的视觉结果；其他未描述的动画异常不能一并宣称消失。

## 修补范围

- 双栏封面的居中位置改为真实 ConstraintLayout `topMargin`，父容器位移不再由模块写入。
- 清除相竞争的底部约束，避免 ConstraintLayout 的垂直 bias 再叠加一次居中偏移。
- 保留当前双栏封面尺寸、状态栏处理、左右布局、原生 SONG/QUEUE 和独立右侧歌词。
- 仅在收起/展开端点且原生页面切换已稳定时更新静态布局；途中不重排封面容器。
- 完整封面的每帧位移、缩放、圆角、暂停动画及视频矩阵仍由 Apple Music 原生实现。玻璃渐隐和 mini 按压效果保留。
- 6.5.x 原有接入和布局函数不变。新增布局计算放在 core，宿主约束写入仍在 host-applemusic，app 未加入反射或宿主动画实现。

## 验证

新增 5 项布局回归，覆盖居中位置能被原生布局坐标读取、状态栏/Fragment 父容器偏移、整体 sheet 位移、狭窄可用区间及无效测量。

977 项测试均通过，失败/错误/跳过为 0；app/host-applemusic Debug Lint、Release vital Lint、全模块测试和 Release 构建通过，部分未变化任务使用 Gradle 已有结果。262 项原包契约、5 个精确 profile/316 个冻结目标、模块架构、32 个渲染参考文件和 2 个声明补丁检查通过。

平板仍需检查：播放/暂停下展开与收起、慢拖到 mini 前的最后几帧、中途反向拖动、SONG/QUEUE、动态封面、横竖屏和关闭双栏恢复。当前 ADB 无设备，没有安装到设备，也没有真机通过结论。

## 试用包

`.scratch/applemusic-700-beta-analysis/AMpp-1.6.2-112-1606-native-artwork-alignment-v10.apk`，源码提交 `6f88272`，版本 1.6.2/112，9,734,160 字节。

SHA-256：`34c850de506e8d5b96035e17e4ba6e506a4eaba8ecd2b354a28225a97ce3a086`。

APK v2 签名通过，沿用 RSA 4096 发布证书，证书 SHA-256 `6ed7800187c3513562334319723498fd01772396b9848d4aeba50622910be0d4`。6 个打包 profile/index JSON 与源码逐字节一致，版本信息、Xposed 入口、自定义封面接管方法缺席、原生观察/动态封面/v8 首帧交接保留均通过检查。

安装后强制停止并重开 Apple Music，使旧进程的模块 Hook 卸载并加载新代码。v9 及此前包保留，数据格式和签名不变。
