package dev.scenenote.screen

import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.scenenote.core.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android 内嵌播放器（S4 边转边看）：`android.media.MediaPlayer` + `SurfaceView`，不引入 media3。
 * - 控制器：prepareAsync；prepared 前的 play / seekTo 记下来，prepared 后补做。
 * - positionMs：主线程协程每 100 ms 轮询 `currentPosition`（只在播放中更新，暂停时以 seek 结果为准）。
 * - View：SurfaceHolder 回调里 `setDisplay(holder)` / 置空；画面按视频宽高比居中留黑边（不拉伸）。
 */
class AndroidVideoPlayerFactory(context: Context) : VideoPlayerFactory {
    private val ctx = context.applicationContext

    override fun create(path: String): PlayerController = AndroidPlayerController(path)

    @Composable
    override fun View(controller: PlayerController, modifier: Modifier) {
        val c = controller as? AndroidPlayerController ?: return
        val size by c.videoSize.collectAsState()
        AndroidView(
            factory = { context -> FitSurfaceLayout(context).also { it.bind(c) } },
            modifier = modifier,
            update = { layout -> layout.bind(c); layout.setVideoSize(size.first, size.second) },
        )
        DisposableEffect(c) { onDispose { c.attachSurface(null) } }
    }
}

class AndroidPlayerController(private val path: String) : PlayerController {
    private val mp = MediaPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _position = MutableStateFlow(0L)
    private val _playing = MutableStateFlow(false)
    private val _duration = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _position.asStateFlow()
    override val playing: StateFlow<Boolean> = _playing.asStateFlow()
    override val durationMs: StateFlow<Long> = _duration.asStateFlow()
    /** 视频像素宽高（onVideoSizeChanged），View 据此保持比例。 */
    val videoSize = MutableStateFlow(0 to 0)

    private var prepared = false
    private var released = false
    private var wantPlay = false
    private var pendingSeek = -1L

    init {
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        mp.setOnPreparedListener { p ->
            if (released) return@setOnPreparedListener
            prepared = true
            _duration.value = p.duration.toLong().coerceAtLeast(0)
            if (pendingSeek >= 0) { seekTo(pendingSeek); pendingSeek = -1 }
            if (wantPlay) { runCatching { p.start() }; _playing.value = p.isPlaying }
        }
        mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) videoSize.value = w to h }
        mp.setOnSeekCompleteListener { p -> if (!released) _position.value = p.currentPosition.toLong() }
        mp.setOnCompletionListener {
            _playing.value = false
            wantPlay = false
            _position.value = _duration.value
        }
        mp.setOnErrorListener { _, what, extra ->
            Diag.log("player", "error what=$what extra=$extra path=$path")
            _playing.value = false
            true
        }
        runCatching {
            mp.setDataSource(path)
            mp.prepareAsync()
        }.onFailure { Diag.log("player", "open failed: ${it.message}") }
        scope.launch {
            while (isActive) {
                if (prepared && !released && _playing.value) {
                    runCatching { _position.value = mp.currentPosition.toLong() }
                }
                delay(100)
            }
        }
    }

    /** SurfaceView 建好 / 销毁时由 View 调用。 */
    fun attachSurface(h: SurfaceHolder?) {
        if (released) return
        runCatching { mp.setDisplay(h) }
        if (h != null) runCatching { mp.setScreenOnWhilePlaying(true) }
    }

    override fun play() {
        if (released) return
        wantPlay = true
        if (prepared) {
            runCatching { mp.start() }
            _playing.value = runCatching { mp.isPlaying }.getOrDefault(false)
        }
    }

    override fun pause() {
        if (released) return
        wantPlay = false
        if (prepared) runCatching { if (mp.isPlaying) mp.pause() }
        _playing.value = false
    }

    override fun seekTo(ms: Long) {
        if (released) return
        val target = ms.coerceAtLeast(0)
        if (prepared) {
            runCatching { mp.seekTo(target, MediaPlayer.SEEK_CLOSEST) }
            _position.value = target
        } else pendingSeek = target
    }

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        _playing.value = false
        runCatching { mp.setDisplay(null) }
        runCatching { if (prepared) mp.stop() }
        runCatching { mp.release() }
    }
}

/**
 * 承载 SurfaceView 的容器：按视频宽高比在给定尺寸内居中（letterbox），背景黑。
 * 视频尺寸未知（还没 prepared）时先铺满。
 */
internal class FitSurfaceLayout(context: Context) : FrameLayout(context) {
    private val surface = SurfaceView(context)
    private var videoW = 0
    private var videoH = 0
    private var controller: AndroidPlayerController? = null

    private val callback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) { controller?.attachSurface(holder) }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        override fun surfaceDestroyed(holder: SurfaceHolder) { controller?.attachSurface(null) }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        surface.holder.addCallback(callback)
    }

    fun bind(c: AndroidPlayerController) {
        if (controller === c) return
        controller?.attachSurface(null)
        controller = c
        if (surface.holder.surface?.isValid == true) c.attachSurface(surface.holder)
    }

    fun setVideoSize(w: Int, h: Int) {
        if (w == videoW && h == videoH) return
        videoW = w; videoH = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pw = MeasureSpec.getSize(widthMeasureSpec)
        val ph = MeasureSpec.getSize(heightMeasureSpec)
        var cw = pw; var ch = ph
        if (videoW > 0 && videoH > 0 && pw > 0 && ph > 0) {
            if (pw.toLong() * videoH > ph.toLong() * videoW) { ch = ph; cw = (ph.toLong() * videoW / videoH).toInt() }
            else { cw = pw; ch = (pw.toLong() * videoH / videoW).toInt() }
        }
        surface.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        setMeasuredDimension(pw, ph)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left; val h = bottom - top
        val cw = surface.measuredWidth; val ch = surface.measuredHeight
        val l = (w - cw) / 2; val t = (h - ch) / 2
        surface.layout(l, t, l + cw, t + ch)
    }

    override fun onDetachedFromWindow() {
        controller?.attachSurface(null)
        super.onDetachedFromWindow()
    }
}
