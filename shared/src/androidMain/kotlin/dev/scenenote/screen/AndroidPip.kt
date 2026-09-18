package dev.scenenote.screen

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import dev.scenenote.core.Diag
import java.lang.ref.WeakReference

/**
 * 画中画（Android S1 字幕条）的平台侧：进程内单例，持当前 MainActivity 的弱引用。
 * - MainActivity.onCreate → [attach]，onDestroy → [detach]；`onPictureInPictureModeChanged` → [onModeChanged]
 *   （写进共享层 [PipState]，页面据此切到「字幕条」布局：黑 60% 底、白字、2 行；字号只允许 14 / 18 sp）。
 * - [enter]：2.39:1 长条（系统允许的最大宽高比；被拒绝时退 16:9）。
 * - [armAutoEnter]：抓取一开始就武装「切走自动进小窗」——磁贴路径里用户授权完直接切回视频 App，不该再回来点「悬浮」。
 *   Android 12+ 用 `setAutoEnterEnabled`（系统接管，动画无缝）；10 / 11 没有这个开关，靠 MainActivity.onUserLeaveHint → [onUserLeaveHint] 主动进。
 * - 被其他 App 的 PiP 顶掉（全系统同时只有一个 PiP）：系统回调 `onPictureInPictureModeChanged(false)`，只记状态，不自动重进。
 */
object AndroidPip {
    private var ref = WeakReference<Activity>(null)
    private val activity: Activity? get() = ref.get()?.takeUnless { it.isFinishing || it.isDestroyed || !it.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    private val aspects = listOf(Rational(239, 100), Rational(16, 9))
    /** 抓取进行中：用户离开 App 时该进字幕条。 */
    @Volatile private var armed = false

    fun attach(a: Activity) { ref = WeakReference(a) }
    fun detach(a: Activity) { if (ref.get() === a) { ref.clear(); armed = false; PipState.set(false) } }
    fun onModeChanged(inPip: Boolean) { PipState.set(inPip) }

    /** 进入画中画；Activity 不可见 / 系统拒绝返回 false。 */
    fun enter(): Boolean {
        val a = activity ?: return false
        for (aspect in aspects) {
            val p = params(aspect, autoEnter = true)
            val ok = runCatching { a.enterPictureInPictureMode(p) }.getOrElse { Diag.log("pip", "$aspect rejected: ${it.message}"); false }
            if (ok) { armed = true; runCatching { a.setPictureInPictureParams(p) }; return true }
        }
        return false
    }

    /** 抓取开始：不进小窗，只武装「切走自动进」。 */
    fun armAutoEnter() {
        armed = true
        val a = activity ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            for (aspect in aspects) if (runCatching { a.setPictureInPictureParams(params(aspect, autoEnter = true)) }.isSuccess) return
            Diag.log("pip", "arm autoEnter: all aspects rejected")
        }
    }

    /** MainActivity.onUserLeaveHint：Android 10 / 11 没有 autoEnter，用户按 Home / 切 App 时手动进。 */
    fun onUserLeaveHint() {
        if (armed && Build.VERSION.SDK_INT < 31 && !PipState.inPip.value) enter()
    }

    /** 抓取停止后关闭 autoEnter，避免离开 App 时又弹出空字幕条。 */
    fun disableAutoEnter() {
        armed = false
        val a = activity ?: return
        if (Build.VERSION.SDK_INT >= 31) runCatching { a.setPictureInPictureParams(params(Rational(239, 100), autoEnter = false)) }
    }

    private fun params(aspect: Rational, autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder().setAspectRatio(aspect).apply {
            if (Build.VERSION.SDK_INT >= 31) { setAutoEnterEnabled(autoEnter); setSeamlessResizeEnabled(false) }
        }.build()
}
