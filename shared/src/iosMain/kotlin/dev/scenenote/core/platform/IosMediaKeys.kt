package dev.scenenote.core.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.Foundation.NSThread
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPNowPlayingPlaybackStatePlaying
import platform.MediaPlayer.MPNowPlayingPlaybackStateStopped
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIApplication
import platform.UIKit.beginReceivingRemoteControlEvents
import platform.UIKit.endReceivingRemoteControlEvents
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * 会话期耳机媒体键（iOS，规格 §3.1「耳机按键」）：只在 Live 期间接管 Now Playing。
 * - 单击（togglePlayPause，部分蓝牙耳机按自己记的状态分别发 play / pause）→ PLAY_PAUSE
 * - 双击（nextTrack）→ NEXT = 跳过当前译文；三击（previousTrack）→ PREVIOUS = 重播上一句
 * - activate(title)：注册五个命令的 handler，写 nowPlayingInfo（标题 + 播放速率 1.0），标记播放中，
 *   并 beginReceivingRemoteControlEvents（iOS 7.1+ 用 MPRemoteCommandCenter 时官方说可不调，留着无害）。
 * - deactivate：移除 target、清 nowPlayingInfo、endReceivingRemoteControlEvents。
 *
 * 平台前提（Apple 文档原话：An app doesn't receive remote control events until it begins playing content /
 * Your app must be the Now Playing app）：
 * 1. 音频会话必须是不可混音的 category（本项目 Live 期为 playAndRecord 且不带 mixWithOthers，满足；
 *    待机期加了 mixWithOthers 的会话拿不到媒体键，与规格"待机期不接管媒体键"一致）；
 * 2. App 必须实际开始出声——只录音不放音时系统不会把它当作 Now Playing App，所以规格要求 Live 期播 −60 dB 近静音轨
 *    （由 AudioSink / 播放队列那边保证，本类不管出声）；
 * 3. 蓝牙耳机走 A2DP（本项目只加 allowBluetoothA2DP、不加 allowBluetooth/HFP），AVRCP 按键才走 MPRemoteCommandCenter；
 *    HFP 通话路由下按键行为由耳机决定，社区反馈不稳定。
 * 4. 用户正在听音乐时，我们 activate 会抢占 Now Playing（因为会话不可混音会打断音乐）——规格接受，入口退回 Action Button。
 * 以上 1–3 未在真机验证（模拟器没有耳机按键）。
 */
class IosMediaKeys : MediaKeys {
    private val _events = MutableSharedFlow<MediaKey>(extraBufferCapacity = 8)
    override val events: SharedFlow<MediaKey> = _events.asSharedFlow()

    /** command → addTargetWithHandler 返回的不透明 target，deactivate 时逐个移除。 */
    private var targets: List<Pair<MPRemoteCommand, Any>> = emptyList()
    private var active = false

    override fun activate(title: String) = onMain {
        val info = MPNowPlayingInfoCenter.defaultCenter()
        if (!active) {
            active = true
            val center = MPRemoteCommandCenter.sharedCommandCenter()
            targets = listOf(
                register(center.togglePlayPauseCommand, MediaKey.PLAY_PAUSE),
                register(center.playCommand, MediaKey.PLAY_PAUSE),
                register(center.pauseCommand, MediaKey.PLAY_PAUSE),
                register(center.nextTrackCommand, MediaKey.NEXT),
                register(center.previousTrackCommand, MediaKey.PREVIOUS),
            )
            UIApplication.sharedApplication.beginReceivingRemoteControlEvents()
        }
        // 已激活时只刷新标题（场景切换）
        info.nowPlayingInfo = mapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to title,
            MPNowPlayingInfoPropertyPlaybackRate to 1.0,
        )
        info.playbackState = MPNowPlayingPlaybackStatePlaying
    }

    override fun deactivate() = onMain {
        if (!active) return@onMain
        active = false
        targets.forEach { (command, target) -> command.removeTarget(target); command.enabled = false }
        targets = emptyList()
        val info = MPNowPlayingInfoCenter.defaultCenter()
        info.playbackState = MPNowPlayingPlaybackStateStopped
        info.nowPlayingInfo = null
        UIApplication.sharedApplication.endReceivingRemoteControlEvents()
    }

    private fun register(command: MPRemoteCommand, key: MediaKey): Pair<MPRemoteCommand, Any> {
        command.enabled = true
        val target = command.addTargetWithHandler { _: MPRemoteCommandEvent? ->
            _events.tryEmit(key)
            MPRemoteCommandHandlerStatusSuccess
        }
        return command to target
    }

    /** MPRemoteCommandCenter / MPNowPlayingInfoCenter 都要求主线程。 */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
    }
}
