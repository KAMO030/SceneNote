package dev.scenenote.core.egress

import com.russhwolf.settings.MapSettings
import dev.scenenote.core.model.LedgerEntry
import dev.scenenote.core.model.PrivacyMode
import dev.scenenote.core.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class MemLedger : Ledger {
    val entries = mutableListOf<LedgerEntry>()
    override suspend fun record(entry: LedgerEntry) { entries += entry }
    override fun since(ts: Long): Flow<List<LedgerEntry>> = flowOf(entries)
    override suspend fun monthlyEstCost(): Double = entries.sumOf { it.estCost ?: 0.0 }
    override suspend fun bytesSince(ts: Long, destination: Destination): Long = entries.sumOf { it.bytes }
}

class EgressGateTest {
    private fun gate(mode: PrivacyMode, limit: Double = 0.0): Pair<EgressGate, AppSettings> {
        val settings = AppSettings(MapSettings()).apply { setPrivacy(mode); monthlyLimit = limit }
        val ledger = MemLedger()
        return EgressGate(settings, ConsentRegistry(), SpendGate(settings, ledger), ledger, EgressGate.defaultHttpClient()) to settings
    }
    private val text = EgressRequest("bailian", "dashscope.aliyuncs.com", EgressKind.TEXT, segmentId = "s1")
    private val audio = text.copy(kind = EgressKind.AUDIO)
    private val model = EgressRequest("model_mirror", "hf-mirror.com", EgressKind.MODEL_ASSET)

    @Test fun lockedDeniesEverythingIncludingModels() = runTest {
        val (g, _) = gate(PrivacyMode.Locked)
        assertIs<Verdict.Deny>(g.verdict(text)); assertIs<Verdict.Deny>(g.verdict(audio)); assertIs<Verdict.Deny>(g.verdict(model))
    }

    @Test fun consentModeAllowsModelDownloadButNotUnconsentedText() = runTest {
        val (g, _) = gate(PrivacyMode.LocalWithPerSegmentConsent)
        assertEquals(Verdict.Allow, g.verdict(model))
        assertIs<Verdict.Deny>(g.verdict(text))
    }

    @Test fun textOnlyAllowsTextDeniesAudio() = runTest {
        val (g, _) = gate(PrivacyMode.TextOnlyCloud)
        assertEquals(Verdict.Allow, g.verdict(text)); assertIs<Verdict.Deny>(g.verdict(audio)); assertEquals(Verdict.Allow, g.verdict(model))
    }

    @Test fun audioCloudAllowsAll() = runTest {
        val (g, _) = gate(PrivacyMode.AudioCloud)
        assertEquals(Verdict.Allow, g.verdict(text)); assertEquals(Verdict.Allow, g.verdict(audio))
    }

    @Test fun spendGateBlocksWhenOverLimit() = runTest {
        val (g, _) = gate(PrivacyMode.AudioCloud, limit = 1.0)
        g.perform(text) { Egressed(Unit, 10, estCost = 2.0) }
        assertIs<Verdict.Deny>(g.verdict(text))
        assertEquals(Verdict.Allow, g.verdict(model))   // 模型下载不受费用闸门约束
    }
}
