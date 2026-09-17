package dev.scenenote.live

import dev.scenenote.audio.AudioSink
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
        try {
            if (primedIdle) { sink.prime(); primedIdle = false }
            var first = true
            val t0 = TimeSource.Monotonic.markNow()
            val stats = item.engine.synthesize(item.req) { chunk ->
                if (item.generation != generation) return@synthesize false
                if (first) { first = false; _playing.value = id; _events.tryEmit(PlaybackEvent.Started(id, t0.elapsedNow().inWholeMilliseconds)) }
                sink.play(chunk)
                item.generation == generation
            }
            if (first) _events.tryEmit(PlaybackEvent.Skipped(id, "empty"))
            else _events.tryEmit(PlaybackEvent.Done(id, stats))
        } catch (e: CancellationException) {
            _events.tryEmit(PlaybackEvent.Skipped(id, "cancelled")); throw e
        } catch (t: Throwable) {
            _events.tryEmit(PlaybackEvent.Failed(id, t.message ?: t.toString()))
        } finally {
            if (_playing.value == id) _playing.value = null
        }
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
}
