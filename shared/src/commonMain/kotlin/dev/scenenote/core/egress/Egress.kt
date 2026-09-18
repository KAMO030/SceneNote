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

enum class EgressKind(val id: String) { AUDIO("audio"), TEXT("text"), MODEL_ASSET("model_asset"), MEDIA_URL("media_url") }
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
            Destination.LAN -> if (privacy.allowsLan) Verdict.Allow else Verdict.Deny("privacy locked: no LAN egress")
            Destination.INTERNET -> when {
                privacy is PrivacyMode.Locked -> Verdict.Deny("privacy locked: no third-party egress")
                // 模型资产下载：用户显式点击、不含任何用户数据，除锁定档外一律放行（05 篇 §7.4）
                req.kind == EgressKind.MODEL_ASSET -> Verdict.Allow
                // 用户粘贴的视频直链：只下载、不上传任何用户数据，除锁定档外放行（I6 屏内 S4）
                req.kind == EgressKind.MEDIA_URL -> Verdict.Allow
                privacy is PrivacyMode.LocalWithPerSegmentConsent && !consent.allows(req.segmentId, req.sessionId) ->
                    Verdict.Deny("per-segment consent required")
                req.kind == EgressKind.AUDIO && !privacy.allowsInternetAudio -> Verdict.Deny("privacy level forbids audio egress")
                req.kind == EgressKind.TEXT && !privacy.allowsInternetText && privacy !is PrivacyMode.LocalWithPerSegmentConsent -> Verdict.Deny("privacy level forbids text egress")
                spend.shouldBlock() -> Verdict.Deny("monthly spend limit reached; local only")
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
