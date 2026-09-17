import SwiftUI
import Shared

@main
struct iOSApp: App {
    init() {
        KoinIosKt.startKoinIos()
        // 调试/验收：xcrun simctl launch <udid> dev.scenenote.app --deeplink=scenenote://selftest?autostart=1
        for arg in CommandLine.arguments where arg.hasPrefix("--deeplink=") {
            DeepLinksIosKt.handleDeepLink(url: String(arg.dropFirst("--deeplink=".count)))
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    if url.isFileURL {
                        // I6 屏内 S4：文件 App / 分享面板"用场记打开"的视频（Info.plist CFBundleDocumentTypes public.movie）
                        IncomingFile.handle(url)
                    } else {
                        DeepLinksIosKt.handleDeepLink(url: url.absoluteString)
                    }
                }
        }
    }
}

/// 外部送来的视频文件：拷进 tmp（原 URL 可能是别人沙盒里的 security-scoped 引用，或本 App 的 Documents/Inbox 副本），
/// 然后交给 scenenote://screen?shared=<url-encoded path>；由 commonMain 的 App 路由进屏内并调 ScreenViewModel.openShared。
enum IncomingFile {
    static func handle(_ url: URL) {
        // 大视频拷贝放后台：冷启动时在主线程同步拷几百 MB 会触发启动看门狗
        DispatchQueue.global(qos: .userInitiated).async { handleSync(url) }
    }

    private static func handleSync(_ url: URL) {
        let secured = url.startAccessingSecurityScopedResource()
        defer { if secured { url.stopAccessingSecurityScopedResource() } }

        let fm = FileManager.default
        let dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("scenenote-shared-\(UUID().uuidString)", isDirectory: true)
        let name = url.lastPathComponent.isEmpty ? "video.mov" : url.lastPathComponent
        let dst = dir.appendingPathComponent(name)
        do {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            try fm.copyItem(at: url, to: dst)
        } catch {
            return
        }
        // 系统"拷贝到场记"落在 Documents/Inbox：拷完清掉，避免沙盒里堆副本
        if url.path.contains("/Documents/Inbox/") { try? fm.removeItem(at: url) }

        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        guard let encoded = dst.path.addingPercentEncoding(withAllowedCharacters: allowed) else { return }
        DispatchQueue.main.async { DeepLinksIosKt.handleDeepLink(url: "scenenote://screen?shared=\(encoded)") }
    }
}
