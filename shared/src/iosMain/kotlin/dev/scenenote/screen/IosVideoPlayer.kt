package dev.scenenote.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreGraphics.CGRect
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 内嵌播放器（S4 边转边看）：AVPlayer + AVPlayerLayer 挂在 UIKitView 里的一个 UIView 上。
 * - positionMs 由 addPeriodicTimeObserverForInterval(100 ms) 在主队列更新；durationMs 先由 AVURLAsset 同步取，播放项就绪后再校准。
 * - playing = rate > 0；播到结尾时收 AVPlayerItemDidPlayToEndTime 置 false。
 * - seekTo 用零容差（字幕精确同步）。
 * - release：removeTimeObserver + 移除通知 + 清空播放项；VM 在 onCleared 调。
 * 注意：不改 AVAudioSession 类别（会话由 IosRouteManager 统一管），静音拨片下 AVPlayer 可能无声——需真机确认后决定是否在进 S4 时切 .playback。
 */
class IosVideoPlayerFactory : VideoPlayerFactory {
    override fun create(path: String): PlayerController = IosPlayerController(path)

    @Composable
    override fun View(controller: PlayerController, modifier: Modifier) {
        val ctl = controller as? IosPlayerController ?: return
        val layer = remember(ctl) { AVPlayerLayer().apply { player = ctl.player; videoGravity = AVLayerVideoGravityResizeAspect } }
        UIKitView(
            factory = { PlayerHostView(layer) },
            modifier = modifier,
            update = { it.setNeedsLayout() },
            onRelease = { layer.player = null },
        )
    }
}

/** 承载 AVPlayerLayer 的 UIView；layoutSubviews 时让子层贴满（旋转 / 尺寸变化都会走这里）。 */
private class PlayerHostView(private val playerLayer: AVPlayerLayer) : UIView(frame = platform.CoreGraphics.CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val b: CValue<CGRect> = bounds
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = b
        CATransaction.commit()
    }
}

class IosPlayerController(path: String) : PlayerController {
    private val _positionMs = MutableStateFlow(0L)
    private val _playing = MutableStateFlow(false)
    private val _durationMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    override val playing: StateFlow<Boolean> = _playing.asStateFlow()
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
    val player: AVPlayer = AVPlayer(playerItem = AVPlayerItem(asset = asset))
    private var timeObserver: Any? = null
    private var endObserver: Any? = null
    private var released = false

    init {
        _durationMs.value = asset.duration.ms().coerceAtLeast(0L)
        timeObserver = player.addPeriodicTimeObserverForInterval(
            interval = CMTimeMakeWithSeconds(0.1, 600),
            queue = dispatch_get_main_queue(),
        ) { t ->
            _positionMs.value = t.ms().coerceAtLeast(0L)
            _playing.value = player.rate > 0f
            if (_durationMs.value <= 0L) player.currentItem?.duration?.ms()?.takeIf { it > 0L }?.let { _durationMs.value = it }
        }
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = player.currentItem,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> _playing.value = false }
    }

    override fun play() { if (released) return; player.play(); _playing.value = true }
    override fun pause() { if (released) return; player.pause(); _playing.value = false }

    override fun seekTo(ms: Long) {
        if (released) return
        val t = CMTimeMakeWithSeconds(ms.coerceAtLeast(0L) / 1000.0, 600)
        player.seekToTime(t, toleranceBefore = CMTimeMake(0, 1), toleranceAfter = CMTimeMake(0, 1))
        _positionMs.value = ms.coerceAtLeast(0L)
    }

    override fun release() {
        if (released) return
        released = true
        player.pause()
        timeObserver?.let { player.removeTimeObserver(it) }
        timeObserver = null
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        player.replaceCurrentItemWithPlayerItem(null)
        _playing.value = false
    }

    private fun CValue<CMTime>.ms(): Long = useContents {
        if (timescale == 0 || (flags and 1u) == 0u) -1L else (value * 1000L) / timescale   // flags bit0 = kCMTimeFlags_Valid
    }
}
