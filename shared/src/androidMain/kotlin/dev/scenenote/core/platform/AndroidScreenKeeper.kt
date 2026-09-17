package dev.scenenote.core.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * 当前前台 Activity 追踪：通过 Application.registerActivityLifecycleCallbacks 拿到最近 resume 的 Activity（弱引用，不持有）。
 * 供 [AndroidScreenKeeper] 操作 window；Activity 重建（旋转 / 深色切换）时通过 [onResumed] 回调把期望状态重新套上去。
 */
class ActivityTracker(app: Application) : Application.ActivityLifecycleCallbacks {
    private var current = WeakReference<Activity>(null)

    /** 有新的 Activity resume 时回调（主线程）。 */
    var onResumed: ((Activity) -> Unit)? = null

    val activity: Activity? get() = current.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    init { app.registerActivityLifecycleCallbacks(this) }

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
        onResumed?.invoke(activity)
    }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityDestroyed(activity: Activity) { if (current.get() === activity) current.clear() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}

/**
 * Android 常亮 + 最亮（共享层 [ScreenKeeper]；M1 对方半屏 / M4 大字卡 / M3 双屏）。
 *
 * - keepAwake：window FLAG_KEEP_SCREEN_ON（不需要 WAKE_LOCK 权限，随 window 生命周期自动失效）。
 * - maxBrightness：window.attributes.screenBrightness = 1f；关闭时还原 BRIGHTNESS_OVERRIDE_NONE（-1f = 跟随系统）。
 *
 * 期望状态记在本类里，Activity 重建后自动重新套用；离开会话页由 VM 调 keepAwake(false) / maxBrightness(false) 还原。
 * 所有 window 操作都切到主线程执行。
 */
class AndroidScreenKeeper(app: Application) : ScreenKeeper {
    private val tracker = ActivityTracker(app)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 期望状态（主线程读写）
    private var wantAwake = false
    private var wantBright = false

    init {
        // Activity 重建（旋转 / 主题切换）后新 window 没有旧 flag：把期望状态重新套上
        tracker.onResumed = { activity -> if (wantAwake || wantBright) apply(activity) }
    }

    override fun keepAwake(on: Boolean) = onMain {
        wantAwake = on
        tracker.activity?.let { apply(it) }
    }

    override fun maxBrightness(on: Boolean) = onMain {
        wantBright = on
        tracker.activity?.let { apply(it) }
    }

    private fun apply(activity: Activity) {
        val window = activity.window ?: return
        if (wantAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        val target = if (wantBright) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (lp.screenBrightness != target) {
            lp.screenBrightness = target
            window.attributes = lp
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }
}
