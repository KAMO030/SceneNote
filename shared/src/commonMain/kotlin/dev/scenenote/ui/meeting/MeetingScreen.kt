package dev.scenenote.ui.meeting

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.CapsuleTone
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneCapsule
import dev.scenenote.core.designsystem.SceneDock
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIconButton
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.model.Scenes
import dev.scenenote.core.model.Segment
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.meeting.MeetingRecorder
import dev.scenenote.meeting.MeetingState
import dev.scenenote.meeting.RecState
import dev.scenenote.polish.SlowPath
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import dev.scenenote.meeting.Reminders
import dev.scenenote.shared.resources.*
import dev.scenenote.ui.settings.privacyLabel
import org.jetbrains.compose.resources.StringResource
import dev.scenenote.core.i18n.stringResource

/** 会议录音 VM：一按即录；停止后回调 sessionId 进纪要页，并排两条本地提醒（10 分钟「纪要好了」/ 3 天「还没分享」）。 */
class MeetingViewModel(private val recorder: MeetingRecorder, private val settings: AppSettings, private val notifier: dev.scenenote.core.platform.Notifier) : ViewModel() {
    val state: StateFlow<MeetingState> = recorder.state
    fun start(sceneId: String = Scenes.meeting.id) { viewModelScope.launch { runCatching { recorder.start(sceneId, settings.myLang) } } }
    fun pause() { viewModelScope.launch { recorder.pause() } }
    fun resume() { viewModelScope.launch { recorder.resume() } }
    fun mark() { viewModelScope.launch { recorder.mark() } }
    fun stop(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val id = recorder.stop()
            if (id.isNotBlank()) { onDone(id); runCatching { Reminders.afterMeeting(notifier, id) } }
        }
    }
    override fun onCleared() { if (state.value.state != RecState.IDLE) recorder.release() }
}

/**
 * 会议录音中（原型 Meeting.dc.html）：一按即录。
 * 玻璃导航（返回 / 「会议」）→ 录音点 + 46 pt 计时 + 波形 + 两枚胶囊 → 流式草稿（时间戳 + 段落，末尾灰字是还没定稿的一句）；
 * dock：标记要点（黄圆，带已标记数）/ 暂停·继续 72 pt 主钮 / 停止成稿（红圆）。
 * 页面上不出现工程数据（引擎 / 路由 / 耗时），见 docs/15。
 */
@Composable
fun MeetingScreen(onBack: () -> Unit, onDone: (sessionId: String) -> Unit, autostart: Boolean = true, vm: MeetingViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    val c = SceneTheme.colors
    val inSession = s.state == RecState.RECORDING || s.state == RecState.PAUSED

    // 自动开始只触发一次：录音器是单例，停止后 state 回到 IDLE 但会保留上一场的 sessionId，
    // 所以"只触发一次"的记号放在本页（rememberSaveable，旋转 / 重建不重复触发），条件只看 IDLE。
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autostart && !autoStarted && vm.state.value.state == RecState.IDLE) { autoStarted = true; vm.start() }
    }

    // 返回：录音中 / 已暂停先停止成稿再走；未开始直接返回；准备中 / 整理中是过渡态，等它结束
    val leave: () -> Unit = {
        when (s.state) {
            RecState.RECORDING, RecState.PAUSED -> vm.stop(onDone)
            RecState.IDLE -> onBack()
            RecState.PREPARING, RecState.FINISHING -> Unit
        }
    }

    GlassScaffold(
        background = c.systemBackground,
        topBar = { SceneNavBar(title = stringResource(Res.string.scene_meeting), onBack = leave) },
        bottomBar = {
            SceneDock {
                MarkButton(count = s.bookmarks, enabled = inSession, onClick = vm::mark)
                MainButton(state = s.state, onStart = { vm.start() }, onPause = vm::pause, onResume = vm::resume)
                SceneIconButton(SceneIcons.Stop, contentDescription = stringResource(Res.string.meeting_stop_finish), onClick = { vm.stop(onDone) }, size = 56.dp, style = ButtonStyle.Destructive, enabled = inSession)
            }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(top = 104.dp)) {
            Header(s)
            DraftList(s, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

// ---------- 头部：状态词 + 计时 + 波形 + 胶囊 ----------

@Composable
private fun Header(s: MeetingState) {
    val c = SceneTheme.colors
    val recording = s.state == RecState.RECORDING
    Column(
        Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.page).padding(top = 6.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            RecDot(active = recording)
            SceneText(stringResource(stateWord(s.state)), style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1)
        }
        val clock = SlowPath.mmss(s.elapsedMs)
        val clockDesc = stringResource(Res.string.meeting_recorded_cd, clock)
        SceneText(
            clock,
            Modifier.semantics { contentDescription = clockDesc },
            style = SceneTheme.type.largeTitle.copy(fontSize = 46.sp, lineHeight = 54.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, fontFeatureSettings = "tnum"),
            color = c.label, maxLines = 1,
        )
        Waveform(rmsDb = s.rmsDb, active = recording)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SceneCapsule(stringResource(Res.string.home_audio_stays_local), tone = CapsuleTone.Tint, icon = SceneIcons.Lock)
            SceneCapsule(privacyLabel(org.koin.compose.koinInject<AppSettings>().privacy.collectAsState().value), tone = CapsuleTone.Gray)
        }
    }
}

/** 录音点：录音中红点 + 呼吸光晕（Reduce Motion 时静止）；其余状态灰点。 */
@Composable
private fun RecDot(active: Boolean) {
    val c = SceneTheme.colors
    val halo = if (active && !SceneTheme.motion.reduced) {
        rememberInfiniteTransition(label = "rec")
            .animateFloat(0.55f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "halo").value
    } else 1f
    Box(
        Modifier.size(20.dp).drawBehind {
            if (active) drawCircle(c.recording.copy(alpha = 0.18f), radius = 10.dp.toPx() * halo)
            drawCircle(if (active) c.recording else c.tertiaryLabel, radius = 5.dp.toPx())
        },
    )
}

private const val BARS = 7

/**
 * 波形：7 根柱是最近 7 次音量采样（右侧最新），每根随目标高度缓动；未在录音时全部落到底。
 * 音量映射：-60 dBFS 以下算安静，0 dBFS 满格。
 */
@Composable
private fun Waveform(rmsDb: Float, active: Boolean) {
    val c = SceneTheme.colors
    val motion = SceneTheme.motion
    val levels = remember { mutableStateListOf<Float>().apply { repeat(BARS) { add(0f) } } }
    LaunchedEffect(rmsDb, active) {
        val level = if (active) ((rmsDb + 60f) / 60f).coerceIn(0f, 1f) else 0f
        levels.removeAt(0); levels.add(level)
    }
    Row(
        Modifier.height(40.dp).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        levels.forEachIndexed { i, target ->
            val h by animateFloatAsState(targetValue = target, animationSpec = tween(motion.fastMs), label = "bar$i")
            val bar = if (active) c.tint else c.tertiaryLabel
            Box(Modifier.width(4.dp).height(6.dp + 30.dp * h).drawBehind { drawRoundRect(bar, cornerRadius = CornerRadius(2.dp.toPx())) })
        }
    }
}

// ---------- 草稿：时间戳 + 段落，末尾灰字；自动跟到底 ----------

@Composable
private fun DraftList(s: MeetingState, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    val motion = SceneTheme.motion
    val list = rememberLazyListState()
    val segments = s.segments
    val partial = s.partial
    // 未定稿那句的时间戳取上一段的结束时刻（它自己还没有起点），避免随计时跳动
    val partialAt = if (partial.isNotBlank()) (segments.lastOrNull()?.endMs ?: 0L) else null
    val count = segments.size + (if (partialAt != null) 1 else 0)

    // 只在用户本来就看着末尾时才跟着滚：最后两项之一可见 = 在底部（新段落加入后原末尾变成倒数第二）
    val follow by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(count, partial) {
        if (count > 0 && follow) { if (motion.reduced) list.scrollToItem(count - 1) else list.animateScrollToItem(count - 1) }
    }

    if (count == 0) {
        Box(modifier.padding(horizontal = SceneSpacing.page, vertical = SceneSpacing.l), contentAlignment = Alignment.TopStart) {
            EmptyOrError(s)
        }
        return
    }
    LazyColumn(
        modifier, state = list,
        contentPadding = PaddingValues(start = SceneSpacing.page, end = SceneSpacing.page, top = SceneSpacing.s, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(segments, key = { _, seg -> seg.id }) { _, seg -> DraftLine(seg) }
        if (partialAt != null) {
            item(key = "partial") {
                DraftRow(time = SlowPath.mmss(partialAt), text = partial, color = c.tertiaryLabel)
            }
        }
        val notice = noticeText(s)
        if (notice != null) item(key = "notice") { SceneText(stringResource(notice), style = SceneTheme.type.footnote, color = c.secondaryLabel) }
    }
}

@Composable
private fun DraftLine(seg: Segment) {
    DraftRow(time = SlowPath.mmss(seg.startMs), text = seg.text, color = SceneTheme.colors.label)
}

@Composable
private fun DraftRow(time: String, text: String, color: Color) {
    val c = SceneTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        SceneText(time, Modifier.width(44.dp), style = SceneTheme.type.footnote.copy(fontFeatureSettings = "tnum"), color = c.tertiaryLabel, maxLines = 1)
        SceneText(text, Modifier.weight(1f), style = SceneTheme.type.subheadline, color = color)
    }
}

/** 空态：一句引导；出错时换成一行提示（语音包没下载 → 灰字，不带按钮）。 */
@Composable
private fun EmptyOrError(s: MeetingState) {
    val c = SceneTheme.colors
    val notice = noticeText(s)
    if (notice != null) SceneText(stringResource(notice), style = SceneTheme.type.footnote, color = c.secondaryLabel)
    else SceneText(stringResource(Res.string.meeting_empty_hint), style = SceneTheme.type.title3.copy(fontWeight = FontWeight.Normal), color = c.tertiaryLabel)
}

/** 出错一行：还没开始就失败 = 语音包没下载；录音中出错 = 识别出错。 */
private fun noticeText(s: MeetingState): StringResource? = when {
    s.error == null -> null
    s.state == RecState.IDLE && s.sessionId == null -> Res.string.live_pack_missing
    else -> Res.string.meeting_asr_error
}

// ---------- dock ----------

/** 标记要点：黄圆 56 pt，已标记数显示在图标下方。 */
@Composable
private fun MarkButton(count: Int, enabled: Boolean, onClick: () -> Unit) {
    val desc = if (count > 0) stringResource(Res.string.meeting_mark_cd_count, count) else stringResource(Res.string.meeting_mark_cd)
    SceneButton(
        onClick = onClick, modifier = Modifier.size(56.dp).semantics { contentDescription = desc },
        style = ButtonStyle.Warning, enabled = enabled, height = 56.dp, shape = CircleShape, contentPadding = 0.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            SceneIcon(SceneIcons.Bookmark, contentDescription = null, size = if (count > 0) 18.dp else 22.dp)
            if (count > 0) SceneText(count.toString(), style = SceneTheme.type.caption2.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

/** 主钮 72 pt：录音中 → 暂停；已暂停 → 继续；未开始 → 开始；准备中 / 整理中禁用。 */
@Composable
private fun MainButton(state: RecState, onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit) {
    val (label, icon, enabled) = when (state) {
        RecState.RECORDING -> Triple(Res.string.live_pause, SceneIcons.Pause, true)
        RecState.PAUSED -> Triple(Res.string.live_resume, SceneIcons.Play, true)
        RecState.IDLE -> Triple(Res.string.live_start, SceneIcons.Play, true)
        RecState.PREPARING -> Triple(Res.string.live_arming, SceneIcons.Play, false)
        RecState.FINISHING -> Triple(Res.string.meeting_finishing, SceneIcons.Stop, false)
    }
    SceneButton(
        onClick = { when (state) { RecState.RECORDING -> onPause(); RecState.PAUSED -> onResume(); RecState.IDLE -> onStart(); else -> Unit } },
        modifier = Modifier.size(72.dp), style = ButtonStyle.Prominent, enabled = enabled, height = 72.dp, contentPadding = 0.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SceneIcon(icon, contentDescription = null, size = 26.dp)
            SceneText(stringResource(label), style = SceneTheme.type.caption1.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

/** 状态行的一个词。 */
private fun stateWord(s: RecState): StringResource = when (s) {
    RecState.IDLE -> Res.string.live_state_idle
    RecState.PREPARING -> Res.string.live_state_arming
    RecState.RECORDING -> Res.string.meeting_recording
    RecState.PAUSED -> Res.string.live_state_paused
    RecState.FINISHING -> Res.string.meeting_finishing_ellipsis
}
