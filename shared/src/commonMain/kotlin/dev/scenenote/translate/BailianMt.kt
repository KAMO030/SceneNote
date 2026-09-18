package dev.scenenote.translate

import dev.scenenote.core.egress.EgressGate
import dev.scenenote.core.egress.EgressKind
import dev.scenenote.core.egress.EgressRequest
import dev.scenenote.core.egress.Egressed
import dev.scenenote.core.model.Lang
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.core.settings.KeyWallet
import dev.scenenote.core.settings.Providers
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlin.time.TimeSource

/**
 * 百炼 qwen-mt 价格（北京地域，元 / 百万 token，输入 / 输出；help.aliyun.com/zh/model-studio/model-pricing 2026-09-15）。
 * flash 为官方通用首选（支持增量流式）；lite 延迟最低但无粤语；turbo 已停更；plus 质量最高但不支持增量流式。新加坡地域价格不同、无免费额度。
 */
object BailianPricing {
    data class Price(val inputPerM: Double, val outputPerM: Double)
    val mt: Map<String, Price> = mapOf(
        "qwen-mt-flash" to Price(0.7, 1.95),
        "qwen-mt-lite" to Price(0.6, 1.6),
        "qwen-mt-plus" to Price(1.8, 5.4),
        "qwen-mt-turbo" to Price(0.7, 1.95),
    )
    val models: List<String> get() = mt.keys.toList()
    fun estimate(model: String, inTok: Int, outTok: Int): Double {
        val p = mt[model] ?: mt.getValue("qwen-mt-flash")
        return inTok / 1_000_000.0 * p.inputPerM + outTok / 1_000_000.0 * p.outputPerM
    }
}

/** qwen-mt 的语言名（translation_options 用英文全名）。 */
object BailianLang {
    fun name(tag: String): String = when (tag) {
        Lang.ZH_CN, Lang.ZH_SICHUAN, Lang.WUU, Lang.NAN -> "Chinese"
        Lang.YUE_HK -> "Cantonese"
        Lang.EN -> "English"
        Lang.JA -> "Japanese"
        Lang.KO -> "Korean"
        Lang.AUTO -> "auto"
        else -> tag
    }
}

/**
 * 阿里云百炼 qwen-mt（OpenAI 兼容模式 /compatible-mode/v1/chat/completions + translation_options）。
 * Key 只从 KeyWallet 读，请求经 EgressGate（kind = TEXT，记账 + 隐私档 + 消费闸门）。
 */
class BailianMtTranslator(
    private val egress: EgressGate,
    private val wallet: KeyWallet,
    private val settings: AppSettings,
) : Translator {
    override val id = "bailian"
    override val cloud = true
    private val provider = Providers.bailian
    val model: String get() = settings.mtModel
    fun hasKey(): Boolean = wallet.hasKey(provider.id)

    /**
     * 翻译档切到「本机」就当云端不存在：FastTranslator 与 FallbackTranslator 都按 supports 选路，
     * 于是快路径、屏内字幕、慢路径一起落到端侧 NMT。设置页的「测试连接」直连 [translate]，不受这里影响。
     */
    override fun supports(src: String, tgt: String): Boolean = hasKey() && !settings.mtLocalOnly && src != tgt

    override suspend fun translate(req: MtRequest): MtResult {
        val key = wallet.key(provider.id) ?: throw MtFailed("Bailian API key not set", retryable = false)
        val base = wallet.baseUrl(provider.id)?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: "https://${provider.host}/compatible-mode/v1"
        val url = "$base/chat/completions"
        val m = model
        val body: JsonObject = buildJsonObject {
            put("model", m)
            putJsonArray("messages") {
                // qwen-mt 只接受一条 user 消息、不支持 system；上下文经 tm_list（翻译记忆）传入
                addJsonObject { put("role", "user"); put("content", req.text) }
            }
            putJsonObject("translation_options") {
                put("source_lang", BailianLang.name(req.src))
                put("target_lang", BailianLang.name(req.tgt))
                if (req.glossary.isNotEmpty()) putJsonArray("terms") { req.glossary.forEach { (s, t) -> addJsonObject { put("source", s); put("target", t) } } }
                if (req.context.isNotEmpty()) putJsonArray("tm_list") { req.context.forEach { (s, t) -> addJsonObject { put("source", s); put("target", t) } } }
            }
        }
        val t0 = TimeSource.Monotonic.markNow()
        return egress.perform(EgressRequest(providerId = provider.id, host = provider.host, kind = EgressKind.TEXT, transport = "http", segmentId = req.segmentId, sessionId = req.sessionId)) { http ->
            val resp = http.post(url) {
                headers { append("Authorization", "Bearer $key") }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val text = resp.bodyAsText()
            val status = resp.status.value
            if (status !in 200..299) {
                val msg = runCatching { EgressGate.json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
                throw MtFailed(
                    when (status) { 401 -> "Bailian key invalid or not enabled (401)"; 429 -> "Bailian rate limited (429)"; else -> "Bailian HTTP $status${msg?.let { ": $it" } ?: ""}" },
                    retryable = status != 401,
                )
            }
            val j = EgressGate.json.parseToJsonElement(text).jsonObject
            val content = j["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw MtFailed("Bailian response missing choices[0].message.content")
            val usage = j["usage"]?.jsonObject
            val inTok = usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val outTok = usage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val cost = BailianPricing.estimate(m, inTok, outTok)
            Egressed(
                MtResult(content.trim(), req.src, req.tgt, provider.id, m, t0.elapsedNow().inWholeMilliseconds, inTok, outTok, cost),
                bytes = body.toString().length.toLong() + text.length, estCost = cost,
            )
        }
    }
}

@Serializable data class KeyTestResult(val ok: Boolean, val message: String, val latencyMs: Long = 0, val model: String = "", val sample: String = "", val estCost: Double? = null)

/** 设置 → Key 钱包 → 测试连接：翻一句固定文本，行内展示结果（原型 KeyWallet：不弹窗）。 */
class KeyTester(private val bailian: BailianMtTranslator, private val consent: dev.scenenote.core.egress.ConsentRegistry) {
    /** 用户主动点"测试"= 对这一次请求的显式授权（逐段授权档下也放行；锁定档仍由 EgressGate 拒绝）。 */
    suspend fun testBailian(): KeyTestResult {
        if (!bailian.hasKey()) return KeyTestResult(false, "key not set")
        consent.grantSession(SESSION)
        return try {
            val r = bailian.translate(MtRequest("你好，很高兴见到你。", Lang.ZH_CN, Lang.EN, sessionId = SESSION))
            KeyTestResult(true, "ok", r.latencyMs, r.model, r.text, r.estCost)
        } catch (e: Exception) { KeyTestResult(false, e.message ?: e.toString()) }
        finally { consent.revokeSession(SESSION) }
    }
    private companion object { const val SESSION = "keytest" }
}
