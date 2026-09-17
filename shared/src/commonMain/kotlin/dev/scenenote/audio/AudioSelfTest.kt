package dev.scenenote.audio

import dev.scenenote.core.platform.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class SelfTestState(
    val recording: Boolean = false,
    val frames: Long = 0,
    val rmsDb: Float = -120f,
    val peakDb: Float = -120f,
    val route: RouteState? = null,
    val lastFile: String? = null,
    val lastFileBytes: Long = 0,
    val error: String? = null,
    val tonePlaying: Boolean = false,
    /** 内存里保留的最近一次录音（最多 60 s），供识别基准使用。 */
    val recordedSec: Int = 0,
)

/** I1 录音自检：采集 → 电平表 → m4a 落盘；播 1 kHz 测试音验证输出（耳机或外放，App 不区分）。 */
class AudioSelfTest(
    private val audio: AudioFactory,
    private val paths: AppPaths,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SelfTestState())
    val state: StateFlow<SelfTestState> = _state.asStateFlow()
    private val routeManager = audio.routeManager()
    private val sink = audio.sink()
    private var source: AudioSource? = null
    private var writer: PcmFileWriter? = null
    private var captureJob: Job? = null
    private var routeJob: Job? = null
    private val pcm = ArrayList<ShortArray>()
    private var pcmSamples = 0
    private val maxSamples = 16_000 * 60

    /** 最近一次录音的 PCM（16 kHz mono）；没有则 null。 */
    fun lastPcm(): ShortArray? {
        if (pcmSamples == 0) return null
        val out = ShortArray(pcmSamples); var off = 0
        for (f in pcm) { f.copyInto(out, off); off += f.size }
        return out
    }

    init {
        routeJob = scope.launch {
            routeManager.routeEvents.onEach { e ->
                if (e == RouteEvent.OldDeviceUnavailable) _state.value = _state.value.copy(tonePlaying = false)
                _state.value = _state.value.copy(route = routeManager.current.value)
            }.collect()
        }
    }

    fun start() {
        if (_state.value.recording) return
        scope.launch {
            try {
                val route = routeManager.ensure()
                _state.value = _state.value.copy(route = route, error = null)
                paths.ensureDir(paths.benchDir)
                val path = paths.join(paths.benchDir, "selftest-${Clock.System.now().toEpochMilliseconds()}.m4a")
                val w = audio.fileWriter(path)
                writer = w
                val src = audio.source().also { source = it }
                var frames = 0L; var peak = -120f
                pcm.clear(); pcmSamples = 0
                // 编码与落盘在 Default 调度器，不占主线程
                captureJob = launch(Dispatchers.Default) {
                    src.frames.onEach { frame ->
                        w.write(frame)
                        if (pcmSamples + frame.size <= maxSamples) { pcm += frame; pcmSamples += frame.size }
                        frames++
                        val rms = Pcm.rmsDb(frame)
                        if (rms > peak) peak = rms
                        if (frames % 5 == 0L) _state.value = _state.value.copy(frames = frames, rmsDb = rms, peakDb = peak)
                    }.collect()
                }
                src.start(CaptureConfig(mode = AudioMode.DEFAULT))
                _state.value = _state.value.copy(recording = true, frames = 0, peakDb = -120f, lastFile = null)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: t.toString(), recording = false)
                stop()
            }
        }
    }

    fun stop() {
        source?.stop(); source = null
        captureJob?.cancel(); captureJob = null
        val path = writer?.runCatching { close() }?.getOrNull(); writer = null
        _state.value = _state.value.copy(recording = false, lastFile = path, lastFileBytes = path?.let { paths.sizeBytes(it) } ?: 0, recordedSec = pcmSamples / 16_000)
    }

    /** 1 kHz · 600 ms，−14 dBFS，从系统当前输出设备播出。 */
    fun playTone() {
        scope.launch {
            try {
                val route = routeManager.ensure()
                _state.value = _state.value.copy(route = route, tonePlaying = true, error = null)
                sink.prime()
                sink.play(Pcm.tone(16_000, 1000.0, 600))
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: t.toString())
            } finally {
                _state.value = _state.value.copy(tonePlaying = false)
            }
        }
    }

    /** 只释放本用例自己的资源；RouteManager 是全局单例，不释放。 */
    fun release() { stop(); routeJob?.cancel(); sink.release() }
}
