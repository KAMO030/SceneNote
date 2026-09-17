package dev.scenenote.core.egress

import dev.scenenote.core.model.LedgerEntry
import dev.scenenote.core.model.PrivacyMode
import dev.scenenote.core.settings.AppSettings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlin.time.Clock

// ---------- core:egress（信任层唯一出网口；06 篇 §7.6 / 07 篇 §7.12） ----------

enum class EgressKind(val id: String) { AUDIO("audio"), TEXT("text"), MODEL_ASSET("model_asset") }
enum class Destination(val id: String) { INTERNET("internet"), LAN("lan") }

data class EgressRequest(
    val providerId: String, val host: String, val kind: EgressKind,
    val transport: String = "http", val segmentId: String? = null, val sessionId: String? = null,
    val destination: Destination = Destination.INTERNET,
)

sealed interface Verdict {
    data object Allow : Verdict
    data class Deny(val reason: String) : Verdict
}

class EgressDenied(val request: EgressRequest, val reason: String) : Exception("egress denied: $reason")

data class Egressed<T>(val value: T, val bytes: Long, val estCost: Double? = null)

interface Ledger {
    suspend fun record(entry: LedgerEntry)
    fun since(ts: Long): Flow<List<LedgerEntry>>
    suspend fun monthlyEstCost(): Double
    suspend fun bytesSince(ts: Long, destination: Destination): Long
}

/** 逐段授权登记：LocalWithPerSegmentConsent 档下，segmentId / sessionId 在集合内才放行。 */
class ConsentRegistry {
    private val segments = mutableSetOf<String>()
    private val sessions = mutableSetOf<String>()
    fun grantSegment(id: String) { segments += id }
    fun grantSession(id: String) { sessions += id }
    fun revokeSession(id: String) { sessions -= id }
    fun allows(segmentId: String?, sessionId: String?): Boolean =
        (segmentId != null && segmentId in segments) || (sessionId != null && sessionId in sessions)
}

/** 本地消费闸门：月度估算费用 ≥ 上限即拦截互联网出站（0 = 不限）。 */
class SpendGate(private val settings: AppSettings, private val ledger: Ledger) {
    suspend fun shouldBlock(): Boolean {
        val limit = settings.monthlyLimit
        if (limit <= 0.0) return false
        return ledger.monthlyEstCost() >= limit
    }
}

/**
 * Egress 门面：所有第三方 / 局域网请求必须经过 [perform]。
 * 判定顺序：隐私档 → 逐段授权 → 消费闸门 → 放行并记账。
 */
class EgressGate(
    private val settings: AppSettings,
    private val consent: ConsentRegistry,
    private val spend: SpendGate,
    private val ledger: Ledger,
    val http: HttpClient,
) {
    suspend fun verdict(req: EgressRequest): Verdict {
        val privacy: PrivacyMode = settings.privacy.value
        return when (req.destination) {
            Destination.LAN -> if (privacy.allowsLan) Verdict.Allow else Verdict.Deny("隐私锁定：不向局域网对端发送")
            Destination.INTERNET -> when {
                privacy is PrivacyMode.Locked -> Verdict.Deny("隐私锁定：不向第三方发送任何数据")
                // 模型资产下载：用户显式点击、不含任何用户数据，除锁定档外一律放行（05 篇 §7.4）
                req.kind == EgressKind.MODEL_ASSET -> Verdict.Allow
                privacy is PrivacyMode.LocalWithPerSegmentConsent && !consent.allows(req.segmentId, req.sessionId) ->
                    Verdict.Deny("需要逐段授权后才能上云")
                req.kind == EgressKind.AUDIO && !privacy.allowsInternetAudio -> Verdict.Deny("当前隐私档不允许音频上云")
                req.kind == EgressKind.TEXT && !privacy.allowsInternetText && privacy !is PrivacyMode.LocalWithPerSegmentConsent -> Verdict.Deny("当前隐私档不允许文本上云")
                spend.shouldBlock() -> Verdict.Deny("已达本月消费上限，已自动切回仅本机")
                else -> Verdict.Allow
            }
        }
    }

    suspend fun <T> perform(req: EgressRequest, block: suspend (HttpClient) -> Egressed<T>): T {
        when (val v = verdict(req)) {
            is Verdict.Deny -> throw EgressDenied(req, v.reason)
            Verdict.Allow -> Unit
        }
        val result = block(http)
        ledger.record(
            LedgerEntry(
                ts = Clock.System.now().toEpochMilliseconds(), providerId = req.providerId, host = req.host,
                kind = req.kind.id, transport = req.transport, bytes = result.bytes, segmentId = req.segmentId,
                estCost = result.estCost, destinationClass = req.destination.id,
            )
        )
        return result.value
    }

    companion object {
        val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        fun defaultHttpClient(): HttpClient = HttpClient {
            install(ContentNegotiation) { json(json) }
            expectSuccess = false
        }
    }
}
