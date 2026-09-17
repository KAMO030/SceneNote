package dev.scenenote.core.platform

import platform.Foundation.NSThread
import platform.UIKit.UIApplication
import platform.UIKit.UIScreen
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * 会话页常亮 + 最亮（iOS）：M1 对方半屏 / M4 大字卡用（规格 §3.5「屏幕最亮、不锁屏」）。
 * - keepAwake：UIApplication.idleTimerDisabled。
 * - maxBrightness：记住当前 UIScreen.mainScreen.brightness 后设为 1.0，关闭时还原到记住的值；
 *   重复打开不覆盖记忆值（否则第二次记住的就是 1.0，还原不回去）。
 * 两者都是 UIKit 主线程 API；VM 离开页面必须调用 off 还原（LiveViewModel.end 已做）。
 */
class IosScreenKeeper : ScreenKeeper {
    /** 打开最亮前用户的亮度；null = 当前没有接管。 */
    private var savedBrightness: Double? = null

    init {
        // UIScreen.brightness 的修改在锁屏前一直生效、与 App 是否在前台无关：退到后台先还原，回前台再接管（Koin 单例，observer 不移除）
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            savedBrightness?.let { UIScreen.mainScreen.brightness = it }
        }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            if (savedBrightness != null) UIScreen.mainScreen.brightness = 1.0
        }
    }

    override fun keepAwake(on: Boolean) = onMain {
        UIApplication.sharedApplication.idleTimerDisabled = on
    }

    override fun maxBrightness(on: Boolean) = onMain {
        val screen = UIScreen.mainScreen
        if (on) {
            if (savedBrightness == null) savedBrightness = screen.brightness
            screen.brightness = 1.0
        } else {
            savedBrightness?.let { screen.brightness = it }
            savedBrightness = null
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
    }
}
