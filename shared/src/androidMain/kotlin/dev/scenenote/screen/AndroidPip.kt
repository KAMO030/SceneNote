package dev.scenenote.screen

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import dev.scenenote.core.Diag
import java.lang.ref.WeakReference

/**
 * 画中画（Android S1 字幕条）的平台侧：进程内单例，持当前 MainActivity 的弱引用。
 * - MainActivity.onCreate → [attach]，onDestroy → [detach]；`onPictureInPictureModeChanged` → [onModeChanged]
 *   （写进共享层 [PipState]，页面据此切到「字幕条」布局：黑 60% 底、白字、2 行；字号只允许 14 / 18 sp）。
 * - [enter]：2.39:1 长条（系统允许的最大宽高比；被拒绝时退 16:9）；Android 12+ 同时打开 autoEnter，
 *   用户切回视频 App 时系统自动进 PiP，无需再点。
 * - 被其他 App 的 PiP 顶掉（全系统同时只有一个 PiP）：系统回调 `onPictureInPictureModeChanged(false)`，只记状态，不自动重进。
 */
object AndroidPip {
    private var ref = WeakReference<Activity>(null)
    private val activity: Activity? get() = ref.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun attach(a: Activity) { ref = WeakReference(a) }
    fun detach(a: Activity) { if (ref.get() === a) { ref.clear(); PipState.set(false) } }
    fun onModeChanged(inPip: Boolean) { PipState.set(inPip) }

    /** 进入画中画；Activity 不可见 / 系统拒绝返回 false。 */
    fun enter(): Boolean {
        val a = activity ?: return false
        if (!a.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        for (aspect in listOf(Rational(239, 100), Rational(16, 9))) {
            val p = params(aspect, autoEnter = true)
            val ok = runCatching { a.enterPictureInPictureMode(p) }.getOrElse { Diag.log("pip", "$aspect rejected: ${it.message}"); false }
            if (ok) { runCatching { a.setPictureInPictureParams(p) }; return true }
        }
        return false
    }

    /** 抓取停止后关闭 autoEnter，避免离开 App 时又弹出空字幕条。 */
    fun disableAutoEnter() {
        val a = activity ?: return
        if (Build.VERSION.SDK_INT >= 31) runCatching { a.setPictureInPictureParams(params(Rational(239, 100), autoEnter = false)) }
    }

    private fun params(aspect: Rational, autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder().setAspectRatio(aspect).apply {
            if (Build.VERSION.SDK_INT >= 31) { setAutoEnterEnabled(autoEnter); setSeamlessResizeEnabled(false) }
        }.build()
}
