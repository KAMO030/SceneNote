package dev.scenenote.live

import dev.scenenote.audio.AudioSink
import dev.scenenote.core.Diag
import dev.scenenote.tts.TtsEngine
import dev.scenenote.tts.TtsRequest
import dev.scenenote.tts.TtsStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

sealed interface PlaybackEvent {
    data class Started(val utteranceId: String, val firstChunkMs: Long) : PlaybackEvent
    data class Done(val utteranceId: String, val stats: TtsStats) : PlaybackEvent
    data class Skipped(val utteranceId: String, val reason: String) : PlaybackEvent
    data class Failed(val utteranceId: String, val reason: String) : PlaybackEvent
}

/**
 * 播放队列（06 篇 §3.4）：串行合成 + 播放；空 → 非空时先 prime 唤醒耳机；
 * [flush] 丢掉未播的（对方打断 / 我在说话 / 设备切换），正在播的立刻停。
 * 积压（规格 §5.4）：后面还排着 ≥ 2 句时本句语速 1.2×（≥ 4 句合并待做）。
 */
class PlaybackQueue(private val sink: AudioSink, private val scope: CoroutineScope) {
    private data class Item(val req: TtsRequest, val engine: TtsEngine, val generation: Int)
    private val queue = Channel<Item>(Channel.UNLIMITED)
    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<PlaybackEvent> = _events
    private val _playing = MutableStateFlow<String?>(null)
    /** 正在播的 utteranceId；空闲为 null。 */
    val playing: StateFlow<String?> = _playing.asStateFlow()
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending.asStateFlow()
    private var generation = 0
    private var primedIdle = true
    private var current: Job? = null
    private val worker: Job = scope.launch {
        for (item in queue) {
            _pending.value = (_pending.value - 1).coerceAtLeast(0)
            if (item.generation != generation) { _events.tryEmit(PlaybackEvent.Skipped(item.req.utteranceId, "flushed")); continue }
            val job = launch { playOne(item) }
            current = job
            job.join()
            current = null
        }
    }

    fun enqueue(req: TtsRequest, engine: TtsEngine) {
        _pending.value += 1
        queue.trySend(Item(req, engine, generation))
    }

    private suspend fun playOne(item: Item) {
        val id = item.req.utteranceId
        val backlog = _pending.value
        try {
            if (primedIdle) { sink.prime(); primedIdle = false }
            var first = true
            val t0 = TimeSource.Monotonic.markNow()
            val req = if (_pending.value >= 2 && item.req.rate == 1f) item.req.copy(rate = BACKLOG_RATE) else item.req
            val stats = item.engine.synthesize(req) { chunk ->
                if (item.generation != generation) return@synthesize false
                if (first) { first = false; _playing.value = id; _events.tryEmit(PlaybackEvent.Started(id, t0.elapsedNow().inWholeMilliseconds)) }
                sink.play(chunk)
                item.generation == generation
            }
            if (first) { Diag.log("tts", "empty utt=${id.take(8)} engine=${item.engine.id}"); _events.tryEmit(PlaybackEvent.Skipped(id, "empty")) }
            else { logPlayed(id, stats, backlog); _events.tryEmit(PlaybackEvent.Done(id, stats)) }
        } catch (e: CancellationException) {
            Diag.log("tts", "cancelled utt=${id.take(8)} backlog=$backlog")
            _events.tryEmit(PlaybackEvent.Skipped(id, "cancelled")); throw e
        } catch (t: Throwable) {
            Diag.log("tts", "FAILED utt=${id.take(8)} engine=${item.engine.id}: ${t.message}")
            _events.tryEmit(PlaybackEvent.Failed(id, t.message ?: t.toString()))
        } finally {
            if (_playing.value == id) _playing.value = null
        }
    }

    /**
     * 每句一行：首音延迟 / 合成 + 播放总耗时 / 这句音频本身多长 / 进队时还排着几句。
     * `first` 大而 `audio` 不长，或 `backlog` 一路往上涨 → 合成追不上说话；这时队列串行等的是算力，
     * 不是播放通道——采集与播放是两条独立的路（Android MODE_NORMAL 的 MIC + MEDIA，iOS playAndRecord），本来就能同时跑。
     */
    private fun logPlayed(id: String, stats: TtsStats, backlog: Int) {
        val audioMs = if (stats.sampleRate > 0) stats.samples * 1000L / stats.sampleRate else 0L
        Diag.log("tts", "played utt=${id.take(8)} engine=${stats.engineId} first=${stats.firstChunkMs}ms total=${stats.totalMs}ms audio=${audioMs}ms backlog=$backlog→${_pending.value}")
    }

    /** 丢弃未播的并停掉正在播的；下一句会重新 prime。 */
    fun flush() {
        generation++
        current?.cancel()
        sink.stop(); sink.flush()
        _playing.value = null
        primedIdle = true
    }

    fun close() { flush(); queue.close(); worker.cancel() }

    companion object { const val BACKLOG_RATE = 1.2f }
}
