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
import android.os.SystemClock
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.pow

class AndroidAudioFactory(private val context: Context) : AudioFactory {
    private val routeManager by lazy { AndroidRouteManager(context.applicationContext) }
    override fun source(): AudioSource = AndroidAudioSource(context.applicationContext, routeManager)
    override fun routeManager(): RouteManager = routeManager
    override fun sink(): AudioSink = AndroidAudioSink(routeManager)
    override fun haptics(): Haptics = AndroidHaptics(context.applicationContext)
    override fun fileWriter(path: String, sampleRate: Int, bitrate: Int): PcmFileWriter = AndroidAacFileWriter(path, sampleRate, bitrate)
}

internal fun AudioDeviceInfo.toRoute(): AudioRoute = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioRoute.BluetoothA2dp
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRoute.BluetoothHfp
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> AudioRoute.Wired
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioRoute.BuiltIn
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.Speaker
    else -> if (Build.VERSION.SDK_INT >= 33 && (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) AudioRoute.BluetoothA2dp else AudioRoute.None
}
internal fun AudioDeviceInfo.isHeadsetOut(): Boolean = toRoute() == AudioRoute.BluetoothA2dp || toRoute() == AudioRoute.Wired

/**
 * AudioRecord 16 kHz 单声道 PCM16；VOICE_RECOGNITION 源不带 AGC/AEC 后处理；MEASUREMENT 用 UNPROCESSED（有支持时）。
 * 铁律：setPreferredDevice(内置麦)，绝不启动 SCO；真实输入设备经 routedDevice 上报给 RouteManager。
 */
class AndroidAudioSource(private val context: Context, private val routeManager: AndroidRouteManager) : AudioSource {
    private val _frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 128)
    override val frames: SharedFlow<ShortArray> = _frames
    override val route: StateFlow<AudioRoute> get() = routeManager.inputRoute

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    override suspend fun start(config: CaptureConfig) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("未授予麦克风权限")
        stop()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessedOk = config.mode == AudioMode.MEASUREMENT && am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        // 诊断开关（adb shell run-as dev.scenenote.app touch files/diag-mic / files/diag-nopref）：换 MIC 源 / 不指定首选设备
        val diagMic = java.io.File(context.filesDir, "diag-mic").exists()
        val diagNoPref = java.io.File(context.filesDir, "diag-nopref").exists()
        val source = if (unprocessedOk) MediaRecorder.AudioSource.UNPROCESSED else if (diagMic) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_RECOGNITION
        dev.scenenote.core.Diag.log("mic", "inputs=" + am.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString { "${it.id}:type${it.type}:${it.address}:${it.productName}" } + " diagMic=$diagMic diagNoPref=$diagNoPref")
        val minBuf = AudioRecord.getMinBufferSize(config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf, config.frameSamples * 2 * 8)
        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(config.sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(bufBytes)
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
        // 铁律：输入固定内置麦（有线 / USB / LE 耳机麦不接管输入）
        if (!diagNoPref) am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { rec.preferredDevice = it }
        record = rec
        running = true
        rec.startRecording()
        check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord 未进入录音状态（可能被其他 App 占用）" }
        routeManager.reportInputDevice(rec.routedDevice)
        dev.scenenote.core.Diag.log("mic", "start source=$source sr=${config.sampleRate} buf=$bufBytes routed=${rec.routedDevice?.let { "${it.id}:type${it.type}:${it.address}" }} preferred=${rec.preferredDevice?.let { "${it.id}:${it.address}" }}")
        worker = thread(name = "scenenote-capture", priority = Thread.MAX_PRIORITY) {
            val frame = ShortArray(config.frameSamples)
            var dropped = 0L; var reads = 0L
            var peak = 0; var sumSq = 0.0; var nSamples = 0L
            while (running) {
                var filled = 0
                while (filled < frame.size && running) {
                    val n = rec.read(frame, filled, frame.size - filled, AudioRecord.READ_BLOCKING)
                    if (n < 0) { running = false; break }
                    if (n == 0) { SystemClock.sleep(2); continue }
                    filled += n
                }
                if (filled == frame.size) {
                    if (!_frames.tryEmit(frame.copyOf())) dropped++
                    for (v in frame) { val a = if (v < 0) -v.toInt() else v.toInt(); if (a > peak) peak = a; sumSq += v.toDouble() * v; nSamples++ }
                    if (++reads % 50 == 0L) {
                        val rms = kotlin.math.sqrt(sumSq / nSamples.coerceAtLeast(1)); val db = 20 * kotlin.math.log10(rms / 32768.0 + 1e-9)
                        dev.scenenote.core.Diag.log("mic", "frames=$reads dropped=$dropped peak=$peak rms=${"%.0f".format(rms)} (${"%.1f".format(db)} dBFS) routed=${rec.routedDevice?.type}")
                        peak = 0; sumSq = 0.0; nSamples = 0
                    }
                    if (reads % 250 == 0L) routeManager.reportInputDevice(rec.routedDevice)  // 每 5 s 复核一次真实输入
                }
            }
            dev.scenenote.core.Diag.log("mic", "stop frames=$reads dropped=$dropped")
            routeManager.captureDropped = dropped
        }
    }

    override fun stop() {
        running = false
        worker?.join(300); worker = null
        record?.runCatching { stop(); release() }; record = null
    }
}

/**
 * 音频会话（Android）：MODE_NORMAL + 媒体焦点；输出由系统媒体路由决定（耳机就走耳机，否则外放，App 不关心）。
 * 输入 / 输出的"真实路由"来自 AudioRecord.routedDevice / AudioTrack.routedDevice（由 source / sink 上报），仅供展示。
 * 输出设备拔出（BECOMING_NOISY / 设备移除）：同步停掉正在播的音频（与系统媒体 App 行为一致），再发事件。
 */
class AndroidRouteManager(private val context: Context) : RouteManager {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _current = MutableStateFlow(snapshot())
    override val current: StateFlow<RouteState> = _current.asStateFlow()
    private val _events = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 64)
    override val routeEvents: Flow<RouteEvent> = _events
    internal val inputRoute = MutableStateFlow(AudioRoute.BuiltIn)
    private var registered = false
    private var focusRequest: AudioFocusRequest? = null
    private val lostHandlers = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var reportedInput: AudioRoute? = null
    @Volatile private var reportedOutput: AudioRoute? = null
    @Volatile private var lastLossAt = 0L
    @Volatile internal var captureDropped = 0L
    private var callbackPrimed = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!callbackPrimed) { callbackPrimed = true; return }   // 注册时会回放全部现有设备，不算"新设备"
            refresh()
            if (addedDevices.any { it.isHeadsetOut() }) _events.tryEmit(RouteEvent.NewDeviceAvailable)
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.isHeadsetOut() }) deviceLost()
            refresh()
        }
    }
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) { deviceLost(); refresh() } }
    }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { focusRequest = null; runLostHandlers(); _events.tryEmit(RouteEvent.Interruption(began = true)) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { runLostHandlers(); _events.tryEmit(RouteEvent.Interruption(began = true)) }
            AudioManager.AUDIOFOCUS_GAIN -> _events.tryEmit(RouteEvent.Interruption(began = false))
        }
    }

    /** 同步停掉正在播的音频；300 ms 内的重复触发（设备回调 + BECOMING_NOISY）只算一次事件。 */
    private fun deviceLost() {
        runLostHandlers()
        reportedOutput = null
        val now = SystemClock.elapsedRealtime()
        if (now - lastLossAt > 300) { lastLossAt = now; _events.tryEmit(RouteEvent.OldDeviceUnavailable) }
    }
    private fun runLostHandlers() { lostHandlers.forEach { h -> runCatching { h() } } }
    internal fun onDeviceLost(handler: () -> Unit): () -> Unit { lostHandlers += handler; return { lostHandlers -= handler } }

    internal fun reportInputDevice(d: AudioDeviceInfo?) { reportedInput = d?.toRoute(); refresh() }
    internal fun reportOutputDevice(d: AudioDeviceInfo?) { reportedOutput = d?.toRoute(); refresh() }

    override suspend fun ensure(): RouteState = withContext(Dispatchers.Main.immediate) {
        // 媒体模式：不进通话模式、不开 SCO（SCO 会把采样率压到 8/16 kHz 并接管麦克风）。
        if (am.mode != AudioManager.MODE_NORMAL) am.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION") if (am.isBluetoothScoOn) { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
        if (!registered) {
            am.registerAudioDeviceCallback(deviceCallback, null)
            ContextCompat.registerReceiver(context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        if (focusRequest == null) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener).build()
            if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = req
        }
        refresh()
        _current.value
    }

    private fun refresh() {
        val s = snapshot()
        _current.value = s
        inputRoute.value = s.input
    }

    private fun snapshot(): RouteState {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val a2dp = outs.any { it.toRoute() == AudioRoute.BluetoothA2dp }
        val wired = outs.any { it.toRoute() == AudioRoute.Wired }
        @Suppress("DEPRECATION") val sco = am.isBluetoothScoOn || (Build.VERSION.SDK_INT >= 31 && am.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && am.mode == AudioManager.MODE_IN_COMMUNICATION)
        // 输出：优先用 AudioTrack 上报的真实路由；否则按 AOSP 媒体策略估算（BLE/A2DP > 有线 > 扬声器）
        val output = reportedOutput ?: when { sco -> AudioRoute.BluetoothHfp; a2dp -> AudioRoute.BluetoothA2dp; wired -> AudioRoute.Wired; else -> AudioRoute.Speaker }
        val input = reportedInput ?: if (sco) AudioRoute.BluetoothHfp else AudioRoute.BuiltIn
        val note = when {
            output == AudioRoute.BluetoothHfp || input == AudioRoute.BluetoothHfp -> "通话模式（HFP），音质受限"
            else -> null
        }
        return RouteState(input = input, output = output, note = note)
    }

    override fun relax() { focusRequest?.let { am.abandonAudioFocusRequest(it) }; focusRequest = null }
}

/**
 * AudioTrack 16 kHz 立体声 PCM16 低延迟流；channelMask 位 1 = 左、2 = 右，未选声道填零。
 * play() 阻塞写入到轨道缓冲即返回（≈ 40 ms 背压）；stop() = pause + flush，同步；注册到 RouteManager.onDeviceLost。
 */
class AndroidAudioSink(private val routeManager: AndroidRouteManager, private val sampleRate: Int = 16_000) : AudioSink {
    private val track: AudioTrack by lazy {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 2 * 2 / 25 /* 40 ms */))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { t -> t.addOnRoutingChangedListener(android.media.AudioRouting.OnRoutingChangedListener { r -> routeManager.reportOutputDevice(r.routedDevice) }, null) }
    }
    private val unregister: () -> Unit = routeManager.onDeviceLost { stop() }
    @Volatile private var stopped = false

    override var volumeDb: Float = 0f
        set(v) { field = v; track.setVolume(10f.pow(v / 20f).coerceIn(0f, 1f)) }

    override suspend fun play(chunk: ShortArray, channelMask: Int) = withContext(Dispatchers.IO) {
        val stereo = ShortArray(chunk.size * 2)
        for (i in chunk.indices) {
            if (channelMask and 1 != 0) stereo[2 * i] = chunk[i]
            if (channelMask and 2 != 0) stereo[2 * i + 1] = chunk[i]
        }
        stopped = false
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) { track.play(); routeManager.reportOutputDevice(track.routedDevice) }
        var off = 0
        while (off < stereo.size && !stopped) {
            val n = track.write(stereo, off, minOf(stereo.size - off, 640), AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            off += n
        }
    }

    /** 同步：pause 立即停止渲染，flush 丢弃缓冲；不切扬声器（AudioTrack 不接触路由）。 */
    override fun stop() { stopped = true; runCatching { track.pause(); track.flush() } }
    override fun flush() { runCatching { track.flush() } }
    override fun prime() {
        // 200 ms −60 dB 噪声唤醒蓝牙耳机；后台线程阻塞写入，不占调用方线程。
        thread(name = "scenenote-prime") {
            runCatching {
                val noise = Pcm.nearSilence(sampleRate, 200)
                val buf = ShortArray(noise.size * 2) { i -> noise[i / 2] }
                stopped = false
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                var off = 0
                while (off < buf.size && !stopped) { val n = track.write(buf, off, minOf(buf.size - off, 640), AudioTrack.WRITE_BLOCKING); if (n <= 0) break; off += n }
            }
        }
    }
    override fun release() { unregister(); runCatching { track.stop(); track.release() } }
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
