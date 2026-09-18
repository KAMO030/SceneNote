package dev.scenenote.translate

import com.russhwolf.settings.MapSettings
import dev.scenenote.core.egress.ConsentRegistry
import dev.scenenote.core.egress.Destination
import dev.scenenote.core.egress.EgressGate
import dev.scenenote.core.egress.Ledger
import dev.scenenote.core.egress.SpendGate
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.LedgerEntry
import dev.scenenote.core.model.PrivacyMode
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.core.settings.KeyWallet
import dev.scenenote.core.settings.Providers
import dev.scenenote.core.settings.SecureStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class MemSecureStore : SecureStore {
    private val m = mutableMapOf<String, String>()
    override fun get(key: String): String? = m[key]
    override fun put(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m -= key }
}

private object NoopLedger : Ledger {
    override suspend fun record(entry: LedgerEntry) = Unit
    override fun since(ts: Long): Flow<List<LedgerEntry>> = flowOf(emptyList())
    override suspend fun monthlyEstCost(): Double = 0.0
    override suspend fun bytesSince(ts: Long, destination: Destination): Long = 0L
}

private class LocalStub : Translator {
    override val id = "nmt"
    override val cloud = false
    override fun supports(src: String, tgt: String) = true
    override suspend fun translate(req: MtRequest) = MtResult("[local] ${req.text}", req.src, req.tgt, id, "stub", 0)
}

/**
 * 设置 → 翻译模型 → 本机：连着云端也只走端侧。
 * 选路全在 [Translator.supports]，[FastTranslator]（实时快路径 / 屏内字幕）与 [FallbackTranslator]（慢路径定稿）共用这一处判断。
 */
class MtLocalTierTest {
    private fun cloud(localOnly: Boolean): BailianMtTranslator {
        val settings = AppSettings(MapSettings()).apply { setPrivacy(PrivacyMode.TextOnlyCloud); mtLocalOnly = localOnly }
        val wallet = KeyWallet(MemSecureStore()).apply { setKey(Providers.bailian.id, "sk-never-sent") }
        val gate = EgressGate(settings, ConsentRegistry(), SpendGate(settings, NoopLedger), NoopLedger, EgressGate.defaultHttpClient())
        return BailianMtTranslator(gate, wallet, settings)
    }

    @Test fun defaultsToCloudTier() {
        assertFalse(AppSettings(MapSettings()).mtLocalOnly)
        assertTrue(cloud(localOnly = false).supports(Lang.ZH_CN, Lang.EN))
    }

    /** 本机档下云端对任何方向都「不支持」，但密钥原封不动——换回任一云端档立刻恢复。 */
    @Test fun localTierDisablesCloudWithoutTouchingKey() {
        val c = cloud(localOnly = true)
        assertTrue(c.hasKey())
        assertFalse(c.supports(Lang.ZH_CN, Lang.EN))
        assertFalse(c.supports(Lang.EN, Lang.JA))
    }

    /** 快路径不再等云端的 1.5 s 超时，直接就是端侧结果。 */
    @Test fun fastPathGoesStraightToLocal() = runTest {
        val ft = FastTranslator({ cloud(localOnly = true) }, { LocalStub() })
        val r = ft.translate(MtRequest("你好", Lang.ZH_CN, Lang.EN))
        assertEquals("[local] 你好", r.result?.text)
        assertTrue(r.degraded)
    }

    /** 慢路径的定稿回填（FallbackTranslator）同样落到端侧。 */
    @Test fun slowPathFallsBackToLocal() = runTest {
        val r = FallbackTranslator(cloud(localOnly = true), LocalStub()).translate(MtRequest("你好", Lang.ZH_CN, Lang.EN))
        assertEquals("[local] 你好", r.text)
        assertEquals("nmt", r.providerId)
    }
}
