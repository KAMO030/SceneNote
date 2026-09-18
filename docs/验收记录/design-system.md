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

## 2026-09-17 补：sheet 背景改为 haze 模糊，无模糊则不透明

- 问题：`SceneSheet` 的 `glassFlat` 一直拿不到取样源（壳覆盖层在 `GlassScaffold` 之外、`SceneEditSheet` 又自己把 `LocalGlassBackdrop` 置空），只画 86% 半透明填充，下面的页面直接穿出来。
- 改法：`GlassScaffold` 新增 `backdrop` 参数；`MainShell` / `DesignSystemGallery` 自建 backdrop，骨架与覆盖层（sheet）共用，sheet 也 `hazeEffect` 取样内容层。`SceneEditSheet` / `KeyWalletOverlay` 不再自行置空；只有内联在内容层里（`overlayHost == null`）的宿主仍置空。
- 回退：`platformSupportsBackdropBlur()`（= `HazeDefaults.blurEnabled()`，Android API < 31 为 false）并入 `rememberGlassSpec` 的 `blurEnabled`；没有模糊（Android < 12 / 降低透明度 / 拿不到内容层）时 `glass()` 不再挂 haze 而直接画填充，sheet 改为完全不透明的 `secondarySystemBackground`，Tab 栏 / 导航按钮仍是 96% 高填充。
- 填充 alpha 从 86% 降到 72%：sheet 填充色与页面底色同为 secondarySystemBackground，86% 时模糊后的差异只剩几个灰阶（截图量得 235–243），肉眼等于不透明；72% 能看到下面白色行、彩色图标的柔和形状，sheet 上的页脚文字仍清晰。
- 验证：vivo V2436A（Android 16 / API 36）设置 → 翻译 Key、场景 → 复制一张再改，两处 sheet 均透出模糊的页面内容；Android < 12 只做逻辑与编译验证（手头无设备）。

## 2026-09-17 补：资料库搜索胶囊改走 haze 模糊；长按删除记录

- 搜索胶囊原来画在资料库内容层里（`backdrop = null`，不能取样自己），只有高填充。改为和 sheet 一样交给壳的覆盖层（`LocalShellOverlay`）：在取样源外面拿壳的 backdrop，列表从下面滚过时被模糊；不在壳里时仍内联渲染、不透明回退。
- 删除：行长按 → `SceneMenu`「删除」（红）→ `SceneAlert` 确认（不可恢复，所以按 HIG 破坏性动作配 Cancel）→ `LibraryViewModel.delete`：会议录音（`audioPath` 在 vault 目录下的）一并删文件，字幕会话的 `audioPath` 指向用户自己的视频不动。原型里"30 天回收站"未做，仍是硬删。
- 验证：iPhone 17 模拟器（iOS 26）——胶囊透出下面的行与日期头；长按 → 菜单 → 弹窗 → 删除后列表即时更新；搜索过滤正常。Android 未验证（Android 16 机锁屏、Android 11 机被其他会话占用）。
- 顺带发现（未修）：老库升级没有迁移，`glossary_candidate` 等 I6 新表不存在时进资料库直接崩（模拟器旧库复现）；已单独开任务。

## 2026-09-17 补：导航栏标题不再压按钮；慢路径补译文、纪要用我的语言写

- `SceneNavBar` 改为自定义 `Layout`：先量两侧，标题只能用中间剩下的宽度——居中放不下就往空的一侧挪，再放不下截断加省略号。对话卡片页「重新整理」改成圆形图标按钮（左「资料库」+ 标题 + 右两枚胶囊在手机宽度上放不下）。iPhone 17 模拟器验证：资料库 / 对话卡片 / ↻ / 完成 四件不重叠。
- 「重新整理没有翻译」：慢路径从没实现过 02 篇 §2.2 的"会话结束后整段再译一遍"，`polished` / `final_translation_json` 只读不写。现在 `SlowPath.card` 先 `backfillTranslations`：实时那会儿没译出来的句子逐句走百炼 MT（经出站闸门，隐私档不允许 / 没 Key 就跳过），写进 `final_translation_json`，`utterances()` 读定稿优先；落库时即使没译文也记 targetLang。
- 纪要：`Templates.minutesSystem` 增加输出语言，取设置里「我的语言」（`settings.myLang`，重新整理时按当时的设置），英文会议也出中文纪要；原文 Tab 保持原语言。
- 未验证：MT 回填与纪要语言需要真机 + Key（模拟器不填 Key）；Android 16 机当前离线。共享单测 88 项通过。

## 2026-09-18 补：主题色改粉色，设置可选内置主题色

- `Palette` 的 tint 四件套（tint / onTint / tintSoft / onTintSoft）拆成 `Accents`：六种内置主题色（粉 · 默认、青绿、蓝、紫、橙、绿），浅 / 深色各一套；`SceneColors.light(accent)` / `dark(accent)` 按主题色生成令牌，`SceneTheme(accent = LocalSceneAccent.current)` 让页面里嵌套的 `SceneTheme(dark = true)` 自动继承。
- 设置 → 外观 → 主题色：一行六个圆点，点一下全 App 立即换色（`AppSettings.accent` 是 StateFlow）；Android 主屏小组件底色也跟随（离开 App 时 `updateAll`）。
- 对比度单测改为遍历全部主题色：浅色 tint 同时压白底与 #F2F2F7 分组底 ≥ 4.5，深色 tint 压纯黑与 #1C1C1E ≥ 4.5；原青绿 0E7C86 在分组底上只有 4.43，调到 0D7680（4.80）。89 项单测通过。
- 验证：iPhone 17 模拟器浅 / 深色首页、设置页六色切换即时生效；Android 编译通过，真机待装。

## 2026-09-18 补：Tab 切换动效

- `SceneTabBar`：选中底改成一枚画在 tab 后面的胶囊，按 tab 序号做弹簧插值（dampingRatio 0.82 / StiffnessMediumLow）滑到新位置，路上按行程进度先胀大再缩回（宽 +50% / 高 +21%，`sin(π·p)^0.5` 让胀大阶段贯穿大半程；内区 54 dp 最高胀到 65.3 dp 几乎撑满，仍在 66 dp 玻璃胶囊内），到位时正常大小（连点从半路接着走）；图标 / 文字颜色 `animateColorAsState` 渐变（normalMs）；新选中的图标 1 → 1.12 → 1 轻弹。系统「减少动态效果」（`SceneMotion.reduced`）时全部 `snap()`。
- `TabContent` 改 `AnimatedContent`：新页淡入 + 朝切换方向平移 1/12 宽（normalMs），旧页淡出（fastMs）；减少动态效果时瞬切。
- 因为新旧 Tab 在过渡期同时存在，Home / Settings / Library 挂到壳覆盖层的 `DisposableEffect` 改为"只清自己挂上去的"（`=== overlayContent`），否则旧 Tab 退场会把新 Tab 的 sheet / 搜索胶囊清掉。
- 验证：iPhone 17 模拟器录屏抽帧（30 fps）：胶囊 5 帧滑到位、颜色交叉渐变、图标轻弹、内容交叉淡入平移；三个 Tab 来回切换后覆盖层仍在。Android 编译通过并已装到 V2436A，未做动效录屏（手机当时切到了用户自己的 App）。

## 2026-09-18 补：数据库迁移（schema v1 → v2）

- 问题：`.sq` 从 I0 到 I5 陆续加表，但 `SceneNoteDb.Schema.version` 一直是 1、没有任何 `.sqm`，旧库升级安装后 I5 的三张表（`session_note` / `bookmark` / `glossary_candidate`）不存在，进资料库 `no such table: glossary_candidate` 直接崩（用户 Android 16 真机 + iPhone 模拟器旧库都复现）。
- 改法：加 `shared/src/commonMain/sqldelight/dev/scenenote/db/migrations/1.sqm`（`CREATE TABLE IF NOT EXISTS` 那三张表），schema 版本自动变 2；两端驱动（`AndroidSqliteDriver` / `NativeSqliteDriver`）在版本不一致时自动跑 `Schema.migrate`。I5 之后建的库跑到这里是空操作。历史上只加过表、没改过列，所以只需建表。
- 验证：iPhone 17 模拟器把库退回 I0 结构 + `user_version = 1` → 启动后 `user_version = 2`、三张表补齐、原有 15 条会话保留；用户 V2436A 真机保留旧库直接覆盖安装，深链进资料库不再崩，历史记录都在。
- 规矩：以后改表结构必须追加新的 `N.sqm`，别再只改 `.sq`（`1.sqm` 头部注释已写）。
