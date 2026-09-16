package dev.scenenote.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.math.pow
import kotlin.random.Random

class AndroidAudioFactory(private val context: Context) : AudioFactory {
    private val routeManager by lazy { AndroidRouteManager(context.applicationContext) }
    override fun source(): AudioSource = AndroidAudioSource(context.applicationContext, routeManager)
    override fun routeManager(): RouteManager = routeManager
    override fun sink(): AudioSink = AndroidAudioSink()
    override fun haptics(): Haptics = AndroidHaptics(context.applicationContext)
}

/** AudioRecord 16 kHz 单声道 PCM16；VOICE_RECOGNITION 源不带 AGC/AEC 后处理，适合 ASR；MEASUREMENT 用 UNPROCESSED。绝不启动 SCO。 */
class AndroidAudioSource(private val context: Context, private val routeManager: AndroidRouteManager) : AudioSource {
    private val _frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 128)
    override val frames: SharedFlow<ShortArray> = _frames
    override val route: StateFlow<AudioRoute> get() = routeManager.inputRoute

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    override suspend fun start(config: CaptureConfig) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("RECORD_AUDIO not granted")
        stop()
        val source = if (config.mode == AudioMode.MEASUREMENT) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf, config.frameSamples * 2 * 8)
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(config.sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(bufBytes)
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        record = rec
        running = true
        rec.startRecording()
        worker = thread(name = "scenenote-capture", priority = Thread.MAX_PRIORITY) {
            val frame = ShortArray(config.frameSamples)
            while (running) {
                var filled = 0
                while (filled < frame.size && running) {
                    val n = rec.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    filled += n
                }
                if (filled == frame.size) _frames.tryEmit(frame.copyOf())
            }
        }
    }

    override fun stop() {
        running = false
        worker?.join(200); worker = null
        record?.runCatching { stop(); release() }; record = null
    }
}

/**
 * 路由铁律（Android）：MODE_NORMAL、SCO 永远关闭、扬声器永远不主动打开；输出由系统媒体路由决定（A2DP 耳机连接即走 A2DP）。
 * 输入永远是内置麦（AudioRecord 在 SCO 关闭时不会用耳机麦）。
 */
class AndroidRouteManager(private val context: Context) : RouteManager {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _current = MutableStateFlow(snapshot(RoutePolicyAudio.ANY))
    override val current: StateFlow<RouteState> = _current.asStateFlow()
    private val _events = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 16)
    override val routeEvents: Flow<RouteEvent> = _events
    internal val inputRoute = MutableStateFlow(AudioRoute.BuiltIn)
    private var policy = RoutePolicyAudio.ANY
    private var registered = false
    private var focusRequest: AudioFocusRequest? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refresh(); if (addedDevices.any { it.isA2dp() }) _events.tryEmit(RouteEvent.NewDeviceAvailable) }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { refresh(); if (removedDevices.any { it.isA2dp() || it.isWired() }) _events.tryEmit(RouteEvent.OldDeviceUnavailable) }
    }
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) { refresh(); _events.tryEmit(RouteEvent.OldDeviceUnavailable) } }
    }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> _events.tryEmit(RouteEvent.Interruption(began = true))
            AudioManager.AUDIOFOCUS_GAIN -> _events.tryEmit(RouteEvent.Interruption(began = false))
        }
    }

    override suspend fun ensure(policy: RoutePolicyAudio): RouteState = withContext(Dispatchers.Main.immediate) {
        this@AndroidRouteManager.policy = policy
        // 铁律：不进通话模式、不开 SCO、不开扬声器。
        if (am.mode != AudioManager.MODE_NORMAL) am.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION") if (am.isBluetoothScoOn) { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
        @Suppress("DEPRECATION") if (am.isSpeakerphoneOn) am.isSpeakerphoneOn = false
        if (!registered) {
            am.registerAudioDeviceCallback(deviceCallback, null)
            ContextCompat.registerReceiver(context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        if (focusRequest == null) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener).build()
            am.requestAudioFocus(req); focusRequest = req
        }
        refresh()
        _current.value
    }

    private fun refresh() {
        val s = snapshot(policy)
        _current.value = s
        inputRoute.value = s.input
    }

    private fun snapshot(policy: RoutePolicyAudio): RouteState {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val a2dp = outs.any { it.isA2dp() }
        val wired = outs.any { it.isWired() }
        @Suppress("DEPRECATION") val sco = am.isBluetoothScoOn
        val output = when { sco -> AudioRoute.BluetoothHfp; a2dp -> AudioRoute.BluetoothA2dp; wired -> AudioRoute.Wired; else -> AudioRoute.Speaker }
        val input = if (sco) AudioRoute.BluetoothHfp else AudioRoute.BuiltIn
        val note = when {
            policy == RoutePolicyAudio.HEADSET_A2DP_ONLY && !a2dp && wired -> "有线耳机：允许，但延迟口径不同"
            policy == RoutePolicyAudio.HEADSET_A2DP_ONLY && !a2dp && !wired -> "未连接 A2DP 耳机"
            else -> null
        }
        return RouteState(input = input, output = output, policy = policy, headsetConnected = a2dp || wired, note = note)
    }

    override fun release() {
        if (registered) { am.unregisterAudioDeviceCallback(deviceCallback); runCatching { context.unregisterReceiver(noisyReceiver) }; registered = false }
        focusRequest?.let { am.abandonAudioFocusRequest(it) }; focusRequest = null
    }

    private fun AudioDeviceInfo.isA2dp() = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || (Build.VERSION.SDK_INT >= 33 && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_BROADCAST))
    private fun AudioDeviceInfo.isWired() = type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_USB_HEADSET
}

/** AudioTrack 16 kHz 立体声 PCM16 低延迟流；channelMask 位 1 = 左、2 = 右，未选声道填零。 */
class AndroidAudioSink(private val sampleRate: Int = 16_000) : AudioSink {
    private val track: AudioTrack by lazy {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 2 * 2 / 25 /* 40 ms */))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
    override var volumeDb: Float = 0f
        set(v) { field = v; track.setVolume(10f.pow(v / 20f).coerceIn(0f, 1f)) }

    override suspend fun play(chunk: ShortArray, channelMask: Int) = withContext(Dispatchers.IO) {
        val stereo = ShortArray(chunk.size * 2)
        for (i in chunk.indices) {
            if (channelMask and 1 != 0) stereo[2 * i] = chunk[i]
            if (channelMask and 2 != 0) stereo[2 * i + 1] = chunk[i]
        }
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        var off = 0
        while (off < stereo.size) {
            val n = track.write(stereo, off, stereo.size - off, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            off += n
        }
    }

    override fun stop() { runCatching { track.pause(); track.flush() } }
    override fun flush() { runCatching { track.flush() } }
    override fun prime() {
        // 200 ms −60 dB 噪声，唤醒蓝牙耳机的省电睡眠。
        val n = sampleRate / 5
        val buf = ShortArray(n * 2) { (Random.nextInt(-33, 33)).toShort() }
        runCatching { if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play(); track.write(buf, 0, buf.size, AudioTrack.WRITE_NON_BLOCKING) }
    }
}

class AndroidHaptics(context: Context) : Haptics {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    override fun play(pattern: HapticPattern) {
        val timings = when (pattern) {
            HapticPattern.START -> longArrayOf(0, 40, 60, 40)
            HapticPattern.READY -> longArrayOf(0, 40)
            HapticPattern.FLIP -> longArrayOf(0, 160)
            HapticPattern.LOST -> longArrayOf(0, 160, 80, 160, 80, 160)
            HapticPattern.DIDNT_GET -> longArrayOf(0, 30, 40, 30)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(timings, -1))
    }
}
