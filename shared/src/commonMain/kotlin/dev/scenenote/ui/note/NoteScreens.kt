package dev.scenenote.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scenenote.core.db.GlossaryCandidate
import dev.scenenote.core.db.SessionKind
import dev.scenenote.core.db.SessionRepository
import dev.scenenote.core.db.SessionRow
import dev.scenenote.core.db.StoredUtterance
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneGlassCapsuleButton
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.model.Segment
import dev.scenenote.core.model.Style
import dev.scenenote.core.platform.PngEncoder
import dev.scenenote.core.platform.Sharer
import dev.scenenote.polish.ConversationCard
import dev.scenenote.polish.Exports
import dev.scenenote.polish.MeetingMinutes
import dev.scenenote.polish.SlowPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel

data class NoteUiState(
    val session: SessionRow? = null,
    val segments: List<Segment> = emptyList(),
    val utterances: List<StoredUtterance> = emptyList(),
    val minutes: MeetingMinutes? = null,
    val card: ConversationCard? = null,
    val markdown: String = "",
    val backend: String = "",
    val candidates: List<GlossaryCandidate> = emptyList(),
    val working: Boolean = false,
    val error: String? = null,
    val exportedPath: String? = null,
)

/** 纪要 / 对话卡片 VM：载入会话 → 跑慢路径（有缓存直接用）→ 导出 / 分享 / 新词候选确认。 */
class NoteViewModel(private val repo: SessionRepository, private val slow: SlowPath, private val exports: Exports, private val sharer: Sharer, private val glossary: dev.scenenote.core.db.GlossaryRepository) : ViewModel() {
    private val _ui = MutableStateFlow(NoteUiState())
    val ui: StateFlow<NoteUiState> = _ui.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    fun load(sessionId: String, style: Style = Style.BUSINESS, force: Boolean = false) {
        viewModelScope.launch {
            val s = repo.byId(sessionId) ?: run { _ui.value = NoteUiState(error = "找不到这条记录"); return@launch }
            _ui.value = NoteUiState(session = s, working = true)   // 换会话 / 重跑时清掉旧产物
            runCatching {
                when (s.kind) {
                    SessionKind.RECORD, SessionKind.SCREEN -> {
                        val segs = repo.segments(sessionId)
                        val out = slow.minutes(s, style, force)
                        _ui.value = _ui.value.copy(segments = segs, minutes = json.decodeFromString<MeetingMinutes>(out.json), markdown = out.markdown, backend = out.backend, working = false)
                    }
                    SessionKind.LIVE -> {
                        val utts = repo.utterances(sessionId)
                        val out = slow.card(s, force)
                        _ui.value = _ui.value.copy(utterances = utts, card = json.decodeFromString<ConversationCard>(out.json), markdown = out.markdown, backend = out.backend, candidates = repo.candidates(sessionId), working = false)
                    }
                }
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; _ui.value = _ui.value.copy(working = false, error = "整理失败") }
        }
    }
    /** 有 Key 后重新成稿 / 换风格（追加新版本，不覆盖原文）。 */
    fun regenerate(style: Style = Style.BUSINESS) { _ui.value.session?.let { load(it.id, style, force = true) } }

    fun shareMarkdown() { val s = _ui.value.session ?: return; viewModelScope.launch { val p = exports.markdown(s, _ui.value.markdown); sharer.shareFile(p, "text/markdown", s.title ?: "场记"); _ui.value = _ui.value.copy(exportedPath = p) } }
    fun shareText() { val s = _ui.value.session ?: return; sharer.shareText(_ui.value.markdown, s.title ?: "场记") }
    fun exportVoxnote() { val s = _ui.value.session ?: return; viewModelScope.launch { val p = exports.voxnote(s); sharer.shareFile(p, "application/json", s.title ?: "场记"); _ui.value = _ui.value.copy(exportedPath = p) } }
    fun sharePng(bytes: ByteArray) { val s = _ui.value.session ?: return; viewModelScope.launch { val p = exports.bytes("${s.title ?: "card"}.png", bytes); sharer.shareFile(p, "image/png", s.title ?: "场记") } }
    /** 新词候选：确认 → 进术语表（词袋按场景；译名挂在我的语言下），忽略 → 只改状态。 */
    fun setCandidate(term: String, status: String) {
        val s = _ui.value.session ?: return
        viewModelScope.launch {
            repo.setCandidateStatus(s.id, term, status)
            if (status == "accepted") {
                val c = _ui.value.candidates.firstOrNull { it.term == term }
                val bucket = dev.scenenote.core.model.Scenes.byId(s.sceneId)?.hotwordBucket ?: "general"
                glossary.upsert(term, bucket, mapOf((s.myLang ?: "zh-CN") to (c?.translation ?: "")))
            }
            _ui.value = _ui.value.copy(candidates = repo.candidates(s.id))
        }
    }
}

/** 纪要（MeetingResult.dc.html）：整理稿 ⇄ 原文；有 Key 是模板成稿，零 Key 是时间轴要点。子组件在 MeetingResultParts.kt。 */
@Composable
fun MeetingResultScreen(sessionId: String, onBack: () -> Unit, vm: NoteViewModel = koinViewModel()) {
    LaunchedEffect(sessionId) { vm.load(sessionId) }
    val ui by vm.ui.collectAsState()
    MeetingResultContent(
        ui = ui,
        onBack = onBack,
        onRegenerate = { vm.regenerate() },
        onShare = { vm.shareMarkdown() },
        onExport = { vm.exportVoxnote() },
    )
}

/**
 * 对话卡片（LiveEnd）：聊完自动沉淀。
 * 玻璃导航（资料库 / 「对话卡片」/ 完成）→ 一张可截图分享的卡片（标题 + 元信息 + 双语对照 + 要点）→ 新词候选 → 页脚一句；
 * dock：分享文字（圆）/ 分享卡片（PNG，主钮）/ 导出（圆）。页面上不出现工程数据，见 docs/15。
 */
@Composable
fun LiveEndScreen(sessionId: String, onBack: () -> Unit, vm: NoteViewModel = koinViewModel()) {
    LaunchedEffect(sessionId) { vm.load(sessionId) }
    val ui by vm.ui.collectAsState()
    val c = SceneTheme.colors
    val koin = getKoin()
    val encoder = remember(koin) { koin.getOrNull<PngEncoder>() }   // 平台没接 PNG 编码时按钮仍可点，只提示分享失败
    val scope = rememberCoroutineScope()
    val cardLayer = rememberGraphicsLayer()
    var shareError by remember { mutableStateOf<String?>(null) }
    val session = ui.session
    val cardReady = session != null && !ui.working && ui.error == null
    val shareCard: () -> Unit = {
        shareError = null
        scope.launch {
            runCatching {
                val enc = encoder ?: error("png encoder missing")
                val bitmap = cardLayer.toImageBitmap()
                val bytes = withContext(Dispatchers.Default) { enc.encode(bitmap) }
                vm.sharePng(bytes)
            }.onFailure { shareError = "分享失败，请重试" }
        }
    }
    // VM 的错误可能带异常原文；只有「找不到这条记录」是给用户看的，其余统一成一句
    val loadError = ui.error?.let { if (it == "找不到这条记录") it else "整理失败" }
    val pending = ui.candidates.filter { it.status == "pending" }

    GlassScaffold(
        background = c.groupedBackground,
        topBar = {
            SceneNavBar(
                title = "对话卡片",
                leading = { SceneGlassCapsuleButton("资料库", onClick = onBack, icon = SceneIcons.ChevronLeft) },
                trailing = {
                    SceneGlassCapsuleButton("重新整理", onClick = { if (!ui.working) vm.regenerate() })
                    SceneGlassCapsuleButton("完成", onClick = onBack, prominentText = true)
                },
            )
        },
        bottomBar = {
            LiveEndDock(canShareCard = cardReady, onShareCard = shareCard, onShareText = vm::shareText, onExport = vm::exportVoxnote)
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 104.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
        ) {
            NoteStatusLine(working = ui.working, error = shareError ?: loadError, onRetry = if (session != null && ui.error != null) ({ vm.regenerate() }) else null)

            // ---- 卡片：整块录进 graphicsLayer，「分享卡片」时截成 PNG ----
            Box(Modifier.padding(horizontal = SceneSpacing.page)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            cardLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(cardLayer)
                        }
                        .clip(RoundedCornerShape(22.dp))
                        .background(c.secondaryGroupedBackground)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CardHeader(
                        title = ui.card?.title?.takeIf { it.isNotBlank() } ?: session?.title?.takeIf { it.isNotBlank() } ?: "对话",
                        meta = session?.let { sessionMeta(it, ui.utterances) } ?: "",
                    )
                    if (ui.utterances.isNotEmpty()) {
                        SceneDivider(inset = 0.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { ui.utterances.forEach { UtteranceLine(it) } }
                    } else if (cardReady) {
                        SceneText("没有记下对话", style = SceneTheme.type.subheadline, color = c.tertiaryLabel)
                    }
                    val points = ui.card?.keyPoints.orEmpty()
                    if (points.isNotEmpty()) KeyPointsBlock(points, cloud = ui.card?.backend.orEmpty().startsWith("cloud:"))
                }
            }

            CandidatesSection(pending, onAccept = { vm.setCandidate(it, "accepted") }, onReject = { vm.setCandidate(it, "rejected") })

            NoteFooter("对方的声音没有保存")
        }
    }
}
