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
    override fun supports(lang: String) = true
    override suspend fun synthesize(req: TtsRequest, onChunk: suspend (ShortArray) -> Boolean): TtsStats {
        var sent = 0
        repeat(chunks) { i -> delay(chunkDelayMs); if (!onChunk(ShortArray(160 * (i + 1)))) return TtsStats(id, 16_000, sent, 0, 0); sent += 160 * (i + 1) }
        return TtsStats(id, 16_000, sent, chunkDelayMs, chunkDelayMs * chunks)
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
