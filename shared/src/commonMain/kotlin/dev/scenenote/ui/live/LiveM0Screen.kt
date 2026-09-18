package dev.scenenote.ui.live

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.scenenote.asr.LocalEngineState
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
import dev.scenenote.core.designsystem.SceneRadius
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.model.LiveState
import dev.scenenote.core.model.PlaybackStatus
import dev.scenenote.core.model.Speaker
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.core.settings.KeyWallet
import dev.scenenote.live.LiveLine
import dev.scenenote.live.PipelineHealth
import dev.scenenote.shared.resources.*
import dev.scenenote.ui.i18n.langName
import org.jetbrains.compose.resources.StringResource
import dev.scenenote.core.i18n.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * 仅听：手机在口袋时偶尔瞥一眼。
 * 导航（返回 / 「仅听」/ 一词状态胶囊）→ 状态行 → 历史句淡出 → 当前句 → 对方 partial → 一行「举起手机」提示；
 * 底部 dock：速译（圆）/ 暂停·继续 72 pt 主钮 / 语音开关 / 结束（红圆）。内容贴底排布。
 * 页面上不出现工程数据（装载耗时 / 路由 / 引擎名），见 docs/15。
 */
@Composable
fun LiveM0Screen(
    sceneId: String, onBack: () -> Unit, onOpenModels: (List<String>) -> Unit, autostart: Boolean, otherLang: String, myLang: String, feed: String,
    onOpenQuickPhrase: () -> Unit,
    vm: LiveViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { vm.enter(sceneId, myLang, otherLang, feed, autostart) }
    val ui by vm.ui.collectAsState()
    val settings = koinInject<AppSettings>()
    val wallet = koinInject<KeyWallet>()
    val c = SceneTheme.colors
    val motion = SceneTheme.motion
    val scroll = rememberScrollState()
    val live = ui.state is LiveState.Live
    val finish: () -> Unit = { vm.end(); onBack() }
    // 一次性引导：首次进入显示气泡，本次停留期间保持，下次起只留一行灰字
    val firstVisit = remember { !settings.hintSeen(HINT_KEY) }
    LaunchedEffect(Unit) { if (firstVisit) settings.markHintSeen(HINT_KEY) }
    val hasKey = remember { wallet.anyKey() }

    // 新句 / partial 到达时贴底：仅听是"瞥一眼"的界面，最新内容永远在拇指区上方
    LaunchedEffect(scroll.maxValue) { if (motion.reduced) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue) }

    GlassScaffold(
        background = c.systemBackground,
        topBar = {
            val (label, tone) = statusCapsule(ui.health, ui.voiceOut, hasKey)
            SceneNavBar(
                title = stringResource(Res.string.scene_listen),
                onBack = finish,
                trailing = { SceneCapsule(stringResource(label), tone = tone) },
            )
        },
        bottomBar = {
            SceneDock {
                SceneIconButton(SceneIcons.Mic, contentDescription = stringResource(Res.string.live_quick_phrase_cd), onClick = { vm.end(); onOpenQuickPhrase() }, size = 56.dp)   // 先结束本会话再切速译（单例状态机 / 麦克风）
                PauseResumeButton(ui.state, onPause = vm::pause, onResume = vm::trigger)
                SceneIconButton(SceneIcons.Speaker, contentDescription = stringResource(if (ui.voiceOut) Res.string.live_voice_on else Res.string.live_voice_off), onClick = { vm.setVoiceOut(!ui.voiceOut) }, size = 56.dp, style = if (ui.voiceOut) ButtonStyle.Tinted else ButtonStyle.Gray)
                SceneIconButton(SceneIcons.Stop, contentDescription = stringResource(Res.string.live_end), onClick = finish, size = 56.dp, style = ButtonStyle.Destructive)
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 104.dp, bottom = 140.dp, start = SceneSpacing.page, end = SceneSpacing.page),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.Bottom),
        ) {
            StatusRow(state = ui.state, live = live)

            // 历史句淡出：越旧越淡
            val history = ui.history.takeLast(4)
            history.forEachIndexed { i, line ->
                val fade = (0.75f - 0.15f * (history.size - 1 - i)).coerceAtLeast(0.3f)
                HistoryLine(line, Modifier.alpha(fade))
            }

            val current = ui.current
            if (current != null) CurrentLine(current) else EmptyGuide(bubble = firstVisit)

            if (ui.partial.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(SceneSpacing.xs)) {
                    SceneText(stringResource(Res.string.live_other_speaking), style = SceneTheme.type.footnote, color = c.secondaryLabel)
                    SceneText(ui.partial, style = SceneTheme.type.title3.copy(fontWeight = FontWeight.Normal), color = c.tertiaryLabel)
                }
            }

            EngineNotice(ui.engine, ui.error, onOpenModels)
            NmtNotice(ui, onOpenModels)
            PostureHint(onEnter = { vm.switchMode("M1") })
        }
    }
}

private const val HINT_KEY = "m0"

/** 状态行：脉冲点 + 一个状态词（进行中 = 「正在听对方」）。 */
@Composable
private fun StatusRow(state: LiveState, live: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically) {
        PulseDot(active = live)
        SceneText(stringResource(stateWord(state)), Modifier.weight(1f), style = SceneTheme.type.footnote, color = SceneTheme.colors.secondaryLabel, maxLines = 1)
    }
}

/** 脉冲点：进行中青绿 + 呼吸光晕（Reduce Motion 时静止）；暂停 / 待机为灰点。 */
@Composable
private fun PulseDot(active: Boolean) {
    val c = SceneTheme.colors
    val halo = if (active && !SceneTheme.motion.reduced) {
        rememberInfiniteTransition(label = "pulse")
            .animateFloat(0.55f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "halo").value
    } else 1f
    Box(
        Modifier.size(24.dp).drawBehind {
            if (active) drawCircle(c.tint.copy(alpha = 0.18f), radius = 12.dp.toPx() * halo)
            drawCircle(if (active) c.tint else c.tertiaryLabel, radius = 6.dp.toPx())
        },
    )
}

/** 历史句：方向小字 + 译文 17 pt + 原文 13 pt 次要色 + 例外胶囊。 */
@Composable
private fun HistoryLine(line: LiveLine, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LineHeader(line, color = c.secondaryLabel)
        SceneText(line.translation ?: line.text, style = SceneTheme.type.body, color = c.label)
        if (line.translation != null) SceneText(line.text, style = SceneTheme.type.footnote, color = c.secondaryLabel)
        LineFlags(line)
    }
}

/** 当前句：方向 + 语言对 → 译文 28 pt → 原文 15 pt → 例外胶囊。 */
@Composable
private fun CurrentLine(line: LiveLine) {
    val c = SceneTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LineHeader(line, color = c.onTintSoft, showLangs = true)
        SceneText(line.translation ?: line.text, style = SceneTheme.type.title1, color = c.label)
        if (line.translation != null) SceneText(line.text, style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        LineFlags(line)
    }
}

/** 空态引导：首次进入是青绿气泡，之后只留一行灰字；同时占住当前句的位置，避免版面跳动。 */
@Composable
private fun EmptyGuide(bubble: Boolean) {
    val c = SceneTheme.colors
    if (bubble) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(SceneRadius.m)).background(c.tintSoft).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            SceneIcon(SceneIcons.Headphones, contentDescription = null, size = 22.dp, tint = c.onTintSoft)
            SceneText(stringResource(Res.string.live_m0_guide), style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.onTintSoft)
        }
    } else {
        SceneText(stringResource(Res.string.live_m0_guide), style = SceneTheme.type.title3.copy(fontWeight = FontWeight.Normal), color = c.tertiaryLabel)
    }
}

/** 一句的方向行：「对方」/「我」（当前句附语言对）+ 播放中的小喇叭。 */
@Composable
private fun LineHeader(line: LiveLine, color: Color, showLangs: Boolean = false) {
    val c = SceneTheme.colors
    val who = stringResource(if (line.speaker == Speaker.ME) Res.string.common_me else Res.string.common_other)
    val direction = if (showLangs) stringResource(Res.string.live_direction_langs, who, langName(line.srcLang), langName(line.tgtLang)) else who
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SceneText(direction, style = SceneTheme.type.footnote, color = color, maxLines = 1)
        if (line.tts == PlaybackStatus.PLAYING) SceneIcon(SceneIcons.Speaker, contentDescription = stringResource(Res.string.live_playing), size = 14.dp, tint = c.tint)
    }
}

/** 译文下方只允许两种例外胶囊：「没播完」（被打断）/「未翻译」（翻译失败）。都没有就不占位。 */
@Composable
private fun LineFlags(line: LiveLine) {
    val untranslated = line.translation == null && line.mtDegraded
    if (!line.interrupted && !untranslated) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (untranslated) SceneCapsule(stringResource(Res.string.live_flag_untranslated), tone = CapsuleTone.Destructive)
        if (line.interrupted) SceneCapsule(stringResource(Res.string.live_flag_interrupted), tone = CapsuleTone.Warning)
    }
}

/** 语音包缺失：一句话 + 「去下载」；录音出错：一句话。其余引擎状态不显示。 */
@Composable
private fun EngineNotice(engine: LocalEngineState, error: String?, onOpenModels: (List<String>) -> Unit) {
    val c = SceneTheme.colors
    if (engine is LocalEngineState.Error) {
        Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            SceneText(stringResource(Res.string.live_pack_missing), Modifier.weight(1f), style = SceneTheme.type.footnote, color = c.destructive)
            SceneButton(stringResource(Res.string.live_download), onClick = { onOpenModels(emptyList()) }, style = ButtonStyle.Tinted, height = SceneSize.glassButton)
        }
    }
    if (error != null) SceneText(stringResource(Res.string.live_record_error), style = SceneTheme.type.footnote, color = c.destructive)
}

/** 一行轻提示「举起手机朝向对方 → 面屏」：可点手动进入面屏。 */
@Composable
private fun PostureHint(onEnter: () -> Unit) {
    val c = SceneTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(SceneRadius.m))
            .clickable(role = Role.Button, onClick = onEnter)
            .defaultMinSize(minHeight = SceneSize.touchTarget).padding(horizontal = SceneSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically,
    ) {
        SceneIcon(SceneIcons.Rotate, contentDescription = null, size = 18.dp, tint = c.secondaryLabel)
        SceneText(stringResource(Res.string.live_posture_hint), Modifier.weight(1f), style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1)
        SceneIcon(SceneIcons.ChevronRight, contentDescription = null, size = 16.dp, tint = c.tertiaryLabel)
    }
}

/** dock 主钮 72 pt：进行中 → 暂停；待机 / 已暂停 → 开始 / 继续；准备中 / 结束中禁用。 */
@Composable
private fun PauseResumeButton(state: LiveState, onPause: () -> Unit, onResume: () -> Unit) {
    val (label, icon, enabled) = when (state) {
        is LiveState.Live -> Triple(Res.string.live_pause, SceneIcons.Pause, true)
        LiveState.Idle -> Triple(Res.string.live_start, SceneIcons.Play, true)
        LiveState.Arming -> Triple(Res.string.live_arming, SceneIcons.Play, false)
        LiveState.Ending -> Triple(Res.string.live_ending, SceneIcons.Stop, false)
        else -> Triple(Res.string.live_resume, SceneIcons.Play, true)
    }
    SceneButton(
        onClick = { if (state is LiveState.Live) onPause() else onResume() },
        modifier = Modifier.size(72.dp), style = ButtonStyle.Prominent, enabled = enabled, height = 72.dp, contentPadding = 0.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SceneIcon(icon, contentDescription = null, size = 26.dp)
            SceneText(stringResource(label), style = SceneTheme.type.caption1.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

/** 导航栏状态胶囊，只有一个词：无翻译 > 无语音 > 离线（未填 Key）> 已连接。 */
private fun statusCapsule(h: PipelineHealth, voiceOut: Boolean, hasKey: Boolean): Pair<StringResource, CapsuleTone> = when {
    h.mt == "unavailable" -> Res.string.live_status_no_mt to CapsuleTone.Destructive
    voiceOut && (h.tts == "none" || h.tts == "failed") -> Res.string.live_status_no_voice to CapsuleTone.Warning
    h.mt == "fallback" || !hasKey -> Res.string.live_status_offline to CapsuleTone.Gray
    else -> Res.string.live_status_connected to CapsuleTone.Tint
}

/** 状态行的一个词（不用 VM 的 hint：那里有工程措辞）。 */
private fun stateWord(s: LiveState): StringResource = when (s) {
    LiveState.Idle -> Res.string.live_state_idle
    LiveState.Arming -> Res.string.live_state_arming
    is LiveState.Live -> Res.string.live_state_listening
    is LiveState.Paused -> Res.string.live_state_paused
    LiveState.NeedForeground -> Res.string.live_state_tap_resume
    LiveState.Degraded -> Res.string.live_state_wear_earbuds
    LiveState.Ending -> Res.string.live_state_ending
}
