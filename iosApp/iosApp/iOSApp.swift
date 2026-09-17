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
                .onOpenURL { url in DeepLinksIosKt.handleDeepLink(url: url.absoluteString) }
        }
    }
}
