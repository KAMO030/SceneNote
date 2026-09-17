package dev.scenenote.core.platform

import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreMotion.CMDeviceMotion
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceProximityStateDidChangeNotification
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * 姿态检测（iOS）：CoreMotion 重力向量 + 近距离传感器（规格 §3.1「姿态」、07 篇 §7.4）。
 *
 * 判定（设备坐标系，单位 g；竖持屏幕朝上时 gravity ≈ (0, −1, 0)，平放屏幕朝上 ≈ (0, 0, −1)）：
 * - |z| > 0.8 → FLAT（平放，正反面都算）
 * - |y| > 0.8 → 竖直：近距离传感器被遮挡 → POCKET；未遮挡且连续稳定 ≥ 800 ms → UPRIGHT
 * - 其余（横持 / 倾斜过渡）视为"不确定"，沿用上一姿态，不抖动
 * - 任意切换后至少保持 500 ms，再允许下一次切换
 *
 * 重力向量分不清"屏幕朝我"还是"朝对方"，UPRIGHT 即视为面向对方候选（由 VM 再延时确认）。
 * 采样与通知都投递到主队列；start / stop 只在 Live 期间由 VM 调用，stop 时关闭 CoreMotion 与近距离监测。
 *
 * 注意：打开 proximityMonitoringEnabled 后，传感器被遮挡时系统会自动熄屏（与通话时相同）——口袋里正好省电；
 * 举给对方看时传感器在屏幕一侧，对方正常距离不会触发。模拟器没有运动 / 近距离传感器，姿态保持 UNKNOWN。
 */
class IosPostureSensor : PostureSensor {
    private val _posture = MutableStateFlow(Posture.UNKNOWN)
    override val posture: StateFlow<Posture> = _posture.asStateFlow()

    private val motion = CMMotionManager()
    private var proximityObserver: Any? = null
    private var running = false

    /** 原始几何姿态（不含近距离），null = 过渡 / 不确定。 */
    private enum class Raw { FLAT, VERTICAL }

    private var covered = false
    private var lastRaw: Raw? = null
    private val clock = TimeSource.Monotonic
    /** 当前"候选姿态"从何时起持续观察到（用于 UPRIGHT 的 800 ms 稳定判定）。 */
    private var candidate: Posture = Posture.UNKNOWN
    private var candidateSince = clock.markNow()
    /** 上一次真正切换的时刻（500 ms 最短驻留）；初值往前拨，让第一次判定不用等。 */
    private var lastSwitch = clock.markNow() - MIN_DWELL_MS.milliseconds

    override fun start() = onMain {
        if (running) return@onMain
        running = true
        candidate = Posture.UNKNOWN; candidateSince = clock.markNow(); lastSwitch = clock.markNow() - MIN_DWELL_MS.milliseconds

        val device = UIDevice.currentDevice
        device.proximityMonitoringEnabled = true
        covered = device.proximityState
        proximityObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            UIDeviceProximityStateDidChangeNotification, `object` = null, queue = NSOperationQueue.mainQueue
        ) { _: NSNotification? ->
            covered = UIDevice.currentDevice.proximityState
            evaluate()
        }

        if (motion.deviceMotionAvailable) {
            motion.deviceMotionUpdateInterval = UPDATE_INTERVAL_S
            motion.startDeviceMotionUpdatesToQueue(NSOperationQueue.mainQueue) { data: CMDeviceMotion?, _: NSError? ->
                val d = data ?: return@startDeviceMotionUpdatesToQueue
                val (y, z) = d.gravity.useContents { y to z }
                // 稳定判定用已去重力的线性加速度（g）：走路 / 摆动 > 0.15 g 即视为不稳，重置 UPRIGHT 计时（与 Android 同语义）
                val (ax, ay, az) = d.userAcceleration.useContents { Triple(x, y, z) }
                val stable = kotlin.math.sqrt(ax * ax + ay * ay + az * az) < 0.15
                lastRaw = when {
                    abs(z) > FLAT_THRESHOLD -> Raw.FLAT
                    abs(y) > VERTICAL_THRESHOLD -> Raw.VERTICAL
                    else -> null
                }
                evaluate(stable)
            }
        }
    }

    override fun stop() = onMain {
        if (!running) return@onMain
        running = false
        if (motion.deviceMotionActive) motion.stopDeviceMotionUpdates()
        proximityObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        proximityObserver = null
        UIDevice.currentDevice.proximityMonitoringEnabled = false
        lastRaw = null; covered = false
        candidate = Posture.UNKNOWN
        _posture.value = Posture.UNKNOWN
    }

    /** 由重力采样或近距离变化触发：先算候选，再按稳定时长 + 最短驻留决定是否切换。 */
    private fun evaluate(stable: Boolean = true) {
        val now = clock.markNow()
        val next = when (lastRaw) {
            Raw.FLAT -> Posture.FLAT
            Raw.VERTICAL -> if (covered) Posture.POCKET else Posture.UPRIGHT
            null -> { if (candidate == Posture.UPRIGHT) candidateSince = now; return }   // 过渡姿态：UPRIGHT 计时重置，不切换
        }
        if (next != candidate) { candidate = next; candidateSince = now }
        if (next == Posture.UPRIGHT && !stable) { candidateSince = now; return }   // 未静止：竖直计时从头算
        if (next == _posture.value) return
        val held = (now - candidateSince).inWholeMilliseconds
        val need = if (next == Posture.UPRIGHT) UPRIGHT_STABLE_MS else 0L
        if (held < need) return
        if ((now - lastSwitch).inWholeMilliseconds < MIN_DWELL_MS) return
        _posture.value = next
        lastSwitch = now
    }

    /** UIDevice / CoreMotion 的开关都要在主线程；VM 在 Main 调度器上调用，这里只是兜底。 */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
    }

    private companion object {
        const val UPDATE_INTERVAL_S = 0.1            // 10 Hz 足够判姿态，省电
        const val FLAT_THRESHOLD = 0.8
        const val VERTICAL_THRESHOLD = 0.8
        const val UPRIGHT_STABLE_MS = 800L
        const val MIN_DWELL_MS = 500L
    }
}
