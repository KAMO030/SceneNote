package dev.scenenote.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// ---------- core:audio / audio-route（附录 A.1；05 篇 §7.3） ----------

enum class AudioMode { DEFAULT, MEASUREMENT, LIVE_HEADSET, LIVE_SPEAKER, MEDIA }
enum class AudioRoute { BuiltIn, BluetoothA2dp, BluetoothHfp, Wired, Speaker, None }

/** 采集配置：16 kHz 单声道 PCM16，20 ms 一帧（320 采样）。 */
data class CaptureConfig(val sampleRate: Int = 16_000, val frameMs: Int = 20, val mode: AudioMode = AudioMode.DEFAULT) {
    val frameSamples: Int get() = sampleRate * frameMs / 1000
}

interface AudioSource {
    val frames: Flow<ShortArray>
    val route: StateFlow<AudioRoute>
    suspend fun start(config: CaptureConfig)
    fun stop()
}

enum class RoutePolicyAudio { HEADSET_A2DP_ONLY, SPEAKER_AEC, ANY }

sealed interface RouteEvent {
    data object NewDeviceAvailable : RouteEvent
    data object OldDeviceUnavailable : RouteEvent
    data object CategoryChange : RouteEvent
    data class Interruption(val began: Boolean) : RouteEvent
    /** 系统把路由拉回了 HFP（通话模式）——违反铁律，需要纠正并提示。 */
    data object PulledToHfp : RouteEvent
}

data class RouteState(
    val input: AudioRoute, val output: AudioRoute, val policy: RoutePolicyAudio,
    val headsetConnected: Boolean, val note: String? = null
) {
    /** 铁律成立：输入 = 内置麦，输出 = A2DP 耳机。 */
    val obeysHeadsetRule: Boolean get() = input == AudioRoute.BuiltIn && output == AudioRoute.BluetoothA2dp
}

/** 路由铁律的唯一入口：耳机模式下输入 = 内置麦、输出 = A2DP；绝不加 HFP / defaultToSpeaker；耳机断开绝不切扬声器。 */
interface RouteManager {
    val current: StateFlow<RouteState>
    val routeEvents: Flow<RouteEvent>
    suspend fun ensure(policy: RoutePolicyAudio): RouteState
    fun release()
}

/** 2 声道交织缓冲，无译文的声道填零；硬件缓冲 ≤ 40 ms；stop+flush 必须在路由回调内同步完成。 */
interface AudioSink {
    suspend fun play(chunk: ShortArray, channelMask: Int = 3)
    var volumeDb: Float
    fun stop()
    fun flush()
    /** 队列空转非空前 200 ms 送 −60 dB 噪声帧唤醒耳机。 */
    fun prime()
}

enum class HapticPattern { START /* 短-短 */, READY /* 短 */, FLIP /* 长 */, LOST /* 长-长-长 */, DIDNT_GET /* 双短 */ }
interface Haptics { fun play(pattern: HapticPattern) }

/** 平台通过 Koin platformModule 提供实现（Android 需要 Context，iOS 直接构造）。 */
interface AudioFactory {
    fun source(): AudioSource
    fun routeManager(): RouteManager
    fun sink(): AudioSink
    fun haptics(): Haptics
}
