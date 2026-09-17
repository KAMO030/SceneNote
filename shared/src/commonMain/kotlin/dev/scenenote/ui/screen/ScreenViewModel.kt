package dev.scenenote.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scenenote.core.db.SessionRepository
import dev.scenenote.core.db.SessionRow
import dev.scenenote.core.platform.Sharer
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.polish.Cue
import dev.scenenote.polish.Exports
import dev.scenenote.polish.Subtitles
import dev.scenenote.screen.JobPhase
import dev.scenenote.screen.MediaItem
import dev.scenenote.screen.MediaPicker
import dev.scenenote.screen.MediaSource
import dev.scenenote.screen.PlayerController
import dev.scenenote.screen.SubtitleJob
import dev.scenenote.screen.SubtitleState
import dev.scenenote.screen.VideoPlayerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

data class ScreenUiState(
    val job: SubtitleState = SubtitleState(),
    val recent: List<SessionRow> = emptyList(),
    val downloading: Float? = null,
    val error: String? = null,
    /** 播放器：当前应显示的 cue（按 position 找）。 */
    val currentCue: Cue? = null,
    /** 转写落后于播放 → 等待遮罩。 */
    val waiting: Boolean = false,
    val showTranslation: Boolean = true,
    val exportedPath: String? = null,
)

/**
 * 屏内 S4（I6）：入口（相册 / 文件 / 直链 / 分享面板）→ 字幕任务 → 播放器（边转边看、自动暂停）→ 导出 SRT / VTT。
 * 播放器控制器由平台工厂创建；VM 持有并在 onCleared 释放。
 */
class ScreenViewModel(
    private val picker: MediaPicker,
    private val job: SubtitleJob,
    private val players: VideoPlayerFactory,
    private val repo: SessionRepository,
    private val exports: Exports,
    private val sharer: Sharer,
    private val settings: AppSettings,
) : ViewModel() {
    private val _ui = MutableStateFlow(ScreenUiState())
    val ui: StateFlow<ScreenUiState> = _ui.asStateFlow()
    var player: PlayerController? = null; private set
    val playerFactory: VideoPlayerFactory get() = players

    init {
        job.state.onEach { s -> _ui.value = _ui.value.copy(job = s); syncCue() }.launchIn(viewModelScope)
        repo.recent(50).onEach { list -> _ui.value = _ui.value.copy(recent = list.filter { it.kind == dev.scenenote.core.db.SessionKind.SCREEN }) }.launchIn(viewModelScope)
    }

    fun pick(source: MediaSource) {
        viewModelScope.launch {
            val item = runCatching { picker.pickVideo(source) }.getOrElse { _ui.value = _ui.value.copy(error = it.message); null } ?: return@launch
            begin(item)
        }
    }
    fun openUrl(url: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(downloading = 0f, error = null)
            val item = runCatching { job.download(url.trim()) { p -> _ui.value = _ui.value.copy(downloading = p) } }.getOrElse { _ui.value = _ui.value.copy(downloading = null, error = it.message); null } ?: return@launch
            _ui.value = _ui.value.copy(downloading = null)
            begin(item)
        }
    }
    /** 分享面板送来的文件（平台在 onNewIntent / onOpenURL 里放入路径）。 */
    fun openShared(path: String, name: String) { begin(MediaItem(path, name, -1, MediaSource.SHARE)) }

    private fun begin(item: MediaItem) {
        player?.release()
        player = players.create(item.path).also { p ->
            p.positionMs.onEach { syncCue() }.launchIn(viewModelScope)
        }
        val src = settings.otherLang; val tgt = settings.myLang
        job.start(item, src, tgt, translate = src != tgt)
    }

    /** 只恢复自己暂停的播放；用户手动暂停不自动开播。 */
    private var autoPaused = false
    /** 按播放位置选字幕；播放位置追上转写进度 → 暂停并显示等待；转写领先 ≥ 5 s（或已完成）才恢复（滞回，避免抖动）。 */
    private fun syncCue() {
        val p = player ?: return
        val pos = p.positionMs.value
        val s = _ui.value.job
        val cue = s.cues.lastOrNull { it.startMs <= pos + 150 && pos < it.endMs + 400 }
        val transcribing = s.phase == JobPhase.EXTRACTING || s.phase == JobPhase.TRANSCRIBING
        val behind = transcribing && pos > s.transcribedMs - 500
        val caughtUp = !transcribing || s.transcribedMs - pos >= 5_000
        if (behind && p.playing.value) { p.pause(); autoPaused = true }
        if (autoPaused && caughtUp && !p.playing.value) { p.play(); autoPaused = false }
        _ui.value = _ui.value.copy(currentCue = cue, waiting = behind || (autoPaused && !caughtUp))
    }
    /** 用户手动暂停 / 播放：清掉自动暂停标记。 */
    fun userPause() { autoPaused = false; player?.pause() }
    fun userPlay() { autoPaused = false; player?.play() }

    fun toggleTranslation() { _ui.value = _ui.value.copy(showTranslation = !_ui.value.showTranslation) }
    fun exportSrt(vtt: Boolean = false) {
        val id = _ui.value.job.sessionId ?: return
        viewModelScope.launch {
            val s = repo.byId(id) ?: return@launch
            val cues = _ui.value.job.cues
            val p = if (vtt) exports.vtt(s, cues, translated = true) else exports.srt(s, cues, translated = true)
            sharer.shareFile(p, if (vtt) "text/vtt" else "application/x-subrip", s.title ?: "字幕")
            _ui.value = _ui.value.copy(exportedPath = p)
        }
    }
    /** 从资料库重新打开一条字幕会话（cues 来自落库的段落 + 译文 note）。 */
    fun reopen(sessionId: String) {
        viewModelScope.launch {
            val s = repo.byId(sessionId) ?: return@launch
            val segs = repo.segments(sessionId)
            val tr = repo.note(sessionId, "subtitle")?.let { runCatching { Json.decodeFromString<Map<String, String>>(it.json) }.getOrNull() }.orEmpty()
            val cues = segs.mapIndexed { i, seg -> Cue(i + 1, seg.startMs, seg.endMs, seg.text, tr[(i + 1).toString()]) }
            // 视频文件还在（cache 未清）就重建播放器；否则只看字幕
            val path = s.audioPath?.takeIf { okio.FileSystem.SYSTEM.exists(it.toPath()) }
            player?.release(); player = path?.let { pth -> players.create(pth).also { p -> p.positionMs.onEach { syncCue() }.launchIn(viewModelScope) } }
            _ui.value = _ui.value.copy(job = SubtitleState(phase = JobPhase.DONE, sessionId = sessionId, cues = cues, transcribedMs = Long.MAX_VALUE, media = path?.let { MediaItem(it, s.title ?: "", -1, MediaSource.FILE) }, translate = tr.isNotEmpty()))
        }
    }
    fun cancel() { job.cancel(); player?.release(); player = null }
    override fun onCleared() { player?.release() }
}
