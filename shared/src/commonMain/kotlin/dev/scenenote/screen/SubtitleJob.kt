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
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.Segment
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.core.platform.MemoryTier
import dev.scenenote.polish.Cue
import dev.scenenote.translate.FastTranslator
import dev.scenenote.translate.FastMt
import dev.scenenote.translate.MtRequest
import dev.scenenote.translate.Script
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
import dev.scenenote.core.i18n.UiText
import dev.scenenote.core.i18n.uiError
import dev.scenenote.core.i18n.uiText
import dev.scenenote.shared.resources.*
import org.jetbrains.compose.resources.getString

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
    val error: UiText? = null,
    /** 有句子的语种和目标语不同才有译文（全是目标语时只出原文字幕，页面不显示译文 / 原文切换）；按识别结果随转写更新。 */
    val translate: Boolean = false,
)

/**
 * 一句字幕的源语言：定稿带语种识别（SenseVoice）就用它；草稿 / 没装多语言识别时按文字脚本猜，与会话设定同家族（方言 / 粤语，脚本分不出）才沿用设定。
 * 不能把设定里的「对方语言」直接当源语言：视频说的是什么语言和它无关（设成日语时中文 / 英文视频会被当日语翻，端侧还会去找日译英包）。
 */
internal fun cueLang(seg: Segment, fallback: String): String {
    if (seg.revision >= 1 && seg.lang != Lang.AUTO) return seg.lang
    val guess = Script.guessLang(seg.text, fallback)
    return if (Lang.family(guess) == Lang.family(fallback)) fallback else guess
}

/** 要不要翻：同一种语言不翻；普通话 / 川渝 / 吴 / 闽是同一套书面语也不翻；书面粤语和普通话不同，要翻。 */
internal fun needsMt(src: String, tgt: String): Boolean =
    src != tgt && (Lang.family(src) != Lang.family(tgt) || src == Lang.YUE_HK || tgt == Lang.YUE_HK)

/** 同语种的句子按先后凑批，每批最多 [size] 句（中间夹着不用翻的句子不影响）。 */
internal fun batchesByLang(indices: List<Int>, langs: List<String>, size: Int = 8): List<List<Int>> {
    val out = mutableListOf<MutableList<Int>>()
    for (i in indices) {
        val last = out.lastOrNull()
        if (last != null && last.size < size && langs[last.last()] == langs[i]) last += i else out += mutableListOf(i)
    }
    return out
}

/**
 * S4 字幕任务（I6）：视频 → 抽音频（平台）→ 本机识别（VAD 切句，不按实时节奏，最多领先播放位置 30 s 音频）→ 按每句识别出的语种翻译（每 8 句一批）→ 落库为 SCREEN 会话。
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
                if (resp.status.value !in 200..299) uiError(Res.string.media_download_failed, resp.status.value)
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

    /**
     * 开始一个字幕任务；返回 sessionId。[srcLang] 只用来选识别引擎（四川话定稿）和猜不出语种时兜底，译文一律翻成 [tgtLang]。
     */
    fun start(media: MediaItem, srcLang: String, tgtLang: String): String {
        val prev = job
        val sessionId = Uuid.random().toString()
        _state.value = SubtitleState(phase = JobPhase.EXTRACTING, sessionId = sessionId, media = media)
        job = scope.launch {
            prev?.cancelAndJoin()
            var session: SherpaStreamingSession? = null
            var collector: Job? = null
            val cues = mutableListOf<Cue>()
            val segIds = mutableListOf<String>(); val revs = mutableListOf<Int>(); val langs = mutableListOf<String>()   // 与 cues 下标对齐
            var created = false
            try {
                val duration = if (media.durationMs > 0) media.durationMs else extractor.durationMs(media.path)
                val wav = extractor.extractPcm16k(media.path) { p -> _state.update { it.copy(progress = p) } }
                val pcm = WavIo.readPcm16k(wav)
                val totalMs = if (duration > 0) duration else pcm.size * 1000L / 16_000
                _state.update { it.copy(phase = JobPhase.TRANSCRIBING, progress = 0f) }
                val ready = engine.load(LoadPlan.forLang(srcLang, tier = MemoryTier.current))
                if (ready is LocalEngineState.Error) uiError(Res.string.live_pack_missing)
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
                                val idx = segIds.indexOf(seg.id)   // rev1 定稿只替换文本（和语种）
                                if (idx >= 0) { if (seg.revision >= revs[idx]) { cues[idx] = cues[idx].copy(text = seg.text); revs[idx] = seg.revision; langs[idx] = cueLang(seg, srcLang) } }
                                else { segIds += seg.id; revs += seg.revision; langs += cueLang(seg, srcLang); cues += Cue(cues.size + 1, seg.startMs, seg.endMs, seg.text) }
                                repo.addSegment(sessionId, seg)
                                _state.update { it.copy(cues = cues.toList(), translate = langs.any { l -> needsMt(l, tgtLang) }) }
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
                // 翻译：按每句识别出的语种翻（视频可能夹着几种语言，也可能本来就是目标语）；同语种每 8 句一批（qwen-mt RPM 60），行数对不上就逐句重译。
                // 某一批失败不放弃后面的批次（别的语种可能能翻）；没连云端也没离线包的语种，后面同语种的批次不再试
                val todo = cues.indices.filter { needsMt(langs[it], tgtLang) }
                if (todo.isNotEmpty()) {
                    _state.update { it.copy(phase = JobPhase.TRANSLATING, progress = 0f) }
                    val translations = mutableMapOf<Int, String>()
                    fun put(i: Int, t: String) { val v = t.trim(); if (v.isNotEmpty() && v != cues[i].text) translations[cues[i].index] = v }   // 端侧同书面语直接回原文：不算译文
                    val batches = batchesByLang(todo, langs)
                    var failed: String? = null   // FastMt.code，UI 边缘映射成文案
                    val unavailable = mutableSetOf<String>()
                    for ((bi, batch) in batches.withIndex()) {
                        ensureActive()
                        val src = langs[batch.first()]
                        if (src !in unavailable) {
                            val r = translator.translate(MtRequest(batch.joinToString("\n") { cues[it].text }, src, tgtLang, sessionId = sessionId))
                            val res = r.result
                            if (res == null) {
                                failed = r.code ?: r.reason
                                if (r.code == FastMt.CODE_NO_KEY_NO_PAIR) unavailable += src
                                dev.scenenote.core.Diag.log("subtitle", "untranslated $src→$tgtLang: ${r.reason}")
                            } else {
                                val lines = res.text.split('\n').map { it.trim() }.filter { it.isNotBlank() }
                                if (lines.size == batch.size) batch.forEachIndexed { k, i -> put(i, lines[k]) }
                                else for (i in batch) translator.translate(MtRequest(cues[i].text, src, tgtLang, sessionId = sessionId)).result?.let { put(i, it.text) }
                            }
                        }
                        _state.update { it.copy(cues = cues.map { c -> translations[c.index]?.let { t -> c.copy(translation = t) } ?: c }, progress = (bi + 1f) / batches.size) }
                    }
                    repo.saveNote(sessionId, "subtitle", "cloud:bailian", "", json.encodeToString(translations.mapKeys { it.key.toString() }), "")
                    _state.update { it.copy(translate = translations.isNotEmpty()) }
                    if (failed != null) _state.update { it.copy(error = UiText.res(when (failed) {
                        FastMt.CODE_NO_KEY_NO_PAIR -> Res.string.screen_untranslated_no_pair
                        FastMt.CODE_CLOUD_DOWN_NO_PAIR -> Res.string.screen_untranslated_cloud_down
                        FastMt.CODE_LOCAL_FAILED -> Res.string.screen_untranslated_local_failed
                        else -> Res.string.screen_untranslated
                    })) }
                }
                repo.end(sessionId, title = getString(Res.string.session_title_subtitle, media.name.substringBeforeLast('.').take(24)), summary = null, audioPath = media.path)
                _state.update { it.copy(phase = JobPhase.DONE, progress = 1f) }
            } catch (e: CancellationException) {
                if (created && cues.isEmpty()) runCatching { repo.delete(sessionId) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = JobPhase.FAILED, error = e.uiText()) }
                if (created && cues.isEmpty()) runCatching { repo.delete(sessionId) }
            } finally {
                collector?.cancel()
                session?.close()
            }
        }
        return sessionId
    }
}
