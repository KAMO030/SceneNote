package dev.scenenote.ui.library

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scenenote.core.db.SessionKind
import dev.scenenote.core.db.SessionRepository
import dev.scenenote.core.db.SessionRow
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneSectionFooter
import dev.scenenote.core.designsystem.SceneSectionHeader
import dev.scenenote.core.designsystem.SceneSegmentedControl
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.designsystem.glass
import dev.scenenote.polish.SlowPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.Instant

/** 资料库 VM：最近会话（对话 / 纪要 / 字幕），按日分组由页面做；搜索走 LIKE。 */
class LibraryViewModel(private val repo: SessionRepository) : ViewModel() {
    val sessions: StateFlow<List<SessionRow>> = repo.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingCandidates: StateFlow<Long> = repo.pendingCandidateCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    suspend fun search(q: String): Set<String> = repo.search(q).toSet()
    fun delete(id: String) { viewModelScope.launch { repo.delete(id) } }
}

/** 分段：全部 / 对话 / 纪要 / 字幕（索引 → 种类；0 = 全部）。 */
private val SEGMENTS = listOf("全部", "对话", "纪要", "字幕")
private val SEGMENT_KINDS: List<SessionKind?> = listOf(null, SessionKind.LIVE, SessionKind.RECORD, SessionKind.SCREEN)

/** 搜索胶囊高度 50 + 与 Tab 栏的 8 dp 间隙；内容底部在 140 dp 之外再多留 56（胶囊 50 + 6）。 */
private val SearchHeight = 50.dp
private val SearchGap = 8.dp
private val SearchReserve = 56.dp
private const val SEARCH_DEBOUNCE_MS = 300L

private fun kindName(kind: SessionKind): String = when (kind) {
    SessionKind.LIVE -> "对话"
    SessionKind.RECORD -> "纪要"
    SessionKind.SCREEN -> "字幕"
}

/** 一天一组：标签（今天 / 昨天 / 具体日期）+ 该天的会话（新的在前）。 */
private data class DayGroup(val label: String, val rows: List<SessionRow>)

private fun dayLabel(date: LocalDate, today: LocalDate): String = when (today.toEpochDays() - date.toEpochDays()) {
    0L -> "今天"
    1L -> "昨天"
    else -> if (date.year == today.year) "${date.month.number}月${date.day}日" else "${date.year}年${date.month.number}月${date.day}日"
}

private fun groupByDay(rows: List<SessionRow>): List<DayGroup> {
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)
    return rows
        .sortedByDescending { it.startedAt }
        .groupBy { Instant.fromEpochMilliseconds(it.startedAt).toLocalDateTime(tz).date }
        .map { (date, list) -> DayGroup(dayLabel(date, today), list) }
}

/**
 * 资料库 Tab（原型 Library.dc.html）：大标题 + 分段 →「N 个新词待确认」青绿行 → 按日分组列表 → 页脚。
 * 搜索是悬浮在 Tab 栏上方的玻璃胶囊，输入后 300 ms 去抖再查库；分段与搜索同时过滤。
 * 行 → [onOpen]（对话卡片 / 纪要）；长按 / 删除留到 I7。
 */
@Composable
fun LibraryTab(onOpen: (sessionId: String) -> Unit = {}, vm: LibraryViewModel = koinViewModel()) {
    val c = SceneTheme.colors
    val sessions by vm.sessions.collectAsState()
    val pending by vm.pendingCandidates.collectAsState()
    var segment by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    // null = 未搜索（不过滤）；非空关键词 → 命中的会话 id
    var hits by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) { hits = null; return@LaunchedEffect }
        delay(SEARCH_DEBOUNCE_MS)
        hits = vm.search(query.trim())
    }

    val kind = SEGMENT_KINDS[segment]
    val filtered = remember(sessions, kind, hits) {
        val h = hits
        sessions.filter { (kind == null || it.kind == kind) && (h == null || it.id in h) }
    }
    val groups = remember(filtered) { groupByDay(filtered) }
    // 新词待确认：点开最近一条对话（候选来自对话卡片）；没有对话时不可点
    val latestTalk = remember(sessions) { sessions.filter { it.kind == SessionKind.LIVE }.maxByOrNull { it.startedAt } }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val imeVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = statusTop + 6.dp, bottom = 140.dp + SearchReserve),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
        ) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SceneText("资料库", style = SceneTheme.type.largeTitle)
                    SceneSegmentedControl(SEGMENTS, segment, onSelect = { segment = it })
                }
            }

            if (pending > 0) {
                item(key = "pending") {
                    SceneGroup(background = c.tintSoft) {
                        LibraryRow(
                            title = "$pending 个新词待确认",
                            titleColor = c.onTintSoft,
                            leading = { Box(Modifier.size(8.dp).background(c.tint, CircleShape)) },
                            chevron = latestTalk != null,
                            onClick = latestTalk?.let { row -> { onOpen(row.id) } },
                        )
                    }
                }
            }

            if (groups.isEmpty()) {
                item(key = "empty") {
                    SceneText(
                        if (sessions.isEmpty()) "录一次会议或聊一次，就会出现在这里" else "没有找到",
                        Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.page + SceneSpacing.row, vertical = 48.dp),
                        style = SceneTheme.type.subheadline, color = c.secondaryLabel, textAlign = TextAlign.Center,
                    )
                }
            } else {
                items(groups, key = { it.label }) { group ->
                    Column {
                        SceneSectionHeader(group.label)
                        SceneGroup {
                            group.rows.forEachIndexed { i, row ->
                                if (i > 0) SceneDivider()
                                val duration = row.durationMs.takeIf { it > 0 }?.let { "时长 ${SlowPath.mmss(it)}" }
                                LibraryRow(
                                    title = row.title?.takeIf { it.isNotBlank() } ?: kindName(row.kind),
                                    subtitle = listOfNotNull(duration, kindName(row.kind)).joinToString(" · "),
                                    chevron = true,
                                    onClick = { onOpen(row.id) },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "footer") { SceneSectionFooter("只存本机") }
        }

        // 搜索胶囊：悬浮在 Tab 栏上方；键盘弹出时贴着键盘。在内容层里，玻璃走高填充回退（不能取样自己）。
        SearchCapsule(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .padding(start = SceneSpacing.page, end = SceneSpacing.page, bottom = if (imeVisible) SearchGap else 140.dp + SearchGap)
                .fillMaxWidth(),
        )
    }
}

/** 列表行：与 [dev.scenenote.core.designsystem.SceneRow] 同尺寸，但标题 / 副标题单行省略（会话标题可能很长）。 */
@Composable
private fun LibraryRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    titleColor: androidx.compose.ui.graphics.Color = SceneTheme.colors.label,
) {
    val c = SceneTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = SceneSize.rowMinHeight)
            .padding(horizontal = SceneSpacing.row, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SceneText(title, style = SceneTheme.type.body.copy(fontWeight = FontWeight.Medium), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) SceneText(subtitle, style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (chevron) SceneIcon(SceneIcons.ChevronRight, contentDescription = null, size = 16.dp, tint = c.tertiaryLabel)
    }
}

/** 底部悬浮搜索胶囊：放大镜 + 单行输入 + 清除；回车收起键盘。 */
@Composable
private fun SearchCapsule(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val style = SceneTheme.type.callout
    Row(
        modifier
            .glass(CircleShape, backdrop = null, elevation = 8.dp)
            .height(SearchHeight)
            .padding(start = SceneSpacing.row, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SceneIcon(SceneIcons.Search, contentDescription = null, size = 18.dp, tint = c.secondaryLabel)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            textStyle = style.copy(color = c.label),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
            cursorBrush = SolidColor(c.tint),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) SceneText("搜索", style = style, color = c.secondaryLabel, maxLines = 1)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(SceneSize.glassButton)
                    .clip(CircleShape)
                    .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button) { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                SceneIcon(SceneIcons.Close, contentDescription = "清除", size = 16.dp, tint = c.secondaryLabel)
            }
        }
    }
}
