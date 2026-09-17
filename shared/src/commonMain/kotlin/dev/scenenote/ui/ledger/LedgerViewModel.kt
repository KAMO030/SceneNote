package dev.scenenote.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scenenote.core.egress.Ledger
import dev.scenenote.core.model.LedgerEntry
import dev.scenenote.core.model.PrivacyMode
import dev.scenenote.core.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock

data class LedgerUiState(
    val days: Int = 1,
    val entries: List<LedgerEntry> = emptyList(),
    /** 三个数字：文本上云 KB / 录音上云 KB / 局域网 KB；模型下载单列。 */
    val textKb: Long = 0, val audioKb: Long = 0, val lanKb: Long = 0, val modelMb: Long = 0,
    val estCost: Double = 0.0,
    val privacy: PrivacyMode = PrivacyMode.LocalWithPerSegmentConsent,
)

/** 数据去向（I7）：今天 / 7 天，每条数据去了哪；数字直接来自 EgressGate 记账，与实际出站字节一致。 */
class LedgerViewModel(private val ledger: Ledger, settings: AppSettings) : ViewModel() {
    private val _days = MutableStateFlow(1)
    val ui: StateFlow<LedgerUiState> = combine(_days.flatMapLatest { d -> ledger.since(Clock.System.now().toEpochMilliseconds() - d * 86_400_000L) }, _days, settings.privacy) { list, d, p ->
        LedgerUiState(
            days = d, entries = list,
            textKb = list.filter { it.kind == "text" }.sumOf { it.bytes } / 1024, audioKb = list.filter { it.kind == "audio" }.sumOf { it.bytes } / 1024,
            lanKb = list.filter { it.destinationClass == "lan" }.sumOf { it.bytes } / 1024, modelMb = list.filter { it.kind == "model_asset" || it.kind == "media_url" }.sumOf { it.bytes } / 1_048_576,
            estCost = list.sumOf { it.estCost ?: 0.0 }, privacy = p,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())
    fun setDays(d: Int) { _days.value = d }
}
