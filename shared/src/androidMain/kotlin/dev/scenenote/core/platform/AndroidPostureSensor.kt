package dev.scenenote.core.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Android 姿态检测（规格 §3.1 姿态触发 / §3.3 礼貌卡；共享层 [PostureSensor]）。
 *
 * 判定链：
 * 1. 重力向量（TYPE_GRAVITY；机型没有该合成传感器时退化为 TYPE_ACCELEROMETER 低通）
 *    |gy| / 9.81 > 0.8 → 竖直；|gz| / 9.81 > 0.8 → 水平（FLAT）；两者都不是 → 倾斜，视为 UNKNOWN。
 * 2. 竖直时再看遮挡：TYPE_PROXIMITY 报 near，或 TYPE_LIGHT < 5 lux → POCKET；
 *    没有这两个传感器的机型只用重力判定（竖直即 UPRIGHT 候选，不会出现 POCKET）。
 * 3. 竖直、未遮挡且稳定 ≥ 800 ms → UPRIGHT；其他状态至少保持 500 ms 才切换，避免抖动。
 *
 * 所有传感器采样 SENSOR_DELAY_UI，回调统一在主线程（registerListener 传入主线程 Handler）。
 * 只在 Live 期由 VM 调 start / stop；stop 后姿态复位为 UNKNOWN。
 */
class AndroidPostureSensor(context: Context) : PostureSensor {
    private val ctx = context.applicationContext
    private val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _posture = MutableStateFlow(Posture.UNKNOWN)
    override val posture: StateFlow<Posture> = _posture.asStateFlow()

    // 传感器可用性：getDefaultSensor 返回 null 即该机型没有对应硬件（或 HAL 未上报）
    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor: Sensor? = if (gravitySensor == null) sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
    private val proximitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val lightSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    /** 是否有遮挡类传感器；没有时降级为纯重力判定。 */
    val hasOcclusionSensors: Boolean get() = proximitySensor != null || lightSensor != null

    // ---- 原始读数（主线程写读） ----
    private val gravity = FloatArray(3)          // 当前重力向量（m/s²）
    private var gravityValid = false
    private val lastGravity = FloatArray(3)      // 上一帧，用于稳定性判断
    private val lpf = FloatArray(3)              // 加速度计退化路径的低通状态
    private var lpfValid = false
    private var proximityNear = false
    private var lux = Float.NaN                  // NaN = 尚未收到光线读数

    // ---- 去抖状态 ----
    private var candidate = Posture.UNKNOWN      // 当前原始判定
    private var candidateSince = 0L              // 该判定持续起点（elapsedRealtime，ms）
    private var running = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY -> { onGravity(event.values[0], event.values[1], event.values[2]) }
                Sensor.TYPE_ACCELEROMETER -> {
                    // 低通：g = α·g + (1−α)·a，α = 0.8（Android 官方示例系数），把线性加速度滤掉只留重力
                    if (!lpfValid) {
                        lpf[0] = event.values[0]; lpf[1] = event.values[1]; lpf[2] = event.values[2]; lpfValid = true
                    } else {
                        for (i in 0..2) lpf[i] = LOW_PASS_ALPHA * lpf[i] + (1f - LOW_PASS_ALPHA) * event.values[i]
                    }
                    onGravity(lpf[0], lpf[1], lpf[2])
                }
                Sensor.TYPE_PROXIMITY -> {
                    // 二值型近距离传感器：远 = maximumRange，近 = 更小的值（官方文档语义）
                    val maxRange = event.sensor.maximumRange
                    proximityNear = event.values[0] < maxRange
                    evaluate()
                }
                Sensor.TYPE_LIGHT -> { lux = event.values[0]; evaluate() }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun start() {
        val sm = sensorManager ?: run { Log.w(TAG, "无 SensorManager，姿态检测不可用"); return }
        if (running) return
        val motion = gravitySensor ?: accelSensor
        if (motion == null) { Log.w(TAG, "无重力 / 加速度传感器，姿态检测不可用"); return }
        running = true
        gravityValid = false
        lpfValid = false
        candidate = Posture.UNKNOWN
        candidateSince = SystemClock.elapsedRealtime()
        proximityNear = false
        lux = Float.NaN
        Log.d(TAG, "start: motion=${motion.name}(type ${motion.type}) proximity=${proximitySensor?.name ?: "无"} light=${lightSensor?.name ?: "无"}")
        sm.registerListener(listener, motion, SensorManager.SENSOR_DELAY_UI, mainHandler)
        proximitySensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, mainHandler) }
        lightSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, mainHandler) }
    }

    override fun stop() {
        if (!running) return
        running = false
        sensorManager?.unregisterListener(listener)
        gravityValid = false
        candidate = Posture.UNKNOWN
        _posture.value = Posture.UNKNOWN
    }

    // ---- 判定 ----

    private fun onGravity(x: Float, y: Float, z: Float) {
        if (gravityValid) { lastGravity[0] = gravity[0]; lastGravity[1] = gravity[1]; lastGravity[2] = gravity[2] }
        gravity[0] = x; gravity[1] = y; gravity[2] = z
        val stable = if (gravityValid) {
            // 相邻两帧重力向量变化 < 1.2 m/s² 视为稳定；走路 / 甩手时会超过而重置 UPRIGHT 计时
            val dx = x - lastGravity[0]; val dy = y - lastGravity[1]; val dz = z - lastGravity[2]
            sqrt(dx * dx + dy * dy + dz * dz) < STABLE_DELTA
        } else false
        gravityValid = true
        evaluate(stable)
    }

    /** 每次传感器事件都重算原始判定并推进去抖计时（重力流是连续的，足以驱动计时，不需要额外定时器）。 */
    private fun evaluate(stable: Boolean = true) {
        if (!running || !gravityValid) return
        val gx = gravity[0]; val gy = gravity[1]; val gz = gravity[2]
        val norm = sqrt(gx * gx + gy * gy + gz * gz).takeIf { it > 1f } ?: SensorManager.STANDARD_GRAVITY
        val vertical = abs(gy) / norm > VERTICAL_RATIO
        val flat = abs(gz) / norm > FLAT_RATIO
        // 有近距离传感器时只信它（夜间举屏给对方看时光线也很暗，不能凭光线判入袋）；没有才用光线退化
        val covered = if (proximitySensor != null) proximityNear else (!lux.isNaN() && lux < DARK_LUX)

        val raw = when {
            vertical && covered -> Posture.POCKET
            vertical -> Posture.UPRIGHT
            flat -> Posture.FLAT
            else -> Posture.UNKNOWN
        }
        val now = SystemClock.elapsedRealtime()
        if (raw != candidate) { candidate = raw; candidateSince = now; return }
        // UPRIGHT 还要求稳定：不稳定就重新计时
        if (raw == Posture.UPRIGHT && !stable) { candidateSince = now; return }
        val hold = if (raw == Posture.UPRIGHT) UPRIGHT_HOLD_MS else HOLD_MS
        if (now - candidateSince >= hold && _posture.value != raw) _posture.value = raw
    }

    private companion object {
        const val TAG = "PostureSensor"
        const val VERTICAL_RATIO = 0.8f      // |gy| / g
        const val FLAT_RATIO = 0.8f          // |gz| / g
        const val DARK_LUX = 5f              // 光线 < 5 lux 视为遮挡
        const val LOW_PASS_ALPHA = 0.8f      // 加速度计低通系数
        const val STABLE_DELTA = 1.2f        // 相邻帧重力变化阈值（m/s²）
        const val UPRIGHT_HOLD_MS = 800L     // 竖直未遮挡稳定 ≥ 800 ms → UPRIGHT
        const val HOLD_MS = 500L             // 其他状态至少保持 500 ms 才切换
    }
}
