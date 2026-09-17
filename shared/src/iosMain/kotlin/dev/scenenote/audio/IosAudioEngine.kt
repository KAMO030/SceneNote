package dev.scenenote.audio

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterInputStatus_NoDataNow
import platform.AVFAudio.AVAudioConverterOutputStatus_Error
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataConsumed
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.inputAvailable
import platform.AudioToolbox.ExtAudioFileCreateWithURL
import platform.AudioToolbox.ExtAudioFileDispose
import platform.AudioToolbox.ExtAudioFileRefVar
import platform.AudioToolbox.ExtAudioFileSetProperty
import platform.AudioToolbox.ExtAudioFileWrite
import platform.AudioToolbox.kAudioFileFlags_EraseFile
import platform.AudioToolbox.kAudioFileM4AType
import platform.AudioToolbox.kExtAudioFileProperty_ClientDataFormat
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatFlagIsSignedInteger
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreFoundation.CFURLRef
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.math.pow

/**
 * 单个 AVAudioEngine 同时承载采集（inputNode tap）与播放（AVAudioPlayerNode → mainMixer）。
 * 会话类别由 [IosRouteManager] 设定；本类只管节点图与启停。
 * - 配置变化通知（耳机插拔常触发）：同步 player.stop()（清空残留调度，避免从新路由外放），标记 needsRestart，并通知采集端重装 tap。
 * - 输出设备拔出：向 RouteManager 注册 onDeviceLost → player.stop()（与系统媒体 App 行为一致）。
 */
class IosAudioEngine(private val routeManager: IosRouteManager) {
    val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()
    /** 播放格式：16 kHz 立体声 Float32 非交织；引擎负责重采样到硬件输出率。 */
    val playbackFormat: AVAudioFormat = AVAudioFormat(commonFormat = AVAudioPCMFormatFloat32, sampleRate = 16_000.0, channels = 2u, interleaved = false)
    private var playerAttached = false
    @Volatile private var needsRestart = false
    private val configListeners = AtomicReference<List<() -> Unit>>(emptyList())
    private val observer: Any
    private val unregisterLost: () -> Unit

    init {
        observer = NSNotificationCenter.defaultCenter.addObserverForName(AVAudioEngineConfigurationChangeNotification, `object` = engine, queue = NSOperationQueue.mainQueue) { _: NSNotification? ->
            player.stop()
            needsRestart = true
            configListeners.value.forEach { l -> runCatching { l() } }
        }
        unregisterLost = routeManager.onDeviceLost { player.stop() }
    }

    fun onConfigurationChange(listener: () -> Unit): () -> Unit {
        configListeners.value = configListeners.value + listener
        return { configListeners.value = configListeners.value - listener }
    }

    /** 主线程调用。引擎未运行或需重启时重建连接并启动；失败抛异常（调用方决定是否上报）。 */
    fun ensureStarted() {
        if (!playerAttached) {
            engine.attachNode(player)
            engine.connect(player, to = engine.mainMixerNode, format = playbackFormat)
            playerAttached = true
        }
        if (needsRestart && engine.running) engine.stop()
        if (needsRestart || !engine.running) memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            engine.prepare()
            if (!engine.startAndReturnError(err.ptr)) error("AVAudioEngine 启动失败：${err.value?.localizedDescription}")
            needsRestart = false
        }
    }

    val playerNode: AVAudioPlayerNode get() = player

    fun stopIfIdle(capturing: Boolean) { if (!capturing && !player.playing && engine.running) engine.stop() }
}

/**
 * inputNode tap → AVAudioConverter（硬件率 Float32 → 16 kHz Int16 单声道）→ 320 采样一帧。
 * 配置变化后自动按新的硬件格式重装 tap / 重建 converter；转换错误经 [errors] 上报。
 */
class IosAudioSource(private val shared: IosAudioEngine, private val routeManager: IosRouteManager) : AudioSource {
    private val _frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 128)
    override val frames: SharedFlow<ShortArray> = _frames
    override val route: StateFlow<AudioRoute> get() = routeManager.inputRoute
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: Flow<String> = _errors
    @Volatile private var tapInstalled = false
    private val carry = AtomicReference(ShortArray(0))
    private var config: CaptureConfig = CaptureConfig()
    private var unregisterConfig: (() -> Unit)? = null

    override suspend fun start(config: CaptureConfig) {
        if (!requestMicPermission()) throw IllegalStateException("未授予麦克风权限")
        this.config = config
        routeManager.ensure()
        withContext(Dispatchers.Main) {
            // 模拟器无头启动 / 无输入设备时，inputNode 初始化会在 AudioToolbox 内 RPC 超时并 abort（不可捕获），先行拦截。
            if (!AVAudioSession.sharedInstance().inputAvailable) throw IllegalStateException("当前没有可用的音频输入设备（模拟器需打开 Simulator.app 并授予 macOS 麦克风权限）")
            installTap()
            shared.ensureStarted()
            unregisterConfig?.invoke()
            unregisterConfig = shared.onConfigurationChange {
                if (tapInstalled) runCatching { installTap(); shared.ensureStarted() }.onFailure { _errors.tryEmit("配置变化后重装采集失败：${it.message}") }
            }
        }
    }

    /** 主线程：按当前硬件格式（重新）安装 tap。引擎若已以"纯输出"启动，输入节点格式为 0 Hz，必须先停下再装。 */
    private fun installTap() {
        if (shared.engine.running) shared.engine.stop()
        val input = shared.engine.inputNode
        if (tapInstalled) { input.removeTapOnBus(0u); tapInstalled = false }
        val hw = input.outputFormatForBus(0u)
        if (hw.sampleRate <= 0.0 || hw.channelCount == 0u) throw IllegalStateException("输入格式无效（${hw.sampleRate} Hz / ${hw.channelCount} ch）")
        val target = AVAudioFormat(commonFormat = AVAudioPCMFormatInt16, sampleRate = config.sampleRate.toDouble(), channels = 1u, interleaved = true)
        val converter = AVAudioConverter(fromFormat = hw, toFormat = target)
        val ratio = config.sampleRate.toDouble() / hw.sampleRate
        val frameSamples = config.frameSamples
        carry.value = ShortArray(0)
        input.installTapOnBus(0u, bufferSize = 2048u, format = hw) { buffer, _ ->
            val inBuf = buffer ?: return@installTapOnBus
            val outCap = (inBuf.frameLength.toDouble() * ratio).toInt() + 64
            val outBuf = AVAudioPCMBuffer(pCMFormat = target, frameCapacity = outCap.toUInt())
            var consumed = false
            val status = memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                val st = converter.convertToBuffer(outBuf, error = err.ptr) { _, statusPtr ->
                    if (!consumed) { consumed = true; statusPtr?.pointed?.value = AVAudioConverterInputStatus_HaveData; inBuf }
                    else { statusPtr?.pointed?.value = AVAudioConverterInputStatus_NoDataNow; null }
                }
                if (st == AVAudioConverterOutputStatus_Error) _errors.tryEmit("采样率转换失败：${err.value?.localizedDescription}")
                st
            }
            if (status == AVAudioConverterOutputStatus_Error) return@installTapOnBus
            val n = outBuf.frameLength.toInt()
            if (n <= 0) return@installTapOnBus
            val data = outBuf.int16ChannelData?.get(0) ?: return@installTapOnBus
            val prev = carry.value
            val merged = ShortArray(prev.size + n)
            prev.copyInto(merged)
            for (i in 0 until n) merged[prev.size + i] = data[i]
            var off = 0
            while (merged.size - off >= frameSamples) {
                _frames.tryEmit(merged.copyOfRange(off, off + frameSamples)); off += frameSamples
            }
            carry.value = merged.copyOfRange(off, merged.size)
        }
        tapInstalled = true
    }

    override fun stop() {
        unregisterConfig?.invoke(); unregisterConfig = null
        if (tapInstalled) { shared.engine.inputNode.removeTapOnBus(0u); tapInstalled = false }
        carry.value = ShortArray(0)
        shared.stopIfIdle(capturing = false)
    }

    private suspend fun requestMicPermission(): Boolean = suspendCancellableCoroutine { cont ->
        AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted -> if (cont.isActive) cont.resume(granted) }
    }
}

/**
 * AVAudioPlayerNode：Short → Float32，channelMask 位 1 = 左、2 = 右。
 * play()：调度后即返回；在飞缓冲 ≥ 6 块（≈ 120 ms）时挂起等待背压释放（可取消，取消即 stop）。
 * stop()：同步清空已调度缓冲（并释放所有等待者）；由 RouteManager.onDeviceLost 在路由回调线程触发。
 */
class IosAudioSink(private val shared: IosAudioEngine, routeManager: IosRouteManager) : AudioSink {
    private val inFlight = AtomicInt(0)
    private val waiter = AtomicReference<(() -> Unit)?>(null)
    private val unregisterLost: () -> Unit = routeManager.onDeviceLost { stop() }

    override var volumeDb: Float = 0f
        set(v) { field = v; shared.playerNode.volume = 10f.pow(v / 20f).coerceIn(0f, 1f) }

    override suspend fun play(chunk: ShortArray, channelMask: Int) {
        withContext(Dispatchers.Main) { shared.ensureStarted() }
        if (inFlight.value >= MAX_IN_FLIGHT) awaitDrain()
        val buf = toBuffer(chunk, channelMask)
        inFlight.incrementAndGet()
        shared.playerNode.scheduleBuffer(buf, completionCallbackType = AVAudioPlayerNodeCompletionDataConsumed) { _ ->
            inFlight.decrementAndGet()
            waiter.value?.let { w -> waiter.value = null; w() }
        }
        if (!shared.playerNode.playing) shared.playerNode.play()
    }

    private suspend fun awaitDrain() = suspendCancellableCoroutine { cont ->
        waiter.value = { if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { waiter.value = null; stop() }
        if (inFlight.value < MAX_IN_FLIGHT) { waiter.value = null; if (cont.isActive) cont.resume(Unit) }
    }

    override fun stop() {
        shared.playerNode.stop()          // 同步清空调度；完成回调会把 inFlight 归零并唤醒等待者
        inFlight.value = 0
        waiter.value?.let { w -> waiter.value = null; w() }
    }
    override fun flush() = stop()
    override fun prime() {
        runCatching {
            shared.ensureStarted()
            shared.playerNode.scheduleBuffer(toBuffer(Pcm.nearSilence(16_000, 200), 3), completionHandler = null)
            if (!shared.playerNode.playing) shared.playerNode.play()
        }
    }
    override fun release() { unregisterLost() }

    private fun toBuffer(chunk: ShortArray, channelMask: Int): AVAudioPCMBuffer {
        val buf = AVAudioPCMBuffer(pCMFormat = shared.playbackFormat, frameCapacity = chunk.size.toUInt())
        buf.frameLength = chunk.size.toUInt()
        val ch = buf.floatChannelData!!
        val l = ch[0]!!; val r = ch[1]!!
        for (i in chunk.indices) {
            val v = chunk[i] / 32768f
            l[i] = if (channelMask and 1 != 0) v else 0f
            r[i] = if (channelMask and 2 != 0) v else 0f
        }
        return buf
    }

    private companion object { const val MAX_IN_FLIGHT = 6 }
}

/**
 * AudioToolbox ExtAudioFile → AAC-LC .m4a（iOS 8+，无 iOS 18 依赖）；客户端格式 Int16 交织单声道 16 kHz。
 * close() = ExtAudioFileDispose，同步写出 moov，返回时文件即完整。
 */
class IosAacFileWriter(private val path: String, private val sampleRate: Int = 16_000, bitrate: Int = 32_000) : PcmFileWriter {
    private var ref: platform.AudioToolbox.ExtAudioFileRef? = null
    private var closed = false

    init {
        memScoped {
            val url = CFBridgingRetain(NSURL.fileURLWithPath(path)) as CFURLRef?
            val fileDesc = alloc<AudioStreamBasicDescription>().apply {
                mSampleRate = sampleRate.toDouble(); mFormatID = kAudioFormatMPEG4AAC; mChannelsPerFrame = 1u; mFramesPerPacket = 1024u
                mFormatFlags = 0u; mBytesPerPacket = 0u; mBytesPerFrame = 0u; mBitsPerChannel = 0u
            }
            val out = alloc<ExtAudioFileRefVar>()
            val st = ExtAudioFileCreateWithURL(url, kAudioFileM4AType, fileDesc.ptr, null, kAudioFileFlags_EraseFile, out.ptr)
            CFBridgingRelease(url)
            if (st != 0 || out.value == null) error("ExtAudioFileCreateWithURL 失败（OSStatus $st）")
            val client = alloc<AudioStreamBasicDescription>().apply {
                mSampleRate = sampleRate.toDouble(); mFormatID = kAudioFormatLinearPCM
                mFormatFlags = kAudioFormatFlagIsSignedInteger or kAudioFormatFlagIsPacked
                mChannelsPerFrame = 1u; mBitsPerChannel = 16u; mBytesPerFrame = 2u; mFramesPerPacket = 1u; mBytesPerPacket = 2u
            }
            val st2 = ExtAudioFileSetProperty(out.value, kExtAudioFileProperty_ClientDataFormat, sizeOf<AudioStreamBasicDescription>().toUInt(), client.ptr)
            if (st2 != 0) { ExtAudioFileDispose(out.value); error("ExtAudioFile 设置客户端格式失败（OSStatus $st2）") }
            ref = out.value
        }
    }

    override fun write(frame: ShortArray) {
        val r = ref ?: return
        if (closed || frame.isEmpty()) return
        frame.usePinned { pinned ->
            memScoped {
                val abl = alloc<AudioBufferList>()
                abl.mNumberBuffers = 1u
                abl.mBuffers[0].mNumberChannels = 1u
                abl.mBuffers[0].mDataByteSize = (frame.size * 2).toUInt()
                abl.mBuffers[0].mData = pinned.addressOf(0)
                ExtAudioFileWrite(r, frame.size.toUInt(), abl.ptr)
            }
        }
    }

    override fun close(): String {
        if (!closed) { closed = true; ref?.let { ExtAudioFileDispose(it) }; ref = null }
        return path
    }
}
