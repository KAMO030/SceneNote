package dev.scenenote.core.platform

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 会话期耳机媒体键（规格 §3.1）：只在 `Live` 期间接管，待机期不接管。
 * 只用平台 `android.media.session.MediaSession`，不引 androidx.media / media3。
 *
 * 路由规则（Android 8+，官方「Handling media buttons」）：系统把媒体键发给「最近一次本地播放过音频的 App」的活跃 session，
 * **与音频焦点无关**（焦点由 `AndroidRouteManager.ensure()` 另行持有，这里不再重复申请）。
 * 会话期 App 持续用 AudioTrack 播 TTS，天然就是最近播放者；但从 activate 到第一句 TTS 之间有空窗，
 * 此时媒体键仍落在上一个音乐 App（单击 = 恢复音乐，反过来打断我们）。所以 activate 时播 300 ms 静音 AudioTrack「占位」——
 * 与 iOS 端 −60 dB 近静音轨同一思路，Android 只需播一次，不必常驻。
 * 不设 MediaButtonReceiver：deactivate → release 后系统不再把媒体键交给本 App（待机期不接管，用户听音乐时入口退回磁贴）。
 *
 * 多击识别：AOSP 系统层只区分单击 / 长按（长按 = 语音助手，App 拿不到），不把有线耳机的双击 / 三击折成 NEXT / PREVIOUS；
 * 蓝牙耳机则由固件自己发 AVRCP NEXT / PREVIOUS。所以 HEADSETHOOK / PLAY_PAUSE / PLAY / PAUSE 一律按「一次点击」进入 400 ms 计数窗：
 * 1 次 = PLAY_PAUSE（暂停 / 继续），2 次 = NEXT（跳过当前译文），≥ 3 次 = PREVIOUS（重播上一句）；NEXT / PREVIOUS 键直接透传。
 * 代价是单击延迟 400 ms 才生效，与 iOS 系统级多击识别一致。
 */
class AndroidMediaKeys(context: Context) : MediaKeys {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _events = MutableSharedFlow<MediaKey>(extraBufferCapacity = 8)
    override val events: SharedFlow<MediaKey> = _events

    // 以下状态只在主线程读写
    private var session: MediaSession? = null
    private var clicks = 0
    private var claimTrack: AudioTrack? = null
    private val releaseClaimLater = Runnable { releaseClaim() }

    private val flushClicks = Runnable {
        val n = clicks
        clicks = 0
        emit(when { n >= 3 -> MediaKey.PREVIOUS; n == 2 -> MediaKey.NEXT; else -> MediaKey.PLAY_PAUSE })
    }

    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val ke = mediaButtonIntent.keyEvent() ?: return super.onMediaButtonEvent(mediaButtonIntent)
            val kind = when (ke.keyCode) {
                KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaKey.PLAY_PAUSE
                KeyEvent.KEYCODE_MEDIA_NEXT -> MediaKey.NEXT
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaKey.PREVIOUS
                else -> return super.onMediaButtonEvent(mediaButtonIntent)
            }
            // 只认按下且非长按重复；UP / 重复事件吞掉，避免默认实现再按播放状态触发一次 onPlay / onPause
            if (ke.action == KeyEvent.ACTION_DOWN && ke.repeatCount == 0) {
                if (kind == MediaKey.PLAY_PAUSE) countClick() else emit(kind)
            }
            return true
        }

        // 不经 KeyEvent 的路径（系统媒体控制 / 车机 / 手表 MediaController.transportControls）：语义同一次点击
        override fun onPlay() = countClick()
        override fun onPause() = countClick()
        override fun onSkipToNext() = emit(MediaKey.NEXT)
        override fun onSkipToPrevious() = emit(MediaKey.PREVIOUS)
    }

    override fun activate(title: String) = onMain {
        val s = session ?: MediaSession(ctx, TAG).also {
            it.setCallback(callback, main)
            it.setPlaybackState(playingState())
            session = it
        }
        s.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build())
        s.isActive = true
        claimMediaButtonSession()
    }

    override fun deactivate() = onMain {
        main.removeCallbacks(flushClicks)
        clicks = 0
        releaseClaim()
        session?.let { runCatching { it.isActive = false; it.release() } }
        session = null
    }

    private fun countClick() {
        clicks++
        main.removeCallbacks(flushClicks)
        main.postDelayed(flushClicks, CLICK_WINDOW_MS)
    }

    private fun emit(k: MediaKey) { _events.tryEmit(k) }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** 始终报 PLAYING：按键在 onMediaButtonEvent 里按原始 KeyEvent 解析，不依赖系统按播放状态做 play / pause 分发。 */
    private fun playingState(): PlaybackState = PlaybackState.Builder()
        .setActions(
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
        )
        .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f, SystemClock.elapsedRealtime())
        .build()

    /**
     * 播 300 ms 静音（MODE_STATIC，16 kHz mono，与 AudioSink 同属 USAGE_MEDIA），让系统把本 uid 记为「最近播放者」，
     * 媒体键立刻切到我们的 session；600 ms 后释放。失败只影响首句 TTS 前的空窗，不抛。
     */
    private fun claimMediaButtonSession() {
        releaseClaim()
        val bytes = CLAIM_SAMPLE_RATE * 2 * 3 / 10   // 300 ms × PCM16 mono
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(CLAIM_SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build()
        }.getOrNull() ?: return
        val ok = runCatching {
            track.write(ByteArray(bytes), 0, bytes) == bytes && track.state == AudioTrack.STATE_INITIALIZED
        }.getOrDefault(false)
        if (!ok) { track.release(); return }
        runCatching { track.play() }.onFailure { track.release(); return }
        claimTrack = track
        main.postDelayed(releaseClaimLater, CLAIM_RELEASE_MS)
    }

    private fun releaseClaim() {
        main.removeCallbacks(releaseClaimLater)
        claimTrack?.let { runCatching { it.stop() }; runCatching { it.release() } }
        claimTrack = null
    }

    private fun Intent.keyEvent(): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 34) getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)   // 33 上类型化 getter 有 b/232589966，与 androidx.core 一致只在 34+ 用
        else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_KEY_EVENT)

    private companion object {
        const val TAG = "SceneNoteLive"
        /** 多击计数窗：有线耳机 HEADSETHOOK 经系统层「按下 → 抬起」转发后到达，蓝牙 AVRCP 抖动更大，取 400 ms。 */
        const val CLICK_WINDOW_MS = 400L
        const val CLAIM_SAMPLE_RATE = 16_000
        const val CLAIM_RELEASE_MS = 600L
    }
}
