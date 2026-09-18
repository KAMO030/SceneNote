package dev.scenenote.ui.screen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scenenote.core.db.SessionRow
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.CapsuleTone
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.GlassStyle
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneCapsule
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneDock
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIconButton
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneRadius
import dev.scenenote.core.designsystem.SceneRow
import dev.scenenote.core.designsystem.SceneSectionHeader
import dev.scenenote.core.designsystem.SceneSegmentedControl
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.designsystem.glass
import dev.scenenote.core.platform.PlatformInfo
import dev.scenenote.core.platform.isAndroid
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.polish.Cue
import dev.scenenote.screen.JobPhase
import dev.scenenote.screen.MediaSource
import dev.scenenote.screen.PlayerController
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.Instant
import dev.scenenote.core.i18n.UiText
import dev.scenenote.core.i18n.string
import dev.scenenote.shared.resources.*
import dev.scenenote.ui.i18n.dayLabel
import dev.scenenote.core.i18n.stringResource

// ============================================================================================
// 屏内字幕（原型 ScreenIntake / ScreenS4）：入口页 → 播放器页。两页共用一个 ScreenViewModel。
// 文案按 docs/15：不出现 S4 / 端侧 / 快路径等工程词；每卡 ≤ 1 行说明；只显示当前平台的一行。
// ============================================================================================

/**
 * 入口（原型 ScreenIntake.dc.html）：玻璃导航「视频字幕」→ 剪贴板直链提示 → 虚线「选择视频」卡（相册 / 文件）
 * → 粘贴链接行 → 已处理列表 → 当前平台的一行诚实说明。
 * [onOpenPlayer]：用户明确要打开某条已处理记录时调用（本流程内 [ScreenFlowScreen] 用它解除「已关闭」标记）。
 */
@Composable
fun ScreenIntakeScreen(onBack: () -> Unit, onOpenPlayer: () -> Unit, onSystemCaption: () -> Unit = {}, vm: ScreenViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsState()
    val c = SceneTheme.colors
    val scroll = rememberScrollState()

    // 剪贴板只在进入时读一次（iOS 每次读都会弹系统「已粘贴」横幅）
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val clipUrl = remember { runCatching { clipboard.getText()?.text?.trim() }.getOrNull()?.takeIf { looksLikeVideoUrl(it) } }
    var clipDismissed by rememberSaveable { mutableStateOf(false) }
    var link by rememberSaveable { mutableStateOf("") }
    val busy = ui.downloading != null

    GlassScaffold(
        background = c.groupedBackground,
        topBar = { SceneNavBar(title = stringResource(Res.string.scene_screen_file), onBack = onBack) },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).navigationBarsPadding().padding(top = 104.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
        ) {
            if (clipUrl != null && !clipDismissed && !busy) {
                ClipboardLinkCard(url = clipUrl, onStart = { clipDismissed = true; vm.openUrl(clipUrl) }, onDismiss = { clipDismissed = true })
            }

            PickVideoCard(enabled = !busy, onGallery = { vm.pick(MediaSource.GALLERY) }, onFile = { vm.pick(MediaSource.FILE) })

            LinkRow(
                value = link, onValueChange = { link = it }, enabled = !busy,
                onStart = { val u = link.trim(); if (u.isNotEmpty()) { vm.openUrl(u); link = "" } },
            )

            ui.downloading?.let { p ->
                Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProgressTrack(fractions = listOf(p.coerceIn(0f, 1f) to c.tint))
                    SceneText(stringResource(Res.string.screen_downloading_pct, (p * 100).toInt()), style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1)
                }
            }
            ui.error?.let { SceneText(it.string(), Modifier.padding(horizontal = SceneSpacing.page), style = SceneTheme.type.footnote, color = c.destructive, maxLines = 2, overflow = TextOverflow.Ellipsis) }

            if (ui.recent.isNotEmpty()) {
                Column {
                    SceneSectionHeader(stringResource(Res.string.screen_processed))
                    SceneGroup {
                        ui.recent.forEachIndexed { i, row ->
                            if (i > 0) SceneDivider()
                            RecentRow(row, onClick = { onOpenPlayer(); vm.reopen(row.id) })
                        }
                    }
                }
            }

            // 平台诚实清单：只显示当前平台的一行（docs/15 §1 ③）
            Column {
                SceneSectionHeader(stringResource(Res.string.screen_other_entries))
                SceneGroup {
                    if (PlatformInfo.isAndroid) SceneRow(title = stringResource(Res.string.screen_syscap_row), subtitle = stringResource(Res.string.screen_syscap_row_sub), chevron = true, onClick = onSystemCaption)
                    else SceneRow(title = stringResource(Res.string.screen_other_app_video), subtitle = stringResource(Res.string.screen_other_app_video_sub))
                }
            }
        }
    }
}

/** 青绿卡：检测到剪贴板里的视频直链 → 「开始」/「忽略」。 */
@Composable
private fun ClipboardLinkCard(url: String, onStart: () -> Unit, onDismiss: () -> Unit) {
    val c = SceneTheme.colors
    Column(
        Modifier.padding(horizontal = SceneSpacing.page).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.tintSoft).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(SceneSpacing.s),
    ) {
        SceneText(stringResource(Res.string.screen_link_detected), style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.onTintSoft, maxLines = 1)
        SceneText(url, style = SceneTheme.type.footnote, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneButton(stringResource(Res.string.live_start), onClick = onStart, modifier = Modifier.weight(1f), style = ButtonStyle.Prominent, height = 40.dp)
            SceneButton(stringResource(Res.string.note_ignore), onClick = onDismiss, modifier = Modifier.width(88.dp), style = ButtonStyle.Gray, height = 40.dp)
        }
    }
}

/** 虚线大卡「选择视频」：相册（主钮）/ 文件。 */
@Composable
private fun PickVideoCard(enabled: Boolean, onGallery: () -> Unit, onFile: () -> Unit) {
    val c = SceneTheme.colors
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.padding(horizontal = SceneSpacing.page).fillMaxWidth().clip(shape).background(c.secondaryGroupedBackground).dashedOutline(shape, c.separator).padding(horizontal = 18.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(c.tintSoft), contentAlignment = Alignment.Center) {
            SceneIcon(SceneIcons.Play, contentDescription = null, size = 28.dp, tint = c.onTintSoft)
        }
        SceneText(stringResource(Res.string.live_pick_video), style = SceneTheme.type.headline, color = c.label)
        SceneText(stringResource(Res.string.screen_pick_hint), style = SceneTheme.type.footnote, color = c.secondaryLabel, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneButton(stringResource(Res.string.screen_gallery), onClick = onGallery, modifier = Modifier.weight(1f), style = ButtonStyle.Prominent, enabled = enabled)
            SceneButton(stringResource(Res.string.screen_file), onClick = onFile, modifier = Modifier.weight(1f), style = ButtonStyle.Gray, enabled = enabled)
        }
    }
}

/** 「粘贴链接」行：输入框 + 「开始」。 */
@Composable
private fun LinkRow(value: String, onValueChange: (String) -> Unit, enabled: Boolean, onStart: () -> Unit) {
    val c = SceneTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val style = SceneTheme.type.subheadline
    val go = { keyboard?.hide(); focus.clearFocus(); onStart() }
    SceneGroup {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = SceneSize.rowMinHeight).padding(start = SceneSpacing.row, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value, onValueChange = onValueChange, modifier = Modifier.weight(1f), enabled = enabled,
                textStyle = style.copy(color = c.label), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                cursorBrush = SolidColor(c.tint),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) SceneText(stringResource(Res.string.screen_paste_link), style = style, color = c.secondaryLabel, maxLines = 1)
                        inner()
                    }
                },
            )
            SceneButton(stringResource(Res.string.live_start), onClick = go, style = ButtonStyle.Tinted, enabled = enabled && value.isNotBlank(), height = 36.dp)
        }
    }
}

/** 已处理的一条：标题 + 时间；点开即播。 */
@Composable
private fun RecentRow(row: SessionRow, onClick: () -> Unit) {
    val c = SceneTheme.colors
    SceneRow(
        title = row.title?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.screen_video),
        subtitle = whenLabel(row.startedAt),
        leading = { Box(Modifier.width(56.dp).height(36.dp).clip(RoundedCornerShape(8.dp)).background(c.fill)) },
        chevron = true,
        onClick = onClick,
    )
}

// ============================================================================================

/**
 * 播放器（原型 ScreenS4.dc.html）：常驻深色。画面（16:9）+ 画面底部字幕条 + 中央玻璃播放键 → 双进度条（播放 / 转写）
 * → 控制行（译文 / 原文 · 阶段词）→ cue 列表（当前句青绿底，点击跳转）→ 等待遮罩；dock：导出 SRT / VTT / 关闭。
 */
@Composable
fun ScreenPlayerScreen(onBack: () -> Unit, vm: ScreenViewModel = koinViewModel()) {
    SceneTheme(dark = true) {
        val ui by vm.ui.collectAsState()
        val c = SceneTheme.colors
        val settings = koinInject<AppSettings>()
        val player = vm.player
        val playing by (player?.playing?.collectAsState() ?: remember { mutableStateOf(false) })
        val position by (player?.positionMs?.collectAsState() ?: remember { mutableStateOf(0L) })
        val playerDuration by (player?.durationMs?.collectAsState() ?: remember { mutableStateOf(0L) })
        val job = ui.job
        val duration = when {
            playerDuration > 0 -> playerDuration
            (job.media?.durationMs ?: -1) > 0 -> job.media!!.durationMs
            else -> job.cues.lastOrNull()?.endMs ?: 0L
        }
        val exportable = job.sessionId != null && job.cues.isNotEmpty()
        val showTr = ui.showTranslation && job.translate

        // 一次性提示：首次进入播放器显示，本次停留期间保持
        val firstVisit = remember { !settings.hintSeen(HINT_S4) }
        LaunchedEffect(Unit) { if (firstVisit) settings.markHintSeen(HINT_S4) }

        GlassScaffold(
            background = c.systemBackground,
            topBar = { SceneNavBar(title = shortTitle(job.media?.name, stringResource(Res.string.scene_screen_file)), onBack = onBack, backContentDescription = stringResource(Res.string.common_close)) },
            bottomBar = {
                SceneDock {
                    SceneButton(stringResource(Res.string.screen_export_srt), onClick = { vm.exportSrt(false) }, modifier = Modifier.weight(1f), style = ButtonStyle.Prominent, enabled = exportable, icon = SceneIcons.Share, height = 56.dp)
                    SceneButton(onClick = { vm.exportSrt(true) }, modifier = Modifier.size(56.dp), style = ButtonStyle.Gray, enabled = exportable, height = 56.dp, contentPadding = 0.dp) {
                        SceneText("VTT", style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                    }
                    SceneIconButton(SceneIcons.Close, contentDescription = stringResource(Res.string.common_close), onClick = onBack, size = 56.dp)
                }
            },
        ) {
            Column(Modifier.fillMaxSize().padding(top = 104.dp)) {
                VideoArea(vm = vm, player = player, playing = playing, cue = ui.currentCue, showTranslation = showTr, waiting = ui.waiting, phase = job.phase)

                Column(Modifier.padding(horizontal = SceneSpacing.page, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val playFrac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    val doneFrac = when {
                        job.phase == JobPhase.DONE -> 1f
                        duration > 0 -> (job.transcribedMs.toFloat() / duration).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    ProgressTrack(
                        fractions = listOf(doneFrac to c.tint.copy(alpha = 0.4f), playFrac to c.tint),
                        onSeek = if (player != null && duration > 0) { f -> player.seekTo((f * duration).toLong()) } else null,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SceneText(mmss(position), style = SceneTheme.type.caption2, color = c.secondaryLabel, maxLines = 1)
                        SceneText(mmss(duration), style = SceneTheme.type.caption2, color = c.secondaryLabel, maxLines = 1)
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(start = SceneSpacing.page, end = SceneSpacing.page, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (job.translate) SceneSegmentedControl(
                        options = listOf(stringResource(Res.string.screen_seg_translation), stringResource(Res.string.screen_seg_original)),
                        selectedIndex = if (showTr) 0 else 1,
                        onSelect = { i -> if ((i == 0) != ui.showTranslation) vm.toggleTranslation() },
                        modifier = Modifier.weight(1f),
                    ) else Spacer(Modifier.weight(1f))
                    phaseWord(job.phase, job.progress)?.let { (label, tone) -> SceneCapsule(label.string(), tone = tone) }
                }

                val err = job.error ?: ui.error
                if (err != null) SceneText(err.string(), Modifier.padding(horizontal = SceneSpacing.page, vertical = 4.dp), style = SceneTheme.type.footnote, color = c.destructive, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (firstVisit) HintBubble(stringResource(Res.string.screen_hint_autopause))

                CueList(
                    cues = job.cues, partial = job.partial, current = ui.currentCue, showTranslation = showTr, phase = job.phase,
                    onSeek = { ms -> player?.seekTo(ms) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private const val HINT_S4 = "s4"

/** 画面区：平台播放视图（16:9）+ 中央玻璃播放键 + 底部字幕条 + 等待遮罩。player 为空（重开的记录）时显示占位。 */
@Composable
private fun VideoArea(vm: ScreenViewModel, player: PlayerController?, playing: Boolean, cue: Cue?, showTranslation: Boolean, waiting: Boolean, phase: JobPhase) {
    val c = SceneTheme.colors
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(c.secondarySystemBackground)) {
        if (player != null) {
            vm.playerFactory.View(player, Modifier.matchParentSize())
        } else {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                SceneText(stringResource(if (phase == JobPhase.DONE) Res.string.screen_no_video_captions_only else Res.string.screen_preparing), style = SceneTheme.type.subheadline, color = c.secondaryLabel)
            }
        }

        if (player != null) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier.align(Alignment.Center)
                    .glass(CircleShape, style = GlassStyle.Clear, backdrop = null, elevation = 8.dp)
                    .size(60.dp)
                    .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button) { if (playing) vm.userPause() else vm.userPlay() },
                contentAlignment = Alignment.Center,
            ) {
                SceneIcon(if (playing) SceneIcons.Pause else SceneIcons.Play, contentDescription = stringResource(if (playing) Res.string.live_pause else Res.string.screen_play), size = 26.dp, tint = Color.White)
            }
        }

        if (cue != null) {
            val big = if (showTranslation) (cue.translation ?: cue.text) else cue.text
            val small = if (showTranslation && cue.translation != null) cue.text else null
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x9E000000)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (small != null) SceneText(small, style = SceneTheme.type.caption1, color = Color(0xFFC7C7CC), maxLines = 2, overflow = TextOverflow.Ellipsis)
                SceneText(big, style = SceneTheme.type.title3.copy(fontSize = 20.sp, lineHeight = 26.sp), color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }

        if (waiting) {
            Column(
                Modifier.matchParentSize().background(Color(0x99000000)),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                PulseDot()
                SceneText(stringResource(Res.string.screen_captions_catching_up), style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
            }
        }
    }
}

/** 叠层进度条：从后往前依次画 [fractions]（先画的在底）。给 [onSeek] 时可点按跳转。 */
@Composable
private fun ProgressTrack(fractions: List<Pair<Float, Color>>, onSeek: ((Float) -> Unit)? = null) {
    val c = SceneTheme.colors
    Box(
        Modifier.fillMaxWidth().height(if (onSeek != null) 20.dp else 6.dp)
            .then(if (onSeek != null) Modifier.pointerInput(onSeek) { detectTapGestures { p -> onSeek((p.x / size.width).coerceIn(0f, 1f)) } } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.fill)) {
            fractions.forEach { (f, color) ->
                if (f > 0f) Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
            }
        }
    }
}

/** 阶段词 + 百分比；空闲不显示。 */
private fun phaseWord(phase: JobPhase, progress: Float): Pair<UiText, CapsuleTone>? {
    val pct = " ${(progress * 100).toInt().coerceIn(0, 100)}%"
    return when (phase) {
        JobPhase.IDLE -> null
        JobPhase.DOWNLOADING -> UiText.res(Res.string.screen_phase_downloading, pct) to CapsuleTone.Gray
        JobPhase.EXTRACTING -> UiText.res(Res.string.screen_phase_extracting, pct) to CapsuleTone.Gray
        JobPhase.TRANSCRIBING -> UiText.res(Res.string.screen_phase_transcribing, pct) to CapsuleTone.Gray
        JobPhase.TRANSLATING -> UiText.res(Res.string.screen_phase_translating) to CapsuleTone.Gray
        JobPhase.DONE -> UiText.res(Res.string.screen_phase_done) to CapsuleTone.Tint
        JobPhase.FAILED -> UiText.res(Res.string.screen_phase_failed) to CapsuleTone.Destructive
    }
}

/** 首次进入的一行青绿提示。 */
@Composable
private fun HintBubble(text: String) {
    val c = SceneTheme.colors
    Row(
        Modifier.padding(horizontal = SceneSpacing.page, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(SceneRadius.m)).background(c.tintSoft).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        SceneIcon(SceneIcons.Pause, contentDescription = null, size = 18.dp, tint = c.onTintSoft)
        SceneText(text, style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.onTintSoft, maxLines = 1)
    }
}

/** cue 列表：时间 + 译文 + 原文；当前句青绿底；点击跳到该句。正在识别的半句灰字挂在末尾。 */
@Composable
private fun CueList(cues: List<Cue>, partial: String, current: Cue?, showTranslation: Boolean, phase: JobPhase, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    val state = rememberLazyListState()
    val currentIndex = current?.index
    // 当前句变化时跟随滚动；用户正在拖动列表时不打断
    LaunchedEffect(currentIndex) {
        if (currentIndex == null || state.isScrollInProgress) return@LaunchedEffect
        val i = (currentIndex - 1).coerceIn(0, (cues.size - 1).coerceAtLeast(0))
        state.animateScrollToItem(i)
    }
    val hasPartial = partial.isNotBlank()
    val total = cues.size + (if (hasPartial) 1 else 0)

    if (total == 0) {
        Box(modifier.fillMaxWidth().padding(horizontal = SceneSpacing.page, vertical = 24.dp), contentAlignment = Alignment.TopCenter) {
            SceneText(
                stringResource(when (phase) { JobPhase.FAILED -> Res.string.screen_no_captions; JobPhase.DONE -> Res.string.screen_no_speech; else -> Res.string.screen_captions_here }),
                style = SceneTheme.type.subheadline, color = c.tertiaryLabel, textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(modifier.fillMaxWidth(), state = state, contentPadding = PaddingValues(start = SceneSpacing.page, end = SceneSpacing.page, top = 4.dp, bottom = 140.dp)) {
        itemsIndexed(cues, key = { _, cue -> cue.index }) { i, cue ->
            val isCurrent = cue.index == currentIndex
            val shape = groupShape(i, total)
            val interaction = remember { MutableInteractionSource() }
            Column {
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(if (isCurrent) c.tintSoft else c.secondaryGroupedBackground)
                        .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button) { onSeek(cue.startMs) }
                        .padding(horizontal = SceneSpacing.row, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
                ) {
                    SceneText(mmss(cue.startMs), Modifier.width(44.dp), style = SceneTheme.type.caption1, color = if (isCurrent) c.onTintSoft else c.secondaryLabel, maxLines = 1)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val big = if (showTranslation) (cue.translation ?: cue.text) else cue.text
                        val small = if (showTranslation && cue.translation != null) cue.text else null
                        SceneText(big, style = SceneTheme.type.subheadline.copy(fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal), color = c.label)
                        if (small != null) SceneText(small, style = SceneTheme.type.caption1, color = c.secondaryLabel)
                    }
                }
                if (i < total - 1) SceneDivider()
            }
        }
        if (hasPartial) {
            item(key = "partial") {
                Row(
                    Modifier.fillMaxWidth().clip(groupShape(total - 1, total)).background(c.secondaryGroupedBackground).padding(horizontal = SceneSpacing.row, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
                ) {
                    Spacer(Modifier.width(44.dp))
                    SceneText(partial, Modifier.weight(1f), style = SceneTheme.type.subheadline, color = c.tertiaryLabel)
                }
            }
        }
    }
}

/** inset grouped 列表在 LazyColumn 里的圆角：首行圆上角、末行圆下角、单行全圆。 */
private fun groupShape(i: Int, total: Int): Shape {
    val r = 18.dp
    return when {
        total == 1 -> RoundedCornerShape(r)
        i == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        i == total - 1 -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RectangleShape
    }
}

/** 等待遮罩里的青绿脉冲点（Reduce Motion 时静止）。 */
@Composable
private fun PulseDot() {
    val c = SceneTheme.colors
    val halo = if (!SceneTheme.motion.reduced) {
        rememberInfiniteTransition(label = "wait")
            .animateFloat(0.55f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "halo").value
    } else 1f
    Box(
        Modifier.size(24.dp).drawBehind {
            drawCircle(c.tint.copy(alpha = 0.25f), radius = 12.dp.toPx() * halo)
            drawCircle(c.tint, radius = 6.dp.toPx())
        },
    )
}

// ============================================================================================

/**
 * 屏内流程：同一个 VM，未选视频 → 入口页；已开始 → 播放器页（避免两条路由各自一个 VM）。
 * 关闭播放器 = `vm.cancel()` + 记住这条会话已关闭（cancel 不清 media / cues），再回入口；新的会话或从「已处理」重开会再进播放器。
 * 直链下载中留在入口页显示进度，下载完成（media 就位）才进播放器。
 */
@Composable
fun ScreenFlowScreen(onBack: () -> Unit, reopenSessionId: String = "", sharedPath: String = "", onSystemCaption: () -> Unit = {}, vm: ScreenViewModel = koinViewModel()) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(reopenSessionId) { if (reopenSessionId.isNotBlank()) vm.reopen(reopenSessionId) }
    LaunchedEffect(sharedPath) { if (sharedPath.isNotBlank()) vm.openShared(sharedPath, sharedPath.substringAfterLast('/')) }
    var closedKey by remember { mutableStateOf<String?>(null) }
    val key = ui.job.sessionId ?: ui.job.media?.path
    val hasContent = ui.job.media != null || ui.job.cues.isNotEmpty()
    val inPlayer = hasContent && key != closedKey
    if (inPlayer) {
        ScreenPlayerScreen(onBack = { closedKey = key; vm.cancel() }, vm = vm)
    } else {
        ScreenIntakeScreen(onBack = onBack, onOpenPlayer = { closedKey = null }, onSystemCaption = onSystemCaption, vm = vm)
    }
}

// ============================================================================================

/** http(s) 且以 .mp4 / .m4v / .mov 结尾（忽略 query）或路径含 /video 才算视频直链。 */
private fun looksLikeVideoUrl(s: String): Boolean {
    if (s.length > 2048 || s.any { it.isWhitespace() }) return false
    val lower = s.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val path = lower.substringBefore('?').substringBefore('#')
    return path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") || path.contains("/video")
}

private fun mmss(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return "${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
}

/** 导航胶囊里的标题：文件名去扩展名，过长截断。 */
private fun shortTitle(name: String?, fallback: String): String {
    val base = name?.substringBeforeLast('.')?.trim().orEmpty().ifBlank { fallback }
    return if (base.length > 16) base.take(15) + "…" else base
}

/** 已处理列表的时间：今天 / 昨天 + 时分，更早只给日期。 */
@Composable
private fun whenLabel(epochMs: Long): String {
    val tz = TimeZone.currentSystemDefault()
    val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz)
    val today = Clock.System.todayIn(tz)
    val hm = "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
    val diff = today.toEpochDays() - dt.date.toEpochDays()
    return if (diff in 0L..1L) "${dayLabel(dt.date, today)} $hm" else dayLabel(dt.date, today)
}

/** 虚线描边：沿圆角轮廓画，内缩半个线宽避免被 clip 吃掉。 */
private fun Modifier.dashedOutline(shape: Shape, color: Color): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    inset(stroke / 2) {
        drawOutline(shape.createOutline(size, layoutDirection, this), color, style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))))
    }
}
