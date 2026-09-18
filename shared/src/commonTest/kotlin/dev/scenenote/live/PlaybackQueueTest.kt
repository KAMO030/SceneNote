package dev.scenenote.live

import dev.scenenote.audio.AudioSink
import dev.scenenote.tts.TtsEngine
import dev.scenenote.tts.TtsRequest
import dev.scenenote.tts.TtsStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class QueueFakeSink : AudioSink {
    val played = mutableListOf<Int>(); var primes = 0; var stops = 0
    override suspend fun play(chunk: ShortArray, channelMask: Int) { played += chunk.size; delay(10) }
    override var volumeDb: Float = 0f
    override fun stop() { stops++ }
    override fun flush() {}
    override fun prime() { primes++ }
    override fun release() {}
}
private class FakeTts(val chunks: Int = 3, val chunkDelayMs: Long = 5) : TtsEngine {
    override val id = "fake"
    val rates = mutableListOf<Float>()
    override fun supports(lang: String) = true
    override suspend fun synthesize(req: TtsRequest, onChunk: suspend (ShortArray) -> Boolean): TtsStats {
        rates += req.rate
        var sent = 0
        repeat(chunks) { i -> delay(chunkDelayMs); if (!onChunk(ShortArray(160 * (i + 1)))) return TtsStats(id, 16_000, sent, 0, 0); sent += 160 * (i + 1) }
        return TtsStats(id, 16_000, sent, chunkDelayMs, chunkDelayMs * chunks)
    }
}

/** 永远不回块的引擎：模拟卡住的系统 TTS（合成到文件后回调不来），首块看门狗该把它掐掉。 */
private class StuckTts : TtsEngine {
    override val id = "stuck"
    override fun supports(lang: String) = true
    override suspend fun synthesize(req: TtsRequest, onChunk: suspend (ShortArray) -> Boolean): TtsStats {
        delay(Long.MAX_VALUE / 2)
        return TtsStats(id, 16_000, 0, 0, 0)
    }
}

class PlaybackQueueTest {
    @Test fun playsSequentiallyAndPrimesOnce() = runTest {
        val sink = QueueFakeSink(); val q = PlaybackQueue(sink, backgroundScope)
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch { q.events.collect { events += it } }
        q.enqueue(TtsRequest("a", "en", "u1"), FakeTts()); q.enqueue(TtsRequest("b", "en", "u2"), FakeTts())
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, sink.primes)
        assertEquals(listOf(160, 320, 480, 160, 320, 480), sink.played)
        assertEquals(listOf("u1", "u2"), events.filterIsInstance<PlaybackEvent.Done>().map { it.utteranceId })
        assertTrue(events.filterIsInstance<PlaybackEvent.Started>().map { it.utteranceId }.containsAll(listOf("u1", "u2")))
    }

    @Test fun backlogOfTwoOrMoreSpeedsUp() = runTest {
        val sink = QueueFakeSink(); val q = PlaybackQueue(sink, backgroundScope)
        val tts = FakeTts()
        (1..4).forEach { q.enqueue(TtsRequest("s$it", "en", "u$it"), tts) }
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf(1.2f, 1.2f, 1f, 1f), tts.rates)   // u1/u2 后面各排着 ≥ 2 句；u3/u4 不加速
    }

    /** 一句卡住不能堵死整条队列：看门狗超时后它标 Failed，后面的照常播。 */
    @Test fun stuckUtteranceGivesUpAndLetsTheRestPlay() = runTest {
        val sink = QueueFakeSink(); val q = PlaybackQueue(sink, backgroundScope)
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch { q.events.collect { events += it } }
        q.enqueue(TtsRequest("stuck", "en", "u1"), StuckTts())
        q.enqueue(TtsRequest("ok", "en", "u2"), FakeTts())
        advanceTimeBy(PlaybackQueue.FIRST_CHUNK_TIMEOUT_MS - 100); runCurrent()
        assertTrue(events.none { it is PlaybackEvent.Failed }, "还没到时限就不该放弃")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("u1"), events.filterIsInstance<PlaybackEvent.Failed>().map { it.utteranceId })
        assertEquals(listOf("u2"), events.filterIsInstance<PlaybackEvent.Done>().map { it.utteranceId })
    }

    /**
     * 合成追不上说话时丢掉过时的：每句 4 s、排 8 句，
     * u3–u5 排队时已经等了 ≥ 6 s 且后面还压着 ≥ 3 句 → 只上屏不念；u6 之后队列短了，照常播。
     */
    @Test fun staleUtterancesAreDroppedWhenSynthesisFallsBehind() = runTest {
        val sink = QueueFakeSink(); val q = PlaybackQueue(sink, backgroundScope, testScheduler.timeSource)
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch { q.events.collect { events += it } }
        val slow = FakeTts(chunks = 1, chunkDelayMs = 3_990)   // 3990 + sink 的 10 ms = 4 s 一句
        (1..8).forEach { q.enqueue(TtsRequest("s$it", "en", "u$it"), slow) }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("u3", "u4", "u5"), events.filterIsInstance<PlaybackEvent.Skipped>().filter { it.reason == "stale" }.map { it.utteranceId })
        assertEquals(listOf("u1", "u2", "u6", "u7", "u8"), events.filterIsInstance<PlaybackEvent.Done>().map { it.utteranceId })
    }

    @Test fun flushDropsPendingAndStopsCurrent() = runTest {
        val sink = QueueFakeSink(); val q = PlaybackQueue(sink, backgroundScope)
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch { q.events.collect { events += it } }
        q.enqueue(TtsRequest("a", "en", "u1"), FakeTts(chunks = 50)); q.enqueue(TtsRequest("b", "en", "u2"), FakeTts())
        advanceTimeBy(40); runCurrent()
        q.flush()
        advanceTimeBy(2_000); runCurrent()
        assertTrue(sink.stops >= 1)
        assertTrue(events.filterIsInstance<PlaybackEvent.Done>().none { it.utteranceId == "u2" })
        assertTrue(events.any { it is PlaybackEvent.Skipped && it.utteranceId == "u2" } || events.none { it is PlaybackEvent.Started && it.utteranceId == "u2" })
        // 下一句会重新 prime
        q.enqueue(TtsRequest("c", "en", "u3"), FakeTts())
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, sink.primes)
        assertTrue(events.filterIsInstance<PlaybackEvent.Done>().any { it.utteranceId == "u3" })
    }
}
