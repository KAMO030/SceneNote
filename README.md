# 场记 SceneNote

纯客户端（无自建后端）的 AI 语音转文字 + 实时翻译 App，Kotlin Multiplatform（Android + iOS）。
方案文档见 [docs/00-导读与术语表.md](docs/00-导读与术语表.md)，执行计划见 [docs/13-执行计划.md](docs/13-执行计划.md)，验收记录在 `docs/验收记录/`。

## 构建

```bash
# JDK 21（本机 JBR 可用），Android SDK 在 ~/Library/Android/sdk（local.properties 的 sdk.dir）
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :androidApp:assembleDebug            # Android debug 包
./gradlew :shared:iosSimulatorArm64Test         # 共享模块单元测试（跑在 iOS 模拟器）
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -arch arm64 CODE_SIGNING_ALLOWED=NO build
```

Xcode 的「Compile Kotlin Framework」脚本会自动解析 JDK 21（`/usr/libexec/java_home -v 21`）。真机运行需在 `iosApp/Configuration/Config.xcconfig` 填 `TEAM_ID`。

## 结构

```
shared/      KMP 库：core/model · core/db(SQLDelight) · core/settings · core/egress · audio · asr · live · ui · di
androidApp/  Android 壳（MainActivity / Application / Manifest）
iosApp/      Xcode 工程（SwiftUI 壳，embedAndSignAppleFrameworkForXcode）
docs/        方案、规格、流程图、执行计划、验收记录
```

三条红线：没有后端；不内置任何 Key；不谈商业模式。
