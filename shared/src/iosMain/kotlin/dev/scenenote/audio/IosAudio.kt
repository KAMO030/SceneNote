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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothA2DP
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
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

class IosAudioFactory : AudioFactory {
    private val routeManager by lazy { IosRouteManager() }
    override fun source(): AudioSource = IosAudioSource()
    override fun routeManager(): RouteManager = routeManager
    override fun sink(): AudioSink = IosAudioSink()
    override fun haptics(): Haptics = IosHaptics()
}

/** I1 接 AVAudioEngine 输入节点 + AVAudioConverter → 16 kHz 单声道 PCM16。I0 为占位。 */
class IosAudioSource : AudioSource {
    override val frames: Flow<ShortArray> = emptyFlow()
    override val route: StateFlow<AudioRoute> = MutableStateFlow(AudioRoute.BuiltIn)
    override suspend fun start(config: CaptureConfig) = Unit
    override fun stop() = Unit
}

/**
 * 路由铁律（iOS）：category = playAndRecord，mode = default，options = [allowBluetoothA2DP] —— 绝不加 allowBluetooth(HFP) / defaultToSpeaker；
 * preferredInput 固定为内置麦。路由变化与打断经 NSNotificationCenter 转成 RouteEvent。
 */
class IosRouteManager : RouteManager {
    private val session = AVAudioSession.sharedInstance()
    private val _current = MutableStateFlow(snapshot(RoutePolicyAudio.ANY))
    override val current: StateFlow<RouteState> = _current.asStateFlow()
    private val _events = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 16)
    override val routeEvents: Flow<RouteEvent> = _events
    private var policy = RoutePolicyAudio.ANY
    private var observers: List<Any> = emptyList()

    override suspend fun ensure(policy: RoutePolicyAudio): RouteState = withContext(Dispatchers.Main) {
        this@IosRouteManager.policy = policy
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            session.setCategory(AVAudioSessionCategoryPlayAndRecord, mode = AVAudioSessionModeDefault,
                options = AVAudioSessionCategoryOptionAllowBluetoothA2DP, error = err.ptr)
            val builtIn: AVAudioSessionPortDescription? = session.availableInputs
                ?.filterIsInstance<AVAudioSessionPortDescription>()
                ?.firstOrNull { port -> port.portType == AVAudioSessionPortBuiltInMic }
            if (builtIn != null) session.setPreferredInput(builtIn, error = err.ptr)
            session.setActive(true, error = err.ptr)
            Unit
        }
        if (observers.isEmpty()) observe()
        refresh()
        _current.value
    }

    private fun observe() {
        val center = NSNotificationCenter.defaultCenter
        val route = center.addObserverForName(AVAudioSessionRouteChangeNotification, `object` = null, queue = NSOperationQueue.mainQueue) { n: NSNotification? ->
            val reason = (n?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongValue ?: 0uL
            refresh()
            when (reason) {
                AVAudioSessionRouteChangeReasonOldDeviceUnavailable -> _events.tryEmit(RouteEvent.OldDeviceUnavailable)
                AVAudioSessionRouteChangeReasonNewDeviceAvailable -> _events.tryEmit(RouteEvent.NewDeviceAvailable)
                AVAudioSessionRouteChangeReasonCategoryChange -> _events.tryEmit(RouteEvent.CategoryChange)
                else -> Unit
            }
            if (_current.value.output == AudioRoute.BluetoothHfp) _events.tryEmit(RouteEvent.PulledToHfp)
        }
        val interruption = center.addObserverForName(AVAudioSessionInterruptionNotification, `object` = null, queue = NSOperationQueue.mainQueue) { n: NSNotification? ->
            val type = (n?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue ?: 0uL
            _events.tryEmit(RouteEvent.Interruption(began = type == AVAudioSessionInterruptionTypeBegan))
        }
        observers = listOf(route, interruption)
    }

    private fun refresh() { _current.value = snapshot(policy) }

    private fun snapshot(policy: RoutePolicyAudio): RouteState {
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
            AVAudioSessionPortHeadsetMic in inputs -> AudioRoute.Wired
            AVAudioSessionPortBuiltInMic in inputs -> AudioRoute.BuiltIn
            else -> AudioRoute.None
        }
        val headset = output == AudioRoute.BluetoothA2dp || output == AudioRoute.Wired
        val note = when {
            output == AudioRoute.BluetoothHfp -> "系统落到了 HFP（通话模式），正在纠正"
            policy == RoutePolicyAudio.HEADSET_A2DP_ONLY && !headset -> "未连接 A2DP 耳机"
            else -> null
        }
        return RouteState(input = input, output = output, policy = policy, headsetConnected = headset, note = note)
    }

    override fun release() {
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers = emptyList()
    }
}

/** I1 接 AVAudioEngine playerNode（16 kHz 立体声，pan 分声道）。I0 为占位，保证 stop/flush 语义可调用。 */
class IosAudioSink : AudioSink {
    override var volumeDb: Float = 0f
    override suspend fun play(chunk: ShortArray, channelMask: Int) = Unit
    override fun stop() = Unit
    override fun flush() = Unit
    override fun prime() = Unit
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
