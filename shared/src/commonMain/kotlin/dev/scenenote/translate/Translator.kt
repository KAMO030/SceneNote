package dev.scenenote.translate

import kotlinx.coroutines.CancellationException
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

/** 译员：云端 BYOK（百炼 qwen-mt …）或端侧（core/nmt 的 [dev.scenenote.nmt.OnnxNmtTranslator]）。 */
interface Translator {
    val id: String
    val cloud: Boolean
    fun supports(src: String, tgt: String): Boolean
    suspend fun translate(req: MtRequest): MtResult
}

/** 云端优先、端侧兜底，不带超时（慢路径回填成稿用；快路径用 [FastTranslator]）。 */
class FallbackTranslator(private val primary: Translator, private val secondary: Translator) : Translator {
    override val id: String get() = primary.id
    override val cloud: Boolean get() = primary.cloud
    override fun supports(src: String, tgt: String): Boolean = primary.supports(src, tgt) || secondary.supports(src, tgt)
    override suspend fun translate(req: MtRequest): MtResult {
        if (!primary.supports(req.src, req.tgt)) return secondary.translate(req)
        return try { primary.translate(req) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { if (secondary.supports(req.src, req.tgt)) secondary.translate(req) else throw e }
    }
}

/**
 * 快路径结果：译文可能来自降级档；unavailable 时 text 为空、UI 显示原文 + 原因。
 * [reason] 是给诊断日志看的英文说明，**不要直接显示给用户**；UI 用 [code] 映射成文案（docs/15 §5）。
 */
data class FastMt(val result: MtResult?, val degraded: Boolean, val reason: String? = null, val code: String? = null) {
    val available: Boolean get() = result != null

    companion object {
        /** 没连云端翻译，也没有这个方向的离线翻译包。 */
        const val CODE_NO_KEY_NO_PAIR = "no_key_no_pair"
        /** 云端超时 / 出错 / 熔断，且没有离线翻译包兜底。 */
        const val CODE_CLOUD_DOWN_NO_PAIR = "cloud_down_no_pair"
        /** 离线翻译本身报错。 */
        const val CODE_LOCAL_FAILED = "local_failed"
    }
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
                return fallback(l, req, "cloud timed out after ${timeoutMs} ms", cloudDown = true)
            } catch (e: MtFailed) {
                if (!e.retryable) trippedAt = TimeSource.Monotonic.markNow()
                health = "fallback"
                return fallback(l, req, e.message ?: "cloud translation failed", cloudDown = true)
            } catch (e: CancellationException) {
                throw e   // 调用方取消（判向更新 / 定稿重译）：不是云端故障，也别去跑端侧
            } catch (e: Exception) {
                health = "fallback"
                return fallback(l, req, e.message ?: "cloud translation failed", cloudDown = true)
            }
        }
        val why = when {
            c == null -> "no cloud key or privacy level forbids"
            else -> "cloud circuit open for ${cooldownMs / 1000} s"
        }
        return fallback(l, req, why, cloudDown = c != null)
    }

    /** [cloudDown]：云端本来可用但这次没成（超时 / 出错 / 熔断）；false = 根本没有云端（没 Key 或联网权限不允许）。 */
    private suspend fun fallback(l: Translator?, req: MtRequest, reason: String, cloudDown: Boolean): FastMt {
        if (l == null) {
            health = "unavailable"
            return FastMt(null, degraded = true, reason = "$reason; local language pair not installed",
                code = if (cloudDown) FastMt.CODE_CLOUD_DOWN_NO_PAIR else FastMt.CODE_NO_KEY_NO_PAIR)
        }
        return try { FastMt(l.translate(req), degraded = true, reason = reason) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { health = "unavailable"; FastMt(null, degraded = true, reason = "$reason; local: ${e.message}", code = FastMt.CODE_LOCAL_FAILED) }
    }

    fun reset() { consecutiveTimeouts = 0; trippedAt = null; health = "ok" }
}
