package dev.scenenote.bench

import dev.scenenote.asr.LocalEngineState
import dev.scenenote.asr.SherpaAsrEngine
import dev.scenenote.asr.toFloatPcm
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.core.platform.MemoryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.TimeSource

/** 关卡 A 基准结果（13 篇 I2）：RTF / 首包 / 内存。跑在旗舰机上，写入 files/bench/asr-bench.jsonl。 */
@Serializable
data class AsrBenchResult(
    val ts: Long,
    val audioMs: Long,
    val loadMs: Map<String, Long>,
    val memBeforeLoadBytes: Long,
    val memAfterLoadBytes: Long,
    val streamingWallMs: Long,
    val streamingRtf: Double,
    val streamingFirstPartialMs: Long?,          // 从开始喂音频到首个非空部分结果的处理耗时
    val streamingText: String,
    val senseVoiceWallMs: Long?,
    val senseVoiceRtf: Double?,
    val senseVoiceText: String?,
    val senseVoiceLang: String?,
    val sichuanWallMs: Long? = null,
    val sichuanRtf: Double? = null,
    val sichuanText: String? = null,
    val runtime: String,
)

class AsrBench(private val engine: SherpaAsrEngine, private val paths: AppPaths) {
    suspend fun run(pcm16k: ShortArray): AsrBenchResult = withContext(Dispatchers.Default) {
        require(pcm16k.size >= 16_000) { "need at least 1 s of audio" }
        val audioMs = pcm16k.size * 1000L / 16_000
        val memBefore = MemoryInfo.residentBytes()
        engine.unload()
        val st = engine.load(dev.scenenote.asr.LoadPlan.ALL, loadAll = true)
        val ready = st as? LocalEngineState.Ready ?: error((st as? LocalEngineState.Error)?.reason ?: "engine not ready")
        val memAfter = MemoryInfo.residentBytes()

        // 流式：尽快喂完整段音频，量处理耗时与首包
        val session = engine.openStream("zh-CN", emptyList())
        var text = ""
        val t0 = TimeSource.Monotonic.markNow()
        var firstPartial: Long? = null
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launchCollect(session.events) { ev ->
            when (ev) {
                is dev.scenenote.asr.AsrEvent.Partial -> if (firstPartial == null && ev.text.isNotBlank()) firstPartial = t0.elapsedNow().inWholeMilliseconds
                is dev.scenenote.asr.AsrEvent.Final -> if (ev.segment.revision == 0) { text = (text + " " + ev.segment.text).trim() }
                else -> Unit
            }
        }
        var off = 0
        while (off < pcm16k.size) { val n = minOf(320, pcm16k.size - off); session.push(pcm16k.copyOfRange(off, off + n)); off += n }
        session.endOfInput()
        // 等 worker 消费完：轮询直到通道处理完（简单起见等待最多 60 s）
        val deadline = TimeSource.Monotonic.markNow()
        while (deadline.elapsedNow().inWholeSeconds < 60 && !(session as dev.scenenote.asr.SherpaStreamingSession).isDrained()) kotlinx.coroutines.delay(20)
        val streamingWall = t0.elapsedNow().inWholeMilliseconds
        job.cancel(); (session as dev.scenenote.asr.SherpaStreamingSession).close()

        var svWall: Long? = null; var svText: String? = null; var svLang: String? = null
        if (ready.hasSenseVoice) {
            val t1 = TimeSource.Monotonic.markNow()
            val segs = engine.transcribe(pcm16k, "zh-CN")
            svWall = t1.elapsedNow().inWholeMilliseconds
            svText = segs.joinToString(" ") { it.text }; svLang = segs.firstOrNull()?.lang
        }
        var scWall: Long? = null; var scText: String? = null
        if (ready.hasSichuan) {
            val t2 = TimeSource.Monotonic.markNow()
            val segs = engine.transcribe(pcm16k, dev.scenenote.core.model.Lang.ZH_SICHUAN)
            scWall = t2.elapsedNow().inWholeMilliseconds
            scText = segs.joinToString(" ") { it.text }
        }
        val result = AsrBenchResult(
            ts = Clock.System.now().toEpochMilliseconds(), audioMs = audioMs, loadMs = ready.loadMs,
            memBeforeLoadBytes = memBefore, memAfterLoadBytes = memAfter,
            streamingWallMs = streamingWall, streamingRtf = streamingWall.toDouble() / audioMs, streamingFirstPartialMs = firstPartial, streamingText = text,
            senseVoiceWallMs = svWall, senseVoiceRtf = svWall?.let { it.toDouble() / audioMs }, senseVoiceText = svText, senseVoiceLang = svLang,
            sichuanWallMs = scWall, sichuanRtf = scWall?.let { it.toDouble() / audioMs }, sichuanText = scText,
            runtime = "sherpa-onnx ${dev.scenenote.asr.SherpaRuntime.version}",
        )
        paths.ensureDir(paths.benchDir)
        paths.appendText(paths.join(paths.benchDir, "asr-bench.jsonl"), Json.encodeToString(result) + "\n")
        result
    }
}

private fun <T> kotlinx.coroutines.CoroutineScope.launchCollect(flow: kotlinx.coroutines.flow.Flow<T>, block: (T) -> Unit): kotlinx.coroutines.Job =
    this.launch { flow.collect { block(it) } }
