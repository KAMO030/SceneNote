package dev.scenenote.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 视频文件的来源（S4）。 */
enum class MediaSource { GALLERY, FILE, URL, SHARE }

data class MediaItem(val path: String, val name: String, val durationMs: Long, val source: MediaSource)

/** 相册 / 文件选择（平台实现：Android ActivityResult；iOS PHPicker / UIDocumentPicker）。返回沙盒内可读路径；取消返回 null。 */
interface MediaPicker {
    suspend fun pickVideo(source: MediaSource): MediaItem?
}

/** 抽音频：视频 → 16 kHz 单声道 PCM16（.wav，写在 cacheDir）。进度 0..1。 */
interface AudioExtractor {
    suspend fun extractPcm16k(videoPath: String, onProgress: (Float) -> Unit = {}): String
    /** 时长（ms），取不到返回 -1。 */
    suspend fun durationMs(videoPath: String): Long
}

/** 播放器控制（S4 边转边看：转写追不上时自动暂停）。 */
interface PlayerController {
    val positionMs: StateFlow<Long>
    val playing: StateFlow<Boolean>
    val durationMs: StateFlow<Long>
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun release()
}

/** 内嵌视频画面（Android VideoView / iOS AVPlayerLayer）；控制器由同一平台实现返回。 */
interface VideoPlayerFactory {
    fun create(path: String): PlayerController
    @Composable fun View(controller: PlayerController, modifier: Modifier)
}

/** Android S1：系统音频抓取（MediaProjection + AudioPlaybackCapture）。iOS 无此能力（接口存在但 available = false）。 */
interface SystemAudioCapture {
    val available: Boolean
    val state: StateFlow<CaptureState>
    /** 16 kHz 单声道 PCM16 帧（20 ms）。 */
    val frames: Flow<ShortArray>
    /** 申请权限并开始（会弹系统的"开始录制/投屏"确认）。 */
    suspend fun start(): Boolean
    fun stop()
    /** 进入画中画字幕条（Android）。 */
    fun enterPip()
}
enum class CaptureState { IDLE, REQUESTING, CAPTURING, BLOCKED /* 2 s 内全零：对方 App 禁止抓取 */, DENIED, ERROR }

class NoopMediaPicker : MediaPicker { override suspend fun pickVideo(source: MediaSource): MediaItem? = null }
class NoopAudioExtractor : AudioExtractor {
    override suspend fun extractPcm16k(videoPath: String, onProgress: (Float) -> Unit): String = error("audio extraction not implemented on this platform")
    override suspend fun durationMs(videoPath: String): Long = -1
}
class NoopSystemAudioCapture : SystemAudioCapture {
    override val available = false
    override val state = kotlinx.coroutines.flow.MutableStateFlow(CaptureState.IDLE)
    override val frames: Flow<ShortArray> = kotlinx.coroutines.flow.emptyFlow()
    override suspend fun start() = false
    override fun stop() {}
    override fun enterPip() {}
}

class NoopPlayerController : PlayerController {
    override val positionMs = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override val playing = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val durationMs = kotlinx.coroutines.flow.MutableStateFlow(0L)
    override fun play() { playing.value = true }
    override fun pause() { playing.value = false }
    override fun seekTo(ms: Long) { positionMs.value = ms }
    override fun release() {}
}
class NoopVideoPlayerFactory : VideoPlayerFactory {
    override fun create(path: String): PlayerController = NoopPlayerController()
    @Composable override fun View(controller: PlayerController, modifier: Modifier) {}
}
