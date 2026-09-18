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
import dev.scenenote.core.i18n.UiText
import dev.scenenote.core.i18n.uiError
import dev.scenenote.core.i18n.uiText
import dev.scenenote.shared.resources.*
import org.jetbrains.compose.resources.getString

data class CaptionState(
    val capture: CaptureState = CaptureState.IDLE,
    val lines: List<Cue> = emptyList(),
    val partial: String = "",
    val error: UiText? = null,
    /** 最近一次翻译没拿到结果（云端断了、端侧也没有）：字幕条退回显示原文，而不是一直空着。 */
    val mtUnavailable: Boolean = false,
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
    /**
     * 磁贴 / 深链 `scenenote://syscaption?autostart=1` 的一次性信号：App 层置 true 再导航，页面收到后 [consumeAutostart] 并 [start]。
     * 不走导航参数是因为页面可能已经开着（launchSingleTop 复用同一 entry，参数不会刷新）。
     */
    private val _autostart = MutableStateFlow(false)
    val autostart: StateFlow<Boolean> = _autostart.asStateFlow()
    fun requestAutostart() { _autostart.value = true }
    fun consumeAutostart() { _autostart.value = false }
    private var session: SherpaStreamingSession? = null
    private var jobs: List<Job> = emptyList()
    private var seq = 0
    /** 识别段 id → 字幕行号：定稿（rev1）替换同一行，不另起一行。只在事件收集协程里读写。 */
    private val segLine = HashMap<String, Int>()
    /** 行号 → 这行的翻译任务：定稿改了文字 / 语种就取消重翻。 */
    private val mtJobs = HashMap<Int, Job>()

    /** [srcLang] 只用来选识别引擎、猜不出语种时兜底；每句按识别出的语种翻成 [tgtLang]，本来就是 [tgtLang] 的不翻。 */
    fun start(srcLang: String, tgtLang: String) {
        if (!capture.available) { _state.update { it.copy(error = UiText.res(Res.string.syscap_unsupported)) }; return }
        if (_state.value.capture == CaptureState.REQUESTING || _state.value.capture == CaptureState.CAPTURING) return   // 磁贴重复点：已在跑就不再开第二路
        scope.launch {
            _state.value = CaptionState(capture = CaptureState.REQUESTING)
            segLine.clear(); mtJobs.clear()
            val ready = engine.load(LoadPlan.forLang(srcLang, tier = MemoryTier.current))
            if (ready is LocalEngineState.Error) { _state.update { it.copy(capture = CaptureState.ERROR, error = UiText.res(Res.string.live_pack_missing)) }; return@launch }
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
                                val known = segLine[seg.id]
                                if (known != null && _state.value.lines.none { it.index == known }) return@onEach   // 早已滚出窗口
                                val index = known ?: ++seq
                                segLine[seg.id] = index
                                segLine.values.removeAll { it <= index - 30 }; mtJobs.keys.removeAll { it <= index - 30 }
                                val lang = cueLang(seg, srcLang)
                                // 不用翻的句子译文就是原文（字幕条只挑有译文的行显示）
                                val cue = Cue(index, seg.startMs, seg.endMs, seg.text, translation = if (needsMt(lang, tgtLang)) null else seg.text)
                                _state.update { st -> st.copy(lines = if (known != null) st.lines.map { if (it.index == index) cue else it } else (st.lines + cue).takeLast(30), partial = "") }
                                mtJobs.remove(index)?.cancel()
                                if (cue.translation == null) mtJobs[index] = launch {
                                    val r = translator.translate(MtRequest(seg.text, lang, tgtLang, sessionId = "s1"))
                                    val t = r.result
                                    if (t == null) _state.update { it.copy(mtUnavailable = true) }
                                    else _state.update { st -> st.copy(mtUnavailable = false, lines = st.lines.map { c -> if (c.index == index && c.text == seg.text) c.copy(translation = t.text) else c }) }
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
        jobs.forEach { it.cancel() }; jobs = emptyList()   // 翻译任务是事件收集协程的子任务，一起取消
        session?.let { it.endOfInput(); it.close() }; session = null
        _state.update { it.copy(capture = CaptureState.IDLE, partial = "") }
    }

    fun enterPip() = capture.enterPip()
}
