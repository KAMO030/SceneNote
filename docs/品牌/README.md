# 品牌 · 记记酱（JIJI）

场记的吉祥物就是 logo 本人。造型照角色设定图画：**猫耳（外耳 / 内耳 / 绒毛三层）+ 黑色圆框眼镜
+ 杏仁眼、异色瞳（左橙金 / 右蓝紫）+ 粉到淡紫的短波波头 + 蓝色挑染 + 呆毛**，
外加三个小记号：左镜片上的**猫爪肉球**、左侧两根**细发夹**、右侧一颗**星**。

五官走正常动漫比例，不是大眼占满脸的 Q 版：眼睛是杏仁形、上眼线粗且外眼角挑出去，
眼镜比脸略宽。轮廓线用淡粉紫（`#96628A`）而不是深色描边，深色只留给眼线和镜框。
介绍页（`docs/介绍页/index.html`）里是 Q 版全身坐姿，桌面图标和开屏用的是同一个人的头像版。

## 一份真源

造型只写在 **`scripts/brand/jiji_logo.py`** 里，一份 path 数据出四端资产：

```bash
python3 scripts/brand/jiji_logo.py            # 全量重新生成
python3 scripts/brand/jiji_logo.py --preview  # 只出 docs/品牌/ 下的 SVG 与预览图
```

改造型 = 改这个脚本再重跑，不要手改产物（Android 的 XML、iOS 的 PNG、`JijiArt.kt` 开头都写了"勿手改"）。
光栅化走 `npx @resvg/resvg-js-cli`，第一次跑会下载。

| 产物 | 用在哪 |
| --- | --- |
| `记记酱.svg` | 透明底角色，文档 / 网页 / 宣传用 |
| `应用图标-浅色.svg` `-深色.svg` `-单色.svg` | 图标三态的设计稿 |
| `androidApp/.../ic_launcher_{background,foreground,monochrome}.xml` | Android adaptive icon 三层 |
| `iosApp/.../AppIcon.appiconset/AppIcon-{Light,Dark,Tinted}.png` | iOS 18 图标三态，各 1024×1024 |
| `iosApp/.../LaunchBackground.colorset` | iOS 冷启动纯色底 |
| `shared/.../ui/brand/JijiArt.kt` | 开屏动效的 path 数据（Compose 自绘） |

## 坐标与安全区

统一 **512×512** 视口，角色 bbox（含白描边）约 `x[32,480] y[11,497]`，水平垂直都已居中。
外围尺寸是卡着画布留白定的——猫耳尖、发梢再往外一点，白描边就会被裁掉。

- **iOS 图标**：角色缩到 0.88 居中，四周留白够 22.37% 圆角切。
- **Android adaptive icon**：前景缩到 **0.57**（`ANDROID_SCALE`），把角色收进 72 dp 安全区——外圈 18 dp 会被各家遮罩吃掉，
  所以背景层只有渐变和光晕，装饰星光只放在不裁切的 iOS 图标里。
  单色层另用 **0.62**（`MONO_SCALE`）：剪影没有那圈白描边，放大一点才和彩色版一样显眼。
- **单色层**（Android 13+ 主题图标 / iOS tinted）：系统只认形状、颜色由系统给，所以走剪影：
  一条闭合轮廓 + `evenOdd` 把镜片和内耳挖成洞，瞳孔再实心补回去。
  两个坑：轮廓底部要连成整块（下巴和发梢之间留 V 形缺口，会被 evenOdd 翻成尖角），
  内耳的洞别挖太大（耳朵会变成细边框，看着像兔耳）。呆毛在剪影里会被看成第三只耳朵，单色版不画。

## 配色

| 用途 | 浅色 | 深色 |
| --- | --- | --- |
| 图标底 / 开屏底 | `#FFE7F3 → #FBB3DA → #C3A4F0` | `#4A2D63 → #33204C → #1E1436` |
| 系统开屏纯色 | `#FBB3DA` | `#33204C` |
| 头发（刘海 / 后发） | `#FDD4E0→#F6BACE→#E3B4D6` / `#EDA2BC→#E08FAE→#C098D6` | 同 |
| 皮肤 | `#FFF9F5→#FDEADF` | 同 |
| 轮廓 / 脸的轮廓 | `#96628A` / `#D79FAC` | 同 |
| 眼线 / 镜框 | `#3A2B3F` / `#17171C` | 同 |
| 发色暗部 · 发丝线 | `#C9B0DE` | 同 |
| 蓝色挑染 | `#AFD6EE` | 同 |
| 内耳绒毛 | `#F6D9E4` → `#FFF4EC` | 同 |
| 虹膜（左 / 右） | `#7A3A0C→#F5A623→#FFE9A8` / `#332477→#7B6BD9→#CFC8F5` | 同 |
| 猫爪肉球 | `#F58CAE` | 同 |

系统开屏的纯色取自渐变中段，Compose 开屏首帧用同一支色再把渐变铺开，两端都不会闪白。
角色一圈 26 单位的白描边（贴纸做法）保证它在任何底色上都跳得出来。

## 开屏

`shared/src/commonMain/kotlin/dev/scenenote/ui/brand/`：

- `JijiLogo.kt` —— 角色绘制，参数是 `blink` / `earTilt` / `ahogeSwing` / `scale` / `alpha`。
  全程一个 Canvas、只用 save/restore 级变换：iOS 上自绘里再开图层会踩 Skiko 的坑。
- `SplashScreen.kt` —— 时间线 1.8 s：渐变铺开 → 记记酱弹入 → 猫耳抖、呆毛摆、眨眼 → 字出来 → 让位给首页。
  Reduce Motion 打开时只剩淡入淡出，压到 0.6 s。进程内只播一次（`SplashOnce`）。

两端的衔接：

- **Android**：`values-v31/themes.xml` 把系统开屏的图标设成桌面图标前景层、底色设成 `brand_splash`；
  API 26–30 用 `drawable/splash_background.xml` 做窗口背景。落地后 `App()` 里的 `SplashScreen` 接上。
- **iOS**：`Info.plist` 的 `UILaunchScreen` 只给一个 `LaunchBackground` 纯色底，
  Compose 起来后直接接动效——放静态图会被系统拉伸铺满，不如纯色干净。
