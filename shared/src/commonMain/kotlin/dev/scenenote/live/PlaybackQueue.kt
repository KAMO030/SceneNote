package dev.scenenote.live

import dev.scenenote.audio.AudioSink
import dev.scenenote.core.Diag
import dev.scenenote.tts.TtsEngine
import dev.scenenote.tts.TtsRequest
import dev.scenenote.tts.TtsStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeMark
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
 *
 * 跟不上说话时的两道闸（规格 §5.4）：
 * - 后面还排着 ≥ 2 句 → 本句语速 [BACKLOG_RATE]；
 * - 还排着 ≥ [STALE_BACKLOG] 句且这句已等了 [STALE_AFTER_MS] → 直接丢掉不念（[isStale]），译文留在屏上。
 * 另有首块看门狗：[FIRST_CHUNK_TIMEOUT_MS] 内一点声音都没出来就放弃这句，
 * 不让一个卡住的引擎把后面整条队列堵死（Android 系统 TTS 曾按 15 s 超时，一句就能堵死一整段对话）。
 */
/** [clock] 只为可测：生产用单调时钟，测试传 `runTest` 的虚拟时钟（墙钟在虚拟时间里根本不走）。 */
class PlaybackQueue(private val sink: AudioSink, private val scope: CoroutineScope, private val clock: TimeSource = TimeSource.Monotonic) {
    private data class Item(val req: TtsRequest, val engine: TtsEngine, val generation: Int, val queuedAt: TimeMark)
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
            if (isStale(item)) {
                Diag.log("tts", "stale, screen only utt=${item.req.utteranceId.take(8)} waited=${item.queuedAt.elapsedNow().inWholeMilliseconds}ms backlog=${_pending.value}")
                _events.tryEmit(PlaybackEvent.Skipped(item.req.utteranceId, "stale")); continue
            }
            val job = launch { playOne(item) }
            current = job
            job.join()
            current = null
        }
    }

    fun enqueue(req: TtsRequest, engine: TtsEngine) {
        _pending.value += 1
        queue.trySend(Item(req, engine, generation, clock.markNow()))
    }

    /**
     * 这句是不是已经过时了：后面还排着一串、而它自己又等了这么久，说明合成追不上说话，
     * 现在念出来也对不上现场（讲座里滞后十几秒的译文没有意义），不如让位给新的。
     * 两个条件要同时成立，偶尔一两句的抖动不会命中。
     */
    private fun isStale(item: Item): Boolean =
        _pending.value >= STALE_BACKLOG && item.queuedAt.elapsedNow().inWholeMilliseconds >= STALE_AFTER_MS

    private suspend fun playOne(item: Item) {
        val id = item.req.utteranceId
        val backlog = _pending.value
        var started = false
        val timedOut = MutableStateFlow(false)
        try {
            if (primedIdle) { sink.prime(); primedIdle = false }
            val t0 = clock.markNow()
            val req = if (backlog >= 2 && item.req.rate == 1f) item.req.copy(rate = BACKLOG_RATE) else item.req
            val stats = coroutineScope {
                val synth = async {
                    item.engine.synthesize(req) { chunk ->
                        if (item.generation != generation) return@synthesize false
                        if (!started) { started = true; _playing.value = id; _events.tryEmit(PlaybackEvent.Started(id, t0.elapsedNow().inWholeMilliseconds)) }
                        sink.play(chunk)
                        item.generation == generation
                    }
                }
                // 首块看门狗：判据用 _playing（跨协程可见），不用 started 这个普通局部变量
                val watchdog = launch {
                    delay(FIRST_CHUNK_TIMEOUT_MS)
                    if (_playing.value != id) { timedOut.value = true; synth.cancel() }
                }
                try { synth.await() } finally { watchdog.cancel() }
            }
            if (!started) { Diag.log("tts", "empty utt=${id.take(8)} engine=${item.engine.id}"); _events.tryEmit(PlaybackEvent.Skipped(id, "empty")) }
            else { logPlayed(id, stats, backlog); _events.tryEmit(PlaybackEvent.Done(id, stats)) }
        } catch (e: CancellationException) {
            // 看门狗掐掉的不往外抛：那是这一句不行，不是整条队列被取消（flush / 设备切换才是）
            if (timedOut.value) {
                Diag.log("tts", "no audio in ${FIRST_CHUNK_TIMEOUT_MS}ms, giving up utt=${id.take(8)} engine=${item.engine.id} backlog=$backlog")
                _events.tryEmit(PlaybackEvent.Failed(id, "no audio in ${FIRST_CHUNK_TIMEOUT_MS}ms"))
            } else {
                Diag.log("tts", "cancelled utt=${id.take(8)} backlog=$backlog")
                _events.tryEmit(PlaybackEvent.Skipped(id, "cancelled")); throw e
            }
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

    companion object {
        /** 后面还排着 ≥ 2 句时的语速。 */
        const val BACKLOG_RATE = 1.2f
        /** 一点声音都没出来就放弃这句的时限：够慢设备合成一小句，又不至于把后面整条队列堵住。 */
        const val FIRST_CHUNK_TIMEOUT_MS = 5_000L
        /** 丢弃过时译文的两个条件：后面还排着这么多句，且本句已经等了这么久。 */
        const val STALE_BACKLOG = 3
        const val STALE_AFTER_MS = 6_000L
    }
}
