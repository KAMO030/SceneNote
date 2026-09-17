package dev.scenenote.ui.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneCapsule
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneRadius
import dev.scenenote.core.designsystem.SceneSectionFooter
import dev.scenenote.core.designsystem.SceneSegmentedControl
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.model.LedgerEntry
import dev.scenenote.core.model.PrivacyMode
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.koin.compose.viewmodel.koinViewModel

/**
 * 数据去向（I7）：今天 / 7 天 → 三个数字块（文字 / 录音 / 局域网）→ 逐条去向。
 * 数字直接来自出网闸门的记账，与实际出站字节一致；语音包下载单独一行（不含用户数据）。
 */
@Composable
fun LedgerScreen(onBack: () -> Unit, vm: LedgerViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsState()
    val c = SceneTheme.colors
    GlassScaffold(
        background = c.groupedBackground,
        topBar = { SceneNavBar(title = "数据去向", onBack = onBack) },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 104.dp, bottom = 48.dp, start = SceneSpacing.page, end = SceneSpacing.page),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.m),
        ) {
            item {
                SceneSegmentedControl(
                    options = listOf("今天", "7 天"),
                    selectedIndex = if (ui.days == 1) 0 else 1,
                    onSelect = { vm.setDays(if (it == 0) 1 else 7) },
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
                    NumberBlock("文字", kb(ui.textKb))
                    NumberBlock("录音", kb(ui.audioKb))
                    NumberBlock("局域网", kb(ui.lanKb))
                }
            }
            item {
                Column {
                    SceneGroup {
                        Row(Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.row, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            SceneText("联网权限", Modifier.weight(1f), style = SceneTheme.type.body)
                            SceneCapsule(privacyWord(ui.privacy))
                        }
                        SceneDivider(inset = SceneSpacing.row)
                        Row(Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.row, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            SceneText("估算花费", Modifier.weight(1f), style = SceneTheme.type.body)
                            SceneText(if (ui.estCost < 0.005) "0 元" else "约 ${fmt2(ui.estCost)} 元", style = SceneTheme.type.body, color = c.secondaryLabel)
                        }
                        if (ui.modelMb > 0) {
                            SceneDivider(inset = SceneSpacing.row)
                            Row(Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.row, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                SceneText("语音包 / 视频下载", Modifier.weight(1f), style = SceneTheme.type.body)
                                SceneText("${ui.modelMb} MB", style = SceneTheme.type.body, color = c.secondaryLabel)
                            }
                        }
                    }
                    SceneSectionFooter("只有翻译文字和你选择上传的录音会离开手机")
                }
            }
            if (ui.entries.isEmpty()) item {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    SceneText("这段时间没有数据离开手机", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
                }
            } else {
                item { SceneText("逐条", Modifier.padding(start = SceneSpacing.row, top = 4.dp), style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.secondaryLabel) }
                item {
                    SceneGroup {
                        ui.entries.forEachIndexed { i, e ->
                            if (i > 0) SceneDivider(inset = SceneSpacing.row)
                            EntryRow(e)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.NumberBlock(label: String, value: String) {
    val c = SceneTheme.colors
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(SceneRadius.l)).background(c.secondaryGroupedBackground).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SceneText(value, style = SceneTheme.type.title2.copy(fontWeight = FontWeight.Bold), color = c.label, maxLines = 1)
        SceneText(label, style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1)
    }
}

@Composable
private fun EntryRow(e: LedgerEntry) {
    val c = SceneTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.row, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            SceneText(kindWord(e), style = SceneTheme.type.body, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SceneText("${timeOf(e.ts)} · ${e.host}", style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SceneText(bytesWord(e.bytes), style = SceneTheme.type.subheadline, color = c.secondaryLabel, maxLines = 1)
    }
}

private fun kindWord(e: LedgerEntry): String = when (e.kind) {
    "text" -> if (e.destinationClass == "lan") "文字 · 局域网" else "翻译文字"
    "audio" -> "录音"
    "model_asset" -> "语音包下载"
    "media_url" -> "视频下载"
    else -> e.kind
}

private fun privacyWord(m: PrivacyMode): String = when (m) {
    PrivacyMode.Locked -> "不联网"; PrivacyMode.LocalWithPerSegmentConsent -> "每次询问"; PrivacyMode.TextOnlyCloud -> "只发文字"; PrivacyMode.AudioCloud -> "文字和录音"
}

private fun kb(v: Long): String = when {
    v <= 0 -> "0"
    v < 1024 -> "$v KB"
    else -> "${fmt1(v / 1024.0)} MB"
}
private fun bytesWord(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1_048_576 -> "${b / 1024} KB"
    else -> "${fmt1(b / 1_048_576.0)} MB"
}
private fun fmt1(d: Double): String { val r = kotlin.math.round(d * 10) / 10; return if (r == kotlin.math.floor(r)) r.toLong().toString() else r.toString() }
private fun fmt2(d: Double): String { val cents = kotlin.math.round(d * 100).toLong(); return "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}" }
private fun timeOf(ts: Long): String {
    val t = Instant.fromEpochMilliseconds(ts).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${t.month.number}/${t.day} ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}
