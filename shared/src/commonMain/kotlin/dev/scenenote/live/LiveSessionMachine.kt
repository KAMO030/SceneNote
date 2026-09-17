package dev.scenenote.live

import dev.scenenote.audio.AudioSink
import dev.scenenote.audio.HapticPattern
import dev.scenenote.audio.Haptics
import dev.scenenote.audio.RouteEvent
import dev.scenenote.audio.RouteManager
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
 * 实时会话状态机（03 篇 §3.5 / 附录 A.2）。
 * - trigger：Idle → Arming（订阅路由事件、ensure 会话、加载模型、prime）→ Live。
 * - 输出设备变化（耳机插拔）：App 不关心，系统自动切换输出；只把已排队的播放停掉避免突兀。
 * - 打断（来电 / Siri）：Paused("call")；结束后重新 ensure 会话再恢复。
 */
class DefaultLiveSessionMachine(
    private val routeManager: RouteManager,
    private val sink: AudioSink,
    private val haptics: Haptics,
    private val scope: CoroutineScope,
    /** I2 起替换为真实的模型加载；返回 false 表示无法进入 Live。 */
    private val arm: suspend () -> Boolean = { true },
) : LiveSessionMachine {
    private val _state = MutableStateFlow<LiveState>(LiveState.Idle)
    override val state: StateFlow<LiveState> = _state.asStateFlow()
    private var routeJob: Job? = null
    private var armJob: Job? = null

    override fun trigger() {
        when (_state.value) {
            LiveState.Idle, LiveState.NeedForeground, LiveState.Degraded -> startArming()
            is LiveState.Paused -> scope.launch { resume() }
            else -> Unit
        }
    }

    private fun startArming() {
        _state.value = LiveState.Arming
        listenRoute()
        armJob?.cancel()
        armJob = scope.launch {
            routeManager.ensure()
            if (!arm()) { _state.value = LiveState.Idle; return@launch }
            sink.prime()
            haptics.play(HapticPattern.START)
            _state.value = LiveState.Live(PlayState.LISTENING, DirState.UNDETERMINED)
        }
    }

    private fun listenRoute() {
        if (routeJob?.isActive == true) return
        routeJob = scope.launch { routeManager.routeEvents.onEach { onRoute(it) }.collect() }
    }

    override fun onRoute(e: RouteEvent) {
        when (e) {
            RouteEvent.OldDeviceUnavailable -> { sink.stop(); sink.flush() }   // 系统已切换输出；丢掉排队中的旧音频即可
            RouteEvent.NewDeviceAvailable, RouteEvent.CategoryChange -> Unit
            is RouteEvent.Interruption -> if (e.began) {
                sink.stop(); sink.flush()
                if (_state.value is LiveState.Live) _state.value = LiveState.Paused("call")
            } else if ((_state.value as? LiveState.Paused)?.reason == "call") scope.launch { resume() }
        }
    }

    private suspend fun resume() {
        if (_state.value !is LiveState.Paused) return
        routeManager.ensure()
        sink.prime()
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
        armJob?.cancel(); routeJob?.cancel(); routeJob = null
        sink.stop(); sink.flush()
        routeManager.relax()
        _state.value = LiveState.Idle
    }
}
