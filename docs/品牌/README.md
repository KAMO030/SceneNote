# 品牌 · 记记酱（JIJI）

场记的吉祥物就是 logo 本人。造型照角色设定图（粉发猫耳 + 黑框眼镜 + 水手服 + 落肩开衫的三视图）画：

- **头**：粉色短波波头，发梢渐变到淡紫蓝；头顶一撮呆毛、分缝里一缕深莓色；刘海中间开一个拱，一缕长发从拱里垂到鼻梁、
  梢上是淡紫蓝；两缕浅蓝挑染；两侧各一个浅蓝缎带蝴蝶结扎着小辫，缎带垂下来。
- **猫耳**：外耳粉色、外缘渐变到蓝紫，内耳浅粉，耳根一簇白绒毛。
- **脸**：黑色粗框眼镜（介于圆和圆角方之间），左镜框左上角挂一颗**肉球**；异色瞳（画面左橙金 / 右蓝紫）；ω 嘴；
  腮红带三道小斜线；眉毛压在刘海上淡淡地透出来。
- **发饰**：画面左边两根细发夹，右边一枚浅蓝的**蝙蝠翅膀发夹**。
- **全身**（介绍页用，Q 版两头身站姿）：白色短款水手服 + 浅蓝领 + 粉蝴蝶结 + 盾形徽章；高腰浅蓝百褶裙、两排金扣、
  裙摆一道白杠、白色荷叶边；落肩的天蓝色大开衫，袖口罗纹、袖上两个白色交叉系带、右前片三颗扣子、萌袖只露指尖；
  白色花边短袜、黑色乐福鞋；腿上的创可贴和爱心贴；粉色猫尾。

线稿用梅子色（`#7E4A74`）而不是纯黑，深色只留给眼线和镜框；蓝色衣物和缎带用蓝线（`#6F8FCB`）。
桌面图标和开屏用头像版，介绍页用全身版，**是同一份 path 数据**。

## 一份真源

造型只写在 **`scripts/brand/jiji_logo.py`** 里，一份 path 数据出各端资产：

```bash
python3 scripts/brand/jiji_logo.py            # 全量重新生成
python3 scripts/brand/jiji_logo.py --preview  # 只出 docs/品牌/ 下的 SVG 与预览图
python3 scripts/brand/jiji_logo.py --draft D  # 只往目录 D 里出草稿 PNG（头像 / 图标各态 / 全身），画的时候边改边看
```

改造型 = 改这个脚本再重跑，不要手改产物（Android 的 XML、iOS 的 PNG、`JijiArt.kt`、介绍页里 `JIJI:BEGIN`～`JIJI:END`
之间的 `<symbol>` 都写了"勿手改"）。光栅化走 `npx @resvg/resvg-js-cli`，第一次跑会下载。

| 产物 | 用在哪 |
| --- | --- |
| `记记酱.svg` | 透明底头像，文档 / 网页 / 宣传用 |
| `记记酱-全身.svg` | 透明底全身 |
| `应用图标-浅色.svg` `-深色.svg` `-单色.svg` | 图标三态的设计稿 |
| `androidApp/.../ic_launcher_{background,foreground,monochrome}.xml` | Android adaptive icon 三层 |
| `iosApp/.../AppIcon.appiconset/AppIcon-{Light,Dark,Tinted}.png` | iOS 18 图标三态，各 1024×1024 |
| `iosApp/.../LaunchBackground.colorset` | iOS 冷启动纯色底 |
| `shared/.../ui/brand/JijiArt.kt` | 开屏动效的 path 数据 + 动效支点（Compose 自绘） |
| `docs/介绍页/index.html` 里的 `<symbol id="jiji">` | 介绍页的全身版，带表情变体和动画分组 |

脚本里的写法约定：path 只用绝对坐标的 `M L C Q A Z`（`mirror()` / `shift()` / `sym()` 只认这个子集）；
左右对称的部件只写画面左边那一半，右边靠镜像；渐变一律 `userSpaceOnUse` 的绝对坐标，颜色可以写 `#RRGGBBAA`；
每条形状有 `part`（动效分组）和 `var`（表情变体：`eo/eh/ew` 睁眼 / 眯眼笑 / 单眼眨，`mw/mo` ω 嘴 / 张嘴），
图标和开屏只取默认表情，介绍页全要。

## 坐标与安全区

- **头像**：**512×512** 视口。bbox（含白描边）约 `x[14,498] y[9,483]`，水平居中、竖直偏上 10，
  所以放进图标时统一下移 `ICON_DY = 10`（按缩放折算）。
- **全身**：**640×800** 视口，对称轴 `x=320`。头像平移 `(64, 0)` 后绕下巴 `(256, 432)` 缩到 **0.92** 压在身体上——
  头收一点衣服才看得清；头的后层（后发 / 小辫 / 缎带）在身体后面，前层在身体前面。
- **iOS 图标**：角色缩到 **0.95**（`IOS_SCALE`）居中，耳尖离 22.37% 圆角还有余量；四角放几颗星。
- **Android adaptive icon**：前景缩到 **0.57**（`ANDROID_SCALE`），把角色收进 72 dp 安全区——外圈 18 dp 会被各家遮罩吃掉，
  所以背景层只有渐变和光晕，星星只放在不裁切的 iOS 图标里。`预览-Android遮罩.png` 是按圆 / squircle / 圆角方 / 方裁出来的核对图。
- **单色层**（Android 13+ 主题图标）：系统只认形状、颜色由系统给。头发 / 耳朵 / 小辫 / 缎带全部实心叠起来就是剪影；
  后发那一片用 `evenOdd` 把整张脸挖掉，刘海和鬓发再实心盖回去，剩下的洞正好是露出来的脸；
  洞里画镜框（描边）、两只眼（各带一颗高光的洞）和 ω 嘴。缩放 **0.62**（`MONO_SCALE`）：剪影没有白描边，放大一点才和彩色版一样显眼。
- **iOS 着色态（tinted）**：系统只看亮度，所以不用剪影，直接把整张彩色稿换成等亮度的灰（再提亮一点，着色后不发闷），透明底。

## 配色

| 用途 | 色值 |
| --- | --- |
| 桌面图标底（浅 / 深） | `#FFE7F3 → #FBB3DA → #C3A4F0` / `#4A2D63 → #33204C → #1E1436`——粉紫渐变**只用在图标上** |
| 开屏底（浅 / 深） | `#F2F2F7` / `#000000`——就是 App 的页面底色 `groupedBackground`，不是品牌色（见下面「开屏」） |
| 头发（顶 → 发梢） | `#FCCBD8 → #F7ABC3 → #F0A4C6 → #C9ACE7 → #A8B7F5` |
| 波波头里层压暗 | `#8A66C4`，透明度 0 → 0.44 |
| 猫耳（外缘 → 内侧） | `#A9B8F7 → #C6B4EF → #F3B0CB → #F8B2C7`；内耳 `#FFDCE6`，绒毛 `#FFFAF5` |
| 皮肤 | `#FFF7F1 → #FFE5D9`，刘海落影 `#F6C6C2` |
| 线稿（发 / 肤 / 蓝衣物 / 镜框 / 眼线） | `#7E4A74` / `#DE98A6` / `#6F8FCB` / `#1D1924` / `#3A2232` |
| 虹膜（左 / 右） | `#7A2E0E→#EC7F1C→#FFBB3A→#FFE68E` / `#2A2270→#5D5FD8→#8FA6F6→#D2E2FF` |
| 蓝色挑染 / 头顶高光 / 分缝深色 | `#BCE0FB` / `#FFE9F0` / `#C2557F` |
| 缎带 / 蝙蝠发夹 / 细发夹 | `#CFE5FB`·`#A9CBF2` / `#D8EDFF` / `#F3E9FF` |
| 肉球 | `#FFB29C`，线 `#5A2E3A` |
| 开衫 / 罗纹 / 裙子 / 水手领 | `#C9E0F9→#AFCDF2` / `#D6E8FC` / `#C3DCF8→#A6C8F2` / `#B9D8F8` |
| 蝴蝶结 / 金扣 / 鞋 / 尾巴 | `#FFB3C9` / `#F6D06A` / `#2C2632` / `#F7A9C2` |

角色一圈 26 单位的白描边（贴纸做法）保证它在任何底色上都跳得出来——介绍页的全身版也带这圈白边，深色星空底上才立得住。

## 开屏

**开屏用 App 自己的设计令牌，不另起一套"品牌皮肤"**（第一版是粉紫渐变底 + 白描边贴纸字 + 星星，用户说和 App 整体风格不符，
已改掉）。App 是 Apple HIG 那一路：UIKit 语义色、Apple 文本样式刻度、用户可换的强调色；开屏照这个来：

| 元素 | 用的令牌 | 说明 |
| --- | --- | --- |
| 底色 | `groupedBackground`（浅 `#F2F2F7` / 深 `#000000`） | 和首页同一支色，也和两端系统开屏的纯色同一支：系统开屏 → Compose 开屏 → 首页，中间没有换底的那一下 |
| 记记酱脚下的圆盘 | `tintSoft` | 半径 96 dp，圆心对着脸（不是对着画框正中——头像上面多出一对耳朵） |
| 声波环 | `tint`，1.5 dp 细线，透明度 ≤ 0.36 | 和实时页"我在听"的脉冲点 / 呼吸灯同一种说法；设置里换了主题色，这里跟着换 |
| 应用名 | `largeTitle` + `label` | 和首页左上角的大标题同一档，不描边、不拉字距 |
| 拉丁名 `SceneNote` | `subheadline` + `secondaryLabel` | 应用名本身就是拉丁字母的语言下不显示 |

`shared/src/commonMain/kotlin/dev/scenenote/ui/brand/`：

- `JijiArt.kt` —— 生成物：形状、渐变、动效支点（耳根、两只眼心、呆毛根）。
- `JijiLogo.kt` —— 角色绘制，参数是 `blink` / `earTilt` / `ahogeSwing` / `scale` / `alpha`，支点读 `JijiArt.pivots`。
  全程一个 Canvas、只用 save/restore 级变换：iOS 上自绘里再开图层会踩 Skiko 的坑。
- `SplashScreen.kt` —— 时间线 1.6 s：记记酱弹入 → 猫耳抖、呆毛摆、眨眼 → 声波推出去 → 字出来 → 内容先淡、底色后淡，让位给首页。
  Reduce Motion 打开时只剩淡入淡出，压到 0.6 s。进程内只播一次（`SplashOnce`）。

两端的衔接（底色常量在脚本的 `SPLASH_BG`，跑脚本时会核对 Android 的 `brand_splash` 和它是否一致）：

- **Android**：`values{,-night}/colors.xml` 的 `brand_splash` = 页面底色；`values-v31/themes.xml` 把系统开屏的图标设成桌面图标前景层；
  API 26–30 用 `drawable/splash_background.xml`（底色 + 居中记记酱）做窗口背景。主题里把状态栏 / 导航栏也铺成 `brand_splash`、
  浅色下用深色图标（`@bool/brand_light_system_bars`），冷启动窗口上不会留一条深色状态栏；Compose 起来后由 `enableEdgeToEdge()` 接管。
- **iOS**：`Info.plist` 的 `UILaunchScreen` 只给一个 `LaunchBackground` 纯色底（脚本生成），
  Compose 起来后直接接动效——放静态图会被系统拉伸铺满，不如纯色干净。
  改过底色后模拟器上头几帧可能还是旧颜色：那是 SpringBoard 缓存的启动快照，卸载重装或重启模拟器才会刷新，不是资源没生效
  （用 `xcrun assetutil --info SceneNote.app/Assets.car` 看 `LaunchBackground` 可以确认）。
