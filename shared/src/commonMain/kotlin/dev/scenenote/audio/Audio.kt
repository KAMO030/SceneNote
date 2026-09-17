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

sealed interface RouteEvent {
    data object NewDeviceAvailable : RouteEvent
    data object OldDeviceUnavailable : RouteEvent
    data object CategoryChange : RouteEvent
    /** 来电 / Siri / 其他 App 抢占音频（began=true）与结束（began=false）。 */
    data class Interruption(val began: Boolean) : RouteEvent
}

/** 当前音频路由，仅供展示与日志；App 不区分耳机与扬声器（决策 2026-09-17：耳机只是一个输出设备）。 */
data class RouteState(val input: AudioRoute, val output: AudioRoute, val note: String? = null)

/**
 * 音频会话管理（单例、全局共享）：配置会话 / 焦点，上报路由与打断事件。
 * iOS：playAndRecord + defaultToSpeaker + allowBluetoothA2DP（有耳机走耳机，没有就外放）；Android：MODE_NORMAL + 媒体焦点。
 */
interface RouteManager {
    val current: StateFlow<RouteState>
    val routeEvents: Flow<RouteEvent>
    suspend fun ensure(): RouteState
    /** 会话结束：放弃音频焦点；监听保持。 */
    fun relax()
}

/**
 * 2 声道交织缓冲，无译文的声道填零；硬件缓冲 ≤ 40 ms。
 * play() 语义：把块交给播放器后即返回（可能因背压短暂挂起），不等播完；stop() 同步清空已调度的全部缓冲。
 */
interface AudioSink {
    suspend fun play(chunk: ShortArray, channelMask: Int = 3)
    var volumeDb: Float
    fun stop()
    fun flush()
    /** 队列由空转非空前调用：送 200 ms −60 dB 噪声帧唤醒耳机（由 PlaybackQueue 负责时机，I3）。 */
    fun prime()
    fun release()
}

enum class HapticPattern { START /* 短-短 */, READY /* 短 */, FLIP /* 长 */, LOST /* 长-长-长 */, DIDNT_GET /* 双短 */ }
interface Haptics { fun play(pattern: HapticPattern) }

/** 16 kHz 单声道 PCM16 → AAC-LC .m4a（Android MediaCodec+MediaMuxer；iOS AVAudioFile）。close() 返回最终文件路径。 */
interface PcmFileWriter {
    fun write(frame: ShortArray)
    fun close(): String
}

/** 平台通过 Koin platformModule 提供实现（Android 需要 Context，iOS 直接构造）。 */
interface AudioFactory {
    fun source(): AudioSource
    fun routeManager(): RouteManager
    fun sink(): AudioSink
    fun haptics(): Haptics
    fun fileWriter(path: String, sampleRate: Int = 16_000, bitrate: Int = 32_000): PcmFileWriter
}

/** 帧级工具：RMS（dBFS）与 1 kHz 测试音。 */
object Pcm {
    fun rmsDb(frame: ShortArray): Float {
        if (frame.isEmpty()) return -120f
        var acc = 0.0
        for (s in frame) { val v = s / 32768.0; acc += v * v }
        val rms = kotlin.math.sqrt(acc / frame.size)
        return if (rms <= 1e-9) -120f else (20.0 * kotlin.math.log10(rms)).toFloat()
    }

    fun tone(sampleRate: Int, hz: Double, ms: Int, amplitude: Double = 0.2): ShortArray {
        val n = sampleRate * ms / 1000
        return ShortArray(n) { i -> (amplitude * 32767.0 * kotlin.math.sin(2.0 * kotlin.math.PI * hz * i / sampleRate)).toInt().toShort() }
    }

    /** −60 dBFS 白噪声：唤醒蓝牙耳机而听不见。 */
    fun nearSilence(sampleRate: Int, ms: Int, seed: Int = 7): ShortArray {
        var x = seed
        return ShortArray(sampleRate * ms / 1000) { x = x * 1103515245 + 12345; ((x ushr 16) % 66 - 33).toShort() }
    }
}
