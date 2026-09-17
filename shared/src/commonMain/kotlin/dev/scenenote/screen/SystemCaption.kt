package dev.scenenote.screen

import dev.scenenote.asr.AsrEvent
import dev.scenenote.asr.LoadPlan
import dev.scenenote.asr.LocalEngineState
import dev.scenenote.asr.SherpaAsrEngine
import dev.scenenote.asr.SherpaStreamingSession
import dev.scenenote.core.platform.MemoryTier
import dev.scenenote.polish.Cue
import dev.scenenote.translate.FastTranslator
import dev.scenenote.translate.MtRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CaptionState(
    val capture: CaptureState = CaptureState.IDLE,
    val lines: List<Cue> = emptyList(),
    val partial: String = "",
    val error: String? = null,
)

/**
 * Android S1「系统字幕」：抓其他 App 的声音 → 本机识别 → 云翻译 → 字幕条（画中画）。
 * 只做字幕，不出声、不落库（会话结束即丢；规格：披露页说明"抓的是声音、不截屏"）。
 */
class SystemCaption(
    private val capture: SystemAudioCapture,
    private val engine: SherpaAsrEngine,
    private val translator: FastTranslator,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(CaptionState())
    val state: StateFlow<CaptionState> = _state.asStateFlow()
    val available: Boolean get() = capture.available
    private var session: SherpaStreamingSession? = null
    private var jobs: List<Job> = emptyList()
    private var seq = 0

    fun start(srcLang: String, tgtLang: String) {
        if (!capture.available) { _state.update { it.copy(error = "本机不支持抓取其他 App 的声音") }; return }
        scope.launch {
            _state.value = CaptionState(capture = CaptureState.REQUESTING)
            val ready = engine.load(LoadPlan.forLang(srcLang, tier = MemoryTier.current))
            if (ready is LocalEngineState.Error) { _state.update { it.copy(capture = CaptureState.ERROR, error = "语音包未下载") }; return@launch }
            val ok = capture.start()
            if (!ok) { _state.update { it.copy(capture = capture.state.value.takeIf { s -> s != CaptureState.IDLE } ?: CaptureState.DENIED) }; return@launch }
            val s = engine.openStream(srcLang, emptyList()) as SherpaStreamingSession
            session = s
            jobs = listOf(
                launch { capture.frames.onEach { s.push(it) }.collect() },
                launch { capture.state.onEach { st -> _state.update { it.copy(capture = st) }; if (st == CaptureState.BLOCKED || st == CaptureState.ERROR) stop() }.collect() },
                launch {
                    s.events.onEach { ev ->
                        when (ev) {
                            is AsrEvent.Partial -> _state.update { it.copy(partial = ev.text) }
                            is AsrEvent.Final -> {
                                val seg = ev.segment
                                if (seg.text.isBlank()) return@onEach
                                val cue = Cue(++seq, seg.startMs, seg.endMs, seg.text)
                                _state.update { it.copy(lines = (it.lines + cue).takeLast(30), partial = "") }
                                if (srcLang != tgtLang) launch {
                                    val r = translator.translate(MtRequest(seg.text, srcLang, tgtLang, sessionId = "s1"))
                                    r.result?.let { t -> _state.update { st -> st.copy(lines = st.lines.map { c -> if (c.index == cue.index) c.copy(translation = t.text) else c }) } }
                                }
                            }
                            else -> Unit
                        }
                    }.collect()
                },
            )
        }
    }

    fun stop() {
        capture.stop()
        jobs.forEach { it.cancel() }; jobs = emptyList()
        session?.let { it.endOfInput(); it.close() }; session = null
        _state.update { it.copy(capture = CaptureState.IDLE, partial = "") }
    }

    fun enterPip() = capture.enterPip()
}
