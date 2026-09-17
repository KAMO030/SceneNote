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
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.LiveState
import dev.scenenote.core.model.PlaybackStatus
import dev.scenenote.live.LiveLine
import dev.scenenote.live.Phrases
import dev.scenenote.live.PipelineHealth

/**
 * M1 耳听·面屏（原型 LiveM1.dc.html，14 篇 §1 ②）：我私听，对方看半屏。
 *
 * 上 1/3 我方：小字「对方 → 我 · 已进耳机」（译文 17 pt + 原文 13 pt）、「我 → 对方 · 已上屏」（原文 13 pt）、
 * 玻璃控制条（回 M0 / 双屏 M3 / 结束）→ 分隔说明 → 下 2/3 **对方半屏旋转 180°**（常驻浅色底，大字 ≥ 28 pt）：
 * 礼貌卡（进入后 3 s）→ Listening… 灰字 partial + 呼吸灯 → Received 对勾 + 32 pt final；常驻「No thanks, I'm fine」→ 播退出语（−6 dB）并回 M0。
 *
 * 与原型的差异：「摘下耳机 → M3」的自动降级改为手动按钮「双屏 M3」，并在没有耳机（扬声器 / 听筒）时行内提示「没有耳机：改用双屏对话」；
 * 「模拟」三态按钮是原型演示用，这里由 VM 的 politeCard / speaking / lastMe 驱动。
 * 同一会话、同一个 VM：切模式不重启音频（进页 / 结束由 LiveConversationScreen 与 M0 负责）。
 */
@Composable
fun LiveM1Screen(vm: LiveViewModel, onBack: () -> Unit, onOpenModels: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val c = SceneTheme.colors
    val live = ui.state is LiveState.Live
    val finish: () -> Unit = { vm.end(); onBack() }

    GlassScaffold(
        background = c.systemBackground,
        topBar = {
            val (label, tone) = m1HealthCapsule(ui.health, ui.voiceOut, live)
            SceneNavBar(
                title = "M1 耳听·面屏",
                onBack = { vm.switchMode("M0") }, backContentDescription = "回 M0",   // 返回 = 回仅听；结束只留红钮（14 篇：Alert 只给不可撤销动作）
                trailing = { SceneCapsule(label, tone = tone) },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            // 原型几何：我方区 0–356 / 说明行 360 / 对方半屏 382–836（844 高），按比例分配，不写死像素
            MySection(
                ui = ui, live = live, onOpenModels = onOpenModels,
                onM0 = { vm.switchMode("M0") }, onM3 = { vm.switchMode("M3") }, onEnd = finish,
                modifier = Modifier.weight(0.42f),
            )
            SeparatorNote()
            FacingPanel(ui = ui, onNoThanks = { vm.politeExit() }, modifier = Modifier.weight(0.58f))
        }
    }
}

// ---------- 上 1/3：我这半屏（私听，字小）----------

/** 状态行 → 两句小字（可滚，不把控制条挤出去）→ 玻璃控制条 → 无耳机提示。 */
@Composable
private fun MySection(
    ui: LiveUiState, live: Boolean, onOpenModels: () -> Unit,
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
        // 状态行：脉冲点 + 「我这半屏 · 私听，字小」（非进行中时改显示状态机提示）+ 语言对
        val stateLabel = m1StateLabel(ui.state)
        val stateHint = ui.hint.takeIf { it.isNotBlank() && it != stateLabel }?.let { " · $it" } ?: ""
        Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            BreathDot(active = live, size = 8.dp, halo = false)
            SceneText(
                if (live) "我这半屏 · 私听，字小" else stateLabel + stateHint,
                Modifier.weight(1f), style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            SceneText("${Lang.displayName(ui.myLang)} ⇄ ${Lang.displayName(ui.otherLang)}", style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1)
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OtherToMe(ui.lastOther, voiceOut = ui.voiceOut, headset = !noHeadset && output != null && output != AudioRoute.None)
            MeToOther(ui.lastMe)
            EngineNotice(ui.engine, ui.error, onOpenModels)
        }

        ControlBar(onM0 = onM0, onM3 = onM3, onEnd = onEnd)

        // 原型「摘下耳机 → M3」是自动降级；这里改为手动：没有耳机时提示改用双屏对话（点提示直接切）
        if (noHeadset) {
            Row(
                Modifier.fillMaxWidth().clip(CircleShape).clickable(role = Role.Button, onClick = onM3).padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                SceneIcon(SceneIcons.Headphones, contentDescription = null, size = 14.dp, tint = c.onWarningSoft)
                SceneText("没有耳机：改用双屏对话", style = SceneTheme.type.caption1.copy(fontWeight = FontWeight.SemiBold), color = c.onWarningSoft, maxLines = 1)
            }
        }
    }
}

/** 「对方 → 我 · 已进耳机」：译文 17 pt 粗 + 原文 13 pt 次要色；还没有句子时占位灰字。 */
@Composable
private fun OtherToMe(line: LiveLine?, voiceOut: Boolean, headset: Boolean) {
    val c = SceneTheme.colors
    val delivery = when {
        !voiceOut -> "仅上屏"
        line?.tts == PlaybackStatus.PLAYING -> "正在播"
        line?.tts == PlaybackStatus.QUEUED -> "待播"
        line?.tts == PlaybackStatus.SHOWN_ON_SCREEN || line?.tts == PlaybackStatus.SKIPPED -> "已上屏"
        line?.tts == PlaybackStatus.MERGED -> "已合并"
        headset -> "已进耳机"
        else -> "已外放"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SceneText("对方 → 我 · $delivery", style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1)
            if (line != null) MtCapsule(line)
        }
        if (line == null) {
            SceneText("等对方开口，译文会进耳机、也在这里出现", style = SceneTheme.type.subheadline, color = c.tertiaryLabel, maxLines = 2)
        } else {
            SceneText(line.translation ?: line.text, style = SceneTheme.type.headline, color = c.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (line.translation != null) SceneText(line.text, style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 「我 → 对方 · 已上屏」：我说的原文 13 pt（译文在对方半屏大字显示，这里不重复）。 */
@Composable
private fun MeToOther(line: LiveLine?) {
    val c = SceneTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SceneText(if (line == null || line.translation != null) "我 → 对方 · 已上屏" else "我 → 对方 · 翻译中", style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1)
            if (line != null) MtCapsule(line)
            if (line?.dirTentative == true) SceneCapsule("判向未定 ?", tone = CapsuleTone.Gray)
        }
        SceneText(
            line?.text ?: "开口说话，译文会以大字出现在对方半屏",
            style = SceneTheme.type.footnote, color = if (line == null) c.tertiaryLabel else c.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 翻译状态胶囊（与 M0 的 LineHeader 同一语义）：未译 / 降级；正常不显示。 */
@Composable
private fun MtCapsule(line: LiveLine) {
    when {
        line.translation == null && line.mtDegraded -> SceneCapsule("未译", tone = CapsuleTone.Destructive)
        line.mtDegraded -> SceneCapsule("降级", tone = CapsuleTone.Warning)
    }
}

/** 引擎 / 采集状态的行内提示（不弹窗）：Error → 红字 + 「去下载模型」；Loading → 灰字；采集错误红字。 */
@Composable
private fun EngineNotice(engine: LocalEngineState, error: String?, onOpenModels: () -> Unit) {
    val c = SceneTheme.colors
    when (engine) {
        is LocalEngineState.Error -> Column(verticalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneText("端侧引擎错误：${engine.reason}", style = SceneTheme.type.footnote, color = c.destructive)
            SceneButton("去下载模型", onClick = onOpenModels, style = ButtonStyle.Tinted, height = 44.dp)
        }
        LocalEngineState.Loading -> SceneText("端侧模型装载中…", style = SceneTheme.type.footnote, color = c.secondaryLabel)
        else -> {}
    }
    if (error != null) SceneText(error, style = SceneTheme.type.footnote, color = c.destructive)
}

/**
 * 玻璃控制条（原型 `.glass` 52 pt 胶囊）：「回 M0」/「双屏 M3」/ 结束（红圆 44）。
 * 它在内容层里，不能取样正在录制的 backdrop（会自己画自己），所以 `backdrop = null` → 高填充材质回退。
 */
@Composable
private fun ControlBar(onM0: () -> Unit, onM3: () -> Unit, onEnd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(CircleShape, backdrop = null, elevation = 6.dp).height(52.dp).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton("回 M0", onClick = onM0, filled = true, modifier = Modifier.weight(1f))
        BarButton("双屏 M3", onClick = onM3, filled = false, modifier = Modifier.weight(1f))
        SceneIconButton(SceneIcons.Stop, contentDescription = "结束会话", onClick = onEnd, size = 44.dp, style = ButtonStyle.Destructive)
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

/** 分隔说明：旋转图标 + 「下半屏朝向对方 · 已旋转 180°」。 */
@Composable
private fun SeparatorNote() {
    val c = SceneTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.page, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        SceneIcon(SceneIcons.Rotate, contentDescription = null, size = 14.dp, tint = c.secondaryLabel)
        SceneText("下半屏朝向对方 · 已旋转 180°", style = SceneTheme.type.caption2, color = c.secondaryLabel, maxLines = 1)
    }
}

// ---------- 下 2/3：对方半屏（旋转 180°，常驻浅色底）----------

/**
 * 对方半屏：整块 `rotate(180f)`（触控随层变换，按钮照常可点）。
 * 无论系统深浅色都用浅色令牌（沉浸式深色只给 M3 / S4）；边缘 1 dp 描边在对方说话时作呼吸灯。
 * 布局：礼貌卡 / 字幕区（占满）→ 常驻「No thanks, I'm fine」（Gray，50 pt）。
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
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())   // 旋转后顶行落在 Home 指示条上，留 inset
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

/** 礼貌卡（进入 M1 后 3 s）：录音点 + 麦克风 + 「Recording · text only, no audio is kept」→ 28 pt 礼貌语（按对方语言）。 */
@Composable
private fun PoliteCard(lang: String) {
    val c = SceneTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).drawBehind { drawCircle(c.recording) })
            SceneIcon(SceneIcons.Mic, contentDescription = null, size = 16.dp, tint = c.onDestructiveSoft)
            SceneText(recordingNotice(lang), style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.onDestructiveSoft, maxLines = 2)
        }
        SceneText(Phrases.politeCard(lang), style = SceneTheme.type.title1, color = c.label)
    }
}

/**
 * 字幕区（礼貌卡淡出后）：
 * - 头行：对方说话中 → 呼吸灯 + Listening…（灰字）；最新一句我说的有译文 → 对勾 + Received；译文未到 → 「…」灰胶囊；还没说 → 静止点 + Listening…
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
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SceneCapsule("…", tone = CapsuleTone.Gray)
                if (me.mtDegraded && me.mtReason != null) SceneCapsule(me.mtReason, tone = CapsuleTone.Warning)
            }
        }
        // 大字区：短句从上排，长句可滚
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (finalText != null) SceneText(finalText, style = SceneTheme.type.facingScreen, color = c.label)
            if (partial != null) SceneText(partial, style = SceneTheme.type.title1.copy(fontWeight = FontWeight.Normal), color = c.tertiaryLabel)   // 对方半屏 ≥ 28 pt
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

/** 「Received」头行：对勾 = 「已收到」，消解「听到了吗？」的反复确认（规格 §3.5）。 */
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

/** 对方半屏边缘的呼吸灯（规格 §3.5「半屏边缘有呼吸灯 = 我在听」）：说话中青绿 1 dp 描边 alpha 脉冲，Reduce Motion 静止；否则分隔线色。 */
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

/** 导航栏健康胶囊：与 M0 同一做法（翻译健康为主；语音异常时并入并缩短文案）。两页各自私有，待抽到共享处。 */
private fun m1HealthCapsule(h: PipelineHealth, voiceOut: Boolean, live: Boolean): Pair<String, CapsuleTone> {
    val (mt, tone) = when (h.mt) {
        "ok" -> (if (live) "混合档 · 正常" else "混合档") to CapsuleTone.Tint
        "slow" -> "云端慢" to CapsuleTone.Gray
        "fallback" -> "已降级" to CapsuleTone.Warning
        "unavailable" -> "无翻译" to CapsuleTone.Destructive
        else -> h.mt to CapsuleTone.Gray
    }
    val tts = when {
        !voiceOut -> null
        h.tts == "无" -> "无语音"
        h.tts == "失败" -> "语音失败"
        else -> null
    }
    return if (tts == null) mt to tone
    else "${mt.substringAfterLast(" · ")} · $tts" to (if (tone == CapsuleTone.Tint) CapsuleTone.Warning else tone)
}

private fun m1StateLabel(s: LiveState): String = when (s) {
    LiveState.Idle -> "待机"; LiveState.Arming -> "准备中"; is LiveState.Live -> "进行中"; is LiveState.Paused -> "已暂停"
    LiveState.NeedForeground -> "需要前台"; LiveState.Degraded -> "降级"; LiveState.Ending -> "结束中"
}
