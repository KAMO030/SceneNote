package dev.scenenote.screen

import dev.scenenote.asr.AsrEvent
import dev.scenenote.asr.LoadPlan
import dev.scenenote.asr.LocalEngineState
import dev.scenenote.asr.SherpaAsrEngine
import dev.scenenote.asr.SherpaStreamingSession
import dev.scenenote.bench.WavIo
import dev.scenenote.core.db.SessionKind
import dev.scenenote.core.db.SessionRepository
import dev.scenenote.core.egress.EgressGate
import dev.scenenote.core.egress.EgressKind
import dev.scenenote.core.egress.EgressRequest
import dev.scenenote.core.egress.Egressed
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.core.platform.MemoryTier
import dev.scenenote.polish.Cue
import dev.scenenote.translate.FastTranslator
import dev.scenenote.translate.MtRequest
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.uuid.Uuid

enum class JobPhase { IDLE, DOWNLOADING, EXTRACTING, TRANSCRIBING, TRANSLATING, DONE, FAILED }

data class SubtitleState(
    val phase: JobPhase = JobPhase.IDLE,
    val sessionId: String? = null,
    val media: MediaItem? = null,
    val progress: Float = 0f,          // 当前阶段 0..1
    /** 转写进度对应的媒体时间（边转边看：播放位置超过它就要等）。 */
    val transcribedMs: Long = 0,
    val cues: List<Cue> = emptyList(),
    val partial: String = "",
    val error: String? = null,
    /** 源语 ≠ 目标语才有译文（同语种只出原文字幕，页面不显示译文 / 原文切换）。 */
    val translate: Boolean = false,
)

/**
 * S4 字幕任务（I6）：视频 → 抽音频（平台）→ 本机识别（VAD 切句，不按实时节奏，最多领先播放位置 30 s 音频）→ 云翻译（每 8 句一批）→ 落库为 SCREEN 会话。
 * 边转边看：cues 与 transcribedMs 单调更新，播放器追上来就暂停等（规格：Waiting 遮罩）。
 */
class SubtitleJob(
    private val extractor: AudioExtractor,
    private val engine: SherpaAsrEngine,
    private val translator: FastTranslator,
    private val repo: SessionRepository,
    private val paths: AppPaths,
    private val egress: EgressGate,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SubtitleState())
    val state: StateFlow<SubtitleState> = _state.asStateFlow()
    private var job: Job? = null
    private val json = Json { encodeDefaults = true }

    fun cancel() { val j = job; job = null; scope.launch { j?.cancelAndJoin(); _state.update { it.copy(phase = JobPhase.IDLE) } } }

    /** 直链：下载到 cacheDir（经 Egress，kind = MEDIA_URL，只记账不上传）。 */
    suspend fun download(url: String, onProgress: (Float) -> Unit): MediaItem = withContext(Dispatchers.Default) {
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "video.mp4" }
        val dest = paths.join(paths.cacheDir, "media-${Uuid.random()}-$name")
        val host = runCatching { Url(url).host }.getOrDefault("unknown")
        egress.perform(EgressRequest(providerId = "media_url", host = host, kind = EgressKind.MEDIA_URL)) { http ->
            var total = 0L
            http.prepareGet(url).execute { resp ->
                if (resp.status.value !in 200..299) error("下载失败 ${resp.status.value}")
                val expected = resp.headers["Content-Length"]?.toLongOrNull() ?: -1L
                val ch = resp.bodyAsChannel()
                FileSystem.SYSTEM.write(dest.toPath()) {
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = ch.readAvailable(buf, 0, buf.size); if (n <= 0) break; write(buf, 0, n); total += n; if (expected > 0) onProgress(total.toFloat() / expected) }
                }
            }
            Egressed(MediaItem(dest, name, -1, MediaSource.URL), bytes = total)
        }
    }

    /** 开始一个字幕任务；返回 sessionId。 */
    fun start(media: MediaItem, srcLang: String, tgtLang: String, translate: Boolean): String {
        val prev = job
        val sessionId = Uuid.random().toString()
        _state.value = SubtitleState(phase = JobPhase.EXTRACTING, sessionId = sessionId, media = media, translate = translate)
        job = scope.launch {
            prev?.cancelAndJoin()
            var session: SherpaStreamingSession? = null
            var collector: Job? = null
            val cues = mutableListOf<Cue>()
            val segIds = mutableListOf<String>(); val revs = mutableListOf<Int>()
            var created = false
            try {
                val duration = if (media.durationMs > 0) media.durationMs else extractor.durationMs(media.path)
                val wav = extractor.extractPcm16k(media.path) { p -> _state.update { it.copy(progress = p) } }
                val pcm = WavIo.readPcm16k(wav)
                val totalMs = if (duration > 0) duration else pcm.size * 1000L / 16_000
                _state.update { it.copy(phase = JobPhase.TRANSCRIBING, progress = 0f) }
                val ready = engine.load(LoadPlan.forLang(srcLang, tier = MemoryTier.current))
                if (ready is LocalEngineState.Error) error("语音包未下载")
                repo.create(sessionId, SessionKind.SCREEN, "screen_file", "S4", srcLang, tgtLang, srcLang, engine.info); created = true
                val s = engine.openStream(srcLang, emptyList()) as SherpaStreamingSession
                session = s
                var lastFinalEndMs = 0L
                collector = launch {
                    s.events.buffer(Channel.UNLIMITED).onEach { ev ->   // 全速转写时 Final 不能因落库耗时被挤掉
                        when (ev) {
                            is AsrEvent.Partial -> _state.update { it.copy(partial = ev.text) }
                            is AsrEvent.Final -> {
                                val seg = ev.segment
                                lastFinalEndMs = maxOf(lastFinalEndMs, seg.endMs)
                                _state.update { it.copy(transcribedMs = maxOf(it.transcribedMs, seg.endMs), progress = (seg.endMs.toFloat() / totalMs).coerceIn(0f, 1f), partial = "") }
                                if (seg.text.isBlank()) return@onEach
                                val idx = segIds.indexOf(seg.id)   // rev1 定稿只替换文本
                                if (idx >= 0) { if (seg.revision >= revs[idx]) { cues[idx] = cues[idx].copy(text = seg.text); revs[idx] = seg.revision } }
                                else { segIds += seg.id; revs += seg.revision; cues += Cue(cues.size + 1, seg.startMs, seg.endMs, seg.text) }
                                repo.addSegment(sessionId, seg)
                                _state.update { it.copy(cues = cues.toList()) }
                            }
                            else -> Unit
                        }
                    }.collect()
                }
                // 不按实时节奏喂，但最多领先已定稿位置 30 s（限制内存与积压）
                val frame = 320
                var off = 0
                while (off < pcm.size) {
                    ensureActive()
                    val fedMs = off * 1000L / 16_000
                    if (fedMs - lastFinalEndMs > 30_000) { delay(50); continue }
                    val end = minOf(off + frame, pcm.size)
                    s.push(ShortArray(frame).also { pcm.copyInto(it, 0, off, end) })
                    off += frame
                    if (off % (frame * 50) == 0) delay(1)
                }
                s.endOfInput()
                while (!s.isDrained()) { ensureActive(); delay(20) }
                delay(60)   // 让最后一句 Final 被收集到
                collector.cancelAndJoin(); collector = null
                s.close(); session = null
                _state.update { it.copy(transcribedMs = totalMs, progress = 1f) }
                // 翻译：每 8 句一批（qwen-mt RPM 60）；行数对不上就逐句重译
                if (translate && cues.isNotEmpty()) {
                    _state.update { it.copy(phase = JobPhase.TRANSLATING, progress = 0f) }
                    val translations = mutableMapOf<Int, String>()
                    val chunks = cues.chunked(8)
                    var failed: String? = null
                    for ((bi, batch) in chunks.withIndex()) {
                        ensureActive()
                        val r = translator.translate(MtRequest(batch.joinToString("\n") { it.text }, srcLang, tgtLang, sessionId = sessionId))
                        val res = r.result
                        if (res == null) { failed = r.reason; break }
                        val lines = res.text.split('\n').map { it.trim() }.filter { it.isNotBlank() }
                        if (lines.size == batch.size) batch.forEachIndexed { i, c -> translations[c.index] = lines[i] }
                        else for (c in batch) { val one = translator.translate(MtRequest(c.text, srcLang, tgtLang, sessionId = sessionId)).result?.text ?: continue; translations[c.index] = one }
                        _state.update { it.copy(cues = cues.map { c -> translations[c.index]?.let { t -> c.copy(translation = t) } ?: c }, progress = (bi + 1f) / chunks.size) }
                    }
                    repo.saveNote(sessionId, "subtitle", "cloud:bailian", "", json.encodeToString(translations.mapKeys { it.key.toString() }), "")
                    if (failed != null) _state.update { it.copy(error = "未翻译：$failed") }
                }
                repo.end(sessionId, title = "字幕 · ${media.name.substringBeforeLast('.').take(24)}", summary = null, audioPath = media.path)
                _state.update { it.copy(phase = JobPhase.DONE, progress = 1f) }
            } catch (e: CancellationException) {
                if (created && cues.isEmpty()) runCatching { repo.delete(sessionId) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = JobPhase.FAILED, error = e.message ?: e.toString()) }
                if (created && cues.isEmpty()) runCatching { repo.delete(sessionId) }
            } finally {
                collector?.cancel()
                session?.close()
            }
        }
        return sessionId
    }
}
