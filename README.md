# 抖音不裁剪 (DouYin NoCrop)

一个 LSPosed 模块：让抖音视频按**原始宽高比完整显示**，不再被左右裁剪。

A modern LSPosed module (Xposed API **102**) that makes Douyin render videos at their
original aspect ratio — no more left/right cropping.

## 原理 / How it works

抖音播放引擎按 cover（放大填满）模式把视频渲染进与容器同尺寸的 surface，当视频比容器
更“宽”时，左右两侧内容会被裁掉。

本模块 hook 抖音换视频的时机（`VideoItemParams.getAweme()`），读取视频宽高比，把视频
渲染 View 的 `LayoutParams` 收缩为“视频宽高比在容器内的最大内接矩形”（保留原有居中
gravity）。这样 surface 宽高比 == 视频宽高比，引擎的 cover 渲染在数学上等价于 fit，
裁剪自然消失，容器多余部分显示背景。

Douyin renders video frames with a "cover" (fill-and-crop) strategy. This module shrinks
the render view to the largest rectangle matching the video's aspect ratio (centered),
so the surface ratio equals the video ratio and nothing gets cropped.

## 功能 / Features

- 视频按原始比例完整显示，左右不再裁剪
- 竖屏/横屏视频均自适应（自动取视频宽高比，三级字段回退）
- 只改 View 布局参数，不碰播放器内核，幂等且无布局循环
- 全部 Hook 使用 `PROTECTIVE` 异常模式：钩子出错自动降级，不会导致抖音闪退

## 要求 / Requirements

- **LSPosed**（支持 Modern Xposed API 102 的版本）
- **作用域**：`com.ss.android.ugc.aweme`（抖音，安装后在 LSPosed 中勾选）
- Android 9+ (minSdk 28)

## 安装 / Install

1. 从 [Releases](../../releases) 下载最新 APK 并安装
2. LSPosed → 模块 → 启用 **抖音不裁剪**
3. 作用域勾选 **抖音**
4. 强行停止并重启抖音

验证日志：`adb logcat -s DouYinNoCrop:D`

## 构建 / Build

```powershell
.\gradlew.bat assembleRelease
# 产物: app\build\outputs\apk\release\app-release.apk
```

- AGP 9.2.1 / Gradle 9.4.1 / compileSdk 37 / minSdk 28 / Java 17
- 依赖仅一行：`compileOnly("io.github.libxposed:api:102.0.0")`（Maven Central）

## 兼容性说明 / Compatibility

Hook 锚点均为未混淆的接口方法与 public 字段（在抖音 40.6.0 上实测）：

| 锚点 | 用途 |
|---|---|
| `VideoItemParams#getAweme()` | 换视频时取视频宽高比 |
| `VideoItemParams#mBaseFeedPlayerView` | 拿播放器 View |
| `BaseFeedPlayerView#getVideoView()` | 拿真正渲染 View（覆盖 Texture/Surface 两条路径） |
| `KeepSurfaceTextureView#onAttachedToWindow()` | attach 时机补偿 |
| `Video.width/height → aspectRatio` | 宽高比三级回退提取 |

抖音大版本更新后如失效，可用 [droidasc](https://github.com/) 等 dex 分析工具重新验证上述
锚点，只需更新 `NoCropModule.java` 中的类名/方法名常量。

## License

MIT
