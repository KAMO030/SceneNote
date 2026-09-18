package dev.scenenote.ui.live

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scenenote.asr.LocalEngineState
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.CapsuleTone
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneCapsule
import dev.scenenote.core.designsystem.SceneIconButton
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.designsystem.glass
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.LiveState
import dev.scenenote.core.model.Speaker
import dev.scenenote.core.platform.ThermalLevel
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.live.LiveLine
import dev.scenenote.live.Phrases
import dev.scenenote.live.PipelineHealth
import dev.scenenote.shared.resources.*
import dev.scenenote.ui.i18n.forLang
import dev.scenenote.ui.i18n.langName
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import dev.scenenote.core.i18n.stringResource
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToInt

/** 每个半屏最多显示的气泡数（更早的句子仍在会话里，结束页可见）。 */
private const val MaxBubbles = 8

/**
 * 双屏（原型 LiveM3.dc.html）：无耳机，两人各看各的半屏。
 * - 常驻深色：整页套 `SceneTheme(dark = true)` 用深色令牌，不写死颜色。
 * - 上半屏我方视角（我的语言）：我说的原文靠右灰泡；对方的译文靠左青绿泡 22 pt；最新 partial 虚线灰泡。
 * - 中央玻璃胶囊（功能层，唯一的玻璃）：谁在说（自动 / 固定）+ 翻转 + 外放 + 仅听 + 结束。
 * - 下半屏 rotate(180°) 对方视角（对方语言）：对方说的原文靠右；给对方的译文靠左 24 pt。对方只看不碰。
 * - 还没分清谁在说 → 虚线气泡 + 「?」；气泡左右滑 > 60 dp = 翻转重译（首次进入提示一次，AppSettings.hintSeen("m3")）。
 * - 文本模式：默认不出声；外放开着时橙色小字「会外放」。
 */
@Composable
fun LiveM3Screen(vm: LiveViewModel, onBack: () -> Unit, onOpenModels: (List<String>) -> Unit) {
    val ui by vm.ui.collectAsState()
    val finish: () -> Unit = { vm.end(); onBack() }

    // 一次性引导：本次进入显示，之后不再打扰
    val settings = koinInject<AppSettings>()
    val firstVisit = remember { !settings.hintSeen("m3") }
    LaunchedEffect(Unit) { if (firstVisit) settings.markHintSeen("m3") }

    SceneTheme(dark = true, typography = SceneTheme.type, accessibility = SceneTheme.a11y) {
        val c = SceneTheme.colors
        GlassScaffold(
            background = c.systemBackground,
            topBar = {
                val (label, tone) = m3StatusCapsule(ui.state, ui.health, ui.speakerOut)
                SceneNavBar(
                    title = stringResource(Res.string.live_row_split),
                    onBack = { vm.switchMode("M0") }, backContentDescription = stringResource(Res.string.live_back_to_listen),
                    trailing = { SceneCapsule(stringResource(label), tone = tone) },
                )
            },
            // 中央胶囊放在功能层槽位里：它要取样内容层做玻璃，不能自己也被录进内容层
            bottomBar = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CenterCapsule(ui = ui, vm = vm, firstVisit = firstVisit, onFinish = finish)
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                MyHalf(
                    ui = ui, onFlip = vm::flip, onOpenModels = onOpenModels,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 104.dp, bottom = 48.dp, start = SceneSpacing.page, end = SceneSpacing.page),
                )
                // 对方半屏：整块旋转 180°，布局上的 top 即物理底边（含底部安全区），bottom 给中央胶囊留位
                val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                TheirHalf(
                    ui = ui,
                    modifier = Modifier.weight(1f).fillMaxWidth().rotate(180f).padding(top = 24.dp + navInset, bottom = 48.dp, start = SceneSpacing.page, end = SceneSpacing.page),
                )
            }
        }
    }
}

// ---------- 上半屏：我方视角 ----------

/** 头行（呼吸点 + 你 · 普通话）→ 本机识别提示 → 气泡列（贴底，新句到达自动滚到底）。 */
@Composable
private fun MyHalf(ui: LiveUiState, onFlip: (String) -> Unit, onOpenModels: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            SpeakingDot(active = ui.speaking)
            SceneText(stringResource(Res.string.live_you_lang, langName(ui.myLang)), style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1)
        }
        EngineNotice(ui, onOpenModels)
        NmtNotice(ui, onOpenModels)
        BubbleColumn(Modifier.weight(1f).fillMaxWidth()) {
            ui.lines.takeLast(MaxBubbles).forEach { line ->
                key(line.id) {
                    if (line.speaker == Speaker.ME) MyHalfMeBubble(line, onFlip = { onFlip(line.id) })
                    else MyHalfOtherBubble(line, onFlip = { onFlip(line.id) })
                }
            }
            if (ui.partial.isNotBlank()) {
                Bubble(side = BubbleSide.Left, tag = stringResource(Res.string.live_speaking_now), text = ui.partial, textStyle = partialStyle(), textColor = c.secondaryLabel, dashed = true)
            }
            if (ui.lines.isEmpty() && ui.partial.isBlank()) {
                SceneText(
                    stringResource(if (ui.state is LiveState.Live) Res.string.live_m3_hint_live else Res.string.live_m3_hint_idle),
                    style = SceneTheme.type.subheadline, color = c.tertiaryLabel,
                )
            }
        }
    }
}

/** 我说的话（右侧灰泡）：小标「你」+ 原文 17 pt。 */
@Composable
private fun MyHalfMeBubble(line: LiveLine, onFlip: () -> Unit) {
    val c = SceneTheme.colors
    Bubble(
        side = BubbleSide.Right,
        tag = stringResource(Res.string.live_you),
        text = line.text, textStyle = SceneTheme.type.body, textColor = c.label,
        dashed = line.dirTentative,
        badges = lineBadges(line),
        onFlip = onFlip,
    )
}

/** 对方的话（左侧青绿泡）：小标「对方」+ 译文 22 pt + 原文 13 pt；缺译文时显示原文。 */
@Composable
private fun MyHalfOtherBubble(line: LiveLine, onFlip: () -> Unit) {
    val c = SceneTheme.colors
    Bubble(
        side = BubbleSide.Left, tinted = true,
        tag = stringResource(Res.string.common_other),
        text = line.translation ?: line.text, textStyle = translationStyle(22.sp, 28.sp), textColor = c.label,
        sub = line.text.takeIf { line.translation != null },
        dashed = line.dirTentative,
        badges = lineBadges(line),
        onFlip = onFlip,
    )
}

/** 一句话的小胶囊，只有三种：「未翻译」（没译文且已放弃）/「没播完」（外放被打断）/「?」（还没分清谁在说）。 */
@Composable
private fun lineBadges(line: LiveLine): List<Badge> = buildList {
    if (line.translation == null && line.mtDegraded) add(Badge(stringResource(Res.string.live_flag_untranslated), CapsuleTone.Destructive))
    if (line.interrupted) add(Badge(stringResource(Res.string.live_flag_interrupted), CapsuleTone.Gray))
    if (line.dirTentative) add(Badge("?", CapsuleTone.Gray))
}

/** 本机识别的行内提示（不弹窗）：不可用 → 红字 + 「去下载语音包」；加载中灰字；录音出错红字（原因只进诊断页）。 */
@Composable
private fun EngineNotice(ui: LiveUiState, onOpenModels: (List<String>) -> Unit) {
    val c = SceneTheme.colors
    when (ui.engine) {
        is LocalEngineState.Error -> Column(verticalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneText(stringResource(Res.string.live_local_asr_unavailable), style = SceneTheme.type.footnote, color = c.destructive)
            SceneButton(stringResource(Res.string.live_download_pack), onClick = { onOpenModels(emptyList()) }, style = ButtonStyle.Tinted, height = SceneSize.glassButton)
        }
        LocalEngineState.Loading -> SceneText(stringResource(Res.string.live_pack_loading), style = SceneTheme.type.footnote, color = c.secondaryLabel)
        else -> {}
    }
    if (ui.error != null) SceneText(stringResource(Res.string.live_record_error_short), style = SceneTheme.type.footnote, color = c.destructive)
}

// ---------- 下半屏：对方视角（旋转 180°，对方语言）----------

/** 头行（Them · English）→ 气泡列。不接手势：对方只看不碰。 */
@Composable
private fun TheirHalf(ui: LiveUiState, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    val w = theirWords(ui.otherLang)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SceneText("${w.them} · ${nativeName(ui.otherLang)}", style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1)
        BubbleColumn(Modifier.weight(1f).fillMaxWidth()) {
            ui.lines.takeLast(MaxBubbles).forEach { line ->
                key(line.id) {
                    val tentative = if (line.dirTentative) listOf(Badge("?", CapsuleTone.Gray)) else emptyList()
                    if (line.speaker == Speaker.OTHER) {
                        // 对方自己说的话（右侧灰泡）：原文 17 pt
                        Bubble(
                            side = BubbleSide.Right,
                            tag = w.theySaid,
                            text = line.text, textStyle = SceneTheme.type.body.forLang(ui.otherLang), textColor = c.label,
                            dashed = line.dirTentative,
                            badges = tentative,
                        )
                    } else {
                        // 给对方的译文（左侧青绿泡）：24 pt（≥ 22）；缺译文时显示原文
                        Bubble(
                            side = BubbleSide.Left, tinted = true,
                            tag = w.meToThem,
                            text = line.translation ?: line.text, textStyle = translationStyle(24.sp, 30.sp).forLang(ui.otherLang), textColor = c.label,
                            dashed = line.dirTentative,
                            badges = (if (line.translation == null && line.mtDegraded) listOf(Badge(w.notTranslated, CapsuleTone.Destructive)) else emptyList()) + tentative,
                        )
                    }
                }
            }
            if (ui.partial.isNotBlank()) {
                Bubble(side = BubbleSide.Right, tag = Phrases.listening(ui.otherLang), text = ui.partial, textStyle = partialStyle(), textColor = c.secondaryLabel, dashed = true)
            }
        }
    }
}

/** 对方半屏的固定文案：按对方语言取（原型是英语：Them · English / They said / Me → them）。 */
private class TheirWords(val them: String, val theySaid: String, val meToThem: String, val notTranslated: String)

private fun theirWords(lang: String): TheirWords = when (lang.substringBefore('-')) {
    "zh", "yue", "wuu", "nan" -> TheirWords("对方", "对方说", "我 → 对方", "未翻译")
    "ja" -> TheirWords("相手", "相手の発言", "私 → 相手", "未翻訳")
    "ko" -> TheirWords("상대", "상대의 말", "나 → 상대", "번역 안 됨")
    else -> TheirWords("Them", "They said", "Me → them", "Not translated")
}

/** 语言的本族名（给对方半屏看）：普通话 / English / 日本語 / 한국어。 */
private fun nativeName(tag: String): String = when (tag) {
    Lang.ZH_CN -> "普通话"; Lang.YUE_HK -> "粵語"; Lang.ZH_SICHUAN -> "四川话"; Lang.WUU -> "上海话"; Lang.NAN -> "闽南语"
    Lang.EN -> "English"; Lang.JA -> "日本語"; Lang.KO -> "한국어"; else -> tag
}

// ---------- 中央玻璃胶囊 ----------

/**
 * 左侧文字：「自动分辨谁在说」/「固定：对方说」（可点：建议固定 → 固定为对方说；已固定 → 恢复自动）；
 * 第二行只在两种情况出现：外放开着 → 橙色「会外放」；首次进入 → 「气泡左右滑可翻转」。
 * 右侧四钮：翻转 / 外放 / 仅听 / 结束。64 dp 高、圆角胶囊、左右 12 dp 边距（原型 `.glass` 条）。
 */
@Composable
private fun CenterCapsule(ui: LiveUiState, vm: LiveViewModel, firstVisit: Boolean, onFinish: () -> Unit) {
    val c = SceneTheme.colors
    val fixed = ui.fixedDirection
    val title = stringResource(when {
        fixed != null -> if (fixed == Speaker.OTHER) Res.string.live_fixed_other else Res.string.live_fixed_me
        ui.suggestFixed -> Res.string.live_fix_other_suggest
        else -> Res.string.settings_direction_auto
    })
    val note: Pair<String, Color>? = when {
        ui.speakerOut -> stringResource(Res.string.live_will_play_aloud) to c.onWarningSoft
        firstVisit -> stringResource(Res.string.live_swipe_to_flip_hint) to c.secondaryLabel
        else -> null
    }
    val onDirClick: (() -> Unit)? = when {
        fixed != null -> { { vm.fixDirection(null) } }
        ui.suggestFixed -> { { vm.fixDirection(Speaker.OTHER) } }
        else -> null
    }
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .glass(CircleShape, elevation = 10.dp)
            .height(64.dp)
            .padding(start = 6.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 谁在说：建议固定 / 已固定时是一枚可点的小胶囊（橙 / 青绿），自动时只是文字
        val dirBg = when { ui.suggestFixed -> c.warningSoft; fixed != null -> c.tintSoft; else -> Color.Transparent }
        val titleColor = if (ui.suggestFixed) c.onWarningSoft else c.onTintSoft
        Column(
            Modifier
                .weight(1f)
                .clip(CircleShape)
                .background(dirBg)
                .then(if (onDirClick != null) Modifier.clickable(role = Role.Button, onClick = onDirClick) else Modifier)
                .height(52.dp)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
        ) {
            SceneText(title, style = SceneTheme.type.caption1.copy(fontWeight = FontWeight.SemiBold), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) SceneText(note.first, style = SceneTheme.type.caption2, color = note.second, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SceneIconButton(SceneIcons.Rotate, contentDescription = stringResource(Res.string.live_flip_last), onClick = vm::flipLast, size = 48.dp, style = ButtonStyle.Gray, enabled = ui.current != null)
        // 外放：默认关；开 = 给对方的译文也出声（橙色提示）
        SceneIconButton(
            SceneIcons.Speaker, contentDescription = stringResource(if (ui.speakerOut) Res.string.live_speaker_on else Res.string.live_speaker_off),
            onClick = { vm.setSpeakerOut(!ui.speakerOut) }, size = 48.dp, style = if (ui.speakerOut) ButtonStyle.Warning else ButtonStyle.Gray,
        )
        SceneButton(onClick = { vm.switchMode("M0") }, style = ButtonStyle.Gray, height = 48.dp, contentPadding = 12.dp) {
            SceneText(stringResource(Res.string.scene_listen), style = SceneTheme.type.caption1.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
        SceneIconButton(SceneIcons.Stop, contentDescription = stringResource(Res.string.live_end), onClick = onFinish, size = 48.dp, style = ButtonStyle.Destructive)
    }
}

/**
 * 右上状态胶囊：一个词 + 颜色（docs/15 §2）。
 * 进行中：过热 / 低电 / 发热 > 无翻译 > 无语音（外放开着时）> 离线 > 已连接；未进行时显示会话状态词。
 */
private fun m3StatusCapsule(state: LiveState, h: PipelineHealth, speakerOut: Boolean): Pair<StringResource, CapsuleTone> = when (state) {
    LiveState.Idle -> Res.string.live_status_standby to CapsuleTone.Gray
    LiveState.Arming -> Res.string.live_arming to CapsuleTone.Gray
    is LiveState.Paused -> Res.string.live_state_paused to CapsuleTone.Gray
    LiveState.NeedForeground -> Res.string.live_state_tap_resume to CapsuleTone.Warning
    LiveState.Ending -> Res.string.live_ending to CapsuleTone.Gray
    is LiveState.Live, LiveState.Degraded -> when {
        h.thermal == ThermalLevel.CRITICAL -> Res.string.live_status_overheat to CapsuleTone.Destructive
        h.lowBattery -> Res.string.live_status_low_battery to CapsuleTone.Warning
        h.thermal >= ThermalLevel.SERIOUS -> Res.string.live_status_warm to CapsuleTone.Warning
        h.mt == "unavailable" -> Res.string.live_status_no_mt to CapsuleTone.Destructive
        speakerOut && (h.tts == "none" || h.tts == "failed") -> Res.string.live_status_no_voice to CapsuleTone.Warning
        h.mt == "fallback" -> Res.string.live_status_offline to CapsuleTone.Warning
        else -> Res.string.live_status_connected to CapsuleTone.Tint
    }
}

// ---------- 气泡 ----------

private enum class BubbleSide { Left, Right }

private class Badge(val text: String, val tone: CapsuleTone)

/** 气泡列：贴底排布，内容超出半屏时可滚，新句 / partial 到达自动滚到底（Reduce Motion 时直接跳）。 */
@Composable
private fun BubbleColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val reduced = SceneTheme.motion.reduced
    LaunchedEffect(scroll.maxValue) { if (reduced) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue) }
    Column(
        modifier.fillMaxSize().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
        content = content,
    )
}

/**
 * 一枚气泡（内容层，不透明，不用玻璃）：小标 11 pt + 正文 + 可选原文 13 pt + 小胶囊。
 * - 右侧 = 说话人自己的原文（灰底，右下角 6 dp）；左侧 = 译文（青绿深底 / 灰底，左下角 6 dp）。
 * - [dashed]：还没分清谁在说或 partial，虚线描边、无实底。
 * - [onFlip] 非空时接左右滑手势（只给我方半屏）。
 */
@Composable
private fun Bubble(
    side: BubbleSide,
    tag: String,
    text: String,
    textStyle: TextStyle,
    textColor: Color,
    sub: String? = null,
    tinted: Boolean = false,
    dashed: Boolean = false,
    badges: List<Badge> = emptyList(),
    onFlip: (() -> Unit)? = null,
) {
    val c = SceneTheme.colors
    val flipLabel = stringResource(Res.string.live_flip_this)
    val shape = if (side == BubbleSide.Right) RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
    else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
    val bg = when { dashed -> c.systemBackground; tinted -> c.tintSoft; else -> c.secondarySystemBackground }
    val tagColor = if (tinted && !dashed) c.onTintSoft else c.secondaryLabel
    val align = if (side == BubbleSide.Right) Alignment.End else Alignment.Start
    Column(
        Modifier
            .fillMaxWidth(if (side == BubbleSide.Right) 0.86f else 0.9f)
            .wrapContentWidth(align)
            .then(if (onFlip != null) Modifier.swipeToFlip(onFlip).semantics { customActions = listOf(CustomAccessibilityAction(flipLabel) { onFlip(); true }) } else Modifier)   // 屏幕阅读器可翻转任一句
            .clip(shape)
            .background(bg)
            .then(if (dashed) Modifier.dashedOutline(shape, c.tertiaryLabel) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SceneText(tag, style = SceneTheme.type.caption2, color = tagColor)
            badges.forEach { b -> SceneCapsule(b.text, tone = b.tone) }
        }
        SceneText(text, style = textStyle, color = textColor)
        if (sub != null) SceneText(sub, style = SceneTheme.type.footnote, color = c.secondaryLabel)
    }
}

/** 译文字号：我方半屏 22 pt、对方半屏 24 pt（原型 600 字重）。 */
@Composable
private fun translationStyle(size: TextUnit, lineHeight: TextUnit): TextStyle =
    SceneTheme.type.title2.copy(fontSize = size, lineHeight = lineHeight, fontWeight = FontWeight.SemiBold)

/** partial 灰字 20 pt（原型），常规字重。 */
@Composable
private fun partialStyle(): TextStyle = SceneTheme.type.title3.copy(fontWeight = FontWeight.Normal)

/** 虚线描边（还没分清谁在说 / partial）：沿气泡自身的不对称圆角轮廓画，内缩半个线宽避免被 clip 吃掉。 */
private fun Modifier.dashedOutline(shape: Shape, color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    inset(stroke / 2) {
        drawOutline(shape.createOutline(size, layoutDirection, this), color, style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
    }
}

/**
 * 气泡左右滑 > 60 dp → 翻转重译（一次拖动只触发一次）。跟手位移（半速、限幅）+ 松手回弹；Reduce Motion 下不位移。
 * 只吃水平拖动，竖向滚动仍归气泡列。
 */
@Composable
private fun Modifier.swipeToFlip(onFlip: () -> Unit): Modifier {
    val flip by rememberUpdatedState(onFlip)
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val reduced = SceneTheme.motion.reduced
    return this
        .offset { IntOffset(offset.value.roundToInt(), 0) }
        .pointerInput(reduced) {
            val threshold = 60.dp.toPx()
            var total = 0f
            var fired = false
            detectHorizontalDragGestures(
                onDragStart = { total = 0f; fired = false },
                onDragEnd = { scope.launch { offset.animateTo(0f) } },
                onDragCancel = { scope.launch { offset.animateTo(0f) } },
            ) { change, delta ->
                change.consume()
                total += delta
                if (!reduced) scope.launch { offset.snapTo((total * 0.5f).coerceIn(-threshold, threshold)) }
                if (!fired && abs(total) > threshold) { fired = true; flip() }
            }
        }
}

/** 头行的「在听」点：有人在说时青绿呼吸，否则灰点（Reduce Motion 时静止）。 */
@Composable
private fun SpeakingDot(active: Boolean) {
    val c = SceneTheme.colors
    val pulse = if (active && !SceneTheme.motion.reduced) {
        rememberInfiniteTransition(label = "speaking")
            .animateFloat(0.5f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "alpha").value
    } else 1f
    Box(Modifier.size(8.dp).alpha(pulse).clip(CircleShape).background(if (active) c.tint else c.tertiaryLabel))
}
