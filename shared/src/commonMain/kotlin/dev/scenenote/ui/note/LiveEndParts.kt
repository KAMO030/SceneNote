package dev.scenenote.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.scenenote.core.db.GlossaryCandidate
import dev.scenenote.core.db.SessionRow
import dev.scenenote.core.db.StoredUtterance
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneDock
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIconButton
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneRadius
import dev.scenenote.core.designsystem.SceneSectionHeader
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.Speaker
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

// ---------- 对话卡片（LiveEnd）的私有组件：卡片头 / 双语对照 / 要点 / 新词候选 / 状态行 / dock ----------

/** 卡片头：标题 22 pt + 一行元信息（日期 · 时长 · 语言对）。 */
@Composable
internal fun CardHeader(title: String, meta: String) {
    val c = SceneTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(SceneSpacing.xs)) {
        SceneText(title, style = SceneTheme.type.title2, color = c.label)
        if (meta.isNotBlank()) SceneText(meta, style = SceneTheme.type.footnote, color = c.secondaryLabel)
    }
}

/**
 * 一句对照：对方句左侧青绿竖线 + 「对方」青绿小字；我方句普通。
 * 主文本 15 pt（有精修用精修，否则原文），译文 13 pt 次要色。
 */
@Composable
internal fun UtteranceLine(u: StoredUtterance) {
    val c = SceneTheme.colors
    val mine = u.speaker == Speaker.ME.name
    val text = u.polished?.takeIf { it.isNotBlank() } ?: u.raw
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        if (!mine) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(c.tint))
            Box(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SceneText(if (mine) "我" else "对方", style = SceneTheme.type.caption1, color = if (mine) c.secondaryLabel else c.onTintSoft)
            SceneText(text, style = SceneTheme.type.subheadline, color = c.label)
            val tr = u.translation?.takeIf { it.isNotBlank() }
            if (tr != null) SceneText(tr, style = SceneTheme.type.footnote, color = c.secondaryLabel)
        }
    }
}

/** 要点块：浅底 14 dp 圆角；标题「要点」+ 整理来源一词（云端整理 / 本机整理）；项目符号列表。 */
@Composable
internal fun KeyPointsBlock(points: List<String>, cloud: Boolean) {
    val c = SceneTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(SceneRadius.m)).background(c.groupedBackground).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SceneText("要点", style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.label)
            SceneIcon(SceneIcons.Sparkle, contentDescription = null, size = 12.dp, tint = c.secondaryLabel)
            SceneText(if (cloud) "云端整理" else "本机整理", style = SceneTheme.type.caption2, color = c.secondaryLabel, maxLines = 1)
        }
        points.forEach { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SceneText("•", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
                SceneText(p, Modifier.weight(1f), style = SceneTheme.type.subheadline, color = c.label)
            }
        }
    }
}

/** 新词候选组：每行 词 → 译法 + 「确认」「忽略」两个 44 pt 按钮；没有候选时不渲染。 */
@Composable
internal fun CandidatesSection(items: List<GlossaryCandidate>, onAccept: (String) -> Unit, onReject: (String) -> Unit) {
    if (items.isEmpty()) return
    Column {
        SceneSectionHeader("新词候选")
        SceneGroup {
            items.forEachIndexed { i, cand ->
                if (i > 0) SceneDivider()
                CandidateRow(cand, onAccept = { onAccept(cand.term) }, onReject = { onReject(cand.term) })
            }
        }
    }
}

@Composable
private fun CandidateRow(cand: GlossaryCandidate, onAccept: () -> Unit, onReject: () -> Unit) {
    val c = SceneTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.row, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SceneText(cand.term, style = SceneTheme.type.body, color = c.label)
            if (cand.translation.isNotBlank()) SceneText(cand.translation, style = SceneTheme.type.footnote, color = c.secondaryLabel)
        }
        SceneButton("忽略", onClick = onReject, style = ButtonStyle.Gray, height = SceneSize.glassButton)
        SceneButton("确认", onClick = onAccept, style = ButtonStyle.Tinted, height = SceneSize.glassButton)
    }
}

/** 状态行：整理中一句；失败一句 + 「重试」；都没有就不占位。 */
@Composable
internal fun NoteStatusLine(working: Boolean, error: String?, onRetry: (() -> Unit)?) {
    val c = SceneTheme.colors
    when {
        working -> SceneText("正在整理…", Modifier.padding(horizontal = SceneSpacing.page), style = SceneTheme.type.footnote, color = c.secondaryLabel)
        error != null -> Row(
            Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.page),
            horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically,
        ) {
            SceneText(error, Modifier.weight(1f), style = SceneTheme.type.footnote, color = c.destructive)
            if (onRetry != null) SceneButton("重试", onClick = onRetry, style = ButtonStyle.Tinted, height = SceneSize.glassButton)
        }
    }
}

/** 页脚一句，居中。 */
@Composable
internal fun NoteFooter(text: String) {
    SceneText(
        text, Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.page + SceneSpacing.row),
        style = SceneTheme.type.footnote, color = SceneTheme.colors.secondaryLabel, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** dock：分享文字（圆）/ 分享卡片 56 pt 主钮 / 导出（圆）。 */
@Composable
internal fun LiveEndDock(canShareCard: Boolean, onShareCard: () -> Unit, onShareText: () -> Unit, onExport: () -> Unit) {
    SceneDock(height = 72.dp) {
        SceneIconButton(NoteIcons.TextLines, contentDescription = "分享文字", onClick = onShareText, size = 56.dp)
        SceneButton(
            text = "分享卡片", onClick = onShareCard, icon = SceneIcons.Share, style = ButtonStyle.Prominent, enabled = canShareCard,
            modifier = Modifier.weight(1f).padding(horizontal = SceneSpacing.s), height = 56.dp,
        )
        SceneIconButton(SceneIcons.Doc, contentDescription = "导出", onClick = onExport, size = 56.dp)
    }
}

// ---------- 文案辅助 ----------

/** 元信息：日期 · 时长 · 语言对（缺的项不显示）。 */
internal fun sessionMeta(session: SessionRow, utterances: List<StoredUtterance>, now: Long = Clock.System.now().toEpochMilliseconds()): String {
    val parts = mutableListOf<String>()
    parts += formatSessionDate(session.startedAt, now)
    if (session.durationMs > 0) parts += formatDuration(session.durationMs)
    langPair(session, utterances)?.let { parts += it }
    return parts.joinToString(" · ")
}

/** 今天 14:02 / 昨天 14:02 / 9月12日 14:02 / 2025年3月3日。 */
internal fun formatSessionDate(epochMs: Long, nowMs: Long): String {
    val tz = TimeZone.currentSystemDefault()
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz)
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date
    val hm = "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    val dayDiff = today.toEpochDays() - t.date.toEpochDays()
    return when {
        dayDiff == 0L -> "今天 $hm"
        dayDiff == 1L -> "昨天 $hm"
        t.year == today.year -> "${t.month.number}月${t.day}日 $hm"
        else -> "${t.year}年${t.month.number}月${t.day}日"
    }
}

/** 45 秒 / 4 分钟 / 1 小时 5 分钟。 */
internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        totalSec < 60 -> "$totalSec 秒"
        h == 0L -> "$m 分钟"
        m == 0L -> "$h 小时"
        else -> "$h 小时 $m 分钟"
    }
}

/** 语言对「普通话 ⇄ 英语」：优先会话记录的我 / 对方语言，缺则从对话行推断。 */
internal fun langPair(session: SessionRow, utterances: List<StoredUtterance>): String? {
    val my = session.myLang ?: utterances.firstOrNull { it.speaker == Speaker.ME.name }?.lang ?: session.defaultLang
    val other = session.otherLang ?: utterances.firstOrNull { it.speaker != Speaker.ME.name }?.lang
        ?: utterances.firstNotNullOfOrNull { it.targetLang }
    if (other == null || other == my) return null
    return "${Lang.displayName(my)} ⇄ ${Lang.displayName(other)}"
}

/** LiveEnd 专用图标（SceneIcons 没有）：文字行 = 分享文字。 */
private object NoteIcons {
    val TextLines: ImageVector by lazy {
        ImageVector.Builder(name = "text.alignleft", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null) {
                moveTo(4f, 6f); lineTo(20f, 6f)
                moveTo(4f, 12f); lineTo(16f, 12f)
                moveTo(4f, 18f); lineTo(12f, 18f)
            }
            .build()
    }
}
