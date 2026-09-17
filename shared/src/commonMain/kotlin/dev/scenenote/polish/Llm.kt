package dev.scenenote.polish

import dev.scenenote.core.egress.EgressGate
import dev.scenenote.core.egress.EgressKind
import dev.scenenote.core.egress.EgressRequest
import dev.scenenote.core.egress.Egressed
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.core.settings.KeyWallet
import dev.scenenote.core.settings.Providers
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.TimeSource

class LlmFailed(message: String, val status: Int = 0) : Exception(message)
data class LlmResult(val text: String, val model: String, val latencyMs: Long, val inputTokens: Int, val outputTokens: Int, val estCost: Double)

/** 百炼通用模型价格（元 / 百万 token，北京；2026-09 估算，只用于账本换算）。 */
object LlmPricing {
    private val table = mapOf("qwen-flash" to (0.15 to 1.5), "qwen-plus" to (0.8 to 2.0), "qwen-turbo" to (0.3 to 0.6))
    fun estimate(model: String, inTok: Int, outTok: Int): Double { val (i, o) = table[model] ?: table.getValue("qwen-flash"); return inTok / 1e6 * i + outTok / 1e6 * o }
}

/**
 * 慢路径的云端大模型（百炼 OpenAI 兼容 chat/completions，JSON 模式）。只在会话结束后整段调用（02 篇 §2.4），
 * 经 EgressGate（kind = TEXT）记账；401 视为 Key 失效由上层停用。
 */
class BailianLlm(private val egress: EgressGate, private val wallet: KeyWallet, private val settings: AppSettings) {
    private val provider = Providers.bailian
    fun available(): Boolean = wallet.hasKey(provider.id)
    val model: String get() = settings.llmModel

    suspend fun complete(system: String, user: String, sessionId: String?, jsonMode: Boolean = true, maxTokens: Int = 2048): LlmResult {
        val key = wallet.key(provider.id) ?: throw LlmFailed("未填写 Key", 401)
        val base = wallet.baseUrl(provider.id)?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: "https://${provider.host}/compatible-mode/v1"
        val m = model
        val body: JsonObject = buildJsonObject {
            put("model", m)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                addJsonObject { put("role", "user"); put("content", user) }
            }
            put("temperature", 0.3)
            put("max_tokens", maxTokens)
            if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
        }
        val t0 = TimeSource.Monotonic.markNow()
        return egress.perform(EgressRequest(providerId = provider.id, host = provider.host, kind = EgressKind.TEXT, transport = "http", sessionId = sessionId)) { http ->
            val resp = http.post("$base/chat/completions") {
                headers { append("Authorization", "Bearer $key") }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val text = resp.bodyAsText()
            val status = resp.status.value
            if (status !in 200..299) {
                val msg = runCatching { EgressGate.json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
                throw LlmFailed(when (status) { 401, 403 -> "Key 无效或未开通"; 429 -> "云端限流，稍后重试"; else -> "云端出错 $status${msg?.let { "：$it" } ?: ""}" }, status)
            }
            val j = EgressGate.json.parseToJsonElement(text).jsonObject
            val content = j["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: throw LlmFailed("响应缺少内容")
            val usage = j["usage"]?.jsonObject
            val inTok = usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val outTok = usage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val cost = LlmPricing.estimate(m, inTok, outTok)
            Egressed(LlmResult(content, m, t0.elapsedNow().inWholeMilliseconds, inTok, outTok, cost), bytes = body.toString().length.toLong() + text.length, estCost = cost)
        }
    }
}
