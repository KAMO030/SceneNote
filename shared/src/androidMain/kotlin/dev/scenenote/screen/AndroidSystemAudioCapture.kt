package dev.scenenote.screen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.scenenote.core.Diag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread

/**
 * Android S1「系统字幕」的声音来源：MediaProjection + AudioPlaybackCapture（Android 10+）。
 *
 * 流程：[start] → 系统「开始录制 / 投屏」弹窗（经 [PickerBridge] 走 MainActivity 的 ActivityResultLauncher）
 * → 启动前台服务 `dev.scenenote.android.CaptureService`（类型 mediaProjection|microphone）
 * → 服务在 startForeground 之后 `getMediaProjection`（Android 14+ 硬规则：先前台再取 projection）并回调 [onProjectionReady]
 * → 这里建 AudioRecord（USAGE_MEDIA / GAME / UNKNOWN，排除自己的 UID）16 kHz 单声道 PCM16，20 ms 一帧经 [frames] 送出。
 *
 * 可抓性探测（规格 §6.2 / §8）：开始后 2 s 内样本全零且 `AudioManager.isMusicActive()` 为真 → 对方 App 禁止抓取
 * （Netflix / Spotify 一类 opt-out）→ state = BLOCKED 并停止；全零但没有任何媒体在放 → 继续等，直到有声音或有媒体在放。
 * 不用 `isClientSilenced()`（它只反映麦克风并发被静音，与 opt-out 无关）。
 *
 * MediaProjection.Callback.onStop（用户从状态栏 chip / 锁屏停止）→ 清理并置 ERROR（接口没有「被外部停止」状态，
 * 用 ERROR 让上层结束会话；具体文案由页面给「已停止，点击恢复」）。
 * 只做音频抓取，不创建 VirtualDisplay（S3 取词以后在同一 projection 上先 registerCallback 再建）。
 */
class AndroidSystemAudioCapture(context: Context) : SystemAudioCapture {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override val available: Boolean = Build.VERSION.SDK_INT >= 29
    private val _state = MutableStateFlow(CaptureState.IDLE)
    override val state: StateFlow<CaptureState> = _state.asStateFlow()
    private val _frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 256)
    override val frames: Flow<ShortArray> = _frames

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private var ready: CompletableDeferred<Boolean>? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Diag.log("syscap", "projection stopped by system / user")
            if (!running && projection == null) return
            AndroidPip.disableAutoEnter()   // 停止后离开 App 不再自动进空字幕条
            stopInternal()
            stopService()
            _state.value = CaptureState.ERROR
        }
    }

    init { active = this }

    /** 所有状态变更都在主线程（服务回调、MediaProjection 回调也在主线程），调用方随便哪个调度器。 */
    override suspend fun start(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!available) return@withContext false
        if (_state.value == CaptureState.CAPTURING) return@withContext true
        stopInternal(); stopService()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Diag.log("syscap", "RECORD_AUDIO not granted")
            _state.value = CaptureState.ERROR
            return@withContext false
        }
        _state.value = CaptureState.REQUESTING
        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // 只保留「整个屏幕」：单 App 投屏对被抓音频 UID 范围的影响待验证（规格 §4.1）
        val consent = if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        val r = PickerBridge.request(consent)
        if (r == null || r.resultCode != Activity.RESULT_OK || r.data == null) {
            Diag.log("syscap", "consent denied result=${r?.resultCode}")
            _state.value = CaptureState.DENIED
            return@withContext false
        }
        val d = CompletableDeferred<Boolean>()
        ready = d
        val svc = Intent().setClassName(ctx, SERVICE_CLASS)
            .putExtra(EXTRA_RESULT_CODE, r.resultCode)
            .putExtra(EXTRA_RESULT_DATA, r.data)
        val launched = runCatching { ContextCompat.startForegroundService(ctx, svc) }
            .onFailure { Diag.log("syscap", "startForegroundService failed: ${it.message}") }.isSuccess
        if (!launched) { ready = null; _state.value = CaptureState.ERROR; return@withContext false }
        val ok = withTimeoutOrNull(8_000) { d.await() } ?: false
        if (!ok) {
            Diag.log("syscap", "service did not become ready")
            stopInternal(); stopService()
            if (_state.value == CaptureState.REQUESTING) _state.value = CaptureState.ERROR
        }
        ok
    }

    override fun stop() = onMain {
        val wasActive = _state.value == CaptureState.CAPTURING || _state.value == CaptureState.REQUESTING
        stopInternal()
        stopService()
        ready?.complete(false); ready = null
        if (wasActive) _state.value = CaptureState.IDLE
        AndroidPip.disableAutoEnter()
    }

    override fun enterPip() { main.post { AndroidPip.enter() } }

    // ---- 前台服务回调（主线程） ----

    /** 服务 startForeground 之后把 projection 交过来；返回 false 表示 AudioRecord 建不起来，服务应自行 stopSelf。 */
    fun onProjectionReady(mp: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        if (_state.value != CaptureState.REQUESTING) { runCatching { mp.stop() }; return false }
        projection = mp
        runCatching { mp.registerCallback(projectionCallback, main) }
        val ok = runCatching { startRecord(mp) }.onFailure { Diag.log("syscap", "AudioRecord failed: ${it.message}") }.getOrDefault(false)
        if (ok) {
            _state.value = CaptureState.CAPTURING
            AndroidPip.armAutoEnter()   // 用户接下来切回视频 App，字幕条自动跟出去
            ready?.complete(true)
        } else {
            stopInternal()
            _state.value = CaptureState.ERROR
            ready?.complete(false)
        }
        ready = null
        return ok
    }

    /** 服务启动失败（如 Android 14+ 前台类型被拒）。 */
    fun onServiceFailed(reason: String) {
        Diag.log("syscap", "service failed: $reason")
        stopInternal()
        if (_state.value == CaptureState.REQUESTING) _state.value = CaptureState.ERROR
        ready?.complete(false); ready = null
    }

    /** 服务被系统杀掉 / 用户在通知栏点「停止」：不是本类发起的停止，置 ERROR 让上层结束会话（同 projection onStop）。 */
    fun onServiceDestroyed() {
        if (projection == null && !running) return
        stopInternal()
        if (_state.value == CaptureState.CAPTURING || _state.value == CaptureState.REQUESTING) _state.value = CaptureState.ERROR
        ready?.complete(false); ready = null
        AndroidPip.disableAutoEnter()
    }

    // ---- 内部 ----

    @RequiresApi(29)
    private fun startRecord(mp: MediaProjection): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, FRAME_SAMPLES * 2 * 16))
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); return false }
        record = rec
        running = true
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        worker = thread(name = "scenenote-syscap", priority = Thread.MAX_PRIORITY) { loop(rec, am) }
        Diag.log("syscap", "capture started buf=${maxOf(minBuf, FRAME_SAMPLES * 2 * 16)}")
        return true
    }

    private fun loop(rec: AudioRecord, am: AudioManager) {
        val frame = ShortArray(FRAME_SAMPLES)
        var probing = true
        var probeSamples = 0L
        var probePeak = 0
        var probeRounds = 0
        var frames = 0L
        var dropped = 0L
        var readError = false
        while (running) {
            var filled = 0
            while (filled < frame.size && running) {
                val n = rec.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
                if (n < 0) { Diag.log("syscap", "read error $n"); readError = true; running = false; break }
                if (n == 0) { SystemClock.sleep(2); continue }
                filled += n
            }
            if (filled < frame.size) break
            frames++
            if (!_frames.tryEmit(frame.copyOf())) dropped++
            if (probing) {
                for (v in frame) { val a = if (v < 0) -v.toInt() else v.toInt(); if (a > probePeak) probePeak = a }
                probeSamples += frame.size
                if (probeSamples >= PROBE_SAMPLES) {
                    probeRounds++
                    when {
                        probePeak > SILENCE_PEAK -> { probing = false; Diag.log("syscap", "probe ok peak=$probePeak rounds=$probeRounds") }
                        am.isMusicActive -> {
                            Diag.log("syscap", "probe BLOCKED: silent while music active (rounds=$probeRounds)")
                            running = false
                            main.post {
                                stopInternal(); stopService()
                                _state.value = CaptureState.BLOCKED
                            }
                            break
                        }
                        else -> { probeSamples = 0; probePeak = 0 }   // 对方还没开始放：继续探测
                    }
                }
            }
            if (frames % 500 == 0L) Diag.log("syscap", "frames=$frames dropped=$dropped")
        }
        Diag.log("syscap", "loop end frames=$frames dropped=$dropped")
        if (readError) main.post {
            // 录音流自己断了（被更高优先级 App 抢占等）：清理并置 ERROR 让上层结束会话
            if (_state.value == CaptureState.CAPTURING) { stopInternal(); stopService(); _state.value = CaptureState.ERROR }
        }
    }

    private fun stopInternal() {
        running = false
        val w = worker
        runCatching { record?.stop() }   // 先解开阻塞在 read 里的 worker，再 join（否则主线程空等 500 ms）
        if (w != null && w !== Thread.currentThread()) runCatching { w.join(500) }
        worker = null
        record?.let { r -> runCatching { r.release() } }
        record = null
        projection?.let { p ->
            runCatching { p.unregisterCallback(projectionCallback) }
            runCatching { p.stop() }
        }
        projection = null
    }

    private fun stopService() {
        runCatching { ctx.stopService(Intent().setClassName(ctx, SERVICE_CLASS)) }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    companion object {
        /** 当前实例（Koin 单例）；前台服务经此回调。 */
        @Volatile var active: AndroidSystemAudioCapture? = null; private set

        const val SERVICE_CLASS = "dev.scenenote.android.CaptureService"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320                      // 20 ms
        private const val PROBE_SAMPLES = SAMPLE_RATE * 2L  // 2 s
        private const val SILENCE_PEAK = 1                  // ≤ 1 视为全零（容忍 dither）
    }
}
