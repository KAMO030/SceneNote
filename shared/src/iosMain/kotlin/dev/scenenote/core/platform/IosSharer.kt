package dev.scenenote.core.platform

import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 系统分享面板（UIActivityViewController）：AirDrop / 备忘录 / 文件 / 微信 都走它。
 * - 文字直接作为 activityItem；文件用 file:// NSURL（沙盒内 Documents/export 的 .md / .voxnote / .png）。
 * - 宿主：key window 的 rootViewController 沿 presentedViewController 走到最顶层（Compose 页面上可能叠着 sheet）。
 * - iPad 上 UIActivityViewController 是 popover，必须给锚点，否则崩溃：锚在宿主 view 正中。
 * UIKit 只能在主线程调用。
 */
class IosSharer : Sharer {
    override fun shareText(text: String, title: String) = present(listOf(text))
    override fun shareFile(path: String, mime: String, title: String) = present(listOf(NSURL.fileURLWithPath(path)))

    private fun present(items: List<Any>) = onMain {
        val host = topViewController() ?: return@onMain
        val vc = UIActivityViewController(activityItems = items, applicationActivities = null)
        vc.popoverPresentationController?.let { pop ->
            val view = host.view
            pop.sourceView = view
            pop.sourceRect = view.bounds.useContents { CGRectMake(size.width / 2, size.height / 2, 0.0, 0.0) }
        }
        host.presentViewController(vc, animated = true, completion = null)
    }

    /** 最顶层已呈现的控制器；找不到 key window 时退回任意 window。 */
    private fun topViewController(): UIViewController? {
        val app = UIApplication.sharedApplication
        val windows = app.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { it.windows.filterIsInstance<UIWindow>() }
        val window = windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull() ?: app.keyWindow
        var vc = window?.rootViewController ?: return null
        while (true) vc = vc.presentedViewController ?: return vc
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
    }
}
