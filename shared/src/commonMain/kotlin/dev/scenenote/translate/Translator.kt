package dev.scenenote.translate

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.TimeSource

/** 一次翻译请求。context 是同一会话里上一两句的原文 + 译文，保证指代一致（02 篇 §2.2 快路径）。 */
data class MtRequest(
    val text: String, val src: String, val tgt: String,
    val context: List<Pair<String, String>> = emptyList(),
    val glossary: Map<String, String> = emptyMap(),
    val segmentId: String? = null, val sessionId: String? = null,
)

data class MtResult(
    val text: String, val src: String, val tgt: String,
    val providerId: String, val model: String, val latencyMs: Long,
    val inputTokens: Int = 0, val outputTokens: Int = 0, val estCost: Double? = null,
)

class MtFailed(message: String, val retryable: Boolean = true, cause: Throwable? = null) : Exception(message, cause)

/** 译员：云端 BYOK（百炼 qwen-mt …）或端侧（core/nmt，后续里程碑）。 */
interface Translator {
    val id: String
    val cloud: Boolean
    fun supports(src: String, tgt: String): Boolean
    suspend fun translate(req: MtRequest): MtResult
}

/** 快路径结果：译文可能来自降级档；unavailable 时 text 为空、UI 显示原文 + 原因。 */
data class FastMt(val result: MtResult?, val degraded: Boolean, val reason: String? = null) {
    val available: Boolean get() = result != null
}

/**
 * 快路径译员（06 篇 §3.1）：云端优先，[timeoutMs] 内没回来就降级到端侧；端侧也没有 → 不可用但不阻塞管线。
 * 连续 [trips] 次超时后进入"熔断"：接下来 [cooldownMs] 内直接走降级，不再等 1.5 s。
 */
class FastTranslator(
    private val cloud: () -> Translator?,
    private val local: () -> Translator?,
    private val timeoutMs: Long = 1500,
    private val trips: Int = 2,
    private val cooldownMs: Long = 30_000,
) {
    private var consecutiveTimeouts = 0
    private var trippedAt: TimeSource.Monotonic.ValueTimeMark? = null
    /** 最近一次的健康：ok / slow / fallback / unavailable。 */
    var health: String = "ok"; private set
    val tripped: Boolean get() = trippedAt?.let { it.elapsedNow().inWholeMilliseconds < cooldownMs } ?: false

    suspend fun translate(req: MtRequest): FastMt {
        if (req.src == req.tgt || req.text.isBlank()) return FastMt(MtResult(req.text, req.src, req.tgt, "none", "identity", 0), degraded = false)
        val c = cloud()?.takeIf { it.supports(req.src, req.tgt) }
        val l = local()?.takeIf { it.supports(req.src, req.tgt) }
        if (c != null && !tripped) {
            try {
                val r = withTimeout(timeoutMs) { c.translate(req) }
                consecutiveTimeouts = 0; health = if (r.latencyMs > timeoutMs * 2 / 3) "slow" else "ok"
                return FastMt(r, degraded = false)
            } catch (e: TimeoutCancellationException) {
                consecutiveTimeouts++
                if (consecutiveTimeouts >= trips) trippedAt = TimeSource.Monotonic.markNow()
                health = "fallback"
                return fallback(l, req, "云端 ${timeoutMs} ms 未响应")
            } catch (e: MtFailed) {
                if (!e.retryable) trippedAt = TimeSource.Monotonic.markNow()
                health = "fallback"
                return fallback(l, req, e.message ?: "云端翻译失败")
            } catch (e: Exception) {
                health = "fallback"
                return fallback(l, req, e.message ?: "云端翻译失败")
            }
        }
        val why = when {
            c == null -> "无云端 Key 或当前隐私档不允许"
            else -> "云端已熔断 ${cooldownMs / 1000} s"
        }
        return fallback(l, req, why)
    }

    private suspend fun fallback(l: Translator?, req: MtRequest, reason: String): FastMt {
        if (l == null) { health = "unavailable"; return FastMt(null, degraded = true, reason = "$reason；端侧语言对未安装") }
        return try { FastMt(l.translate(req), degraded = true, reason = reason) }
        catch (e: Exception) { health = "unavailable"; FastMt(null, degraded = true, reason = "$reason；端侧：${e.message}") }
    }

    fun reset() { consecutiveTimeouts = 0; trippedAt = null; health = "ok" }
}
