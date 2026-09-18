package dev.scenenote.ui.live

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.scenenote.asr.LocalEngineState
import dev.scenenote.audio.AudioRoute
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.CapsuleTone
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneCapsule
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIconButton
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.designsystem.glass
import dev.scenenote.core.model.LiveState
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.live.LiveLine
import dev.scenenote.live.Phrases
import dev.scenenote.live.PipelineHealth
import dev.scenenote.shared.resources.*
import dev.scenenote.ui.i18n.forLang
import org.jetbrains.compose.resources.StringResource
import dev.scenenote.core.i18n.stringResource
import org.koin.compose.koinInject

/**
 * 面屏（原型 LiveM1.dc.html）：我私听，对方看半屏。
 *
 * 上 0.42：「对方说」译文 + 原文、「你说」原文 → 玻璃控制条（仅听 / 双屏 / 结束）→ 没耳机时的「改用双屏」提示。
 * 下 0.58：对方半屏旋转 180°（常驻浅色底，大字 ≥ 28 pt）：礼貌卡（进入后 3 s）→ 在听 → 已收到 + 大字；常驻「No thanks」按钮。
 *
 * 文案遵守 docs/15：页面上只有一个状态词（右上胶囊），不显示路由 / 引擎 / 播放去向等工程信息；
 * 「下半屏给对方看」只在首次进入提示一次（AppSettings.hintSeen("m1")）。
 * 同一会话、同一个 VM：切模式不重启音频（进页 / 结束由 LiveConversationScreen 与 M0 负责）。
 */
@Composable
fun LiveM1Screen(vm: LiveViewModel, onBack: () -> Unit, onOpenModels: (List<String>) -> Unit) {
    val ui by vm.ui.collectAsState()
    val c = SceneTheme.colors
    val live = ui.state is LiveState.Live
    val finish: () -> Unit = { vm.end(); onBack() }

    // 一次性引导：本次进入显示，之后不再打扰
    val settings = koinInject<AppSettings>()
    val firstVisit = remember { !settings.hintSeen("m1") }
    LaunchedEffect(Unit) { if (firstVisit) settings.markHintSeen("m1") }

    GlassScaffold(
        background = c.systemBackground,
        topBar = {
            val (label, tone) = m1StatusCapsule(ui.state, ui.health, ui.voiceOut)
            SceneNavBar(
                title = stringResource(Res.string.live_row_facing),
                onBack = { vm.switchMode("M0") }, backContentDescription = stringResource(Res.string.live_back_to_listen),   // 返回 = 回仅听；结束只留红钮
                trailing = { SceneCapsule(stringResource(label), tone = tone) },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            MySection(
                ui = ui, firstVisit = firstVisit, onOpenModels = onOpenModels,
                onM0 = { vm.switchMode("M0") }, onM3 = { vm.switchMode("M3") }, onEnd = finish,
                modifier = Modifier.weight(0.42f),
            )
            FacingPanel(ui = ui, onNoThanks = { vm.politeExit() }, modifier = Modifier.weight(0.58f))
        }
    }
}

// ---------- 上半区：我看的部分 ----------

/** 两块文字（可滚，不把控制条挤出去）→ 玻璃控制条 → 没耳机提示 / 首次引导。 */
@Composable
private fun MySection(
    ui: LiveUiState, firstVisit: Boolean, onOpenModels: (List<String>) -> Unit,
    onM0: () -> Unit, onM3: () -> Unit, onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = SceneTheme.colors
    val output = ui.route?.output
    val noHeadset = output == AudioRoute.Speaker || output == AudioRoute.BuiltIn
    Column(
        modifier.fillMaxWidth().padding(top = 104.dp, start = SceneSpacing.page, end = SceneSpacing.page, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OtherSaid(ui.lastOther)
            YouSaid(ui.lastMe)
            EngineNotice(ui.engine, ui.error, onOpenModels)
            NmtNotice(ui, onOpenModels)
        }

        ControlBar(onM0 = onM0, onM3 = onM3, onEnd = onEnd)

        // 没有耳机时提示改用双屏（点提示直接切）；否则首次进入提示一次「下半屏给对方看」
        when {
            noHeadset -> Row(
                Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onM3).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                SceneIcon(SceneIcons.Headphones, contentDescription = null, size = 14.dp, tint = c.onWarningSoft)
                SceneText(stringResource(Res.string.live_no_headset_use_split), style = SceneTheme.type.caption1.copy(fontWeight = FontWeight.SemiBold), color = c.onWarningSoft, maxLines = 1)
            }
            firstVisit -> Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                SceneIcon(SceneIcons.Rotate, contentDescription = null, size = 14.dp, tint = c.secondaryLabel)
                SceneText(stringResource(Res.string.live_bottom_half_for_them), style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1)
            }
        }
    }
}

/** 「对方说」：译文 17 pt 粗 + 原文 13 pt 次要色；还没有句子时占位灰字。 */
@Composable
private fun OtherSaid(line: LiveLine?) {
    val c = SceneTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SceneText(stringResource(Res.string.live_they_said), style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1)
            if (line != null) LineCapsules(line)
        }
        if (line == null) {
            SceneText(stringResource(Res.string.live_wait_them), style = SceneTheme.type.subheadline, color = c.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            SceneText(line.translation ?: line.text, style = SceneTheme.type.headline, color = c.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (line.translation != null) SceneText(line.text, style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 「你说」：我说的原文 13 pt（译文在对方半屏大字显示，这里不重复）。 */
@Composable
private fun YouSaid(line: LiveLine?) {
    val c = SceneTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SceneText(stringResource(Res.string.live_you_said), style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1)
            if (line != null) LineCapsules(line)
        }
        SceneText(
            line?.text ?: stringResource(Res.string.live_you_speak_hint),
            style = SceneTheme.type.footnote, color = if (line == null) c.tertiaryLabel else c.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 一句话的小胶囊：「未翻译」（没译文且已放弃）/「?」（还没分清谁在说）；正常不显示。 */
@Composable
private fun LineCapsules(line: LiveLine) {
    if (line.translation == null && line.mtDegraded) SceneCapsule(stringResource(Res.string.live_flag_untranslated), tone = CapsuleTone.Destructive)
    if (line.dirTentative) SceneCapsule("?", tone = CapsuleTone.Gray)
}

/** 本机识别的行内提示（不弹窗）：不可用 → 红字 + 「去下载语音包」；加载中 → 灰字；录音出错 → 红字（原因只进诊断页）。 */
@Composable
private fun EngineNotice(engine: LocalEngineState, error: String?, onOpenModels: (List<String>) -> Unit) {
    val c = SceneTheme.colors
    when (engine) {
        is LocalEngineState.Error -> Column(verticalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneText(stringResource(Res.string.live_local_asr_unavailable), style = SceneTheme.type.footnote, color = c.destructive)
            SceneButton(stringResource(Res.string.live_download_pack), onClick = { onOpenModels(emptyList()) }, style = ButtonStyle.Tinted, height = 44.dp)
        }
        LocalEngineState.Loading -> SceneText(stringResource(Res.string.live_pack_loading), style = SceneTheme.type.footnote, color = c.secondaryLabel)
        else -> {}
    }
    if (error != null) SceneText(stringResource(Res.string.live_record_error_short), style = SceneTheme.type.footnote, color = c.destructive)
}

/**
 * 玻璃控制条（原型 `.glass` 52 pt 胶囊）：仅听 / 双屏 / 结束（红圆 44）。
 * 它在内容层里，不能取样正在录制的 backdrop（会自己画自己），所以 `backdrop = null` → 高填充材质回退。
 */
@Composable
private fun ControlBar(onM0: () -> Unit, onM3: () -> Unit, onEnd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(CircleShape, backdrop = null, elevation = 6.dp).height(52.dp).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(stringResource(Res.string.scene_listen), onClick = onM0, filled = true, modifier = Modifier.weight(1f))
        BarButton(stringResource(Res.string.live_row_split), onClick = onM3, filled = false, modifier = Modifier.weight(1f))
        SceneIconButton(SceneIcons.Stop, contentDescription = stringResource(Res.string.live_end), onClick = onEnd, size = 44.dp, style = ButtonStyle.Destructive)
    }
}

/** 玻璃条里的文字按钮（44 pt，单色标签）：`filled` = 原型里 70% 白的那格（与 Tab 栏选中项同一做法）。 */
@Composable
private fun BarButton(text: String, onClick: () -> Unit, filled: Boolean, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val fill = if (c.isDark) c.label.copy(alpha = 0.14f) else c.systemBackground.copy(alpha = 0.7f)
    Box(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(fill) else Modifier)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SceneText(text, style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.label, maxLines = 1)
    }
}

// ---------- 下半区：对方半屏（旋转 180°，常驻浅色底）----------

/**
 * 对方半屏：整块 `rotate(180f)`（触控随层变换，按钮照常可点）。
 * 无论系统深浅色都用浅色令牌（沉浸式深色只给双屏）；边缘 1 dp 描边在对方说话时作呼吸灯。
 * 布局：礼貌卡 / 字幕区（占满）→ 常驻「No thanks, I'm fine」（Gray，50 pt）。文案是给对方看的，按对方语言取。
 */
@Composable
private fun FacingPanel(ui: LiveUiState, onNoThanks: () -> Unit, modifier: Modifier = Modifier) {
    SceneTheme(dark = false, accessibility = SceneTheme.a11y) {
        val c = SceneTheme.colors
        val lang = ui.otherLang
        val radius = 24.dp
        Column(
            modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())   // 旋转后顶行落在 Home 指示条上，留 inset
                .rotate(180f)
                .clip(RoundedCornerShape(radius))
                .background(c.secondarySystemBackground)
                .breathingEdge(active = ui.speaking && !ui.politeCard, radius = radius)
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (ui.politeCard) PoliteCard(lang) else Subtitles(ui, lang)
            }
            SceneButton(text = Phrases.noThanks(lang), onClick = onNoThanks, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.Gray, height = 50.dp)
        }
    }
}

/** 礼貌卡（进入后 3 s）：录音点 + 麦克风 + 录音告知 → 28 pt 礼貌语（按对方语言）。 */
@Composable
private fun PoliteCard(lang: String) {
    val c = SceneTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).drawBehind { drawCircle(c.recording) })
            SceneIcon(SceneIcons.Mic, contentDescription = null, size = 16.dp, tint = c.onDestructiveSoft)
            SceneText(recordingNotice(lang), style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.onDestructiveSoft, maxLines = 2)
        }
        SceneText(Phrases.politeCard(lang), style = SceneTheme.type.title1.forLang(lang), color = c.label)
    }
}

/**
 * 字幕区（礼貌卡淡出后）：
 * - 头行：对方说话中 → 呼吸灯 + Listening…；最新一句我说的有译文 → 对勾 + Received；译文未到 → 「…」灰胶囊；还没说 → 静止点 + Listening…
 * - 正文：32 pt final（`lastMe.translation ?: lastMe.text`）；partial 到达（speaking 且非空）时灰字 20 pt 跟在后面，final 不消失。
 */
@Composable
private fun Subtitles(ui: LiveUiState, lang: String) {
    val c = SceneTheme.colors
    val me = ui.lastMe
    val finalText = me?.let { it.translation ?: it.text }
    val partial = ui.partial.takeIf { ui.speaking && it.isNotBlank() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            ui.speaking || me == null -> ListeningRow(lang, active = ui.speaking)
            me.translation != null -> ReceivedRow(lang)
            else -> SceneCapsule("…", tone = CapsuleTone.Gray)
        }
        // 大字区：短句从上排，长句可滚
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (finalText != null) SceneText(finalText, style = SceneTheme.type.facingScreen.forLang(lang), color = c.label)
            if (partial != null) SceneText(partial, style = SceneTheme.type.title1.copy(fontWeight = FontWeight.Normal).forLang(lang), color = c.tertiaryLabel)   // 对方半屏 ≥ 28 pt
        }
    }
}

/** 「Listening…」头行：呼吸灯（alpha 脉冲，Reduce Motion 静止）+ 灰字。 */
@Composable
private fun ListeningRow(lang: String, active: Boolean) {
    val c = SceneTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        BreathDot(active = active, size = 12.dp, halo = true)
        SceneText(Phrases.listening(lang), style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.secondaryLabel, maxLines = 1)
    }
}

/** 「Received」头行：对勾 = 「已收到」，消解「听到了吗？」的反复确认。 */
@Composable
private fun ReceivedRow(lang: String) {
    val c = SceneTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SceneIcon(SceneIcons.Check, contentDescription = null, size = 20.dp, tint = c.onTintSoft)
        SceneText(Phrases.received(lang), style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.onTintSoft, maxLines = 1)
    }
}

// ---------- 呼吸灯 ----------

/** 呼吸点：active 时 alpha 脉冲（Reduce Motion 静止满亮）；非 active 为灰点。`halo` 加一圈青绿光晕。 */
@Composable
private fun BreathDot(active: Boolean, size: Dp, halo: Boolean) {
    val c = SceneTheme.colors
    val pulse = breathAlpha(animate = active)
    Box(
        Modifier.size(size * 2).drawBehind {
            // 在绘制阶段读动画值：只重绘、不重组
            val a = pulse.value
            if (active && halo) drawCircle(c.tint.copy(alpha = 0.18f * a), radius = size.toPx())
            drawCircle(if (active) c.tint.copy(alpha = a) else c.tertiaryLabel, radius = size.toPx() / 2)
        },
    )
}

/** 对方半屏边缘的呼吸灯（「半屏边缘有呼吸灯 = 我在听」）：说话中青绿 1 dp 描边 alpha 脉冲，Reduce Motion 静止；否则分隔线色。 */
@Composable
private fun Modifier.breathingEdge(active: Boolean, radius: Dp): Modifier {
    val c = SceneTheme.colors
    val pulse = breathAlpha(animate = active)
    return drawBehind {
        val w = 1.dp.toPx()
        drawRoundRect(
            color = if (active) c.tint.copy(alpha = pulse.value) else c.separator,
            topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius(radius.toPx() - w / 2), style = Stroke(width = w),
        )
    }
}

/** 呼吸的 alpha（0.35 ⇄ 1，900 ms 往返）；不动画或 Reduce Motion 时恒为 1。返回 State，调用方在绘制阶段读。 */
@Composable
private fun breathAlpha(animate: Boolean): State<Float> =
    if (animate && !SceneTheme.motion.reduced) {
        rememberInfiniteTransition(label = "breath")
            .animateFloat(0.35f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "alpha")
    } else remember { mutableStateOf(1f) }

// ---------- 文案 / 状态映射 ----------

/** 礼貌卡上的录音告知（按对方语言）；Phrases 里暂无此句，先放在页面内。 */
private fun recordingNotice(lang: String): String = when (lang.substringBefore('-')) {
    "zh", "yue", "wuu", "nan" -> "录音中 · 只保留文字，不保存声音"
    "ja" -> "録音中 · テキストのみ保存、音声は保存しません"
    "ko" -> "녹음 중 · 텍스트만 저장, 음성은 저장하지 않아요"
    else -> "Recording · text only, no audio is kept"
}

/**
 * 右上状态胶囊：一个词 + 颜色（docs/15 §2）。
 * 进行中：无翻译 > 无语音 > 离线 > 已连接；未进行时显示会话状态词（准备中 / 已暂停 / 点一下继续 / 结束中）。
 */
internal fun m1StatusCapsule(state: LiveState, h: PipelineHealth, voiceOut: Boolean): Pair<StringResource, CapsuleTone> = when (state) {
    LiveState.Idle -> Res.string.live_status_standby to CapsuleTone.Gray
    LiveState.Arming -> Res.string.live_arming to CapsuleTone.Gray
    is LiveState.Paused -> Res.string.live_state_paused to CapsuleTone.Gray
    LiveState.NeedForeground -> Res.string.live_state_tap_resume to CapsuleTone.Warning
    LiveState.Ending -> Res.string.live_ending to CapsuleTone.Gray
    is LiveState.Live, LiveState.Degraded -> when {
        h.mt == "unavailable" -> Res.string.live_status_no_mt to CapsuleTone.Destructive
        voiceOut && (h.tts == "none" || h.tts == "failed") -> Res.string.live_status_no_voice to CapsuleTone.Warning
        h.mt == "fallback" -> Res.string.live_status_offline to CapsuleTone.Warning
        else -> Res.string.live_status_connected to CapsuleTone.Tint
    }
}
