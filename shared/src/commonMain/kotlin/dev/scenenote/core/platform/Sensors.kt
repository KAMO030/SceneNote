package dev.scenenote.core.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 姿态（03 篇 §3.3 / 07 篇 §7.4）：只在 App 前台亮屏时有意义。
 * FLAT = 水平放着；POCKET = 竖直但被遮挡（近距离传感器 / 光线极暗）；UPRIGHT = 竖直、未遮挡且稳定 ≥ 1 s（举到胸前或朝向对方）。
 * 重力传感器分不清"屏幕朝我"还是"朝对方"，M1 的真实路径是解锁 → 掏出 → 翻转，所以 UPRIGHT 即视为面向对方的候选。
 */
enum class Posture { UNKNOWN, FLAT, POCKET, UPRIGHT }

interface PostureSensor {
    val posture: StateFlow<Posture>
    fun start()
    fun stop()
}

/** 热 / 电四级阶梯（规格 §5.5）：iOS ProcessInfo.thermalState；Android PowerManager 热状态 NONE/LIGHT → NOMINAL、MODERATE → FAIR、SEVERE → SERIOUS、CRITICAL+ → CRITICAL。 */
enum class ThermalLevel { NOMINAL, FAIR, SERIOUS, CRITICAL }

interface ThermalMonitor {
    val level: StateFlow<ThermalLevel>
    /** 电量 < 20% → 省电（与 SERIOUS 同一组动作）。 */
    val lowBattery: StateFlow<Boolean>
    fun start()
    fun stop()
}

/** 会话页常亮 + 最亮（M1 对方半屏 / M4 大字卡）；离开页面必须还原。 */
interface ScreenKeeper {
    fun keepAwake(on: Boolean)
    fun maxBrightness(on: Boolean)
}

/** 会话期耳机媒体键（仅 Live 期间接管：单击 = 暂停 / 继续，双击 = 跳过当前译文，三击 = 重播上一句）。 */
enum class MediaKey { PLAY_PAUSE, NEXT, PREVIOUS }

interface MediaKeys {
    val events: SharedFlow<MediaKey>
    /** 接管：注册 Now Playing / MediaSession，标题给系统面板看。 */
    fun activate(title: String)
    fun deactivate()
}

// ---------- 无实现平台的占位（不崩溃、不触发） ----------
class NoopPostureSensor : PostureSensor {
    override val posture = MutableStateFlow(Posture.UNKNOWN)
    override fun start() {}
    override fun stop() {}
}
class NoopThermalMonitor : ThermalMonitor {
    override val level = MutableStateFlow(ThermalLevel.NOMINAL)
    override val lowBattery = MutableStateFlow(false)
    override fun start() {}
    override fun stop() {}
}
class NoopScreenKeeper : ScreenKeeper {
    override fun keepAwake(on: Boolean) {}
    override fun maxBrightness(on: Boolean) {}
}
class NoopMediaKeys : MediaKeys {
    override val events = MutableSharedFlow<MediaKey>(extraBufferCapacity = 8)
    override fun activate(title: String) {}
    override fun deactivate() {}
}
