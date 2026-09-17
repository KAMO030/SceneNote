# 验收记录 · core/design-system（07 篇 §7.14）与页面原型

日期：2026-09-17 · 执行：Claude（代码 + iOS 模拟器 + vivo 真机验证）

## 交付物

| 项 | 状态 | 位置 |
|---|---|---|
| 可点击交互原型：17 张 390×844 画板（四 Tab / M0 → M1 → M3 → 结束 / M4 / S4 播放器 / 会议 / 纪要 / Key 钱包 sheet / 去向账本 / 场景编辑 / 出门预热），Liquid Glass 功能层 + 「reduceTransparency」回退开关 | ✅ | 画布 https://claude.ai/artifact/MAj8d8pkjFPWt5AHG3DZpd；源文件 `docs/原型/`；说明 `docs/14-页面原型与设计系统.md` |
| Tokens：`Palette`（纯 Kotlin ARGB，单测输入）→ `SceneColors` 语义色（浅 / 深）、`SceneTypography` Apple 刻度、`SceneSpacing` 4/8/16/20、`SceneRadius` 10/14/20/28、`SceneMotion` | ✅ | `core/designsystem/Palette.kt`、`Tokens.kt` |
| `SceneTheme`：跟随系统深浅色（不提供 App 内开关）、系统无障碍偏好（expect/actual：UIAccessibility / Settings）、按压态 `PressHighlightIndication`（不引入 material-ripple） | ✅ | `Theme.kt`、`Accessibility*.kt`、`Indication.kt` |
| Liquid Glass：`GlassBackdrop` + `Modifier.glass()`（GraphicsLayer 录制内容 → 玻璃条嵌套重绘 + BlurEffect + 饱和度）；Reduce Transparency → 不透明；Increase Contrast → 加强描边 | ✅ 代码 / ⚠️ iOS 见下 | `Glass.kt`、`GlassScaffold.kt` |
| 组件：`SceneNavBar`（圆形玻璃按钮）、`SceneTabBar`（浮动胶囊）、`SceneDock`、`SceneSheet`（grabber + 取消 / 完成 + 下滑关闭）、`SceneGroup / SceneRow / SceneDivider / SceneSectionHeader / Footer / SceneRowIcon / SceneCard`、`SceneToggle`、`SceneSegmentedControl`、`SceneButton` 四档 + 黄标 / 破坏性、`SceneIconButton`、`SceneCapsule`、`SceneMenu`、`SceneAlert`、`SceneIcons` | ✅ | `core/designsystem/*.kt` |
| 预览页 `scenenote://gallery`（设置 → 工程自检 → 设计系统预览） | ✅ | `ui/gallery/DesignSystemGallery.kt` |
| 对比度单测（≤ 17 pt 4.5:1、≥ 18 pt 3:1、玻璃最坏情况） | ✅ 5 单测 | `commonTest/.../TokensContrastTest.kt` |
| 迁移期双栈：`SceneNoteTheme` = 外层 `SceneTheme` + 内层 `MaterialTheme`（I0 占位页续命） | ✅ | `ui/theme/Theme.kt` |

## 验收结果

| 验收项 | 结果 | 备注 |
|---|---|---|
| 双端编译（`compileAndroidMain`、`linkDebugFrameworkIosSimulatorArm64`、`assembleDebug`、xcodebuild） | ✅ | |
| 共享单测 37 项（含 5 项对比度） | ✅ | `:shared:iosSimulatorArm64Test` |
| 预览页 · iPhone 17 模拟器（iOS 26） | ✅ | 玻璃为高填充半透明回退（见下） |
| 预览页 · vivo V2054A（Android 11 / API 30） | ✅ | API < 31 无 RenderEffect，同样走高填充回退；Tab 栏能透出内容 |
| **Compose 自绘背景模糊 · iOS** | ❌ 关闭 | Skiko（CMP 1.12.0）在"内容 GraphicsLayer 既直接绘制、又被玻璃条的 layer 嵌套绘制"时于 `RenderNode::drawShadow` 崩溃（`EXC_BAD_ACCESS` stack guard，与是否设置 BlurEffect 无关；崩溃报告 `SceneNote-2026-09-17-041145 / 041200 / 041612.ips`）。`platformSupportsBackdropBlur()` iOS 返回 false。iOS 26+ 按方案由原生宿主承载真 Liquid Glass（待验证 48），iOS 18 用回退 |
| **Compose 自绘背景模糊 · Android ≥ 12** | ⏳ 未测 | 手头只有 Android 11 真机；路径与 haze 一致（RenderNode + RenderEffect），需 API 31+ 设备验证（待验证 46） |

## 待决策 / 待验证补充

- 待验证 46（补）：Android 12+ 真机验证 `Modifier.glass()` 的模糊与滚动跟随；若 tick 订阅方案掉帧，改为 `GraphicsLayer.toImageBitmap()` 或引入 haze 2.0 正式版。
- 待验证 48（补）：iOS 26 原生 `UITabBarController / UINavigationController / UISheetPresentationController` 外壳 + Compose 内容层的 interop 落地；在此之前 iOS 玻璃为不透明回退。
- 待决策 30（补）：Android 字体仍为系统默认套 Apple 刻度；Inter + 思源黑体未引入。

## 2026-09-17 补：玻璃改用 haze 1.7.3，页面文案整改

- `Modifier.glass()` / `GlassScaffold` 内部改为 haze（`hazeSource` 取样内容层，`hazeEffect` 画功能层），删除自绘 GraphicsLayer 嵌套路径与 `platformSupportsBackdropBlur()`。公开 API 不变。
- 行为：iOS（Skia）真模糊——iPhone 17 模拟器首页 / 设置 / Key 钱包 sheet 滚动与打开均不再崩溃；Android 12+ RenderEffect 真模糊（待真机）；Android 11（vivo）走 haze `fallbackTint` 高填充回退，与此前一致。待验证 46 的"iOS 崩溃"已消除。
- 页面按 `docs/15-文案与信息密度规范.md` 整改：去掉 M0/混合档/端侧/判向等工程词，每组 ≤ 1 说明 + ≤ 1 页脚，平台无关项不渲染（Android 无 Apple / ML Kit，iOS 无磁贴），首次启动进新手引导，会话页只显示对话与一个状态词。
