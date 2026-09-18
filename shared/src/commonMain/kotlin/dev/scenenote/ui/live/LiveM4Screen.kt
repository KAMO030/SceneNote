package dev.scenenote.ui.live

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scenenote.asr.LocalEngineState
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.CapsuleTone
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneCapsule
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneDock
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.designsystem.glass
import dev.scenenote.core.model.LiveState
import dev.scenenote.live.LiveLine
import dev.scenenote.shared.resources.*
import dev.scenenote.ui.i18n.forLang
import dev.scenenote.ui.i18n.langName
import org.jetbrains.compose.resources.StringResource
import dev.scenenote.core.i18n.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.PI
import kotlin.math.sin

/** 页面三态：idle 一行说明 → holding 波形 + partial → card 深色大字卡。 */
private enum class M4Phase { Idle, Holding, Card }

/**
 * 速译会话页：导航（返回 / 「速译」/ 语言对胶囊），内容层三态，dock 里 96 dp 胶囊「按住说话」→「松手 → 出字」→「再说一句 / 朗读 · 外放」。
 * 速译默认不出声：进页先关 voiceOut，朗读只由「朗读 · 外放」触发。页面上不出现工程数据（耗时 / 引擎名），见 docs/15。
 */
@Composable
fun LiveM4Screen(sceneId: String, onBack: () -> Unit, onOpenModels: (List<String>) -> Unit, autostart: Boolean, otherLang: String, myLang: String, feed: String) {
    val vm: LiveViewModel = koinViewModel()
    LaunchedEffect(Unit) { vm.enter(sceneId, myLang, otherLang, feed, autostart, voiceOut = false) }
    val ui by vm.ui.collectAsState()
    val c = SceneTheme.colors
    val current = ui.current
    val phase = when {
        ui.holding -> M4Phase.Holding
        current != null -> M4Phase.Card
        ui.state is LiveState.Live -> M4Phase.Holding
        else -> M4Phase.Idle
    }
    // 「朗读 · 外放」没有可用语音时的行内提示；换句即清
    var speakHint by remember { mutableStateOf<StringResource?>(null) }
    LaunchedEffect(current?.id) { speakHint = null }
    val playing = current != null && ui.playing == current.id
    val back: () -> Unit = { vm.end(); onBack() }

    GlassScaffold(
        background = c.systemBackground,
        topBar = {
            SceneNavBar(title = stringResource(Res.string.home_quick_phrase), onBack = back, trailing = { LangPairCapsule(ui.myLang, ui.otherLang) })
        },
        bottomBar = {
            SceneDock(height = 112.dp) {
                // 胶囊始终在树上（只换文案），否则切态时 pointerInput 被重建、松手事件会丢
                HoldCapsule(
                    holding = ui.holding,
                    label = stringResource(when {
                        ui.holding -> Res.string.m4_release_to_show
                        phase == M4Phase.Card -> Res.string.m4_say_another
                        else -> Res.string.m4_hold_to_talk
                    }),
                    onPress = { if (phase == M4Phase.Card) vm.clear(); vm.holdStart() },
                    onRelease = vm::holdEnd,
                    modifier = Modifier.weight(1f),
                )
                if (phase == M4Phase.Card) {
                    Spacer(Modifier.width(8.dp))
                    SceneButton(
                        onClick = { if (playing) vm.stopSpeaking() else if (!vm.speakCurrent()) speakHint = Res.string.live_status_no_voice },
                        style = ButtonStyle.Warning, height = 96.dp, contentPadding = 8.dp, modifier = Modifier.width(120.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SceneIcon(if (playing) SceneIcons.Stop else SceneIcons.Speaker, contentDescription = null, size = 22.dp)
                            SceneText(stringResource(if (playing) Res.string.m4_stop_speaking else Res.string.m4_speak_aloud), style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                            SceneText(stringResource(Res.string.live_will_play_aloud), style = SceneTheme.type.caption2, color = c.onWarningSoft, maxLines = 1)
                        }
                    }
                }
            }
        },
    ) {
        when (phase) {
            M4Phase.Idle -> IdleContent(ui, onOpenModels)
            M4Phase.Holding -> HoldingContent(ui, onOpenModels)
            M4Phase.Card -> PhraseCard(current!!, speakHint)
        }
    }
}

/** 导航栏右侧的语言对玻璃胶囊「普通话 → 英语」：速译单向 我 → 对方，不可点。 */
@Composable
private fun LangPairCapsule(myLang: String, otherLang: String) {
    Box(
        Modifier.glass(CircleShape, elevation = 6.dp).height(SceneSize.glassButton).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        SceneText(
            stringResource(Res.string.common_lang_pair, langName(myLang), langName(otherLang)),
            style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = SceneTheme.colors.label, maxLines = 1,
        )
    }
}

/**
 * dock 里的 96 dp 按住胶囊：按下 → [onPress]，抬起 / 取消 → [onRelease]。
 * 未按住 = prominent 青绿 + 麦克风；按住 = destructiveSoft 令牌「松手 → 出字」。
 */
@Composable
private fun HoldCapsule(holding: Boolean, label: String, onPress: () -> Unit, onRelease: () -> Unit, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val bg = if (holding) c.destructiveSoft else c.tint
    val fg = if (holding) c.onDestructiveSoft else c.onTint
    Row(
        modifier
            .shadow(if (holding) 0.dp else 6.dp, CircleShape, clip = false, ambientColor = c.tint.copy(alpha = 0.3f), spotColor = c.tint.copy(alpha = 0.3f))
            .clip(CircleShape)
            .background(bg)
            .height(96.dp)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    press()
                    tryAwaitRelease()
                    release()
                })
            }
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!holding) SceneIcon(SceneIcons.Mic, contentDescription = null, size = 28.dp, tint = fg)
        SceneText(label, style = SceneTheme.type.title3.copy(fontWeight = FontWeight.Bold), color = fg, maxLines = 1)
    }
}

/** idle：麦克风 + 一行说明。 */
@Composable
private fun IdleContent(ui: LiveUiState, onOpenModels: (List<String>) -> Unit) {
    val c = SceneTheme.colors
    Column(
        Modifier.fillMaxSize().padding(top = 104.dp, bottom = 164.dp, start = 32.dp, end = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SceneIcon(SceneIcons.Mic, contentDescription = null, size = 48.dp, tint = c.tint)
        SceneText(stringResource(Res.string.m4_idle_hint), style = SceneTheme.type.subheadline, color = c.secondaryLabel, textAlign = TextAlign.Center)
        EngineNotice(ui, onOpenModels)
    }
}

/** holding：波形 + 灰字 partial + 一行说明。未按住但会话已开始时波形静止、提示「正在出字」。 */
@Composable
private fun HoldingContent(ui: LiveUiState, onOpenModels: (List<String>) -> Unit) {
    val c = SceneTheme.colors
    Column(
        Modifier.fillMaxSize().padding(top = 104.dp, bottom = 164.dp, start = 32.dp, end = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Waveform(active = ui.holding)
        SceneText(ui.partial.ifBlank { "…" }, style = SceneTheme.type.title2.copy(fontWeight = FontWeight.SemiBold), color = c.tertiaryLabel, textAlign = TextAlign.Center)
        SceneText(stringResource(if (ui.holding) Res.string.m4_release_when_done else Res.string.m4_transcribing), style = SceneTheme.type.footnote, color = c.secondaryLabel, textAlign = TextAlign.Center)
        EngineNotice(ui, onOpenModels)
    }
}

private val WaveBars = listOf(18, 34, 46, 26, 40, 16, 30)

/** 七根波形柱：按住时起伏；Reduce Motion 或未按住时静止。 */
@Composable
private fun Waveform(active: Boolean) {
    val c = SceneTheme.colors
    val animate = active && !SceneTheme.motion.reduced
    val t = if (animate) {
        rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart)).value
    } else 0f
    Row(Modifier.height(48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        WaveBars.forEachIndexed { i, base ->
            val scale = if (animate) 0.45f + 0.55f * (0.5f + 0.5f * sin(2f * PI.toFloat() * (t + i * 0.19f))) else 1f
            Box(Modifier.width(5.dp).height((base * scale).dp).clip(RoundedCornerShape(3.dp)).background(c.tint))
        }
    }
}

/** 行内提示（不弹窗）：准备中灰字；语音包缺失一句话 + 「去下载」；暂停 / 需前台一个词；录音出错一句话。 */
@Composable
private fun EngineNotice(ui: LiveUiState, onOpenModels: (List<String>) -> Unit) {
    val c = SceneTheme.colors
    when (ui.engine) {
        LocalEngineState.Loading -> SceneText(stringResource(Res.string.live_state_arming), style = SceneTheme.type.footnote, color = c.secondaryLabel, textAlign = TextAlign.Center)
        is LocalEngineState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SceneText(stringResource(Res.string.live_pack_missing), style = SceneTheme.type.footnote, color = c.destructive, textAlign = TextAlign.Center)
            SceneButton(stringResource(Res.string.live_download), onClick = { onOpenModels(emptyList()) }, style = ButtonStyle.Tinted, height = SceneSize.glassButton)
        }
        else -> {}
    }
    stateWord(ui.state)?.let { SceneText(stringResource(it), style = SceneTheme.type.footnote, color = c.secondaryLabel, textAlign = TextAlign.Center) }
    if (ui.error != null) SceneText(stringResource(Res.string.live_record_error), style = SceneTheme.type.footnote, color = c.destructive, textAlign = TextAlign.Center)
}

/** 非待机 / 非进行中状态的一个词（不用 VM 的 hint：那里有工程措辞）；准备中已由引擎行覆盖，返回 null。 */
private fun stateWord(s: LiveState): StringResource? = when (s) {
    is LiveState.Paused -> Res.string.live_state_paused
    LiveState.NeedForeground -> Res.string.live_state_tap_resume
    LiveState.Ending -> Res.string.live_state_ending
    else -> null
}

/**
 * card：深色大字卡（常驻深色 → 子树套 `SceneTheme(dark = true)` 用深色令牌，不写死颜色）。
 * 只有译文 + 原文：38 sp 译文（缺译文时显示原文）+ 15 sp 原文；翻译失败时一个「未翻译」胶囊。
 */
@Composable
private fun PhraseCard(line: LiveLine, speakHint: StringResource?) {
    SceneTheme(dark = true, accessibility = SceneTheme.a11y) {
        val c = SceneTheme.colors
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 104.dp, bottom = 164.dp, start = 12.dp, end = 12.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(c.secondarySystemBackground)
                .padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 大字区：短句垂直居中，长句可滚
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight), contentAlignment = Alignment.CenterStart) {
                    SceneText(
                        line.translation ?: line.text,
                        style = SceneTheme.type.facingScreen.copy(fontSize = 38.sp, lineHeight = 45.sp).forLang(line.tgtLang),
                        color = c.label,
                    )
                }
            }
            if (line.translation != null) {
                Column {
                    SceneDivider(inset = 0.dp)
                    SceneText(line.text, modifier = Modifier.padding(top = 12.dp), style = SceneTheme.type.subheadline, color = c.secondaryLabel)
                }
            }
            val untranslated = line.translation == null && line.mtDegraded
            val translating = line.translation == null && !line.mtDegraded
            if (untranslated || translating || speakHint != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (untranslated) SceneCapsule(stringResource(Res.string.live_flag_untranslated), tone = CapsuleTone.Destructive)
                    if (translating) SceneText(stringResource(Res.string.m4_translating), style = SceneTheme.type.caption1, color = c.secondaryLabel)
                    if (speakHint != null) SceneCapsule(stringResource(speakHint), tone = CapsuleTone.Warning)
                }
            }
        }
    }
}
