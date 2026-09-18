package dev.scenenote.live

import dev.scenenote.asr.AsrEvent
import dev.scenenote.asr.NativeSpeakerExtractor
import dev.scenenote.audio.AudioSink
import dev.scenenote.bench.LatencyProbe
import dev.scenenote.bench.Mark
import dev.scenenote.core.model.Interaction
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.ModeSpec
import dev.scenenote.core.model.PlaybackStatus
import dev.scenenote.core.model.Speaker
import dev.scenenote.core.platform.ThermalLevel
import dev.scenenote.translate.FastTranslator
import dev.scenenote.translate.MtRequest
import dev.scenenote.tts.TtsPreference
import dev.scenenote.tts.TtsRequest
import dev.scenenote.tts.TtsRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 一行 = 一个 utterance：原文（可被 rev1 覆盖）+ 译文 + 播放状态 + 判向。 */
data class LiveLine(
    val id: String, val speaker: Speaker, val srcLang: String, val tgtLang: String,
    val text: String, val revision: Int, val startMs: Long,
    val translation: String? = null, val mtEngine: String? = null, val mtDegraded: Boolean = false, val mtReason: String? = null, val mtLatencyMs: Long? = null,
    val tts: PlaybackStatus = PlaybackStatus.QUEUED, val ttsFirstMs: Long? = null,
    /** 判向置信（脚本 / 声纹）；tentative = 两句防抖期沿用旧方向，UI 用虚线气泡 + 「?」。 */
    val dirConfidence: Float = 1f, val dirTentative: Boolean = false, val dirBasis: String = "",
    /** 被打断未播完 / 我开口时转屏幕。 */
    val interrupted: Boolean = false,
)

/** 各阶段健康（06 篇 §3.5 Stage Health 的精简版；值是状态码，UI 自己映射文案）。 */
data class PipelineHealth(
    val mt: String = "ok", val mtEngine: String? = null, val tts: String = "—" /* — | ready | none | failed */, val ttsEngine: String? = null, val lastError: String? = null,
    val thermal: ThermalLevel = ThermalLevel.NOMINAL, val lowBattery: Boolean = false,
    /** 声纹：missing（未装）/ unenrolled（未注册）/ enrolled（已注册）。 */
    val voice: String = "missing",
)

/**
 * 快路径：ASR Final → 判向（脚本 + 声纹 + 两句防抖）→ 翻译（云端 1.5 s 超时 → 端侧）→ TTS → 播放队列。
 * rev1（SenseVoice / 川渝定稿）到达时只改原文；若译文尚未送 TTS 则重译，已播过的不改（02 篇 §2.2）。
 * 草稿（rev0，refining）先上屏、翻译最多等 [REFINE_WAIT_MS] 让定稿赶上：流式 zipformer 会叠字，句尾即跑的定稿通常 0.1–1 s 就到，
 * 等它再翻译能让播出去的译文来自干净的原文；超时就照草稿翻。
 * 软半双工（规格 §5.4）：对方插话 > 300 ms 压低 −12 dB，> 1.5 s 丢弃剩余队列标「未播完」；我开口立即停并把未播译文转屏幕。
 */
class FastPath(
    private val translator: FastTranslator,
    private val ttsRouter: TtsRouter,
    private val queue: PlaybackQueue,
    private val sink: AudioSink,
    private val probe: LatencyProbe?,
    private val scope: CoroutineScope,
) {
    private val _lines = MutableStateFlow<List<LiveLine>>(emptyList())
    val lines: StateFlow<List<LiveLine>> = _lines.asStateFlow()
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()
    private val _health = MutableStateFlow(PipelineHealth())
    val health: StateFlow<PipelineHealth> = _health.asStateFlow()
    val playing: StateFlow<String?> get() = queue.playing
    private val _speaking = MutableStateFlow(false)
    /** VAD 判定有人在说（呼吸灯）。 */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private var mode: ModeSpec? = null
    private var myLang = "zh-CN"; private var otherLang = "en"
    /** 对方语言 = 自动：按定稿语种识别（SenseVoice：中 / 英 / 粤 / 日 / 韩）在会话里学；进程内记住上次学到的。 */
    private var otherAuto = false
    private var lastDetectedOther: String? = null
    private val _resolvedOtherLang = MutableStateFlow(otherLang)
    /** 当前生效的对方语言（自动时 = 已识别到的，未识别前 = 英语）。 */
    val resolvedOtherLang: StateFlow<String> = _resolvedOtherLang.asStateFlow()
    private var voiceOut = true
    private var ttsPref = TtsPreference.AUTO
    private var sessionId: String? = null
    private val mtJobs = mutableMapOf<String, Job>()
    private val ttsEnqueued = mutableSetOf<String>()
    /** 翻译正在等定稿的草稿 id（等待超时或 rev1 到达即移除）。 */
    private val awaitingRefine = mutableSetOf<String>()
    private var direction = DirectionStage(myLang, otherLang)
    private var extractor: NativeSpeakerExtractor? = null
    private val decisions = mutableMapOf<String, DirectionDecision>()
    /** 术语表（按场景词袋，进入会话时装载）：目标语言 → {源词 → 译名}；纠错映射：错 → 对。 */
    private var glossary: Map<String, Map<String, String>> = emptyMap()
    private var corrections: List<Pair<String, String>> = emptyList()
    fun setGlossary(byTarget: Map<String, Map<String, String>>, corrections: List<Pair<String, String>>) { this.glossary = byTarget; this.corrections = corrections }
    private fun applyCorrections(text: String): String = corrections.fold(text) { acc, (w, r) -> if (w.isNotBlank()) acc.replace(w, r) else acc }
    private var thermal = ThermalLevel.NOMINAL
    private var duckJob: Job? = null
    private var restoreJob: Job? = null
    private var ducked = false
    /** 「我」的声纹：注册后跨会话保留（VM 持久化到设置，下次带回来）。 */
    private var meEmbedding: FloatArray? = null
    private val _meVoice = MutableStateFlow<FloatArray?>(null)
    val meVoice: StateFlow<FloatArray?> = _meVoice.asStateFlow()
    /** 最近送播的译文（开放式耳机 / 外放泄漏过滤：ASR 输出与之相似度 > 0.8 丢弃，规格 §5.4）。 */
    private val recentSpoken = ArrayDeque<String>()

    init {
        queue.events.onEach { ev ->
            when (ev) {
                is PlaybackEvent.Started -> {
                    update(ev.utteranceId) { it.copy(tts = PlaybackStatus.PLAYING, ttsFirstMs = ev.firstChunkMs) }
                    probe?.mark(ev.utteranceId, Mark.TTS_FIRST); probe?.mark(ev.utteranceId, Mark.SINK_WRITE)   // 首帧写入 = 本机口径终点
                    if (interjectionApplies && _speaking.value && duckJob?.isActive != true) startDuckTimer()   // 对方已在说话时才开播：同样按插话处理
                }
                is PlaybackEvent.Done -> update(ev.utteranceId) { it.copy(tts = PlaybackStatus.DONE) }
                // flushed / cancelled / stale：译文本来就在屏上，标「已上屏」而不是「跳过」；只有被打断的那句算 interrupted
                is PlaybackEvent.Skipped -> update(ev.utteranceId) { it.copy(tts = if (ev.reason in SCREEN_ONLY) PlaybackStatus.SHOWN_ON_SCREEN else PlaybackStatus.SKIPPED, interrupted = it.tts == PlaybackStatus.PLAYING || ev.reason == "flushed") }
                is PlaybackEvent.Failed -> { update(ev.utteranceId) { it.copy(tts = PlaybackStatus.SKIPPED) }; _health.value = _health.value.copy(tts = "failed", lastError = ev.reason) }
            }
        }.launchIn(scope)
    }

    fun configure(mode: ModeSpec, myLang: String, otherLang: String, voiceOut: Boolean, ttsPref: TtsPreference, sessionId: String, extractor: NativeSpeakerExtractor? = null, initialMe: FloatArray? = null) {
        val auto = otherLang == Lang.AUTO
        val resolved = if (auto) (lastDetectedOther ?: Lang.EN) else otherLang
        val langChanged = myLang != this.myLang || resolved != this.otherLang || auto != this.otherAuto
        this.mode = mode; this.myLang = myLang; this.otherLang = resolved; this.otherAuto = auto; this.sessionId = sessionId
        _resolvedOtherLang.value = resolved
        this.voiceOut = voiceOut   // 出声与否由 VM 按模式决定（M3 黄标外放可覆盖 TEXT 模式）
        this.ttsPref = ttsPref
        if (meEmbedding == null && initialMe != null) { meEmbedding = initialMe; _meVoice.value = initialMe }
        if (langChanged || extractor !== this.extractor) { this.extractor = extractor; direction = DirectionStage(myLang, if (auto) Lang.AUTO else resolved, extractor, initialMe = meEmbedding) }
        translator.reset()
        _health.value = _health.value.copy(voice = when { extractor == null -> "missing"; direction.enrolled -> "enrolled"; else -> "unenrolled" })
        refreshTtsHealth()
    }

    /** 同一会话内切模式（M0 ⇄ M1 ⇄ M3）：不重启音频，只换判向 / 输出规则。 */
    fun switchMode(mode: ModeSpec, voiceOut: Boolean) { this.mode = mode; this.voiceOut = voiceOut; refreshTtsHealth() }

    fun setVoiceOut(on: Boolean) { voiceOut = on; if (!on) queue.flush(); refreshTtsHealth() }

    /** 热 / 电阶梯（规格 §5.5）：SERIOUS 强制系统 TTS；CRITICAL 暂停 TTS 只出字。 */
    fun setThermal(level: ThermalLevel, lowBattery: Boolean) {
        thermal = if (lowBattery && level < ThermalLevel.SERIOUS) ThermalLevel.SERIOUS else level
        _health.value = _health.value.copy(thermal = level, lowBattery = lowBattery)
        if (thermal == ThermalLevel.CRITICAL) queue.flush()
        refreshTtsHealth()
    }

    private val voiceRefineAllowed: Boolean get() = thermal < ThermalLevel.SERIOUS   // 规格 §5.5：serious 关声纹

    private fun effectiveTtsPref(): TtsPreference = when {
        thermal == ThermalLevel.CRITICAL -> TtsPreference.OFF
        thermal == ThermalLevel.SERIOUS && ttsPref != TtsPreference.OFF -> TtsPreference.SYSTEM
        else -> ttsPref
    }

    private fun refreshTtsHealth() {
        val tgt = when (mode?.interaction) { Interaction.SIMPLEX_OUT -> otherLang; else -> myLang }
        val c = ttsRouter.choose(tgt, if (voiceOut) effectiveTtsPref() else TtsPreference.OFF)
        _health.value = _health.value.copy(tts = if (c.available) "ready" else "none", ttsEngine = c.engine?.id ?: c.reasonCode)
    }

    fun onAsr(ev: AsrEvent) {
        when (ev) {
            is AsrEvent.Partial -> _partial.value = ev.text
            is AsrEvent.Error -> _health.value = _health.value.copy(lastError = ev.message)
            is AsrEvent.SpeechStart -> { _speaking.value = true; onSpeechStart() }
            is AsrEvent.SpeechEnd -> { _speaking.value = false; onSpeechEnd() }
            is AsrEvent.UtteranceAudio -> onAudio(ev.utteranceId, ev.samples)
            is AsrEvent.Final -> {
                _partial.value = ""
                val seg = ev.segment
                val existing = _lines.value.firstOrNull { it.id == seg.id }
                dev.scenenote.core.Diag.log("fast", "Final utt=${seg.id.take(8)} rev=${seg.revision} text=\"${seg.text}\" existing=${existing != null} mode=${mode?.id} my=$myLang other=$otherLang voiceOut=$voiceOut")
                if (existing == null) {
                    if (isLeak(seg.text)) { dev.scenenote.core.Diag.log("fast", "DROPPED as leak: \"${seg.text}\" recent=$recentSpoken"); return }   // 刚播出的译文被麦克风拾回：丢弃，不上屏
                    playingDuring[seg.id] = speechStartedWhilePlaying
                    val corrected = applyCorrections(seg.text)
                    val detected = detectedLang(seg)
                    val d = applyLid(seg.id, detected, decide(corrected))
                    decisions[seg.id] = d
                    dev.scenenote.core.Diag.log("fast", "decide → ${d.speaker} conf=${d.confidence} tentative=${d.tentative} basis=${d.basis} lid=$detected other=$otherLang")
                    if (!d.tentative && d.confidence >= 0.8f) direction.noteAccepted()
                    val (src, tgt) = langsFor(d.speaker, corrected, detected)
                    val line = LiveLine(seg.id, d.speaker, src, tgt, corrected, seg.revision, seg.startMs, dirConfidence = d.confidence, dirTentative = d.tentative, dirBasis = d.basis)
                    _lines.value = _lines.value + line
                    if (d.speaker == Speaker.ME && voiceOut && mode?.interaction != Interaction.SIMPLEX_OUT) onMeSpoke()
                    if (ev.refining) awaitingRefine += seg.id
                    translate(line, afterMs = if (ev.refining) REFINE_WAIT_MS else 0)
                } else if (seg.revision > existing.revision) {
                    val corrected = applyCorrections(seg.text)
                    val detected = detectedLang(seg)
                    val cur = decisions[seg.id] ?: DirectionDecision(existing.speaker, existing.dirConfidence, existing.dirTentative, existing.dirBasis)
                    val d = applyLid(seg.id, detected, cur)
                    val (src, tgt) = langsFor(d.speaker, corrected, detected)
                    if (d !== cur) dev.scenenote.core.Diag.log("fast", "lid → ${d.speaker} $src→$tgt other=$otherLang")
                    val updated = existing.copy(text = corrected, revision = seg.revision, speaker = d.speaker, srcLang = src, tgtLang = tgt, dirConfidence = d.confidence, dirTentative = d.tentative, dirBasis = d.basis)
                    update(seg.id) { updated }
                    val waiting = awaitingRefine.remove(seg.id)
                    if (seg.id !in ttsEnqueued && (waiting || corrected != existing.text || src != existing.srcLang || d.speaker != existing.speaker)) translate(updated)
                }
            }
        }
    }

    /** 定稿带的语种（SenseVoice LID）；草稿 / 速译（我说什么都翻成对方语言）不用。 */
    private fun detectedLang(seg: dev.scenenote.core.model.Segment): String? =
        seg.lang.takeIf { seg.revision >= 1 && it != Lang.AUTO && mode?.interaction != Interaction.SIMPLEX_OUT }

    /**
     * 定稿语种落在我的语言家族之外 → 一定是对方在说（乱码草稿会被脚本判成我）；对方语言 = 自动时顺手学到它，
     * 之后我说的话就翻成这种语言。家族相同（对方说我的语言 / 方言）分不出，交给声纹。
     */
    private fun applyLid(id: String, detected: String?, current: DirectionDecision): DirectionDecision {
        if (detected == null || Lang.family(detected) == Lang.family(myLang)) return current
        if (otherAuto && detected != otherLang) { otherLang = detected; lastDetectedOther = detected; _resolvedOtherLang.value = detected; refreshTtsHealth() }
        if (current.speaker == Speaker.OTHER) return current
        return direction.overrideBy(Speaker.OTHER, 0.9f, "lid")?.also { decisions[id] = it } ?: current
    }

    /**
     * 源 / 目标语言：① 定稿 LID 是我家族之外的语言 → 它到我的语言；② 脚本不同的语对（中 ↔ 英）里脚本本身就是语言证据，
     * 防抖只影响「谁说」的标签，不改源语言（对方语言 = 自动时按脚本猜，猜不出算对方语言）；③ 同脚本语对（普通话 ↔ 方言）才按说话人取。
     */
    private fun langsFor(speaker: Speaker, text: String? = null, detected: String? = null): Pair<String, String> {
        if (detected != null && Lang.family(detected) != Lang.family(myLang)) return detected to myLang
        if (text != null && Lang.family(myLang) != Lang.family(otherLang)) {
            val g = if (otherAuto) dev.scenenote.translate.Script.guessLang(text, otherLang) else dev.scenenote.translate.Script.pick(text, myLang, otherLang)
            return if (Lang.family(g) == Lang.family(myLang)) myLang to otherLang else g to myLang
        }
        return if (speaker == Speaker.ME) myLang to otherLang else otherLang to myLang
    }

    /** 与最近送播的译文相似度 > 0.8 → 视为泄漏（字符二元组 Dice）。 */
    private fun isLeak(text: String): Boolean {
        val t = norm(text); if (t.length < 4) return false
        return recentSpoken.any { sim(norm(it), t) > 0.8f }
    }
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun sim(a: String, b: String): Float {
        if (a.length < 2 || b.length < 2) return if (a == b) 1f else 0f
        val ga = (0 until a.length - 1).map { a.substring(it, it + 2) }.groupingBy { it }.eachCount()
        val gb = (0 until b.length - 1).map { b.substring(it, it + 2) }.groupingBy { it }.eachCount()
        val inter = ga.entries.sumOf { (k, v) -> minOf(v, gb[k] ?: 0) }
        return 2f * inter / (ga.values.sum() + gb.values.sum())
    }

    private fun decide(text: String): DirectionDecision = when (mode?.interaction) {
        Interaction.SIMPLEX_IN -> DirectionDecision(Speaker.OTHER, 1f, false, "simplex")
        Interaction.SIMPLEX_OUT -> DirectionDecision(Speaker.ME, 1f, false, "simplex")
        else -> if (mode?.autoDirection == true) direction.decideByText(text) else DirectionDecision(Speaker.OTHER, 1f, false, "fixed")
    }

    /**
     * 句音频到达：
     * - 速译（M4）：我说的话注册「我」（两句一致才落定）。
     * - 对话：未注册时用高置信我方句注册；已注册用声纹复核脚本判向，未送播则改判重译。
     * - 仅听（M0）：规格 §3.2 ⑤「我的声音不译」——声纹命中我 → 标 ME、停掉它的播放。
     * 注册与复核都要求该句期间没有译文在播（防把泄漏的 TTS / 对方注册成我）；固定方向或发热 serious 时不做声纹。
     */
    private fun onAudio(id: String, samples: FloatArray) {
        val line = _lines.value.firstOrNull { it.id == id } ?: return
        if (direction.fixed != null || !voiceRefineAllowed) return
        val quiet = playingDuring[id] != true
        when (mode?.interaction) {
            Interaction.SIMPLEX_OUT -> if (quiet && direction.enrollMe(samples)) onEnrolled()
            Interaction.SIMPLEX_IN -> {
                if (!direction.enrolled) return
                val refined = direction.refineByVoice(samples, DirectionDecision(Speaker.OTHER, 0.3f, false, "simplex")) ?: return
                if (refined.speaker == Speaker.ME) {
                    update(id) { it.copy(speaker = Speaker.ME, dirBasis = "voice", tts = PlaybackStatus.SKIPPED) }
                    if (queue.playing.value == id || id in ttsEnqueued && queue.pending.value > 0) queue.flush()
                    mtJobs.remove(id)?.cancel()
                }
            }
            else -> {
                if (mode?.autoDirection != true) return
                if (!direction.enrolled) {
                    if (quiet && line.speaker == Speaker.ME && line.dirConfidence >= 0.8f && direction.enrollMe(samples)) onEnrolled()
                    return
                }
                val cur = decisions[id] ?: return
                val refined = direction.refineByVoice(samples, cur) ?: return
                decisions[id] = refined
                if (id in ttsEnqueued) return   // 已播出的不改（02 篇 §2.2）
                val (src, tgt) = langsFor(refined.speaker, line.text)
                val updated = line.copy(speaker = refined.speaker, srcLang = src, tgtLang = tgt, dirConfidence = refined.confidence, dirTentative = false, dirBasis = refined.basis)
                update(id) { updated }
                translate(updated)
            }
        }
    }
    /** 每句开始时是否有译文在播（SpeechStart 时记录，Final 时结算）。 */
    private val playingDuring = mutableMapOf<String, Boolean>()
    private var speechStartedWhilePlaying = false

    /** 用户主动重置声纹。 */
    fun forgetVoice() { direction.forgetMe(); meEmbedding = null; _meVoice.value = null; _health.value = _health.value.copy(voice = if (extractor == null) "missing" else "unenrolled") }
    private fun onEnrolled() { meEmbedding = direction.meEmbeddingOrNull; _meVoice.value = meEmbedding; _health.value = _health.value.copy(voice = "enrolled") }

    /** 用户翻转某一行（M3 气泡左右滑 / 翻转按钮）：反向重译；返回 true = 已连续纠正 3 次，建议固定方向。 */
    fun flip(lineId: String): Boolean {
        val line = _lines.value.firstOrNull { it.id == lineId } ?: return false
        val to = if (line.speaker == Speaker.ME) Speaker.OTHER else Speaker.ME
        val suggestFixed = direction.userFlipped(to)
        val (src, tgt) = langsFor(to)
        val updated = line.copy(speaker = to, srcLang = src, tgtLang = tgt, dirConfidence = 1f, dirTentative = false, dirBasis = "user")
        update(lineId) { updated }
        ttsEnqueued -= lineId
        translate(updated)
        return suggestFixed
    }

    /** 固定方向（退化为单工）：null = 恢复自动。 */
    fun fixDirection(speaker: Speaker?) { direction.fixed = speaker }

    private fun translate(line: LiveLine, afterMs: Long = 0) {
        mtJobs.remove(line.id)?.cancel()
        mtJobs[line.id] = scope.launch {
            if (afterMs > 0) { delay(afterMs); awaitingRefine -= line.id }
            val ctx = _lines.value.filter { it.id != line.id && it.translation != null && it.srcLang == line.srcLang }.takeLast(2).map { it.text to it.translation!! }
            dev.scenenote.core.Diag.log("fast", "translate utt=${line.id.take(8)} ${line.srcLang}→${line.tgtLang} \"${line.text}\"")
            val terms = glossary[line.tgtLang].orEmpty().filterKeys { line.text.contains(it) }   // 只注入本句命中的术语
            val r = translator.translate(MtRequest(line.text, line.srcLang, line.tgtLang, ctx, glossary = terms, segmentId = line.id, sessionId = sessionId))
            if (!isActive) return@launch   // 被更新的判向 / 定稿取消：FastTranslator 把取消当失败返回，不能拿它去覆盖新任务的结果
            val res = r.result
            dev.scenenote.core.Diag.log("fast", "translated utt=${line.id.take(8)} result=${res?.text?.let { "\"$it\"" }} engine=${res?.providerId}:${res?.model} degraded=${r.degraded} reason=${r.reason} ${res?.latencyMs}ms")
            _health.value = _health.value.copy(mt = translator.health, mtEngine = res?.let { "${it.providerId}:${it.model}" } ?: r.reason, lastError = if (res == null) r.reason else _health.value.lastError)
            if (res != null) probe?.mark(line.id, Mark.MT_FIRST)
            update(line.id) { it.copy(translation = res?.text, mtEngine = res?.let { m -> "${m.providerId}:${m.model}" }, mtDegraded = r.degraded, mtReason = r.reason, mtLatencyMs = res?.latencyMs) }
            // 对话模式只朗读对方 → 我的译文（我说的话给对方看屏）；单工按模式
            val speakIt = res != null && voiceOut && (mode?.interaction != Interaction.CONVERSATION || line.speaker == Speaker.OTHER)
            dev.scenenote.core.Diag.log("fast", "speakIt=$speakIt (voiceOut=$voiceOut interaction=${mode?.interaction} speaker=${line.speaker})")
            if (speakIt) {
                val choice = ttsRouter.choose(line.tgtLang, effectiveTtsPref())
                val engine = choice.engine
                dev.scenenote.core.Diag.log("fast", "tts choose ${line.tgtLang} → ${engine?.id} reason=${choice.reasonCode}")
                if (engine != null) { ttsEnqueued += line.id; rememberSpoken(res!!.text); queue.enqueue(TtsRequest(res.text, line.tgtLang, line.id), engine) }
                else { update(line.id) { it.copy(tts = PlaybackStatus.SHOWN_ON_SCREEN) }; probe?.complete(line.id, "no_tts") }
            } else { update(line.id) { it.copy(tts = PlaybackStatus.SHOWN_ON_SCREEN) }; probe?.complete(line.id, if (res == null) "no_mt" else "screen") }
            mtJobs.remove(line.id)
        }
    }

    // ---------- 软半双工 ----------
    /** 仅听（M0）里对方一直说是常态（听讲座 / 被搭话），不是插话：不压低、不丢队列，时延靠播放队列的积压加速兜住。 */
    private val interjectionApplies: Boolean get() = mode?.interaction != Interaction.SIMPLEX_IN
    private fun onSpeechStart() {
        speechStartedWhilePlaying = queue.playing.value != null || queue.pending.value > 0
        restoreJob?.cancel(); restoreJob = null
        if (!speechStartedWhilePlaying || !interjectionApplies) return
        startDuckTimer()
    }
    private fun startDuckTimer() {
        duckJob?.cancel()
        duckJob = scope.launch {
            delay(300)                       // 插话 > 300 ms：duck −12 dB
            if (!ducked) { ducked = true; sink.volumeDb = -12f }
            delay(1200)                      // 累计 > 1.5 s：丢弃剩余队列并标「未播完」
            queue.flush()
            restore()
        }
    }
    private fun onSpeechEnd() {
        duckJob?.cancel(); duckJob = null
        if (ducked) { restoreJob?.cancel(); restoreJob = scope.launch { delay(500); restore() } }   // 语音结束 500 ms 后恢复
    }
    private fun restore() { if (ducked) { ducked = false; sink.volumeDb = 0f } }
    /** 我开口：立即停播，未播译文转屏幕文字（VAD 分不清谁在说，只能在 Final 判为我时触发；流式声纹待后续）。 */
    private fun onMeSpoke() { duckJob?.cancel(); restoreJob?.cancel(); restore(); queue.flush() }

    /** M4「朗读 · 外放」/ 礼貌退出语：把某一行（或临时文本）再送一次 TTS；gainDb 用于退出语 −6 dB。 */
    fun speak(lineId: String): Boolean {
        val line = _lines.value.firstOrNull { it.id == lineId } ?: return false
        val text = line.translation ?: line.text
        val lang = if (line.translation != null) line.tgtLang else line.srcLang
        return speakText(text, lang, lineId)
    }
    fun speakText(text: String, lang: String, utteranceId: String, gainDb: Float = 0f): Boolean {
        val engine = ttsRouter.choose(lang, if (ttsPref == TtsPreference.OFF) TtsPreference.AUTO else ttsPref).engine ?: return false
        sink.volumeDb = gainDb
        rememberSpoken(text)
        ttsEnqueued += utteranceId
        queue.enqueue(TtsRequest(text, lang, utteranceId), engine)
        if (gainDb != 0f) scope.launch {
            // 这一句播完（或被跳过 / 失败）就把音量还原
            queue.events.collect { ev ->
                val done = when (ev) { is PlaybackEvent.Done -> ev.utteranceId; is PlaybackEvent.Failed -> ev.utteranceId; is PlaybackEvent.Skipped -> ev.utteranceId; else -> null }
                if (done == utteranceId) { sink.volumeDb = 0f; throw kotlinx.coroutines.CancellationException("restored") }
            }
        }
        return true
    }

    private fun rememberSpoken(text: String) { recentSpoken.addLast(text); while (recentSpoken.size > 4) recentSpoken.removeFirst() }

    companion object {
        const val REFINE_WAIT_MS = 1_200L
        /** 这些原因下译文没念出来但留在屏上（[PlaybackQueue] 的 flush / 取消 / 积压丢弃）。 */
        private val SCREEN_ONLY = setOf("flushed", "cancelled", "stale")
    }

    private fun update(id: String, f: (LiveLine) -> LiveLine) {
        val cur = _lines.value
        val i = cur.indexOfFirst { it.id == id }
        if (i >= 0) _lines.value = cur.toMutableList().also { it[i] = f(cur[i]) }
    }

    /** 对方打断 / 我在说 / 设备切换：丢掉未播的，已在屏上的保留。 */
    fun flush(reason: String) { duckJob?.cancel(); restoreJob?.cancel(); restore(); queue.flush(); _health.value = _health.value.copy(lastError = null) }

    /** 新会话：清句子 / 队列，自动学到的对方语言也归零（上一场是日本人不代表这一场也是；礼貌卡先按英语，对方一开口就换）。 */
    fun clear() { lastDetectedOther = null; if (otherAuto) { otherLang = Lang.EN; _resolvedOtherLang.value = Lang.EN }; mtJobs.values.forEach { it.cancel() }; mtJobs.clear(); ttsEnqueued.clear(); awaitingRefine.clear(); decisions.clear(); playingDuring.clear(); recentSpoken.clear(); direction.reset(); flush("clear"); _lines.value = emptyList(); _partial.value = "" }
}
