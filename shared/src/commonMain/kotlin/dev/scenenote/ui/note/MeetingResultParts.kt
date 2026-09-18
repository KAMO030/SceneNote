package dev.scenenote.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.scenenote.core.db.SessionRow
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.CapsuleTone
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneCapsule
import dev.scenenote.core.designsystem.SceneCard
import dev.scenenote.core.designsystem.SceneDock
import dev.scenenote.core.designsystem.SceneGlassCapsuleButton
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIconButton
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneRadius
import dev.scenenote.core.designsystem.SceneSectionFooter
import dev.scenenote.core.designsystem.SceneSegmentedControl
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.model.Segment
import dev.scenenote.polish.MeetingMinutes
import dev.scenenote.polish.SlowPath
import dev.scenenote.polish.TimelinePoint
import dev.scenenote.polish.TodoItem
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import dev.scenenote.core.i18n.UiText
import dev.scenenote.core.i18n.string
import dev.scenenote.shared.resources.*
import dev.scenenote.core.i18n.stringResource
import kotlin.time.Clock

// ---------- 纪要页（MeetingResult.dc.html）的内容与私有子组件；壳在 NoteScreens.kt 的 MeetingResultScreen ----------

/**
 * 纪要：玻璃导航（返回 / 「纪要」/ 重新整理）→ 分段「整理稿 / 原文」→ 一张卡；dock：分享（主）+ 导出（圆）。
 * 页面上只出现用户语言：整理来源只说「云端 / 本机」，不显示模型名、耗时、路由（docs/15）。
 */
@Composable
internal fun MeetingResultContent(
    ui: NoteUiState,
    onBack: () -> Unit,
    onRegenerate: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
) {
    val c = SceneTheme.colors
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val ready = !ui.working && ui.session != null

    GlassScaffold(
        background = c.groupedBackground,
        topBar = {
            SceneNavBar(
                title = stringResource(Res.string.note_minutes_title),
                onBack = onBack,
                trailing = { SceneGlassCapsuleButton(stringResource(Res.string.note_regenerate), onClick = { if (!ui.working) onRegenerate() }, icon = SceneIcons.Rotate) },
            )
        },
        bottomBar = {
            SceneDock(height = 72.dp) {
                SceneButton(onClick = onShare, modifier = Modifier.weight(1f).padding(end = 12.dp), style = ButtonStyle.Prominent, enabled = ready, height = 56.dp) {
                    SceneIcon(SceneIcons.Share, contentDescription = null, size = 20.dp)
                    SceneText(stringResource(Res.string.note_share), style = SceneTheme.type.headline)
                }
                SceneIconButton(SceneIcons.Doc, contentDescription = stringResource(Res.string.note_export), onClick = onExport, size = 56.dp, enabled = ready)
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 104.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.padding(horizontal = SceneSpacing.page)) {
                SceneSegmentedControl(options = listOf(stringResource(Res.string.note_seg_minutes), stringResource(Res.string.note_seg_transcript)), selectedIndex = tab, onSelect = { tab = it })
            }
            StatusLine(working = ui.working, error = ui.error)
            if (tab == 0) MinutesPane(ui) else TranscriptPane(ui.segments)
        }
    }
}

/** 分段下方一行状态：整理中… / 错误；都没有就不占位。 */
@Composable
private fun StatusLine(working: Boolean, error: UiText?) {
    val c = SceneTheme.colors
    val inset = Modifier.padding(horizontal = SceneSpacing.page + SceneSpacing.row)
    when {
        working -> SceneText(stringResource(Res.string.note_minutes_working), inset, style = SceneTheme.type.footnote, color = c.secondaryLabel)
        error != null -> SceneText(error.string(), inset, style = SceneTheme.type.footnote, color = c.destructive)
    }
}

// ---------- 整理稿 ----------

/** 整理稿卡：标题 + 元信息 → 来源胶囊 → 橙色提示 → 议题 / 结论 / 待办 / 承诺 → 时间轴要点；零 Key 时页脚提示填 Key。 */
@Composable
private fun MinutesPane(ui: NoteUiState) {
    val c = SceneTheme.colors
    val m = ui.minutes
    if (m == null) {
        if (!ui.working && ui.error == null) SceneText(stringResource(Res.string.note_no_minutes), Modifier.padding(horizontal = SceneSpacing.page + SceneSpacing.row), style = SceneTheme.type.footnote, color = c.tertiaryLabel)
        return
    }
    val cloud = ui.backend.startsWith("cloud:")
    val zeroKey = m.timeline.isNotEmpty() && m.topics.isEmpty()
    val empty = m.topics.isEmpty() && m.conclusions.isEmpty() && m.todos.isEmpty() && m.commitments.isEmpty() && m.timeline.isEmpty()

    SceneCard {
        Column(verticalArrangement = Arrangement.spacedBy(SceneSpacing.xs)) {
            SceneText(m.title.ifBlank { ui.session?.title ?: stringResource(Res.string.note_minutes_title) }, style = SceneTheme.type.title2)
            metaLine(ui.session)?.let { SceneText(it, style = SceneTheme.type.footnote, color = c.secondaryLabel) }
        }
        Row {
            if (cloud) SceneCapsule(stringResource(Res.string.note_backend_cloud), tone = CapsuleTone.Tint, icon = SceneIcons.Sparkle)
            else SceneCapsule(stringResource(Res.string.note_backend_local), tone = CapsuleTone.Gray)
        }
        if (m.flags.isNotEmpty()) WarningNotice(flagText(m.flags))
        if (empty) SceneText(stringResource(Res.string.note_nothing_to_process), style = SceneTheme.type.subheadline, color = c.tertiaryLabel)
        if (m.topics.isNotEmpty()) Section(stringResource(Res.string.md_topics)) { m.topics.forEach { Bullet(it) } }
        if (m.conclusions.isNotEmpty()) Section(stringResource(Res.string.md_conclusions)) { m.conclusions.forEach { Bullet(it) } }
        if (m.todos.isNotEmpty()) Section(stringResource(if (zeroKey) Res.string.note_todos_candidates else Res.string.md_todos)) { m.todos.forEach { TodoRow(it) } }
        if (m.commitments.isNotEmpty()) Section(stringResource(Res.string.md_commitments)) { m.commitments.forEach { Quote(it) } }
        if (m.timeline.isNotEmpty()) Section(stringResource(Res.string.md_timeline)) { m.timeline.forEach { TimelineRow(it) } }
    }
    if (zeroKey) SceneSectionFooter(stringResource(Res.string.note_fill_key_for_cloud))
}

/** 卡内小节：15 pt 半粗标题 + 内容。 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SceneText(title, style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold))
        content()
    }
}

/** 圆点条目（议题 / 结论）。 */
@Composable
private fun Bullet(text: String) {
    val c = SceneTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp).size(5.dp).clip(RoundedCornerShape(50)).background(c.secondaryLabel))
        SceneText(text, Modifier.weight(1f), style = SceneTheme.type.subheadline)
    }
}

/** 待办：复选框样式（仅展示，不可勾）+ 文本；负责人 / 期限另起一行小字。 */
@Composable
private fun TodoRow(todo: TodoItem) {
    val c = SceneTheme.colors
    val meta = listOfNotNull(todo.owner?.takeIf { it.isNotBlank() }, todo.due?.takeIf { it.isNotBlank() }).joinToString(" · ")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 1.dp).size(20.dp).border(1.5.dp, c.tertiaryLabel, RoundedCornerShape(6.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SceneText(todo.text, style = SceneTheme.type.subheadline)
            if (meta.isNotEmpty()) SceneText(meta, style = SceneTheme.type.footnote, color = c.secondaryLabel)
        }
    }
}

/** 承诺句：左侧 2 dp 青绿竖线。 */
@Composable
private fun Quote(text: String) {
    val c = SceneTheme.colors
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(c.tint))
        SceneText(text, Modifier.weight(1f), style = SceneTheme.type.subheadline, color = c.label)
    }
}

/** 时间轴要点：mm:ss + 一句 + 高亮词胶囊（放不下换行）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineRow(p: TimelinePoint) {
    val c = SceneTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        SceneText(SlowPath.mmss(p.atMs), Modifier.widthIn(min = 44.dp).padding(top = 2.dp), style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SceneText(p.text, style = SceneTheme.type.subheadline)
            if (p.highlights.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.highlights.forEach { SceneCapsule(it, tone = CapsuleTone.Tint) }
                }
            }
        }
    }
}

/** 橙色提示条：一句话，不解释原因。 */
@Composable
private fun WarningNotice(text: String) {
    val c = SceneTheme.colors
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(SceneRadius.s)).background(c.warningSoft).padding(horizontal = 10.dp, vertical = 8.dp)) {
        SceneText(text, style = SceneTheme.type.footnote, color = c.onWarningSoft)
    }
}

// ---------- 原文 ----------

/** 原文：逐段 mm:ss + 文本。 */
@Composable
private fun TranscriptPane(segments: List<Segment>) {
    val c = SceneTheme.colors
    if (segments.isEmpty()) {
        SceneText(stringResource(Res.string.note_no_transcript), Modifier.padding(horizontal = SceneSpacing.page + SceneSpacing.row), style = SceneTheme.type.footnote, color = c.tertiaryLabel)
        return
    }
    SceneCard {
        segments.forEach { s ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                SceneText(SlowPath.mmss(s.startMs), Modifier.widthIn(min = 44.dp).padding(top = 2.dp), style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1)
                SceneText(s.text, Modifier.weight(1f), style = SceneTheme.type.subheadline)
            }
        }
    }
}

// ---------- 纯函数 ----------

/** 元信息一行：「9月17日 13:10 · 32 分钟」。 */
@Composable
private fun metaLine(s: SessionRow?): String? {
    s ?: return null
    val mins = (s.durationMs / 60_000).toInt()
    val dur = if (mins < 1) stringResource(Res.string.note_under_a_minute) else stringResource(Res.string.duration_minutes, mins)
    return stringResource(Res.string.note_meta_line, formatSessionDate(s.startedAt, Clock.System.now().toEpochMilliseconds()), dur)
}

/** flags 折成一句用户语言：云端没成功 → 说明这次是本机；其余（事实核对 / 改动幅度）→ 提醒对照原文。 */
@Composable
private fun flagText(flags: List<String>): String =
    stringResource(if (flags.any { SlowPath.isCloudFailedFlag(it) }) Res.string.note_flag_cloud_failed else Res.string.note_flag_generic)
