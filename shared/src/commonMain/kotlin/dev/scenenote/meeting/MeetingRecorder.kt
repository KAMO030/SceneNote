package dev.scenenote.meeting

import dev.scenenote.asr.AsrEvent
import dev.scenenote.asr.LoadPlan
import dev.scenenote.asr.LocalEngineState
import dev.scenenote.asr.SherpaAsrEngine
import dev.scenenote.asr.SherpaStreamingSession
import dev.scenenote.asr.StreamingAsrSession
import dev.scenenote.audio.AudioFactory
import dev.scenenote.audio.AudioMode
import dev.scenenote.audio.AudioSource
import dev.scenenote.audio.CaptureConfig
import dev.scenenote.audio.PcmFileWriter
import dev.scenenote.audio.Pcm
import dev.scenenote.core.db.SessionKind
import dev.scenenote.core.db.SessionRepository
import dev.scenenote.core.model.Segment
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.core.platform.MemoryTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlinx.datetime.toLocalDateTime
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import dev.scenenote.shared.resources.*
import org.jetbrains.compose.resources.getString

enum class RecState { IDLE, PREPARING, RECORDING, PAUSED, FINISHING }

data class MeetingState(
    val state: RecState = RecState.IDLE,
    val sessionId: String? = null,
    val elapsedMs: Long = 0,
    val rmsDb: Float = -120f,
    val segments: List<Segment> = emptyList(),
    val partial: String = "",
    val bookmarks: Int = 0,
    val audioPath: String? = null,
    val error: String? = null,
)

/**
 * 会议录音（I5）：一按即录 → 本机流式识别（zipformer 草稿 + 定稿模型按内存分级）→ 段落落库 → 音频 AAC 落盘；
 * 暂停 / 继续、标记要点（时间戳书签）；停止后交给慢路径成稿。录音与文字都不出手机。
 */
class MeetingRecorder(
    private val audio: AudioFactory,
    private val engine: SherpaAsrEngine,
    private val repo: SessionRepository,
    private val paths: AppPaths,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(MeetingState())
    val state: StateFlow<MeetingState> = _state.asStateFlow()

    private var source: AudioSource? = null
    private var session: StreamingAsrSession? = null
    private var writer: PcmFileWriter? = null
    private var jobs: List<Job> = emptyList()
    private var startedMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var accumulatedMs = 0L
    private var offsetMs = 0L          // 暂停前累计的音频时长，段落时间戳加上它
    private var sessionId = ""
    private var lang = "zh-CN"

    suspend fun start(sceneId: String, lang: String): String {
        if (_state.value.state != RecState.IDLE) return sessionId
        this.lang = lang
        val gen = ++generation
        _state.value = MeetingState(state = RecState.PREPARING)
        val ready = engine.load(LoadPlan.forLang(lang, tier = MemoryTier.current))
        if (gen != generation || _state.value.state != RecState.PREPARING) return ""   // 装载期间被 release：不再开录
        if (ready is LocalEngineState.Error) { _state.update { it.copy(state = RecState.IDLE, error = ready.reason) }; throw IllegalStateException(ready.reason) }
        sessionId = Uuid.random().toString()
        repo.create(sessionId, SessionKind.RECORD, sceneId, null, lang, null, null, engine.info)
        paths.ensureDir(paths.vaultDir)
        val audioPath = paths.join(paths.vaultDir, "meeting-$sessionId.m4a")
        writer = audio.fileWriter(audioPath)
        audio.routeManager().ensure()
        _state.update { it.copy(sessionId = sessionId, audioPath = audioPath) }
        accumulatedMs = 0; offsetMs = 0; audioMs = 0
        openStream()
        return sessionId
    }

    private suspend fun openStream() {
        val s = engine.openStream(lang, emptyList()).also { session = it }
        val src = audio.source().also { source = it }
        val w = writer
        startedMark = TimeSource.Monotonic.markNow()
        jobs = listOf(
            scope.launch(Dispatchers.Default) {
                var n = 0
                src.frames.onEach { f ->
                    w?.write(f); s.push(f); n++
                    audioMs += f.size * 1000L / 16_000
                    if (n % 10 == 0) { val rms = Pcm.rmsDb(f); val el = accumulatedMs + audioMs; _state.update { it.copy(rmsDb = rms, elapsedMs = el) } }
                }.collect()
            },
            scope.launch {
                s.events.onEach { ev ->
                    when (ev) {
                        is AsrEvent.Partial -> _state.update { it.copy(partial = ev.text) }
                        is AsrEvent.Final -> {
                            val seg = ev.segment.copy(startMs = ev.segment.startMs + offsetMs, endMs = ev.segment.endMs + offsetMs)
                            _state.update { st ->
                                val cur = st.segments
                                val i = cur.indexOfFirst { it.id == seg.id }
                                val next = if (i >= 0) { if (cur[i].revision <= seg.revision) cur.toMutableList().also { it[i] = seg } else cur } else cur + seg
                                st.copy(segments = next, partial = "")
                            }
                            if (seg.text.isNotBlank()) repo.addSegment(sessionId, seg)
                        }
                        is AsrEvent.Error -> _state.update { it.copy(error = ev.message) }
                        else -> Unit
                    }
                }.collect()
            },
        )
        src.start(CaptureConfig(mode = AudioMode.DEFAULT))
        _state.update { it.copy(state = RecState.RECORDING, error = null) }
    }

    private suspend fun closeStream() {
        source?.stop(); source = null
        val s = session; session = null
        jobs.firstOrNull()?.cancelAndJoin()          // 帧泵先停干净，文件写入不再有并发
        s?.endOfInput()
        if (s is SherpaStreamingSession) { withTimeoutOrNull(800) { while (!s.isDrained()) delay(10) }; delay(40); s.close() }   // 让最后一句 Final 被收集到
        jobs.getOrNull(1)?.cancelAndJoin(); jobs = emptyList()
        accumulatedMs += audioMs; audioMs = 0            // 用音频时钟累加，与段落时间戳同源
        offsetMs = accumulatedMs
        startedMark = null
    }
    private var audioMs = 0L
    private var generation = 0

    suspend fun pause() {
        if (_state.value.state != RecState.RECORDING) return
        closeStream()
        _state.update { it.copy(state = RecState.PAUSED, partial = "", elapsedMs = accumulatedMs) }
    }

    suspend fun resume() { if (_state.value.state == RecState.PAUSED) openStream() }

    /** 标记要点：记当前时间戳（纪要里高亮这一段）。 */
    suspend fun mark(label: String = "") {
        val at = _state.value.elapsedMs
        repo.addBookmark(sessionId, at, label)
        _state.update { it.copy(bookmarks = it.bookmarks + 1) }
    }

    /** 停止：收尾识别、关文件、结束会话（标题取首句）。返回 sessionId。 */
    suspend fun stop(): String {
        val st = _state.value.state
        if (st == RecState.PREPARING) { generation++; _state.update { it.copy(state = RecState.IDLE) }; return "" }   // 还没开录：作废
        if (st == RecState.IDLE || st == RecState.FINISHING) return ""     // 重复点击不再返回 id（避免叠两个纪要页）
        _state.update { it.copy(state = RecState.FINISHING) }
        if (st != RecState.PAUSED) closeStream()
        val path = writer?.runCatching { close() }?.getOrNull(); writer = null
        val first = _state.value.segments.firstOrNull()?.text?.take(24)
        repo.end(sessionId, title = first?.let { getString(Res.string.session_title_meeting_with, it) } ?: getString(Res.string.session_title_meeting_at, clockHm()), summary = null, audioPath = path)
        _state.update { it.copy(state = RecState.IDLE, audioPath = path) }
        return sessionId
    }
    private fun clockHm(): String {
        val now = Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        return "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
    }

    fun release() { scope.launch { runCatching { stop() } } }
}
