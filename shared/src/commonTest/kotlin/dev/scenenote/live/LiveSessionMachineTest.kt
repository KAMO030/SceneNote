package dev.scenenote.live

import dev.scenenote.audio.AudioRoute
import dev.scenenote.audio.AudioSink
import dev.scenenote.audio.HapticPattern
import dev.scenenote.audio.Haptics
import dev.scenenote.audio.RouteEvent
import dev.scenenote.audio.RouteManager
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

private class FakeRoute : RouteManager {
    val events = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 8)
    override val current: StateFlow<RouteState> = MutableStateFlow(RouteState(AudioRoute.BuiltIn, AudioRoute.Speaker))
    override val routeEvents = events
    var ensureCalls = 0
    override suspend fun ensure(): RouteState { ensureCalls++; return current.value }
    override fun relax() = Unit
}

private class FakeSink : AudioSink {
    var stops = 0; var flushes = 0; var primes = 0
    override var volumeDb: Float = 0f
    override suspend fun play(chunk: ShortArray, channelMask: Int) = Unit
    override fun stop() { stops++ }
    override fun flush() { flushes++ }
    override fun prime() { primes++ }
    override fun release() = Unit
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

    @Test fun armFailureReturnsIdle() = runTest {
        val m = DefaultLiveSessionMachine(FakeRoute(), FakeSink(), FakeHaptics(), backgroundScope, arm = { false })
        m.trigger(); runCurrent()
        assertEquals(LiveState.Idle, m.state.value)
    }

    @Test fun outputDeviceRemovedStopsQueueButStaysLive() = runTest {
        val route = FakeRoute(); val sink = FakeSink()
        val m = DefaultLiveSessionMachine(route, sink, FakeHaptics(), backgroundScope)
        m.trigger(); runCurrent()
        route.events.tryEmit(RouteEvent.OldDeviceUnavailable); runCurrent()
        assertIs<LiveState.Live>(m.state.value)        // 耳机只是输出设备，拔掉后继续（系统已切外放）
        assertTrue(sink.stops >= 1 && sink.flushes >= 1)
    }

    @Test fun interruptionPausesThenResumesWithReEnsure() = runTest {
        val route = FakeRoute(); val sink = FakeSink()
        val m = DefaultLiveSessionMachine(route, sink, FakeHaptics(), backgroundScope)
        m.trigger(); runCurrent()
        val calls = route.ensureCalls
        m.onRoute(RouteEvent.Interruption(began = true))
        assertEquals(LiveState.Paused("call"), m.state.value)
        m.onRoute(RouteEvent.Interruption(began = false)); runCurrent()
        assertIs<LiveState.Live>(m.state.value)
        assertTrue(route.ensureCalls > calls)
        assertEquals(2, sink.primes)
    }

    @Test fun endReturnsToIdle() = runTest {
        val m = DefaultLiveSessionMachine(FakeRoute(), FakeSink(), FakeHaptics(), backgroundScope)
        m.trigger(); runCurrent()
        m.end()
        assertEquals(LiveState.Idle, m.state.value)
    }
}
