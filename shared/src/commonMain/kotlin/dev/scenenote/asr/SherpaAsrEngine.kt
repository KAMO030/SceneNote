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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            if (!store.isInstalled(vadPack)) error("未安装 ${vadPack.name}")
            if (!store.isInstalled(asrPack)) error("未安装 ${asrPack.name}")
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
        val rec = online ?: error("引擎未装载")
        val spec = vadSpec ?: error("VAD 未装载")
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
            val rec = online ?: error("引擎未装载")
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
 * 一路流式会话：帧 → VAD → （说话中 + 300 ms hangover）→ zipformer 部分结果；VAD 段完成 → 定稿（rev0 = zipformer，rev1 = SenseVoice 可选）。
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

    @Volatile private var drained = false
    /** endOfInput 之后 worker 是否已处理完全部帧（基准用）。 */
    fun isDrained(): Boolean = drained
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
            for (f in preRoll) stream.accept(f)          // 补喂预滚帧
            preRoll.clear()
            _events.tryEmit(AsrEvent.SpeechStart(clockMs))
        }
        if (!speaking) { preRoll.addLast(floats); while (preRoll.size > preRollFrames) preRoll.removeFirst() }
        if (speech) hangoverMs = hangoverTotalMs else if (speaking) hangoverMs -= frameMs
        if (speaking) {
            stream.accept(floats)
            while (rec.isReady(stream)) rec.decode(stream)
            val text = rec.result(stream).text
            if (text.isNotBlank() && text != lastPartial) {
                lastPartial = text
                if (firstPartialAt == null) { firstPartialAt = probe?.nowMs(); diag("first partial: $text") }
                _events.tryEmit(AsrEvent.Partial(text, uttStartMs))
            }
            if (rec.isEndpoint(stream)) { diag("zipformer endpoint"); finalizeUtterance(force = false) }   // rule3 兜底（最长句）
            else if (!speech && hangoverMs <= 0) { diag("VAD hangover expired"); finalizeUtterance(force = false) }
        }
        // 取走 VAD 已完成的段：声纹判向 + SenseVoice 定稿的音频。
        // VAD 的 minSilence(0.3 s) 与我们的 hangover(300 ms) 谁先到不确定：段先弹出就先存起来等 finalize 认领；
        // finalize 先到就挂起 utt 等段（否则会把上一句的 id 配到这一句的音频上 → 定稿串句，vivo 实测）。
        drainSegments()
        clockMs += frameMs
    }

    private fun drainSegments() {
        for (seg in vad.popSegments()) {
            diag("VAD segment popped samples=${seg.samples.size} (${seg.samples.size / 16} ms) pending=${pendingUtt?.first?.take(8)} orphans=${orphanSegs.size}")
            val p = pendingUtt
            if (p != null) { pendingUtt = null; dispatchSegment(p.first, p.second, seg.samples) }
            else { orphanSegs.addLast(clockMs to seg.samples); while (orphanSegs.size > 3) orphanSegs.removeFirst() }
        }
    }

    /** finalize 已发生、还在等 VAD 段音频的 utt（id, startMs）。 */
    private var pendingUtt: Pair<String, Long>? = null
    /** VAD 段先于 finalize 弹出：按弹出时的时钟暂存，finalize 时认领「本句开始之后弹出」的那段。 */
    private val orphanSegs = ArrayDeque<Pair<Long, FloatArray>>()

    /** 定稿可接受的语种：会话语种 + 流式草稿模型本身的中英（SenseVoice 把英文短句误判成日 / 韩时保留草稿，vivo 实测「Today is library」→ ja）。 */
    private val acceptLangs: Set<String> = setOf(lang, Lang.ZH_CN, Lang.EN) + (if (lang == Lang.ZH_SICHUAN || lang == Lang.YUE_HK) setOf(Lang.ZH_CN) else emptySet())

    /** 把 VAD 段音频交给声纹（UtteranceAudio）与 SenseVoice 定稿（rev1）。 */
    private fun dispatchSegment(id: String, start: Long, samples: FloatArray) {
        _events.tryEmit(AsrEvent.UtteranceAudio(id, samples))
        val sense = sense ?: return
        scope.launch {
            runCatching { sense.transcribe(samples) }.onSuccess { r ->
                val l1 = SherpaAsrEngine.mapLang(r.lang, lang)
                diag("SenseVoice rev1 utt=${id.take(8)} lang=${r.lang}->$l1 text=\"${r.text}\"")
                if (l1 !in acceptLangs) { diag("SenseVoice rev1 dropped: $l1 not in $acceptLangs"); return@onSuccess }
                if (r.text.isNotBlank()) _events.tryEmit(AsrEvent.Final(Segment(id = id, startMs = start, endMs = start + samples.size * 1000L / 16_000,
                    lang = l1, rawText = r.text, text = TextCleaner.clean(r.text, l1), isFinal = true, source = Source.LOCAL, revision = 1,
                    engine = info.copy(model = ModelCatalog.senseVoice.id))))
            }.onFailure { diag("SenseVoice FAILED: $it"); _events.tryEmit(AsrEvent.Error("定稿失败：${it.message}", recoverable = true)) }
        }
    }

    private fun finalizeUtterance(force: Boolean) {
        if (!speaking) return
        speaking = false; hangoverMs = 0
        _events.tryEmit(AsrEvent.SpeechEnd(clockMs))
        probe?.mark(uttId, Mark.VAD_END, profile = "offline")
        while (rec.isReady(stream)) rec.decode(stream)
        val text = rec.result(stream).text.trim()
        rec.reset(stream)
        diag("finalize force=$force utt=${uttId.take(8)} text=\"$text\" cleaned=\"${TextCleaner.clean(text, lang)}\"")
        if (text.isNotEmpty()) {
            probe?.mark(uttId, Mark.ASR_FINAL)
            _events.tryEmit(AsrEvent.Final(Segment(id = uttId, startMs = uttStartMs, endMs = clockMs, lang = lang, rawText = text, text = TextCleaner.clean(text, lang),
                isFinal = true, source = Source.LOCAL, revision = 0, engine = info)))
            // 认领本句期间弹出的 VAD 段（弹出时钟 ≥ 本句开始）；没有就挂起等下一次弹出
            val idx = orphanSegs.indexOfFirst { it.first >= uttStartMs }
            if (idx >= 0) { val (_, samples) = orphanSegs.removeAt(idx); repeat(idx) { orphanSegs.removeFirst() }; dispatchSegment(uttId, uttStartMs, samples) }
            else pendingUtt = uttId to uttStartMs
        } else pendingUtt = null
        if (force) vad.flush()
        lastPartial = ""
    }

    fun close() { if (closed) return; closed = true; frames.close(); worker.cancel(); stream.close(); onClosed() }
}
