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
    private var vad: NativeVad? = null
    private var sense: NativeOfflineRecognizer? = null
    private var sichuan: NativeOfflineRecognizer? = null
    private var speaker: NativeSpeakerExtractor? = null

    val hasSenseVoice: Boolean get() = sense != null
    val speakerExtractor: NativeSpeakerExtractor? get() = speaker

    private var currentPlan: LoadPlan? = null

    /** 装载已安装的模型；VAD + 流式识别为必需，定稿 / 声纹按计划。计划变化时重新装载。 */
    suspend fun load(plan: LoadPlan = LoadPlan(), loadAll: Boolean = false): LocalEngineState = withContext(Dispatchers.Default) {
        if (_state.value is LocalEngineState.Ready && currentPlan == plan && !loadAll) return@withContext _state.value
        if (_state.value is LocalEngineState.Ready) unload()
        currentPlan = plan
        _state.value = LocalEngineState.Loading
        val times = mutableMapOf<String, Long>()
        try {
            val vadPack = ModelCatalog.vadSilero; val asrPack = ModelCatalog.zipformerZhEn
            if (!store.isInstalled(vadPack)) error("未安装 ${vadPack.name}")
            if (!store.isInstalled(asrPack)) error("未安装 ${asrPack.name}")
            times["vad"] = timed { vad = SherpaNative.vad(VadSpec(model = store.path(vadPack, "silero_vad"), minSilenceSec = 0.3f, minSpeechSec = 0.25f, maxSpeechSec = 20f)) }
            times["asr"] = timed {
                online = SherpaNative.online(OnlineTransducerSpec(
                    encoder = store.path(asrPack, "encoder"), decoder = store.path(asrPack, "decoder"), joiner = store.path(asrPack, "joiner"),
                    tokens = store.path(asrPack, "tokens"), numThreads = numThreads,
                ))
            }
            val wantSense = loadAll || plan.finalizer == Finalizer.SENSE_VOICE
            val wantSichuan = loadAll || plan.finalizer == Finalizer.SICHUAN
            if (wantSense && store.isInstalled(ModelCatalog.senseVoice)) times["sense"] = timed {
                sense = SherpaNative.senseVoice(SenseVoiceSpec(model = store.path(ModelCatalog.senseVoice, "model"), tokens = store.path(ModelCatalog.senseVoice, "tokens"), numThreads = numThreads))
            }
            if (wantSichuan && store.isInstalled(ModelCatalog.paraformerSichuan)) times["sichuan"] = timed {
                sichuan = SherpaNative.paraformer(ParaformerSpec(model = store.path(ModelCatalog.paraformerSichuan, "model"), tokens = store.path(ModelCatalog.paraformerSichuan, "tokens"), numThreads = numThreads))
            }
            if ((loadAll || plan.speaker) && store.isInstalled(ModelCatalog.speakerCampp)) times["speaker"] = timed {
                speaker = SherpaNative.speaker(SpeakerSpec(model = store.path(ModelCatalog.speakerCampp, "3dspeaker")))
            }
            _state.value = LocalEngineState.Ready(times, sense != null, speaker != null, sichuan != null)
        } catch (t: Throwable) {
            unload(); _state.value = LocalEngineState.Error(t.message ?: t.toString())
        }
        _state.value
    }

    fun unload() {
        online?.close(); online = null; vad?.close(); vad = null; sense?.close(); sense = null; sichuan?.close(); sichuan = null; speaker?.close(); speaker = null
        currentPlan = null
        _state.value = LocalEngineState.Unloaded
    }

    private inline fun timed(block: () -> Unit): Long { val t = TimeSource.Monotonic.markNow(); block(); return t.elapsedNow().inWholeMilliseconds }

    override suspend fun openStream(lang: String, hotwords: List<String>): StreamingAsrSession {
        val rec = online ?: error("引擎未装载")
        val v = vad ?: error("VAD 未装载")
        // 四川话：流式草稿仍用 zipformer（普通话口音），定稿用川渝 Paraformer 覆盖（非流式路径，02 篇 §2.1）
        val finalizer = if (lang == Lang.ZH_SICHUAN) (sichuan ?: sense) else sense
        return SherpaStreamingSession(rec, v, finalizer, info, lang, probe, CoroutineScope(Dispatchers.Default))
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
) : StreamingAsrSession {
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
                if (frame == null) { diag("endOfInput frames=$nFrames speechFrames=$nSpeechFrames"); finalizeUtterance(force = true); break }
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
        // 取走 VAD 已完成的段：声纹判向 + SenseVoice 定稿的音频
        for (seg in vad.popSegments()) {
            diag("VAD segment popped samples=${seg.samples.size} (${seg.samples.size / 16} ms) pendingAudio=${pendingAudio?.take(8)} pendingSense=${pendingSense?.first?.take(8)}")
            pendingAudio?.let { id -> _events.tryEmit(AsrEvent.UtteranceAudio(id, seg.samples)) }
            if (sense == null || pendingSense == null) { pendingAudio = null; continue }
            val (id, start) = pendingSense!!; pendingSense = null; pendingAudio = null
            val floatsSeg = seg.samples
            scope.launch {
                runCatching { sense.transcribe(floatsSeg) }.onSuccess { r ->
                    val l1 = SherpaAsrEngine.mapLang(r.lang, lang)
                    diag("SenseVoice rev1 utt=${id.take(8)} lang=${r.lang}->$l1 text=\"${r.text}\"")
                    if (r.text.isNotBlank()) _events.tryEmit(AsrEvent.Final(Segment(id = id, startMs = start, endMs = start + floatsSeg.size * 1000L / 16_000,
                        lang = l1, rawText = r.text, text = TextCleaner.clean(r.text, l1), isFinal = true, source = Source.LOCAL, revision = 1,
                        engine = info.copy(model = ModelCatalog.senseVoice.id))))
                }.onFailure { diag("SenseVoice FAILED: $it"); _events.tryEmit(AsrEvent.Error("定稿失败：${it.message}", recoverable = true)) }
            }
        }
        clockMs += frameMs
    }

    private var pendingSense: Pair<String, Long>? = null
    /** 等待 VAD 段音频的 utteranceId（声纹用；无定稿引擎时也发）。 */
    private var pendingAudio: String? = null

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
            if (sense != null) pendingSense = uttId to uttStartMs
            pendingAudio = uttId
        }
        if (force) vad.flush()
        lastPartial = ""
    }

    fun close() { frames.close(); worker.cancel(); stream.close() }
}
