package dev.scenenote.asr

import dev.scenenote.bench.LatencyProbe
import dev.scenenote.bench.Mark
import dev.scenenote.core.model.EngineInfo
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.Segment
import dev.scenenote.core.model.Source
import dev.scenenote.core.platform.MemoryTier
import dev.scenenote.models.ModelCatalog
import dev.scenenote.models.ModelStore
import dev.scenenote.polish.TextCleaner
import dev.scenenote.translate.Script
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** 装载计划：只常驻一个流式 + 一个定稿（05 篇内存分级；四模型全驻留在 vivo 上实测 899 MB）。 */
enum class Finalizer { NONE, SENSE_VOICE, SICHUAN }
data class LoadPlan(val finalizer: Finalizer = Finalizer.SENSE_VOICE, val speaker: Boolean = false) {
    companion object {
        /**
         * 按会话语言选定稿引擎：四川话 → 川渝 Paraformer；其余 → SenseVoice（已安装才装）。
         * < 4 GB 机型（05 篇内存分级）实时档只留 zipformer + VAD，不带 SenseVoice 定稿，给 TTS 模型让内存。
         */
        fun forLang(lang: String, speaker: Boolean = false, tier: MemoryTier = MemoryTier.current) = LoadPlan(
            finalizer = when {
                lang == Lang.ZH_SICHUAN -> Finalizer.SICHUAN
                tier == MemoryTier.LOW -> Finalizer.NONE
                else -> Finalizer.SENSE_VOICE
            },
            speaker = speaker,
        )
        /** 基准：全部已安装模型都装，量最坏内存。 */
        val ALL = LoadPlan(Finalizer.SENSE_VOICE, speaker = true)
    }
}

/** 端侧引擎装载状态（真机上装载 zipformer + VAD ≈ 1–2 s 目标，见 03 篇 §3.3）。 */
sealed interface LocalEngineState {
    data object Unloaded : LocalEngineState
    data object Loading : LocalEngineState
    data class Ready(val loadMs: Map<String, Long>, val hasSenseVoice: Boolean, val hasSpeaker: Boolean, val hasSichuan: Boolean = false) : LocalEngineState
    data class Error(val reason: String) : LocalEngineState
}

/**
 * sherpa-onnx 端侧识别引擎（02 篇 §2.1 两阶段：流式 zipformer 出草稿，SenseVoice 定稿）。
 * 句尾由 Silero VAD 判定（矩阵规格：hangover 300 ms → TURN_END），zipformer 自带端点规则只作 rule3 兜底。
 */
class SherpaAsrEngine(
    private val store: ModelStore,
    private val probe: LatencyProbe? = null,
    private val numThreads: Int = 2,
) : AsrEngine {
    override val info = EngineInfo("sherpa-onnx", ModelCatalog.zipformerZhEn.id, SherpaRuntime.version)
    override val langs: Set<String> get() = if (state.value is LocalEngineState.Ready) buildSet { add(Lang.ZH_CN); add(Lang.EN); if (sichuan != null) add(Lang.ZH_SICHUAN); if (sense != null) add(Lang.YUE_HK) } else emptySet()
    override val cloud = false
    override val streaming = true

    private val _state = MutableStateFlow<LocalEngineState>(LocalEngineState.Unloaded)
    val state: StateFlow<LocalEngineState> = _state.asStateFlow()

    private var online: NativeOnlineRecognizer? = null
    /** 装载探针：确认 VAD 模型可用；每个流式会话自带独立 VAD 实例（[openStream]），共用一个会在两路并行时踩坏 native 状态。 */
    private var vad: NativeVad? = null
    private var vadSpec: VadSpec? = null
    private var sense: NativeOfflineRecognizer? = null
    private var sichuan: NativeOfflineRecognizer? = null
    private var speaker: NativeSpeakerExtractor? = null

    val hasSenseVoice: Boolean get() = sense != null
    val speakerExtractor: NativeSpeakerExtractor? get() = speaker

    private var currentPlan: LoadPlan? = null
    /** 正在使用本引擎原生句柄的流式会话数：> 0 时绝不卸载（否则 native 句柄悬空 → SIGSEGV；vivo Android 16 实测）。 */
    private var openSessions = 0
    private val loadMutex = Mutex()

    /** 已驻留的模型是否覆盖计划所需（多装了不算不兼容）。 */
    private fun covers(plan: LoadPlan): Boolean {
        val finOk = when (plan.finalizer) { Finalizer.NONE -> true; Finalizer.SENSE_VOICE -> sense != null || !store.isInstalled(ModelCatalog.senseVoice); Finalizer.SICHUAN -> sichuan != null || !store.isInstalled(ModelCatalog.paraformerSichuan) }
        val spkOk = !plan.speaker || speaker != null || !store.isInstalled(ModelCatalog.speakerCampp)
        return online != null && vad != null && finOk && spkOk
    }

    /**
     * 装载已安装的模型；VAD + 流式识别为必需，定稿 / 声纹按计划。
     * 已驻留集合覆盖计划 → 直接返回；缺的只做增量装载（不卸载已有的，尤其有会话在用时）；
     * 需要收缩驻留（换定稿模型、省内存）只在没有会话打开时做。
     */
    suspend fun load(plan: LoadPlan = LoadPlan(), loadAll: Boolean = false): LocalEngineState = loadMutex.withLock { withContext(Dispatchers.Default) {
        val ready = _state.value is LocalEngineState.Ready
        if (ready && !loadAll && covers(plan)) { currentPlan = plan; return@withContext _state.value }
        val incremental = ready && (openSessions > 0 || loadAll)
        if (ready && !incremental) unloadInternal()
        currentPlan = plan
        if (!incremental) _state.value = LocalEngineState.Loading
        val times = mutableMapOf<String, Long>()
        try {
            val vadPack = ModelCatalog.vadSilero; val asrPack = ModelCatalog.zipformerZhEn
            if (!store.isInstalled(vadPack)) error("pack not installed: ${vadPack.id}")
            if (!store.isInstalled(asrPack)) error("pack not installed: ${asrPack.id}")
            if (vad == null) times["vad"] = timed { vadSpec = VadSpec(model = store.path(vadPack, "silero_vad"), minSilenceSec = 0.3f, minSpeechSec = 0.25f, maxSpeechSec = 20f); vad = SherpaNative.vad(vadSpec!!) }
            if (online == null) times["asr"] = timed {
                online = SherpaNative.online(OnlineTransducerSpec(
                    encoder = store.path(asrPack, "encoder"), decoder = store.path(asrPack, "decoder"), joiner = store.path(asrPack, "joiner"),
                    tokens = store.path(asrPack, "tokens"), numThreads = numThreads,
                ))
            }
            val wantSense = loadAll || plan.finalizer == Finalizer.SENSE_VOICE
            val wantSichuan = loadAll || plan.finalizer == Finalizer.SICHUAN
            if (wantSense && sense == null && store.isInstalled(ModelCatalog.senseVoice)) times["sense"] = timed {
                sense = SherpaNative.senseVoice(SenseVoiceSpec(model = store.path(ModelCatalog.senseVoice, "model"), tokens = store.path(ModelCatalog.senseVoice, "tokens"), numThreads = numThreads))
            }
            if (wantSichuan && sichuan == null && store.isInstalled(ModelCatalog.paraformerSichuan)) times["sichuan"] = timed {
                sichuan = SherpaNative.paraformer(ParaformerSpec(model = store.path(ModelCatalog.paraformerSichuan, "model"), tokens = store.path(ModelCatalog.paraformerSichuan, "tokens"), numThreads = numThreads))
            }
            if ((loadAll || plan.speaker) && speaker == null && store.isInstalled(ModelCatalog.speakerCampp)) times["speaker"] = timed {
                speaker = SherpaNative.speaker(SpeakerSpec(model = store.path(ModelCatalog.speakerCampp, "3dspeaker")))
            }
            val prev = (_state.value as? LocalEngineState.Ready)?.loadMs.orEmpty()
            _state.value = LocalEngineState.Ready(prev + times, sense != null, speaker != null, sichuan != null)
        } catch (t: Throwable) {
            if (openSessions == 0) unloadInternal()
            _state.value = LocalEngineState.Error(t.message ?: t.toString())
        }
        _state.value
    } }

    /** 有会话在用时不真正卸载（延后到最后一个会话关闭）。 */
    fun unload() { if (openSessions > 0) { pendingUnload = true; return }; unloadInternal() }
    private var pendingUnload = false
    private fun unloadInternal() {
        online?.close(); online = null; vad?.close(); vad = null; vadSpec = null; sense?.close(); sense = null; sichuan?.close(); sichuan = null; speaker?.close(); speaker = null
        currentPlan = null; pendingUnload = false
        _state.value = LocalEngineState.Unloaded
    }
    internal fun sessionOpened() { openSessions++ }
    internal fun sessionClosed() { openSessions = (openSessions - 1).coerceAtLeast(0); if (openSessions == 0 && pendingUnload) unloadInternal() }

    private inline fun timed(block: () -> Unit): Long { val t = TimeSource.Monotonic.markNow(); block(); return t.elapsedNow().inWholeMilliseconds }

    override suspend fun openStream(lang: String, hotwords: List<String>): StreamingAsrSession {
        val rec = online ?: error("engine not loaded")
        val spec = vadSpec ?: error("VAD not loaded")
        val v = SherpaNative.vad(spec)   // 每会话独立（屏内字幕与实时会话可能同时开着）
        // 四川话：流式草稿仍用 zipformer（普通话口音），定稿用川渝 Paraformer 覆盖（非流式路径，02 篇 §2.1）
        val finalizer = if (lang == Lang.ZH_SICHUAN) (sichuan ?: sense) else sense
        sessionOpened()
        return SherpaStreamingSession(rec, v, finalizer, info, lang, probe, CoroutineScope(Dispatchers.Default), onClosed = { v.close(); sessionClosed() })
    }

    override suspend fun transcribe(pcm16k: ShortArray, lang: String): List<Segment> = withContext(Dispatchers.Default) {
        val floats = pcm16k.toFloatPcm()
        val s = if (lang == Lang.ZH_SICHUAN) (sichuan ?: sense) else sense
        if (s != null) {
            val r = s.transcribe(floats)
            val l1 = mapLang(r.lang, lang)
            listOf(Segment(id = Uuid.random().toString(), startMs = 0, endMs = pcm16k.size * 1000L / 16_000, lang = l1,
                rawText = r.text, text = TextCleaner.clean(r.text, l1), isFinal = true, source = Source.LOCAL, revision = 1, engine = info.copy(model = ModelCatalog.senseVoice.id)))
        } else {
            val rec = online ?: error("engine not loaded")
            val stream = rec.createStream()
            try {
                stream.accept(floats); stream.inputFinished()
                while (rec.isReady(stream)) rec.decode(stream)
                val r = rec.result(stream)
                listOf(Segment(id = Uuid.random().toString(), startMs = 0, endMs = pcm16k.size * 1000L / 16_000, lang = lang,
                    rawText = r.text, text = TextCleaner.clean(r.text, lang), isFinal = true, source = Source.LOCAL, revision = 0, engine = info))
            } finally { stream.close() }
        }
    }

    companion object {
        /** SenseVoice 的 lang 标签（zh/en/yue/ja/ko）→ BCP-47。 */
        fun mapLang(tag: String, fallback: String): String = when (tag.lowercase().trim('<', '>', '|')) {
            "zh" -> Lang.ZH_CN; "en" -> Lang.EN; "yue" -> Lang.YUE_HK; "ja" -> Lang.JA; "ko" -> Lang.KO; else -> fallback
        }
    }
}

/**
 * 一路流式会话：帧 → VAD → （说话中 + 300 ms hangover）→ zipformer 部分结果 → 句尾出草稿（rev0）；
 * 同一句的音频（预滚 + 说话期间喂给 zipformer 的全部帧）随即交给 SenseVoice 定稿（rev1）与声纹（UtteranceAudio）。
 * 不用 VAD 自己弹出的段：Silero 的分段（min_silence / max_speech 20 s）与我们的句边界（hangover / rule3）不一致，
 * 连续讲话时会把上一句的 id 配到下一句的音频上（定稿串句），且要等到 VAD 段结束才有定稿；用本句音频则对齐精确、句尾即出。
 * 流式 zipformer（int8 双语）对拖长音 / 近讲会叠字（「很很高兴兴」），定稿就是纠它的。
 */
class SherpaStreamingSession(
    private val rec: NativeOnlineRecognizer,
    private val vad: NativeVad,
    private val sense: NativeOfflineRecognizer?,
    private val info: EngineInfo,
    private val lang: String,
    private val probe: LatencyProbe?,
    private val scope: CoroutineScope,
    private val onClosed: () -> Unit = {},
) : StreamingAsrSession {
    private var closed = false
    private val frames = Channel<ShortArray?>(Channel.UNLIMITED)
    private val _events = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 64)
    override val events: Flow<AsrEvent> = _events
    private val stream = rec.createStream()
    private val worker: Job
    private var clockMs = 0L            // 已消费音频的时间轴（20 ms/帧）
    private var speaking = false
    private var hangoverMs = 0L
    private var uttStartMs = 0L
    private var uttId = ""
    private var lastPartial = ""
    private var firstPartialAt: Long? = null
    private val hangoverTotalMs = 300L
    /** 预滚：VAD 判定"开始说话"比真实起点晚 100–300 ms，把之前的帧补喂给 ASR（矩阵规格：预滚 200 ms，这里取 300 ms）。 */
    private val preRoll = ArrayDeque<FloatArray>()
    private val preRollFrames = 15
    /** 本句音频（预滚 + 说话期间的帧），句尾交给定稿与声纹；rule3 封顶 20 s ≈ 1.3 MB。 */
    private val uttAudio = ArrayList<FloatArray>()

    @Volatile private var drained = false
    private val refining = MutableStateFlow(0)
    /** endOfInput 之后 worker 已处理完全部帧、且没有还在跑的定稿（stop 时等它把 rev1 发完再关事件流）。 */
    fun isDrained(): Boolean = drained && refining.value == 0
    private var nFrames = 0L; private var nSpeechFrames = 0L
    private fun diag(msg: String) = dev.scenenote.core.Diag.log("asr", "[$clockMs ms] $msg")

    init {
        diag("session open lang=$lang sense=${sense != null}")
        worker = scope.launch {
            for (frame in frames) {
                if (frame == null) { diag("endOfInput frames=$nFrames speechFrames=$nSpeechFrames"); finalizeUtterance(force = true); drainSegments(); break }
                process(frame)
            }
            drained = true
        }
    }

    override fun push(frame: ShortArray) { frames.trySend(frame) }
    override fun forceEndpoint() { frames.trySend(ShortArray(0)) }   // 空帧 = TURN_END 标记
    override fun endOfInput() { frames.trySend(null) }

    private fun process(frame: ShortArray) {
        if (frame.isEmpty()) { finalizeUtterance(force = true); return }
        val floats = frame.toFloatPcm()
        val frameMs = frame.size * 1000L / 16_000
        vad.accept(floats)
        val speech = vad.isSpeechDetected()
        nFrames++; if (speech) nSpeechFrames++
        if (nFrames % 100 == 0L) diag("frames=$nFrames speechFrames=$nSpeechFrames speaking=$speaking vadSpeech=$speech")
        if (speech && !speaking) {
            diag("VAD speech START utt=${uttId.take(8)} preRoll=${preRoll.size}")
            speaking = true; uttId = Uuid.random().toString(); lastPartial = ""; firstPartialAt = null
            uttStartMs = clockMs - preRoll.size * frameMs
            for (f in preRoll) { stream.accept(f); uttAudio += f }          // 补喂预滚帧
            preRoll.clear()
            _events.tryEmit(AsrEvent.SpeechStart(clockMs))
        }
        if (!speaking) { preRoll.addLast(floats); while (preRoll.size > preRollFrames) preRoll.removeFirst() }
        if (speech) hangoverMs = hangoverTotalMs else if (speaking) hangoverMs -= frameMs
        if (speaking) {
            stream.accept(floats); uttAudio += floats
            while (rec.isReady(stream)) rec.decode(stream)
            val text = draftText(rec.result(stream))
            if (text.isNotBlank() && text != lastPartial) {
                lastPartial = text
                if (firstPartialAt == null) { firstPartialAt = probe?.nowMs(); diag("first partial: $text") }
                _events.tryEmit(AsrEvent.Partial(text, uttStartMs))
            }
            if (rec.isEndpoint(stream)) { diag("zipformer endpoint"); finalizeUtterance(force = false) }   // rule3 兜底（最长句）
            else if (!speech && hangoverMs <= 0) { diag("VAD hangover expired"); finalizeUtterance(force = false) }
        }
        drainSegments()
        clockMs += frameMs
    }

    /** VAD 内部会攒已完成的段直到被取走：只取走丢掉，句音频用 [uttAudio]。 */
    private fun drainSegments() { vad.popSegments() }

    /**
     * 定稿语种取舍：中 / 英 / 会话语种随时接受；日 / 韩 / 粤只在草稿不像英文时接受——SenseVoice 会把口音英文短句转写成假名
     * （vivo 实测「Today is library」→ テデイリバア），而真正的日 / 韩语在中英流式模型下只会出乱码汉字或空白。
     */
    private fun acceptLid(l1: String, draft: String): Boolean =
        l1 == lang || l1 == Lang.ZH_CN || l1 == Lang.EN || Script.guessLang(draft, "") != Lang.EN

    /**
     * 草稿为空时定稿单独成句的门槛：中英流式模型一个字都没认出来，SenseVoice 却说是中 / 英 → 多半是噪声幻觉（「Today is.」「The.」），丢；
     * 说是日 / 韩 / 粤且 ≥ 4 个字才当作它不会的语种（1 s 以上的话不会只有两三个字）。
     */
    private fun acceptOrphan(l1: String, text: String): Boolean =
        l1 != Lang.ZH_CN && l1 != Lang.EN && text.count { it.isLetterOrDigit() } >= 4

    /** 把本句音频交给声纹（UtteranceAudio）与 SenseVoice 定稿（rev1）；[draft] = 流式草稿（空 = zipformer 没认出来，可能是它不会的语种）。 */
    private fun dispatchUtterance(id: String, start: Long, samples: FloatArray, draft: String) {
        _events.tryEmit(AsrEvent.UtteranceAudio(id, samples))
        val sense = sense ?: return
        refining.update { it + 1 }
        scope.launch {
            val r = runCatching { sense.transcribe(samples) }
            refining.update { it - 1 }
            r.onSuccess { r ->
                val l1 = SherpaAsrEngine.mapLang(r.lang, lang)
                diag("SenseVoice rev1 utt=${id.take(8)} lang=${r.lang}->$l1 text=\"${r.text}\"")
                if (!acceptLid(l1, draft)) { diag("SenseVoice rev1 dropped: $l1 looks like accented English draft \"$draft\""); return@onSuccess }
                if (draft.isEmpty() && !acceptOrphan(l1, r.text)) { diag("SenseVoice orphan dropped: $l1 \"${r.text}\""); return@onSuccess }
                if (r.text.isNotBlank()) _events.tryEmit(AsrEvent.Final(Segment(id = id, startMs = start, endMs = start + samples.size * 1000L / 16_000,
                    lang = l1, rawText = r.text, text = TextCleaner.clean(r.text, l1), isFinal = true, source = Source.LOCAL, revision = 1,
                    engine = info.copy(model = ModelCatalog.senseVoice.id))))
            }.onFailure { diag("SenseVoice FAILED: $it"); _events.tryEmit(AsrEvent.Error("finalize failed: ${it.message}", recoverable = true)) }
        }
    }

    private fun finalizeUtterance(force: Boolean) {
        if (!speaking) return
        speaking = false; hangoverMs = 0
        _events.tryEmit(AsrEvent.SpeechEnd(clockMs))
        probe?.mark(uttId, Mark.VAD_END, profile = "offline")
        while (rec.isReady(stream)) rec.decode(stream)
        val text = draftText(rec.result(stream)).trim()
        rec.reset(stream)
        diag("finalize force=$force utt=${uttId.take(8)} text=\"$text\" cleaned=\"${TextCleaner.clean(text, lang)}\"")
        if (text.isNotEmpty()) {
            probe?.mark(uttId, Mark.ASR_FINAL)
            _events.tryEmit(AsrEvent.Final(Segment(id = uttId, startMs = uttStartMs, endMs = clockMs, lang = lang, rawText = text, text = TextCleaner.clean(text, lang),
                isFinal = true, source = Source.LOCAL, revision = 0, engine = info), refining = sense != null))
            dispatchUtterance(uttId, uttStartMs, concat(uttAudio), text)
        } else if (clockMs - uttStartMs >= 1_000) {
            // 中英流式模型一个字都没认出来但说了 ≥ 1 s：可能是它不会的语种（日 / 韩 / 粤），只交给 SenseVoice 定稿（rev1 直接成句）
            dispatchUtterance(uttId, uttStartMs, concat(uttAudio), "")
        }
        uttAudio.clear()
        if (force) vad.flush()
        lastPartial = ""
    }

    /** 草稿文本：去掉 zipformer 的时序叠字后再拼（token 自带空格前缀，直接拼接 = 原 text）。 */
    private fun draftText(r: NativeOnlineResult): String {
        val kept = DraftDedup.keep(r.tokens, r.timestamps)
        return if (kept.size == r.tokens.size) r.text else kept.joinToString("")
    }

    private fun concat(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size }); var off = 0
        for (p in parts) { p.copyInto(out, off); off += p.size }
        return out
    }

    fun close() { if (closed) return; closed = true; frames.close(); worker.cancel(); stream.close(); onClosed() }
}
