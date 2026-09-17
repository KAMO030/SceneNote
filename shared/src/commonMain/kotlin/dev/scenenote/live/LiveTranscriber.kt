package dev.scenenote.live

import dev.scenenote.asr.AsrEvent
import dev.scenenote.asr.SherpaAsrEngine
import dev.scenenote.asr.SherpaStreamingSession
import dev.scenenote.asr.StreamingAsrSession
import dev.scenenote.audio.AudioFactory
import dev.scenenote.audio.AudioMode
import dev.scenenote.audio.AudioSource
import dev.scenenote.audio.CaptureConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 采集 → 流式识别会话 → 事件转给 [onEvent]（快路径 FastPath 接翻译 / TTS）。 */
class LiveTranscriber(
    private val audio: AudioFactory,
    private val engine: SherpaAsrEngine,
    private val scope: CoroutineScope,
    private val onEvent: (AsrEvent) -> Unit,
) {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var source: AudioSource? = null
    private var session: StreamingAsrSession? = null
    private var jobs: List<Job> = emptyList()

    /** [feedPath] 非空时用 WAV 回放替代麦克风（验收脚本）。 */
    suspend fun start(lang: String, feedPath: String? = null) {
        stop()
        _error.value = null
        val s = engine.openStream(lang, emptyList()).also { session = it }
        val src = (if (feedPath != null) dev.scenenote.audio.FileAudioSource(feedPath) else audio.source()).also { source = it }
        jobs = listOf(
            scope.launch { src.frames.onEach { s.push(it) }.collect() },
            scope.launch { s.events.onEach { ev -> if (ev is AsrEvent.Error) _error.value = ev.message; onEvent(ev) }.collect() },
        )
        src.start(CaptureConfig(mode = AudioMode.DEFAULT))
        _running.value = true
    }

    /** 速译（M4）：松手 → 立即把当前句收尾。 */
    fun forceEndpoint() { session?.forceEndpoint() }

    /** 停采集；识别会话先 endOfInput 再等它把队列里的帧处理完（≤ 600 ms）才关闭，避免丢最后一句（M4 松手）。 */
    fun stop() {
        source?.stop(); source = null
        val s = session; session = null
        val captureJob = jobs.firstOrNull()
        captureJob?.cancel()
        s?.endOfInput()
        val eventJob = jobs.getOrNull(1)
        jobs = emptyList()
        _running.value = false
        if (s is SherpaStreamingSession) scope.launch {
            withTimeoutOrNull(600) { while (!s.isDrained()) delay(10) }
            eventJob?.cancel(); s.close()
        } else eventJob?.cancel()
    }
}
