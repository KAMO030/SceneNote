import AppIntents
import Foundation
import Shared

// MARK: - 深链常量
//
// 系统入口（Action Button / 快捷指令 / Spotlight / Siri / 将来的控制中心按钮）统一带着场景 ID 拉起 App，
// 走与 Android 磁贴、耳机键相同的 scenenote:// 深链（07 篇 §7.10），由共享层 DeepLinks → App.kt 导航。

/// 三个 MVP 入口对应的深链。场景 ID 与 shared 的 Scenes.kt 一致：listen（M0）/ quick_phrase（M4）/ live_talk（M1）。
enum SceneNoteDeepLink {
    /// 开始仅听：M0，进页即起录。
    static let listen = "scenenote://scene/listen?autostart=1"
    /// 速译一句：M4 按住说话，不自动起录。
    static let quickPhrase = "scenenote://scene/quick_phrase"
    /// 面对面对话：M1 耳听·面屏，进页即起录。
    static let liveTalk = "scenenote://scene/live_talk?autostart=1"
}

/// 把深链交给共享层。
///
/// 三个 Intent 都声明 `openAppWhenRun = true`：系统先把 App 拉到前台，再在 **App 进程内** 执行 perform()，
/// 所以这里直接调用 Kotlin 的 `DeepLinksIosKt.handleDeepLink(url:)`（与 iOSApp.swift 的 onOpenURL 走同一入口），
/// 不依赖系统把自家 scheme 回送到 `onOpenURL`。共享层的 `DeepLinks.pending` 是 StateFlow：
/// 冷启动时即使 Compose 还没组合完，值也会保留到 App() 首次收集时再导航。
///
/// 备选路径（真机验证若发现直接调用时机不对，换成这一种，二选一，不要同时走，否则会叠推两个会话页）：
///     func perform() async throws -> some IntentResult & OpensIntent {
///         .result(opensIntent: OpenURLIntent(URL(string: SceneNoteDeepLink.listen)!))
///     }
/// 这条经系统打开 URL → iOSApp.swift `onOpenURL` → 共享层。
enum SceneNoteIntentBridge {
    @MainActor
    static func deliver(_ link: String) {
        DeepLinksIosKt.handleDeepLink(url: link)
    }
}

// MARK: - App Intents（iOS 16+；本工程部署目标 iOS 18）

/// 「开始仅听」：手机在口袋，对方说什么耳机里听译文（M0）。
struct StartListeningIntent: AppIntent {
    static let title: LocalizedStringResource = "开始仅听"
    static let description = IntentDescription("打开场记并立即开始仅听：对方说什么，耳机里听译文。", categoryName: "面对面对话")
    /// 必须打开 App：会话页与音频引擎都在前台进程里。
    static let openAppWhenRun: Bool = true

    @MainActor
    func perform() async throws -> some IntentResult {
        SceneNoteIntentBridge.deliver(SceneNoteDeepLink.listen)
        return .result()
    }
}

/// 「速译一句」：按住说话，松手大字朝向对方（M4）。
struct QuickPhraseIntent: AppIntent {
    static let title: LocalizedStringResource = "速译一句"
    static let description = IntentDescription("打开场记的速译一句：说一句，大字朝向对方。", categoryName: "面对面对话")
    static let openAppWhenRun: Bool = true

    @MainActor
    func perform() async throws -> some IntentResult {
        SceneNoteIntentBridge.deliver(SceneNoteDeepLink.quickPhrase)
        return .result()
    }
}

/// 「面对面对话」：戴上耳机就能听，掏出手机对方看半屏（M1）。
struct StartTalkIntent: AppIntent {
    static let title: LocalizedStringResource = "面对面对话"
    static let description = IntentDescription("打开场记并开始面对面对话：戴上耳机就能听，掏出手机对方看半屏。", categoryName: "面对面对话")
    static let openAppWhenRun: Bool = true

    @MainActor
    func perform() async throws -> some IntentResult {
        SceneNoteIntentBridge.deliver(SceneNoteDeepLink.liveTalk)
        return .result()
    }
}

// MARK: - App Shortcuts
//
// 声明后无需用户手动添加：快捷指令 App「App 快捷指令」区、Spotlight 搜索、Siri 短语、
// 设置 → 操作按钮 → 快捷指令 里都能直接选到这三条。
// `\(.applicationName)` 由系统替换为本地化的 App 名（CFBundleDisplayName「场记」）。

struct SceneNoteShortcuts: AppShortcutsProvider {
    /// 快捷指令 App 里的磁贴底色：与设计系统的青绿主色对齐。
    static let shortcutTileColor: ShortcutTileColor = .teal

    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartListeningIntent(),
            phrases: [
                "用\(.applicationName)开始仅听",
                "\(.applicationName)仅听",
                "打开\(.applicationName)仅听",
                "Start listening with \(.applicationName)",
                "\(.applicationName) listen only",
            ],
            shortTitle: "开始仅听",
            systemImageName: "headphones"
        )
        AppShortcut(
            intent: QuickPhraseIntent(),
            phrases: [
                "用\(.applicationName)速译一句",
                "\(.applicationName)速译一句",
                "打开\(.applicationName)速译",
                "Quick phrase with \(.applicationName)",
                "\(.applicationName) quick phrase",
            ],
            shortTitle: "速译一句",
            systemImageName: "text.bubble"
        )
        AppShortcut(
            intent: StartTalkIntent(),
            phrases: [
                "用\(.applicationName)面对面对话",
                "\(.applicationName)面对面对话",
                "打开\(.applicationName)对话",
                "Start a conversation with \(.applicationName)",
                "\(.applicationName) face to face",
            ],
            shortTitle: "面对面对话",
            systemImageName: "person.2.wave.2"
        )
    }
}
