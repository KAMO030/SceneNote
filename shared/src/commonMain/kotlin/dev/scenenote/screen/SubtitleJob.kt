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
import dev.scenenote.core.model.Segment
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.core.platform.MemoryTier
import dev.scenenote.polish.Cue
import dev.scenenote.translate.FastTranslator
import dev.scenenote.translate.MtRequest
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
)

/**
 * S4 字幕任务（I6）：视频 → 抽音频（平台）→ 本机识别（VAD 切句，不按实时节奏、能多快跑多快）→ 云翻译（每 8 句一批）→ 落库为 SCREEN 会话。
 * 边转边看：cues 与 transcribedMs 随时更新，播放器追上来就暂停等（规格：Waiting 遮罩）。
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

    fun cancel() { job?.cancel(); _state.value = _state.value.copy(phase = JobPhase.IDLE) }

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
        job?.cancel()
        val sessionId = Uuid.random().toString()
        segIds.clear(); revs.clear()
        _state.value = SubtitleState(phase = JobPhase.EXTRACTING, sessionId = sessionId, media = media)
        job = scope.launch {
            try {
                val duration = if (media.durationMs > 0) media.durationMs else extractor.durationMs(media.path)
                repo.create(sessionId, SessionKind.SCREEN, "screen_file", "S4", srcLang, tgtLang, srcLang, engine.info)
                val wav = extractor.extractPcm16k(media.path) { p -> _state.value = _state.value.copy(progress = p) }
                val pcm = WavIo.readPcm16k(wav)
                _state.value = _state.value.copy(phase = JobPhase.TRANSCRIBING, progress = 0f)
                val ready = engine.load(LoadPlan.forLang(srcLang, tier = MemoryTier.current))
                if (ready is LocalEngineState.Error) error(ready.reason)
                val session = engine.openStream(srcLang, emptyList()) as SherpaStreamingSession
                val cues = mutableListOf<Cue>()
                val collector = launch {
                    session.events.onEach { ev ->
                        when (ev) {
                            is AsrEvent.Partial -> _state.value = _state.value.copy(partial = ev.text)
                            is AsrEvent.Final -> {
                                val seg = ev.segment
                                if (seg.text.isBlank()) return@onEach
                                val idx = segIds.indexOf(seg.id)   // rev1 定稿只替换文本
                                if (idx >= 0) { if (seg.revision >= revs[idx]) { cues[idx] = cues[idx].copy(text = seg.text); revs[idx] = seg.revision } }
                                else { segIds += seg.id; revs += seg.revision; cues += Cue(cues.size + 1, seg.startMs, seg.endMs, seg.text) }
                                repo.addSegment(sessionId, seg)
                                _state.value = _state.value.copy(cues = cues.toList(), partial = "", transcribedMs = seg.endMs)
                            }
                            else -> Unit
                        }
                    }.collect()
                }
                // 不按实时节奏：一次喂 20 ms 帧，让 worker 尽量跑满；每 2 s 音频让出一次调度以便 UI 刷新
                val frame = 320
                var off = 0
                while (off < pcm.size) {
                    val end = minOf(off + frame, pcm.size)
                    session.push(ShortArray(frame).also { pcm.copyInto(it, 0, off, end) })
                    off += frame
                    if (off % (frame * 100) == 0) { delay(1); _state.value = _state.value.copy(progress = off.toFloat() / pcm.size) }
                }
                session.endOfInput()
                withTimeoutOrNull(60_000) { while (!session.isDrained()) delay(20) }
                collector.cancel(); session.close()
                _state.value = _state.value.copy(transcribedMs = duration.takeIf { it > 0 } ?: _state.value.transcribedMs, progress = 1f)
                // 翻译：每 8 句一批（qwen-mt RPM 60）
                if (translate && cues.isNotEmpty()) {
                    _state.value = _state.value.copy(phase = JobPhase.TRANSLATING, progress = 0f)
                    val translations = mutableMapOf<Int, String>()
                    cues.chunked(8).forEachIndexed { bi, batch ->
                        val text = batch.joinToString("\n") { it.text }
                        val r = translator.translate(MtRequest(text, srcLang, tgtLang, sessionId = sessionId))
                        val lines = r.result?.text?.split('\n')?.map { it.trim() }.orEmpty()
                        batch.forEachIndexed { i, c -> lines.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { translations[c.index] = it } }
                        _state.value = _state.value.copy(cues = cues.map { c -> translations[c.index]?.let { t -> c.copy(translation = t) } ?: c }, progress = (bi + 1f) / (cues.size / 8f + 1f))
                        if (r.result == null) return@forEachIndexed
                    }
                    repo.saveNote(sessionId, "subtitle", "cloud:bailian", "", json.encodeToString(translations.mapKeys { it.key.toString() }), "")
                }
                repo.end(sessionId, title = "字幕 · ${media.name.substringBeforeLast('.').take(24)}", summary = null)
                _state.value = _state.value.copy(phase = JobPhase.DONE, progress = 1f)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.value = _state.value.copy(phase = JobPhase.FAILED, error = e.message ?: e.toString()) }
        }
        return sessionId
    }
    private val segIds = mutableListOf<String>()
    private val revs = mutableListOf<Int>()
}
