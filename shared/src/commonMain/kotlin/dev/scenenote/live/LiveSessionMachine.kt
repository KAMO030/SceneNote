package dev.scenenote.live

import dev.scenenote.audio.AudioSink
import dev.scenenote.audio.HapticPattern
import dev.scenenote.audio.Haptics
import dev.scenenote.audio.RouteEvent
import dev.scenenote.audio.RouteManager
import dev.scenenote.audio.RoutePolicyAudio
import dev.scenenote.core.model.DirState
import dev.scenenote.core.model.FastPathEvent
import dev.scenenote.core.model.LiveState
import dev.scenenote.core.model.PlayState
import dev.scenenote.core.model.StageHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

interface LiveSessionMachine {
    val state: StateFlow<LiveState>
    fun trigger()
    fun onRoute(e: RouteEvent)
    fun onHealth(h: FastPathEvent.Health)
    fun end()
}

/**
 * 实时会话状态机（03 篇 §3.5 / 附录 A.2）：
 * Idle → Arming（重载模型 + 路由铁律 + prime）→ Live(LISTENING, UNDETERMINED)。
 * 耳机断开 → 回调内同步 stop + flush → Paused(earbud_lost)，绝不切扬声器；耳机重连 → 校验路由后回 Live。
 * 来电 / Siri → Paused(call)；end → Ending → Idle。
 * I0 只落地状态迁移与路由铁律；管线阶段在 I3 接入。
 */
class DefaultLiveSessionMachine(
    private val routeManager: RouteManager,
    private val sink: AudioSink,
    private val haptics: Haptics,
    private val scope: CoroutineScope,
    private val policy: RoutePolicyAudio = RoutePolicyAudio.HEADSET_A2DP_ONLY,
    /** I2 起替换为真实的模型加载；返回 false 表示无法进入 Live。 */
    private val arm: suspend () -> Boolean = { true },
) : LiveSessionMachine {
    private val _state = MutableStateFlow<LiveState>(LiveState.Idle)
    override val state: StateFlow<LiveState> = _state.asStateFlow()
    private var routeJob: Job? = null
    private var armJob: Job? = null

    override fun trigger() {
        when (val s = _state.value) {
            LiveState.Idle, LiveState.NeedForeground, LiveState.Degraded -> startArming()
            is LiveState.Paused -> if (s.reason != "earbud_lost") resume()
            else -> Unit
        }
    }

    private fun startArming() {
        _state.value = LiveState.Arming
        armJob?.cancel()
        armJob = scope.launch {
            val route = routeManager.ensure(policy)
            if (policy == RoutePolicyAudio.HEADSET_A2DP_ONLY && !route.headsetConnected) {
                _state.value = LiveState.Degraded   // 没有 A2DP 耳机：只能降级到 M3 双屏（UI 决定）
                return@launch
            }
            if (!arm()) { _state.value = LiveState.Idle; return@launch }
            sink.prime()
            listenRoute()
            haptics.play(HapticPattern.START)
            _state.value = LiveState.Live(PlayState.LISTENING, DirState.UNDETERMINED)
        }
    }

    private fun listenRoute() {
        routeJob?.cancel()
        routeJob = scope.launch { routeManager.routeEvents.onEach { onRoute(it) }.collect() }
    }

    override fun onRoute(e: RouteEvent) {
        when (e) {
            RouteEvent.OldDeviceUnavailable -> {
                // 铁律：回调内同步 stop + flush；不切扬声器；泄漏窗口 ≤ 100 ms。
                sink.stop(); sink.flush()
                if (_state.value is LiveState.Live) { haptics.play(HapticPattern.LOST); _state.value = LiveState.Paused("earbud_lost") }
            }
            RouteEvent.NewDeviceAvailable -> {
                val s = _state.value
                if (s is LiveState.Paused && s.reason == "earbud_lost") scope.launch {
                    val route = routeManager.ensure(policy)
                    if (route.obeysHeadsetRule) resume()
                }
            }
            is RouteEvent.Interruption -> if (e.began) { sink.stop(); _state.value = LiveState.Paused("call") } else if ((_state.value as? LiveState.Paused)?.reason == "call") resume()
            RouteEvent.PulledToHfp -> scope.launch { routeManager.ensure(policy) }
            RouteEvent.CategoryChange -> Unit
        }
    }

    private fun resume() {
        haptics.play(HapticPattern.READY)
        _state.value = LiveState.Live(PlayState.LISTENING, DirState.UNDETERMINED)
    }

    override fun onHealth(h: FastPathEvent.Health) {
        if (h.state == StageHealth.FALLBACK && _state.value is LiveState.Live) {
            // 保持 Live；Degraded 只在整条快路径都不可用时进入（I3）。
        }
    }

    override fun end() {
        if (_state.value == LiveState.Idle) return
        _state.value = LiveState.Ending
        armJob?.cancel(); routeJob?.cancel()
        sink.stop(); sink.flush()
        routeManager.release()
        _state.value = LiveState.Idle
    }
}
