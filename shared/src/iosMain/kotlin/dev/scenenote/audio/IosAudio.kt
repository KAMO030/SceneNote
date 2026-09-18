package dev.scenenote.audio

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothA2DP
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionPortBluetoothA2DP
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBluetoothLE
import platform.AVFAudio.AVAudioSessionPortBuiltInMic
import platform.AVFAudio.AVAudioSessionPortBuiltInReceiver
import platform.AVFAudio.AVAudioSessionPortBuiltInSpeaker
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortHeadphones
import platform.AVFAudio.AVAudioSessionPortHeadsetMic
import platform.AVFAudio.AVAudioSessionPortUSBAudio
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonCategoryChange
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonNewDeviceAvailable
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.availableInputs
import platform.AVFAudio.currentRoute
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import kotlin.concurrent.AtomicReference
import kotlin.concurrent.Volatile

class IosAudioFactory : AudioFactory {
    private val routeManager by lazy { IosRouteManager() }
    private val engine by lazy { IosAudioEngine(routeManager) }
    override fun source(): AudioSource = IosAudioSource(engine, routeManager)
    override fun routeManager(): RouteManager = routeManager
    override fun sink(): AudioSink = IosAudioSink(engine, routeManager)
    override fun haptics(): Haptics = IosHaptics()
    override fun fileWriter(path: String, sampleRate: Int, bitrate: Int): PcmFileWriter = IosAacFileWriter(path, sampleRate, bitrate)
}

/**
 * 音频会话（iOS）：category = playAndRecord，mode = default，options = [defaultToSpeaker, allowBluetoothA2DP]
 * —— 有耳机走耳机，没有就外放；不加 allowBluetooth(HFP)（会把采样率压到 8/16 kHz 并接管麦克风）。
 * 路由变化观察者用 queue = null：输出设备拔出时在投递线程同步停掉正在播的音频，再发异步事件。
 */
class IosRouteManager : RouteManager {
    private val session = AVAudioSession.sharedInstance()
    private val _current = MutableStateFlow(snapshot())
    override val current: StateFlow<RouteState> = _current.asStateFlow()
    private val _events = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 64)
    override val routeEvents: Flow<RouteEvent> = _events
    internal val inputRoute = MutableStateFlow(AudioRoute.BuiltIn)
    private val handlers = AtomicReference<List<() -> Unit>>(emptyList())
    private var observers: List<Any> = emptyList()
    /** 会话是否已按铁律配置并激活（打断结束后需要重新 setActive）。 */
    @Volatile var sessionActive: Boolean = false
        private set

    internal fun onDeviceLost(handler: () -> Unit): () -> Unit {
        handlers.value = handlers.value + handler
        return { handlers.value = handlers.value - handler }
    }
    internal fun runLostHandlers() { handlers.value.forEach { h -> runCatching { h() } } }

    override suspend fun ensure(): RouteState = withContext(Dispatchers.Main) {
        memScoped {
            val e1 = alloc<ObjCObjectVar<NSError?>>()
            if (!session.setCategory(AVAudioSessionCategoryPlayAndRecord, mode = AVAudioSessionModeDefault,
                    options = AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetoothA2DP, error = e1.ptr))
                error("AVAudioSession.setCategory failed: ${e1.value?.localizedDescription}")
            val builtIn: AVAudioSessionPortDescription? = session.availableInputs
                ?.filterIsInstance<AVAudioSessionPortDescription>()
                ?.firstOrNull { port -> port.portType == AVAudioSessionPortBuiltInMic }
            if (builtIn != null) {
                val e2 = alloc<ObjCObjectVar<NSError?>>()
                if (!session.setPreferredInput(builtIn, error = e2.ptr)) error("setPreferredInput(builtIn) failed: ${e2.value?.localizedDescription}")
            }
            val e3 = alloc<ObjCObjectVar<NSError?>>()
            if (!session.setActive(true, error = e3.ptr)) error("AVAudioSession.setActive failed: ${e3.value?.localizedDescription}")
            sessionActive = true
        }
        if (observers.isEmpty()) observe()
        refresh()
        _current.value
    }

    private fun observe() {
        val center = NSNotificationCenter.defaultCenter
        // queue = null：块在通知投递线程同步执行 —— 铁律要求的"回调内同步 stop"。
        val route = center.addObserverForName(AVAudioSessionRouteChangeNotification, `object` = null, queue = null) { n: NSNotification? ->
            val reason = (n?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongValue ?: 0uL
            if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) runLostHandlers()
            refresh()
            when (reason) {
                AVAudioSessionRouteChangeReasonOldDeviceUnavailable -> _events.tryEmit(RouteEvent.OldDeviceUnavailable)
                AVAudioSessionRouteChangeReasonNewDeviceAvailable -> _events.tryEmit(RouteEvent.NewDeviceAvailable)
                AVAudioSessionRouteChangeReasonCategoryChange -> _events.tryEmit(RouteEvent.CategoryChange)
                else -> Unit
            }
        }
        val interruption = center.addObserverForName(AVAudioSessionInterruptionNotification, `object` = null, queue = null) { n: NSNotification? ->
            val type = (n?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue ?: 0uL
            val began = type == AVAudioSessionInterruptionTypeBegan
            if (began) { runLostHandlers(); sessionActive = false }
            _events.tryEmit(RouteEvent.Interruption(began = began))
        }
        observers = listOf(route, interruption)
    }

    private fun refresh() { val s = snapshot(); _current.value = s; inputRoute.value = s.input }

    private fun snapshot(): RouteState {
        val route = session.currentRoute
        val outputs: List<String> = route.outputs.filterIsInstance<AVAudioSessionPortDescription>().mapNotNull { port -> port.portType }
        val inputs: List<String> = route.inputs.filterIsInstance<AVAudioSessionPortDescription>().mapNotNull { port -> port.portType }
        val output = when {
            AVAudioSessionPortBluetoothHFP in outputs -> AudioRoute.BluetoothHfp
            AVAudioSessionPortBluetoothA2DP in outputs || AVAudioSessionPortBluetoothLE in outputs -> AudioRoute.BluetoothA2dp
            AVAudioSessionPortHeadphones in outputs || AVAudioSessionPortUSBAudio in outputs -> AudioRoute.Wired
            AVAudioSessionPortBuiltInSpeaker in outputs || AVAudioSessionPortBuiltInReceiver in outputs -> AudioRoute.Speaker
            else -> AudioRoute.None
        }
        val input = when {
            AVAudioSessionPortBluetoothHFP in inputs -> AudioRoute.BluetoothHfp
            AVAudioSessionPortHeadsetMic in inputs || AVAudioSessionPortUSBAudio in inputs -> AudioRoute.Wired
            AVAudioSessionPortBuiltInMic in inputs -> AudioRoute.BuiltIn
            else -> AudioRoute.None
        }
        val note = when {
            output == AudioRoute.BluetoothHfp || input == AudioRoute.BluetoothHfp -> "HFP call mode, limited audio quality"
            else -> null
        }
        return RouteState(input = input, output = output, note = note)
    }

    /** 会话结束：不移除观察者（全局单例）；会话去激活交给引擎空闲判断（IosAudioEngine.stopIfIdle）。 */
    override fun relax() = Unit
}

class IosHaptics : Haptics {
    override fun play(pattern: HapticPattern) {
        when (pattern) {
            HapticPattern.START, HapticPattern.DIDNT_GET -> repeat(2) { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight).impactOccurred() }
            HapticPattern.READY -> UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight).impactOccurred()
            HapticPattern.FLIP -> UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy).impactOccurred()
            HapticPattern.LOST -> UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
        }
    }
}
