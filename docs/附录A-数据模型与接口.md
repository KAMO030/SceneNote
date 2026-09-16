# 附录 A · 核心 Kotlin 数据模型与接口草案

> **这篇讲什么**：共享层（commonMain）里核心数据结构和接口的 Kotlin 草案：场景、识别、润色、信任（A.1）；实时快路径（A.2）；P2P 协议（A.3）；屏内（A.4）；以及两份规格里旧名字到主方案新名字的映射（A.5）。**三份文档里的模块名和数据结构以本附录为唯一真相源。**
> **谁该看**：开发。
> **交叉引用说明**：正文里的 "§x.y" 是拆分前完整版的章节编号，对应关系：§0–§1 → 01 篇，§2 → 02 篇，§3 → 03 篇，§4–§6 → 04 篇，§7.1–7.4 → 05 篇，§7.5–7.9 → 06 篇，§7.10–7.14 → 07 篇，§8 → 08 篇，§9 → 09 篇，§10 → 10 篇，§11 → 11 篇，§12 → 12 篇，附录 A / B → 附录篇。完整版存于 `archive/`。

## 先看这个（大白话）

- `ScenePreset` 是一张场景卡的全部参数；`Transcript / Segment` 是识别结果的统一表示（三种引擎都映射到它）；`PrivacyMode` 四档、`RoutePolicy` 三态、`PipelineProfile` 四档就是 02 / 03 里那些开关。
- `LlmBackend` 描述润色用哪个后端（云端 / 苹果系统模型 / 谷歌系统模型 / 本地 GGUF / 纯规则），`LlmGateway` 把它映射到 Koog 的执行器。
- `LivePipeline` 的每个 Stage 都是 `Stage<I, O>`，带超时和健康状态；`AudioSink` 必须能同步停止并清空（耳机断开铁律）。
- P2P 的 `AudioFrame` 帧头带序号、时间戳、计数低位和说话人置信度；控制消息是一个密封类族；密钥模型是 sender-key。
- 屏内的 `ScreenInConfig`、`SubtitleTrack / Cue`、`CaptureProbe`、`CaptionHost` 对应 03 的 S 系列。
- 有些类型标了"接口而非 expect"——那是只能在 Swift 里实现再注入的东西（见 05）。

---

## 附录 A：核心 Kotlin 数据模型与接口草案

### A.1 场景、识别、润色、信任（v1 保留 + 实时字段）

```kotlin
// ---------- core:model（全部不可变，kotlinx.serialization） ----------
@Serializable enum class Style { NEUTRAL, FORMAL, CASUAL, BUSINESS, ACADEMIC, SOCIAL, CUSTOM }
@Serializable enum class SceneKind {
    MEETING, DICTATION, DIALECT_FAMILY, LECTURE, INTERVIEW, SUPPORT, CUSTOM,
    LIVE_TALK /* 面对面对话（旗舰） */, LECTURE_LISTEN /* 讲座旁听 = M0 + 学术，v1.1 */, SCREEN_IN /* 屏内 */
}
@Serializable enum class SubScene { WECHAT, EMAIL, SOCIAL, QUICK_PHRASE /* M4 速译一句 */,
    VIDEO_SUBTITLE, COURSE_NOTES, DRAMA_SPEAKING /* 屏内三子卡 */ }
@Serializable enum class RoutePolicy { LOCAL_ONLY, AUTO, CLOUD_FIRST }
@Serializable enum class Source { LOCAL, CLOUD, REMOTE /* P2P 远端流经听者本机 ASR */ }

@Serializable sealed interface PrivacyMode {
    // 四档只约束互联网出站；局域网出站（P2P 音频 / HINT / 网页字幕）由 Locked 单独禁止、LocalWithPerSegmentConsent 需会话级一次授权、其余允许并在账本单列（§7.12）
    @Serializable data object Locked : PrivacyMode                        // App 自身不向第三方 API / 局域网对端 / 模型直链发任何请求（系统托管资产下载除外，§4.5）
    @Serializable data object LocalWithPerSegmentConsent : PrivacyMode    // 互联网默认不出站，按 segmentId / 会话临时授权；局域网 P2P 需会话级一次授权
    @Serializable data object TextOnlyCloud : PrivacyMode                 // 文本可上互联网，音频不可；局域网音频（P2P）允许并单列记账
    @Serializable data object AudioCloud : PrivacyMode                    // 音频与文本可上互联网；局域网允许
}

/** tag 取值（三份文档统一用此 BCP-47 写法；《矩阵规格》旧 `Lang` 枚举的 zh-Hans / yue / zh-Hans-x-sichuan / und 映射为 zh-CN / yue-HK / zh-CN-sichuan / auto，见 A.5）：
 *  zh-CN（含东北等官话口音，不单列）、yue-HK、zh-CN-sichuan、wuu-CN、nan-CN、en、ja、ko、de、fr、es、auto（仅 zh/yue/en 判别） */
@Serializable data class LangChip(val tag: String, val default: Boolean = false, val shownOnCard: Boolean = false)
@Serializable data class VadProfile(val thresholdx1000: Int = 500, val minSilenceMs: Int = 250, val minSpeechMs: Int = 250,
    val bgmReject: Boolean = false /* 媒体档：谱平坦度检测丢弃纯音乐段 */) {
    companion object { fun media() = VadProfile(thresholdx1000 = 650, minSilenceMs = 600, minSpeechMs = 250, bgmReject = true) }  // 屏内媒体档预设；无独立 PipelineProfile.SCREEN_IN
}
@Serializable data class EntryBinding(val kind: String /* control, actionButton, actionButtonLong, tile, tileScreenIn, shortcut, widget, mediaKey */, val id: String)
@Serializable data class FewShot(val before: String, val after: String)

@Serializable enum class ProfileTier { OFFLINE, HYBRID, ONLINE, S2S }

@Serializable data class ScenePreset(
    val id: String, val kind: SceneKind, val subScene: SubScene? = null, val name: String, val version: Int,
    val route: RoutePolicy, val cloudProviderId: String? = null, val refineByDefault: Boolean = false,
    val langChips: List<LangChip>, val dialectPriority: List<String> = emptyList(),
    val style: Style, val customStyleInstruction: String? = null,      // CUSTOM 时 styleVer = sha256(描述+示例)[0..8]
    val translationTargets: List<String> = emptyList(),
    val templateId: String, val privacy: PrivacyMode,
    val hotwordBucket: String, val fewShots: List<FewShot> = emptyList(),
    val vad: VadProfile = VadProfile(),
    val audioMode: AudioMode = AudioMode.DEFAULT,                          // DEFAULT / MEASUREMENT / LIVE_HEADSET / LIVE_SPEAKER / MEDIA
    val entryBindings: List<EntryBinding> = emptyList(),
    // ---- v2 实时字段（录音场景为 null）----
    val liveModeId: String? = null,                                        // "M0".."M12"，引用内置 ModeSpec 表
    val screenModeId: String? = null,                                      // "S1".."S8"，引用内置 ScreenInModeSpec 表
    val profilePreference: ProfileTier = ProfileTier.HYBRID,
    val captionStyle: CaptionStyle? = null, val dubbing: DubbingPolicy = DubbingPolicy.OFF
)

@Serializable data class EngineInfo(val provider: String, val model: String, val version: String)
@Serializable data class Word(val startMs: Long, val endMs: Long, val text: String, val confidence: Float? = null)
@Serializable data class Edit(val type: String, val before: String, val after: String)
@Serializable data class Flag(val type: String /* uncertain, fact_risk, fact_check_failed, pii, term_replaced */, val span: String, val reason: String? = null)

/** 识别层不可变段；润色/翻译结果与修订放独立表，Transcript 本身不持 var。lang 为段级属性。speaker 在实时会话映射为 ME / OTHER / REMOTE:<streamId>。 */
@Serializable data class Segment(
    val id: String, val startMs: Long, val endMs: Long, val lang: String,
    val rawText: String, val text: String, val confidence: Float? = null, val speaker: String? = null,
    val words: List<Word> = emptyList(), val isFinal: Boolean,
    val source: Source, val revision: Int, val engine: EngineInfo, val pendingCloud: Boolean = false
)
@Serializable data class Transcript(val id: String, val sceneId: String, val defaultLang: String,
    val engine: EngineInfo, val segments: List<Segment>)

/** accepted 由 S3 决定：校验通过且未超风格阈值 → true；fact_check_failed → false。 */
@Serializable data class PolishResult(val segmentId: String, val polished: String,
    val translations: Map<String, String> = emptyMap(), val edits: List<Edit> = emptyList(),
    val flags: List<Flag> = emptyList(), val providerUsed: String, val accepted: Boolean,
    val editDistanceRatio: Float, val factCheckPassed: Boolean)

@Serializable data class PolishRevision(val segmentId: String, val rev: Int, val styleVer: String,
    val backend: String, val text: String, val userEdited: Boolean, val acceptedAt: Long?)

@Serializable data class Correction(val wrong: String, val right: String, val bucket: String, val count: Int = 1)

@Serializable data class LedgerEntry(val ts: Long, val providerId: String, val host: String,
    val kind: String /* audio, text, model_asset */, val transport: String /* http, ws, sse, lan_ws, lan_http, ble */,
    val bytes: Long, val segmentId: String? = null, val estCost: Double? = null,
    val destinationClass: String = "internet" /* internet | lan */)

// ---------- core:audio / audio-route ----------
enum class AudioMode { DEFAULT, MEASUREMENT, LIVE_HEADSET, LIVE_SPEAKER, MEDIA }
enum class AudioRoute { BuiltIn, BluetoothA2dp, BluetoothHfp, Wired, Speaker, None }
interface AudioSource {
    val frames: Flow<ShortArray>; val route: StateFlow<AudioRoute>
    suspend fun start(config: CaptureConfig); fun stop()
}
expect fun createAudioSource(): AudioSource
expect class AacFileWriter(path: Path, sampleRate: Int = 16000, bitrate: Int = 32_000) { fun write(frame: ShortArray); fun close() }

enum class RoutePolicyAudio { HEADSET_A2DP_ONLY, SPEAKER_AEC, ANY }
sealed interface RouteEvent { data object NewDeviceAvailable : RouteEvent; data object OldDeviceUnavailable : RouteEvent
    data object CategoryChange : RouteEvent; data class Interruption(val began: Boolean) : RouteEvent; data object PulledToHfp : RouteEvent }
interface RouteManager { suspend fun ensure(policy: RoutePolicyAudio): RouteState; val routeEvents: Flow<RouteEvent> }
interface AudioSink { suspend fun play(chunk: ShortArray, channelMask: Int = 3); var volumeDb: Float; fun stop(); fun flush() /* 硬件缓冲 ≤ 40 ms */; fun prime() }  // 2ch 交织缓冲，无译文声道填零
expect class EchoControl { fun enable(on: Boolean) }
expect class Haptics { fun play(pattern: HapticPattern) }   // START(短-短) READY(短) FLIP(长) LOST(长-长-长) DIDNT_GET(双短)
expect class PostureSensor { val events: Flow<PostureEvent> } // FacingOther / Pocket / Flat
expect class ThermalMonitor { val state: StateFlow<Thermal> }   // Android 7 级映射：NONE/LIGHT→NOMINAL, MODERATE→FAIR, SEVERE→SERIOUS, CRITICAL+→CRITICAL

// ---------- core:asr-engine ----------
sealed interface AsrEvent {
    data class Volatile(val text: String) : AsrEvent
    data class Final(val segment: Segment) : AsrEvent
    data class Error(val cause: Throwable, val recoverable: Boolean, val authFailure: Boolean = false) : AsrEvent
}
interface StreamingAsrSession { fun push(frame: ShortArray); fun endOfInput(); fun forceEndpoint() /* TURN_END */; val events: Flow<AsrEvent> }
interface AsrEngine {
    val info: EngineInfo; val coverage: Set<String>; val streaming: Boolean; val supportsHotwords: Boolean /* 仅 zipformer transducer + modified_beam_search */
    suspend fun openSession(lang: String, hotwords: List<String>, vad: VadProfile): StreamingAsrSession
    suspend fun recognize(pcm: ShortArray, lang: String, hotwords: List<String>): Segment   // 非流式定稿 / 按段精修 / S4 批式
}
/** SherpaEngine 的 iOS/Android 实现直接 cinterop sherpa-onnx c-api.h；音频经 CPointer<ShortVar> 零拷贝，不经 Swift 桥。
 *  SherpaEngine 是 MVP 唯一的端侧 ASR / VAD / 声纹实现，两端一致。SystemAsrPlugin（iOS SpeechAnalyzer / SpeechTranscriber / SFSpeechRecognizer、Android SpeechRecognizer）
 *  只是本接口下的可选插件：MVP 不实现、不注册，v1.1 评估后作为设置里手动启用的选项（iOS 侧届时需 Swift 注入，MVP 无此注入点，§7.2）。 */
interface LangIdentifier { suspend fun identify(pcmHead1s: ShortArray, candidates: Set<String>): Pair<String, Float> }   // 二分类；MVP 实现 = ScriptLangIdentifier（ASR 输出 CJK / 拉丁比例）；whisper-tiny 版（≈ 98 MB）可选
interface SpeakerEmbedder { suspend fun embed(pcm: ShortArray): FloatArray; fun cosine(a: FloatArray, b: FloatArray): Float }
enum class RouteKind { LocalOnly, LocalFinalCloudOptional, LocalDraftCloudRefine, CloudOnly, CloudFirst, Refused }
data class Route(val kind: RouteKind, val streaming: AsrEngine?, val finalize: AsrEngine?, val refine: AsrEngine?, val reason: String? = null)
interface EngineSelector { fun decide(scene: ScenePreset, ctx: SelectorContext): Route
    fun profileFor(route: Route, scene: ScenePreset, ctx: SelectorContext): PipelineProfile }   // §7.5 映射表

// ---------- core:tts ----------
interface TtsEngine { val langs: Set<String>; val cloud: Boolean
    fun synthesize(text: String, lang: String, voice: String? = null): Flow<TtsChunk> }     // 按标点切块流式
expect class SystemTts : TtsEngine
data class TtsChunk(val pcm: ShortArray, val last: Boolean)

// ---------- core:nlp ----------
sealed interface LlmBackend {
    data class Cloud(val providerId: String, val model: String, val baseUrl: String, val schemaMode: SchemaMode) : LlmBackend
    data object AppleFoundation : LlmBackend      // Swift 实现注入（Foundation Models 为 Swift-only）
    data object MlKitGenAi : LlmBackend
    data class LocalGguf(val path: String) : LlmBackend      // 仅慢路径
    data object RulesOnly : LlmBackend
}
enum class SchemaMode { JSON_SCHEMA_STRICT, JSON_OBJECT_LOCAL_VALIDATE, OUTPUT_CONFIG_FORMAT }

// ---------- core:llm-gateway（§7.13）----------
// LlmBackend → Koog 的映射：Cloud → (LLMProvider, LLModel, clientSettings(baseUrl))；
// LocalGguf / AppleFoundation / MlKitGenAi / LiteRT 各自实现 Koog LLMClient（execute / executeStreaming）；RulesOnly 不经 Koog
interface LlmGateway {
    fun executor(backend: LlmBackend): PromptExecutor      // Koog MultiLLMPromptExecutor，按 provider 路由；快路径不包 RetryingLLMClient
    fun model(backend: LlmBackend): LLModel                 // 来自随版本内置的 Provider 清单；capabilities 供 EngineSelector 匹配
    fun usage(): Flow<TokenUsage>                           // 回填去向账本与本地消费闸门
}
interface TermPostProcessor { fun apply(text: String, bucket: String): Pair<String, List<Flag>> }   // S0 纠错映射 + 同音 + 形近
interface TermMatcher { fun hits(raw: String, bucket: String, limit: Int = 10): List<GlossaryEntry> } // 快路径 Aho-Corasick
interface FactGuard { fun check(cleaned: String, polished: String, glossary: List<GlossaryEntry>, style: Style): FactCheckResult }
interface PolishPipeline {
    fun run(transcript: Transcript, scene: ScenePreset, targets: List<String>,
            glossary: List<GlossaryEntry>, backend: LlmBackend): Flow<PolishResult>
}
interface FastTranslator {                                                                     // 快路径翻译后端抽象
    suspend fun translate(src: String, srcLang: String, tgtLang: String, prev: String?, terms: List<GlossaryEntry>,
                          styleLine: String?, provisional: Boolean, donePrefix: String?): String
}
// ---------- core:nmt（端侧 NMT，默认离线档，两端一致；§7.4 / §7.7）----------
@Serializable data class NmtModelSpec(val pairId: String /* "zh-en" */, val srcLang: String, val tgtLang: String,
    val encoderPath: String, val decoderPath: String, val spmSourcePath: String, val spmTargetPath: String,
    val sizeBytes: Long, val sha256: String, val license: String /* opus-mt：CC-BY-4.0，待核实 */)
class OnnxNmtTranslator(private val spec: NmtModelSpec, private val ort: OrtRuntime /* 与 sherpa-onnx 共用的同一份 onnxruntime */,
                        private val threads: Int = 2, private val beam: Int = 2) : FastTranslator {
    // 解码由我们控制：ORT seq2seq（encoder 一次 + decoder 逐步 + KV cache），beam 2–4、长度惩罚、重复抑制；serious 热状态时 beam 1
    // 术语：S0 前置把 terms 命中跨度替换为占位符 token ⟨T1⟩…（SentencePiece 词表中的保留符号），解码后按位置回填用户译法，因此离线档同样吃术语表
    // provisional / donePrefix 语义与云端一致：稳定前缀先译、final 后只译增量；styleLine 忽略（opus-mt 无风格控制，UI 标"离线 · 基础质量"）
    override suspend fun translate(src: String, srcLang: String, tgtLang: String, prev: String?, terms: List<GlossaryEntry>,
                                   styleLine: String?, provisional: Boolean, donePrefix: String?): String = TODO()
    fun warmUp() {}          // 出门预热 / 会话开始时加载 session，避免首句冷启动
    fun release() {}         // 内存分级 < 4 GB 或后台时卸载
}
interface NmtModelRegistry { fun installed(): List<NmtModelSpec>; suspend fun ensure(pairId: String): NmtModelSpec /* 未下载 → 抛 NeedsDownload(pairId, sizeBytes)，UI 提示下载（Background Assets / PAD / 手动导入） */ }
interface SystemTranslatorPlugin : FastTranslator   // 可选插件（v1.1，设置里手动启用）：Android ML Kit Translation；iOS Apple Translation 宿主桥经 Swift 注入。MVP 不实现、不注册
@Serializable data class GlossaryEntry(val term: String, val reading: String? = null /* 拼音串 */,
    val translations: Map<String, String> = emptyMap(), val bucket: String, val count: Int = 0, val locked: Boolean = true,
    val sourceMediaId: String? = null, val sourceMs: Long? = null /* 观影词汇来源 */)

// ---------- core:security / network / vault ----------
expect class SecretStore { fun put(alias: String, value: ByteArray); fun get(alias: String): ByteArray?; fun delete(alias: String) }
/** 所有出站的唯一门面：HTTP / WS 帧 / SSE / 局域网帧都经它计数并按会话隐私档做运行时门禁；Locked 会话抛 EgressDenied。 */
interface Egress {
    suspend fun http(session: SessionId, kind: String, request: HttpRequestBuilder.() -> Unit): HttpResponse
    suspend fun ws(session: SessionId, kind: String, url: String): CountingWebSocketSession
    fun sse(session: SessionId, kind: String, url: String): Flow<ServerSentEvent>
    suspend fun lan(session: SessionId, kind: String /* audio | text */, peer: PeerId, bytes: ByteArray)   // P2P 出站计数
}
interface PrivacyGate { fun allow(session: SessionId, kind: String /* audio, text, model_asset */, segmentId: String?,
    destination: String = "internet" /* internet | lan */): Boolean }
interface Ledger { suspend fun record(entry: LedgerEntry); fun monthly(): Flow<List<LedgerEntry>>; fun monthlyEstCost(): Flow<Double> }
interface SpendGate { val monthlyLimit: StateFlow<Double>; fun shouldBlock(): Boolean }      // 本地消费闸门
interface ModelDownloader { fun download(model: ModelSpec, userUrl: String): Flow<DownloadProgress> }  // 仅用户指定直链，经 Egress
interface ModelPackImporter { suspend fun import(file: Path): ModelSpec }                     // .sherpa-pack 手动导入 + sha256 校验
interface SyncRoot { val id: String; suspend fun list(path: String): List<VaultEntry>
    suspend fun read(path: String): ByteArray; suspend fun append(path: String, bytes: ByteArray); fun changes(): Flow<VaultChange>? }
interface VaultIndexer { suspend fun rebuild(root: SyncRoot, incremental: Boolean = true) }
interface WidgetSnapshotWriter { suspend fun write() }                                       // 进入后台时写 App Group JSON

// ---------- core:export ----------
interface CardRenderer { suspend fun render(note: NoteView, style: Style, template: String): ImageBitmap }
interface VoxNotePacker { suspend fun pack(noteId: String, includeAudio: Boolean, includeRaw: Boolean): Path
    suspend fun unpack(file: Path): ImportedNote }
data class SceneExportOptions(val includeGlossary: Boolean = false)
interface ScenePackIo {
    fun export(preset: ScenePreset, options: SceneExportOptions): String   // 默认剥离 fewShots/术语内容/cloudProviderId/route/entryBindings/声纹/凭据
    fun import(json: String): ImportedScene                                // 含 counts 供 UI 显示；文本视为数据
}
```

### A.2 实时快路径（屏外，`core:live-pipeline`）

```kotlin
// ---------- 模式矩阵 ----------
@Serializable enum class InputSource { MIC, SYSTEM_AUDIO, SCREEN_TEXT, MEDIA_FILE, REMOTE_STREAM }
@Serializable enum class OutputForm { TEXT, VOICE, BOTH }
@Serializable enum class OutputDevice { LOCAL, REMOTE_APP, REMOTE_WEB, MY_WATCH, REMOTE_BROADCAST }
@Serializable enum class Interaction { CONVERSATION, SIMPLEX_IN, SIMPLEX_OUT }
@Serializable enum class VoiceRoute { MY_HEADSET, SPLIT_LR, SPEAKER_YELLOW, NONE }
@Serializable data class ModeSpec(val id: String /* "M0".."M12" */, val input: InputSource = InputSource.MIC,
    val form: OutputForm, val device: OutputDevice, val interaction: Interaction,
    val voiceRoute: VoiceRoute, val showFacingScreen: Boolean, val autoDirection: Boolean,
    val requiresAec: Boolean, val requiresP2p: Boolean, val whisperGainDb: Float = -20f, val stage: String /* P0|P1|P2|YELLOW */)

@Serializable enum class Speaker { ME, OTHER, REMOTE, UNKNOWN }
@Serializable enum class SegmentState { PARTIAL, STABLE_PREFIX, FINAL }
@Serializable enum class PlaybackStatus { QUEUED, PLAYING, DONE, SKIPPED, SHOWN_ON_SCREEN, MERGED }
@Serializable enum class UttSource { LOCAL_MIC, REMOTE_AUDIO, REMOTE_HINT, SYSTEM_AUDIO, MEDIA_FILE }

/** 实时段（两份规格中的旧名 `Segment`（实时）即本类型）：与主 App Segment 的区别是携带 utteranceId / state / streamId；
 *  只有 FINAL 段映射为 Segment（isFinal=true，source=LOCAL/CLOUD/REMOTE，engine=asrEngine，speaker → "ME"/"OTHER"/"REMOTE:<streamId>"）；
 *  PARTIAL / STABLE_PREFIX 段与 provisional 译文只存 utterances 表，不进 polish_revisions——"无损映射"不成立，只承诺 FINAL 段可映射。 */
@Serializable data class LiveSegment(val id: String, val utteranceId: String, val state: SegmentState, val text: String,
    val startMs: Long, val endMs: Long?, val asrEngine: EngineInfo, val confidence: Float? = null, val streamId: Int = 0)
@Serializable data class Translation(val text: String, val engine: String, val provisional: Boolean, val latencyMs: Int, val targetLang: String)
@Serializable data class Utterance(val id: String, val speaker: Speaker, val lang: String, val segments: List<LiveSegment>, val raw: String,
    val fastTranslation: Translation?, val polished: String? = null,
    val finalTranslation: Map<String, Translation> = emptyMap() /* 按目标语；M10 多语每个 listenLang 一条 */,   // 慢路径只补写 polished / finalTranslation / glossaryHits
    val ttsPlayback: PlaybackStatus = PlaybackStatus.QUEUED, val source: UttSource = UttSource.LOCAL_MIC,
    val captureTsMs: Long, val glossaryHits: List<String> = emptyList())
@Serializable data class PipelineProfile(val tier: ProfileTier, val asr: String, val mt: String, val tts: String,
    val eagerTranslate: Boolean, val timeoutsMs: Map<String, Int> /* mt=1500, ttsFirst=800, asrFirst=1200 */)
@Serializable data class Conversation(val id: String, val modeId: String /* 只存 id，运行时查内置 ModeSpec 表 */, val myLang: String, val otherLang: String, val sceneId: String,
    val style: Style, val profile: PipelineProfile, val utterances: List<Utterance>, val summary: String? = null,
    val myAudioPath: String? = null /* 对方音频不落盘 */)

// ---------- Stage 链 ----------
sealed interface FastPathEvent {
    data class Partial(val segment: LiveSegment) : FastPathEvent
    data class StablePrefix(val segment: LiveSegment) : FastPathEvent
    data class Final(val utterance: Utterance) : FastPathEvent
    data class Direction(val speaker: Speaker, val confidence: Float, val tentative: Boolean) : FastPathEvent
    data class TranslationDelta(val utteranceId: String, val delta: String, val provisional: Boolean) : FastPathEvent
    data class TtsChunk(val utteranceId: String, val pcm: ShortArray, val last: Boolean) : FastPathEvent
    data class Health(val stage: String, val state: StageHealth) : FastPathEvent
    data class Flushed(val reason: String /* other_interrupt | me_speaking | earbud_lost */, val movedToScreen: List<String>) : FastPathEvent
}
enum class StageHealth { OK, SLOW, FALLBACK, PROBING /* Fallback 期每 5 句影子请求 / RTT 探针 */, RECOVERED }
interface Stage<I, O> { fun process(input: Flow<I>): Flow<O>; val health: StateFlow<StageHealth> }
// VadGate(200 ms 预滚 / 300 ms hangover) → AsrStage → SegmenterStage(mergeGapMs=400；远端流 TURN_END 强制端点)
//   → DirectionStage(scriptLid / optional whisperLid, embedder, cosine 0.6, lidTrust 0.7, minConf 0.55；发送侧 ME 门控复用 embedder) → TranslateStage(fast, fallback, eager, timeout 1500, terms, styleLine)
//   → TtsStage(engine, fallback, firstChunkTimeout 800) → PlaybackQueue(sink, haptics：≥2 句 1.15–1.25×；≥4 句合并；duck/flush/skip/replay) → AudioSink
class Ducker(queue: PlaybackQueue, interruptMs: Int = 300, dropMs: Int = 1500, recoverMs: Int = 500)
interface LivePipeline {
    fun start(conv: Conversation, source: AudioSource, sink: AudioSink): Flow<FastPathEvent>
    fun flipDirection(); fun skip(); fun replay(); fun setMode(modeId: String) /* 升级阶梯只改 sinks/inputs，不销毁 capture */
    fun end(): Conversation                                                    // 触发慢路径
}

// ---------- 实时会话状态机 ----------
sealed interface LiveState {
    data object Idle : LiveState; data object Arming : LiveState; data class Live(val play: PlayState, val dir: DirState) : LiveState
    data class Paused(val reason: String /* call | siri | earbud_lost */) : LiveState
    data object NeedForeground : LiveState; data object Degraded : LiveState; data object Ending : LiveState
}
enum class PlayState { LISTENING, TRANSLATING, SPEAKING, DUCKED, FLUSHED }
enum class DirState { UNDETERMINED, OTHER_SPEAKING, ME_SPEAKING }
interface LiveSessionMachine { val state: StateFlow<LiveState>; fun trigger(); fun onRoute(e: RouteEvent); fun onHealth(h: FastPathEvent.Health); fun end() }
```

### A.3 P2P 协议（`core:p2p-transport`，v1.1 起）

```kotlin
/** 帧头明文、payload 加密：magic 'X'(1B) | ver(1B) | flags(1B) | streamId(2B) | seq(2B) | ptsMs(4B) | ctrLow(4B 计数低位) | len(2B) | opus[] | tag(16B) */
class AudioFrame(val streamId: Int, val seq: Int, val ptsMs: Long, val flags: Int, val ctrLow: Int, val opusPacket: ByteArray) {
    companion object { const val TURN_START = 1; const val TURN_END = 2; const val SPEECH = 4; const val FEC = 8; const val KEY = 16
        const val OWNER_CONF_SHIFT = 5 /* bits5–6：发送侧 ME 声纹置信 0–3，听者可丢弃低置信段 */ }
}
interface FrameCodec { fun encode(f: AudioFrame, key: StreamKey, nonce: Nonce): ByteArray; fun decode(b: ByteArray, key: StreamKey): AudioFrame }
class Nonce(val streamPrefix: ByteArray /* 4B 随机，每流 / 每方向独立 */, var counter: Long /* 8B 单调；帧头携带低 4B，接收端按 epoch + seq 重建高位 */)
class PairKeys(val c2s: ByteArray, val s2c: ByteArray /* 成员 ↔ host：X25519 共享密钥经 HKDF(label c2s / s2c) 派生的两把方向密钥，只用于控制通道与流密钥分发 */)
class StreamKey(val epoch: Int, val streamId: Int, val key: ByteArray /* sender-key：说话方生成，经成对通道加密分发给 roster；host 只转发密文；成员变动即 REKEY */)
expect class OpusEncoder(sampleRate: Int = 16000, bitrateBps: Int = 24_000 /* BLE 16_000 */, fec: Boolean = false /* Wi-Fi/TCP 档关；BLE 档开 */, dtx: Boolean = false) {
    fun encode(frame20ms: ShortArray): ByteArray
    fun packMulti(frames: List<ByteArray>): ByteArray   // Opus 多帧 TOC code 3 packet（Wi-Fi 下 2 帧 = 40 ms）
}
expect class OpusDecoder(sampleRate: Int = 16000) { fun decode(packet: ByteArray?, plc: Boolean): ShortArray }
class JitterBuffer(targetMs: Int /* Wi-Fi 60 / BLE 120 */, minMs: Int = 40, maxMs: Int = 200, fecLookahead: Int = 0 /* BLE 档 1 包 */) {
    fun push(frame: AudioFrame)
    fun drainForAsr(force: Boolean = false): List<AudioFrame>   // TURN_END 时 force=true 立即排空
    val gapMs: StateFlow<Int>                                    // 连续丢失 > 200 ms → 插静音并标 [信号中断]
}
@Serializable data class Caps(val asrLangs: List<String>, val mtPairs: List<String>, val ttsLangs: List<String>, val hasCloudKey: Boolean,
    val audioEgress: String /* local | cloud:<provider>：向说话方声明"我会把你的音频送到哪"，经 ROSTER 广播 */, val listenLang: String, val wantsHint: String /* none|asr|mt */, val canHintAsr: Boolean, val canHintMt: Boolean, val cpuClass: Int, val thermal: String)
@Serializable data class Member(val id: String, val name: String, val platform: String, val joinOrder: Int, val caps: Caps?)
@Serializable sealed interface Ctrl {                                   // JSON 文本帧，带 sid/mid
    @Serializable data class Hello(val name: String, val platform: String, val protoVer: Int, val pubKey: String, val joinOrder: Int) : Ctrl
    @Serializable data class CapsMsg(val caps: Caps) : Ctrl
    @Serializable data class Roster(val members: List<Member>, val hostId: String, val hotspotHost: Boolean) : Ctrl
    @Serializable data class Mode(val interaction: Interaction, val speakerAllowlist: List<Int>, val singleSpeech: Boolean = true /* 圆桌默认同一时刻一路 */) : Ctrl
    @Serializable data class RoomPolicy(val localAsrOnly: Boolean) : Ctrl                 // host→all：禁用听者在线档
    @Serializable data class Suppressed(val streamId: Int, val ptsMs: Long) : Ctrl         // host 同源去重（ptsMs ± 200 ms + 能量）回给被抑制方
    @Serializable data class SrcLang(val streamId: Int, val lang: String, val conf: Float, val source: String, val publicTerms: List<String> = emptyList()) : Ctrl
    @Serializable data class Turn(val streamId: Int, val utteranceId: String, val ptsMs: Long, val end: Boolean) : Ctrl
    @Serializable data class Silence(val streamId: Int, val sinceMs: Long) : Ctrl          // 每 2 s
    @Serializable data class WantHint(val streamId: Int, val level: String, val targetLang: String) : Ctrl
    @Serializable data class Hint(val utteranceId: String, val text: String, val partial: Boolean, val lang: String, val translated: Boolean) : Ctrl
    @Serializable data class Feedback(val utteranceId: String, val kind: String /* retry|slower|didntGet */) : Ctrl
    @Serializable data class Clock(val t0: Long, val t1: Long, val t2: Long, val t3: Long) : Ctrl
    @Serializable data class Ping(val ts: Long) : Ctrl; @Serializable data class Pong(val ts: Long) : Ctrl
    @Serializable data class Resume(val lastSeq: Map<Int, Int>) : Ctrl                    // host 补发 10 s 音频 + 200 条控制
    @Serializable data class Rekey(val epoch: Int, val streamId: Int, val wrappedKey: String /* 经成对通道加密的新流密钥 */) : Ctrl
    @Serializable data class End(val reason: String) : Ctrl
}
enum class P2pLinkState { DISCOVERING, PAIRING, CONNECTED, RECONNECTING, CLOSED }
interface P2pHost { suspend fun open(room: RoomConfig /* pin / salt 校验、localAsrOnly */): RoomInfo /* 含二维码 payload scenenote://join?host&port&pk&salt&lang */
    val members: StateFlow<List<Member>>; fun broadcast(ctrl: Ctrl); fun fanOut(frame: AudioFrame, from: Member); fun close(reason: String) }
interface P2pClient { suspend fun join(info: RoomInfo, myCaps: Caps): P2pSession }
interface P2pSession { val state: StateFlow<P2pLinkState>; val audioIn: Flow<AudioFrame>; val ctrlIn: Flow<Ctrl>
    suspend fun sendAudio(f: AudioFrame); suspend fun send(ctrl: Ctrl); fun close() }
expect class Discovery { fun advertise(service: String, txt: Map<String, String>); fun browse(service: String): Flow<PeerInfo> }   // NWBrowser / NsdManager
expect class HotspotHelper { suspend fun ensureLocalOnlyHotspot(): HotspotInfo? }   // Android LocalOnlyHotspot；iOS 仅引导
expect class BleTransport : P2pSession                                              // v2：GATT Notify 分片 + 滑动窗口 32 + NACK
class RemoteOpusSource(session: P2pSession, streamId: Int, jitter: JitterBuffer, decoder: OpusDecoder) : AudioSource
```

### A.4 屏内（`core:screen-pipeline` / `subtitle` / `overlay` / `ocr` / `media` / `audio-capture`）

```kotlin
@Serializable enum class CaptionHostKind { IN_APP_PLAYER, PIP, OVERLAY, HEADSET_TTS, WATCH, NOTIFICATION }
@Serializable enum class DubbingPolicy { DUCK_REPLACE, MIX_NO_FOCUS, MUTE_ORIGINAL, BILINGUAL_MIX, OFF }
@Serializable enum class Tier { CORE, ANDROID_ENHANCED, IOS_EXPERIMENTAL, FALLBACK }
@Serializable enum class Platform { ANDROID, IOS }
@Serializable data class ScreenInModeSpec(val id: String /* "S1".."S8" */, val input: InputSource, val form: OutputForm, val device: OutputDevice,
    val interaction: Interaction = Interaction.SIMPLEX_IN, val hosts: List<CaptionHostKind> /* PIP → OVERLAY → HEADSET_TTS */,
    val dubbing: DubbingPolicy = DubbingPolicy.OFF, val tier: Map<Platform, Tier> /* 商店描述与帮助页按此措辞 */,
    val requiresProjection: Boolean, val requiresBroadcast: Boolean)
@Serializable sealed interface MediaRef {
    @Serializable data class File(val uri: String, val bookmark: ByteArray?, val durationMs: Long, val title: String) : MediaRef
    @Serializable data class Url(val url: String, val isHls: Boolean) : MediaRef
    @Serializable data class SystemApp(val packageOrBundle: String?) : MediaRef
}
sealed interface CaptureCapability { object Ok : CaptureCapability
    data class Silenced(val app: String?, val reason: String /* opt_out | drm | hls | webview | flag_secure */) : CaptureCapability
    object Unsupported : CaptureCapability; object NeedsBroadcastPicker : CaptureCapability /* iOS 等待"开始直播" */ }
data class PcmChunk(val samples: ShortArray, val sampleRate: Int = 16_000, val tsMs: Long)
@Serializable data class SubtitleCue(val id: String, val index: Int, val startMs: Long, val endMs: Long, val src: String, val srcLang: String,
    val fast: Translation?, val polished: Translation? = null, val isFinal: Boolean, val glossaryHits: List<String> = emptyList(), val corrected: Boolean = false)
@Serializable data class SubtitleTrack(val id: String, val mediaId: String, val srcLang: String, val targetLangs: List<String>, val cues: List<SubtitleCue>,
    val profile: PipelineProfile, val polishedComplete: Boolean = false, val timebase: String = "media" /* media（S4 精确）| session（S1 会话相对） */) {
    fun toSrt(lang: String, bilingual: Boolean, polished: Boolean): String; fun toVtt(lang: String, bilingual: Boolean, polished: Boolean): String }
@Serializable data class CaptionStyle(val host: CaptionHostKind, val position: Position = Position.BOTTOM_CENTER, val fontSp: Int = 18 /* PIP 仅 14/18；22/28 仅 OVERLAY / IN_APP_PLAYER */,
    val maxLines: Int = 2, val bilingual: Boolean = false, val bgAlpha: Float = 0.6f)
@Serializable data class ScreenInConfig(val modeId: String /* 只存 id */, val media: MediaRef, val targetLangs: List<String>, val sceneId: String, val style: Style,
    val captionStyle: CaptionStyle, val dubbing: DubbingPolicy, val privacy: PrivacyMode, val route: RoutePolicy, val profile: PipelineProfile, val ocrRegion: Rect? = null)
@Serializable data class ScreenInSessionRecord(val id: String, val modeId: String, val media: MediaRef, val startedAt: Long, val track: SubtitleTrack,
    val probe: CaptureCapability?, val summary: String? = null, val vocabCandidates: List<String> = emptyList(), val artifacts: List<String> = emptyList() /* SRT/VTT 路径 */)
sealed interface ScreenInEvent {
    data class Cue(val cue: SubtitleCue) : ScreenInEvent
    data class Progress(val transcribedMs: Long, val playheadMs: Long) : ScreenInEvent   // S4：transcribed < playhead + 5000 → Waiting
    data class Probe(val result: CaptureCapability) : ScreenInEvent
    data class Host(val kind: CaptionHostKind, val active: Boolean) : ScreenInEvent
    data class Health(val stage: String, val state: StageHealth) : ScreenInEvent
    data class DuckDetectedAsPause(val switchedTo: DubbingPolicy) : ScreenInEvent
}
enum class ScreenInState { IDLE, INTAKE, REQUESTING_PERMISSION, PROBING, DEGRADED_CHOICE, LISTENING, SPEAKING_TTS, WAITING, PAUSED,
    INTERRUPTED, OCR_ACTIVE, MIC_FALLBACK, STOPPING, FINALIZING }

// expect/actual 边界（Android actual / iOS actual）：
expect class SystemAudioCapture { fun start(): Flow<PcmChunk>; fun stop() }                 // AudioPlaybackCapture / App Group socket
expect class CaptureProbe { suspend fun probe(): CaptureCapability }                        // Android：excludeUid(自身) + 自家播放器静默 + 2 s RMS≈0 且 isMusicActive → opt-out（isClientSilenced 只用于麦克风路径提示）；iOS：扩展侧 RMS
expect class MediaAudioExtractor { fun pcm(ref: MediaRef): Flow<PcmChunk> }                // MediaExtractor+MediaCodec / AVAssetReader + MTAudioProcessingTap；iOS HLS → Unsupported（movpkg 不可读）
expect class CaptionHost { fun show(style: CaptionStyle); fun update(cues: List<SubtitleCue>); fun hide(); val supported: Set<CaptionHostKind> }  // iOS PIP 宿主由 Swift 实现注入（ContentSource 为 Swift-only）；Android S3 截帧前先 hide()
expect class ScreenTextGrabber { suspend fun grab(region: Rect?): ImageBitmap? }           // VirtualDisplay+ImageReader / Unsupported
expect class OcrEngine { suspend fun recognize(bitmap: ImageBitmap): List<OcrLine> }        // ML Kit v2 bundled / Vision（静态图）
expect class SystemDucker { suspend fun beginSentence(policy: DubbingPolicy); fun endSentence() }   // 控制他人焦点（与 PlayerDucker 分离）
expect class PlayerDucker { fun duck(db: Float); fun restore() }                            // 控制自家播放器音量
// 另有：DrmProbe、OverlayPermission(+OemGuide)、SubtitleBurner(v2)、ShareIntake、ClipboardLinkDetector、SystemSubtitleUserToggle(手动开关，替代不可检测的 iOS 27 Generated Subtitles)、
//       SessionKeepAlive(前台服务 / PiP + audio 后台模式)、PlatformCaps(supportsSystemCapture: Tier, supportsOcrOverlay, supportsOverlay, singlePipOnly)
interface ScreenInPipeline {
    fun start(config: ScreenInConfig): Flow<ScreenInEvent>
    fun retarget(lang: String); fun restyle(style: Style)      // 从当前位置重翻不重转写
    fun addToGlossary(cueId: String, term: String, translation: String)  // 同一视频后续 cue 立即重翻
    fun stop(): ScreenInSessionRecord                            // 触发慢路径精修 → 精修轨 → SRT
}
```

### A.5 两份规格旧名 → 主方案新名映射（规格中的模块树与数据结构以本附录为唯一真相源，规格对应章节已标注"历史草案"）

| 规格旧名 | 主方案新名 | 说明 |
|---|---|---|
| `:core:audio` AudioCapture / `PcmFrame` | `core/audio` AudioSource（`Flow<ShortArray>`）+ `core/audio-route` RouteManager | 录音与实时共用 |
| `:core:vad` | `core/audio` VadGate（Silero） | — |
| `:core:lid` | `core/asr-engine` LangIdentifier（输出脚本 / 可选 whisper-tiny）+ SpeakerEmbedder | LID 包不进 MVP |
| `:core:mt` Translator | `core/nlp` FastTranslator + `core/nmt` OnnxNmtTranslator（默认离线档）/ 云端 / SystemTranslatorPlugin（v1.1 可选） | 系统翻译降为可选插件，MVP 无 Swift 注入 |
| `:pipeline` / `:core:pipeline` | `core/live-pipeline`、`core/screen-pipeline` | — |
| `:p2p` | `core/p2p-transport` + `core/security`（Crypto） | — |
| `:modes` ModeSpec × 12 | `core/model` ModeSpec（静态表随版本） | 无 M2 |
| `:knowledge` | `core/nlp` + `core/database` | — |
| `:core:syscapture` | `core/audio-capture` | — |
| `:core:caption` | `core/overlay` | — |
| `:core:intake` | `core/media`（ShareIntake / ClipboardLinkDetector） | — |
| `:feature:screenin` / `:feature:screenin:android` / `:ios` | `feature/screen` + `platform/android-service` + `iosApp/SwiftImpl` | — |
| `PipelineProfile.SCREEN_IN` | ProfileTier 按 §7.5 映射 + `VadProfile.media()` + `AudioMode.MEDIA` | 无独立档位 |
| `Lang` 枚举（zh-Hans / yue / zh-Hans-x-sichuan / und） | `String` BCP-47：zh-CN / yue-HK / zh-CN-sichuan / auto | A.1 |
| `Segment`（实时） | `LiveSegment`；`Segment` 留给录音层 | 仅 FINAL 可映射 |
| `Conversation.scene: Scene` / `ScreenInConfig.scene` | `sceneId: String` | — |
| `Conversation.glossaryHits` | `Utterance.glossaryHits` | — |
| `Conversation.mode: ModeSpec` / `ScreenInConfig.mode` / `ScreenInSessionRecord.mode` | `modeId: String` | 只存 id |
| `Utterance.finalTranslation: Translation?` | `Map<String, Translation>` | 多语目标 |
| `FastPathEvent.TtsChunk.pcm: ByteArray` | `ShortArray` | — |
| `TranslationDelta` 无 `provisional` | 有 `provisional` | — |
| `PipelineProfile(asr, mt, tts, …)` | 增 `tier: ProfileTier` | — |
| `InputSource` 四值 | 五值（含 `REMOTE_STREAM`） | — |
| `CaptureCapability` | 增 `NeedsBroadcastPicker`、`flag_secure` 原因 | — |
| `ScreenInEvent` / `SubtitleTrack` | 增 `DuckDetectedAsPause`、`timebase` | — |
| `StageHealth` 四值 | 五值（含 `PROBING`） | — |
| `Caps` 无 `audioEgress`；无 `ROOM_POLICY` / `SUPPRESSED` | 有 | §3.4 |
| `Nonce(sessionPrefix, counter)` + `SessionKey` | `PairKeys`（HKDF 方向密钥）+ `StreamKey`（sender-key）+ 帧头 `ctrLow` | §3.4 |
| `scenenote://join` / `_scenenote._tcp` | `scenenote://join` / `_scenenote._tcp` | 单一 scheme |
| `CLOCK` 四次往返（v1 围桌） | 三次往返 | — |
| 屏内"慢路径"（逐句第二遍） | 快路径内 partial → final 定稿 | 慢路径只指会话末 S0–S4 |
| `SystemSubtitleDetector` | `SystemSubtitleUserToggle` | Generated Subtitles 不可检测 |
| `config.mode: MEETING / INTERVIEW / DICTATION / …` | `AudioMode`（DEFAULT / MEASUREMENT / LIVE_HEADSET / LIVE_SPEAKER / MEDIA） | 单一枚举 |
