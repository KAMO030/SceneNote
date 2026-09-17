package dev.scenenote.bench

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * 延迟基准打点（13 篇 I1；主方案 §11 H11 本机口径）。
 * 起点 = VAD 回推的句尾（vadEnd）；本机终点 = TTS 首帧写入 AudioSink（sinkWrite）；实验室终点由旁录得到，不在本类。
 * 每个 utteranceId 一条记录，写入 JSON Lines；分位数由 [Percentiles] 计算。
 */
@Serializable
data class LatencyRecord(
    val utteranceId: String,
    val profile: String,
    val vadEndMs: Long,
    val asrFinalMs: Long? = null,
    val mtFirstMs: Long? = null,
    val ttsFirstMs: Long? = null,
    val sinkWriteMs: Long? = null,
    val note: String? = null,
) {
    /** 句尾 → 首帧写入：本机口径的端到端。 */
    val endToEndMs: Long? get() = sinkWriteMs?.let { it - vadEndMs }
    val asrMs: Long? get() = asrFinalMs?.let { it - vadEndMs }
    val mtMs: Long? get() = if (asrFinalMs != null && mtFirstMs != null) mtFirstMs - asrFinalMs else null
    val ttsMs: Long? get() = if (mtFirstMs != null && ttsFirstMs != null) ttsFirstMs - mtFirstMs else null
}

enum class Mark { VAD_END, ASR_FINAL, MT_FIRST, TTS_FIRST, SINK_WRITE }

class LatencyProbe(private val sink: (String) -> Unit = {}) {
    private val clock = TimeSource.Monotonic.markNow()
    private val open = mutableMapOf<String, LatencyRecord>()
    private val _records = MutableStateFlow<List<LatencyRecord>>(emptyList())
    val records: StateFlow<List<LatencyRecord>> = _records.asStateFlow()

    fun nowMs(): Long = clock.elapsedNow().inWholeMilliseconds

    fun mark(utteranceId: String, mark: Mark, profile: String = "unknown", atMs: Long = nowMs()) {
        val cur = open[utteranceId] ?: LatencyRecord(utteranceId, profile, vadEndMs = atMs).also { if (mark != Mark.VAD_END) return }
        val next = when (mark) {
            Mark.VAD_END -> cur.copy(vadEndMs = atMs, profile = profile)
            Mark.ASR_FINAL -> cur.copy(asrFinalMs = atMs)
            Mark.MT_FIRST -> cur.copy(mtFirstMs = cur.mtFirstMs ?: atMs)
            Mark.TTS_FIRST -> cur.copy(ttsFirstMs = cur.ttsFirstMs ?: atMs)
            Mark.SINK_WRITE -> cur.copy(sinkWriteMs = cur.sinkWriteMs ?: atMs)
        }
        open[utteranceId] = next
        if (mark == Mark.SINK_WRITE) complete(utteranceId)
    }

    fun complete(utteranceId: String, note: String? = null) {
        val rec = open.remove(utteranceId)?.let { if (note != null) it.copy(note = note) else it } ?: return
        _records.value = _records.value + rec
        sink(Json.encodeToString(rec))
    }

    fun summary(): BenchSummary = BenchSummary.of(_records.value)
    /** 只清内存记录（latency.jsonl 保留作历史）。 */
    fun clear() { open.clear(); _records.value = emptyList() }
}

@Serializable
data class BenchSummary(val count: Int, val e2eP50: Long?, val e2eP95: Long?, val asrP50: Long?, val mtP50: Long?, val ttsP50: Long?) {
    companion object {
        fun of(records: List<LatencyRecord>): BenchSummary {
            val e2e = records.mapNotNull { it.endToEndMs }
            return BenchSummary(
                count = records.size,
                e2eP50 = Percentiles.p(e2e, 50.0), e2eP95 = Percentiles.p(e2e, 95.0),
                asrP50 = Percentiles.p(records.mapNotNull { it.asrMs }, 50.0),
                mtP50 = Percentiles.p(records.mapNotNull { it.mtMs }, 50.0),
                ttsP50 = Percentiles.p(records.mapNotNull { it.ttsMs }, 50.0),
            )
        }
    }
}

object Percentiles {
    /** 最近秩法（nearest-rank）：与验收脚本保持同一口径。 */
    fun p(values: List<Long>, percentile: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = kotlin.math.ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
