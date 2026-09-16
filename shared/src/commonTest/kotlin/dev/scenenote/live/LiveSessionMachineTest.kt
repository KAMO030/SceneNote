package dev.scenenote.live

import dev.scenenote.audio.AudioRoute
import dev.scenenote.audio.AudioSink
import dev.scenenote.audio.HapticPattern
import dev.scenenote.audio.Haptics
import dev.scenenote.audio.RouteEvent
import dev.scenenote.audio.RouteManager
import dev.scenenote.audio.RoutePolicyAudio
import dev.scenenote.audio.RouteState
import dev.scenenote.core.model.LiveState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeRoute(var headset: Boolean = true) : RouteManager {
    val events = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 8)
    private val _current = MutableStateFlow(state())
    override val current: StateFlow<RouteState> get() = _current
    override val routeEvents = events
    var ensureCalls = 0
    private fun state() = RouteState(AudioRoute.BuiltIn, if (headset) AudioRoute.BluetoothA2dp else AudioRoute.Speaker, RoutePolicyAudio.HEADSET_A2DP_ONLY, headset)
    override suspend fun ensure(policy: RoutePolicyAudio): RouteState { ensureCalls++; _current.value = state(); return _current.value }
    override fun release() = Unit
}

private class FakeSink : AudioSink {
    var stops = 0; var flushes = 0; var primes = 0
    override var volumeDb: Float = 0f
    override suspend fun play(chunk: ShortArray, channelMask: Int) = Unit
    override fun stop() { stops++ }
    override fun flush() { flushes++ }
    override fun prime() { primes++ }
}

private class FakeHaptics : Haptics { val played = mutableListOf<HapticPattern>(); override fun play(pattern: HapticPattern) { played += pattern } }

class LiveSessionMachineTest {
    @Test fun triggerArmsThenLive() = runTest {
        val route = FakeRoute(); val sink = FakeSink(); val haptics = FakeHaptics()
        val m = DefaultLiveSessionMachine(route, sink, haptics, backgroundScope)
        m.trigger(); runCurrent()
        assertIs<LiveState.Live>(m.state.value)
        assertEquals(1, sink.primes)
        assertEquals(listOf(HapticPattern.START), haptics.played)
    }

    @Test fun noHeadsetDegrades() = runTest {
        val route = FakeRoute(headset = false)
        val m = DefaultLiveSessionMachine(route, FakeSink(), FakeHaptics(), backgroundScope)
        m.trigger(); runCurrent()
        assertEquals(LiveState.Degraded, m.state.value)
    }

    @Test fun earbudLostStopsAndFlushesNeverSpeaker() = runTest {
        val route = FakeRoute(); val sink = FakeSink()
        val m = DefaultLiveSessionMachine(route, sink, FakeHaptics(), backgroundScope)
        m.trigger(); runCurrent()
        route.headset = false
        m.onRoute(RouteEvent.OldDeviceUnavailable)
        assertEquals(LiveState.Paused("earbud_lost"), m.state.value)
        assertTrue(sink.stops >= 1 && sink.flushes >= 1)
        // 再按触发不会在无耳机时恢复
        m.trigger(); runCurrent()
        assertEquals(LiveState.Paused("earbud_lost"), m.state.value)
        // 耳机重连 → 校验路由 → 恢复
        route.headset = true
        m.onRoute(RouteEvent.NewDeviceAvailable); runCurrent()
        assertIs<LiveState.Live>(m.state.value)
    }

    @Test fun endReturnsToIdle() = runTest {
        val m = DefaultLiveSessionMachine(FakeRoute(), FakeSink(), FakeHaptics(), backgroundScope)
        m.trigger(); runCurrent()
        m.end()
        assertEquals(LiveState.Idle, m.state.value)
    }
}
