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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.scenenote.asr.LocalEngineState
import dev.scenenote.audio.AudioRoute
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
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.LiveState
import dev.scenenote.core.model.PlaybackStatus
import dev.scenenote.core.model.Speaker
import dev.scenenote.live.LiveLine
import dev.scenenote.live.PipelineHealth
import org.koin.compose.viewmodel.koinViewModel

/**
 * M0 仅听（原型 LiveM0.dc.html）：手机在口袋时偶尔瞥一眼。
 * 玻璃导航（返回 / 「M0 仅听」/ 健康胶囊）→ 状态行 → 历史句淡出 → 当前句 28 pt → 对方 partial → M1 卡（I4）→ 震动语义；
 * 底部 dock：速译（圆）/ 暂停·继续 72 pt 主钮 / 语音开关 / 结束（红圆）。内容贴底排布，与原型 `justify-content: flex-end` 一致。
 */
@Composable
fun LiveM0Screen(
    sceneId: String, onBack: () -> Unit, onOpenModels: () -> Unit, autostart: Boolean, otherLang: String, myLang: String, feed: String,
    onOpenQuickPhrase: () -> Unit,
    vm: LiveViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) { vm.enter(sceneId, myLang, otherLang, feed, autostart) }
    val ui by vm.ui.collectAsState()
    val c = SceneTheme.colors
    val motion = SceneTheme.motion
    val scroll = rememberScrollState()
    val live = ui.state is LiveState.Live
    val finish: () -> Unit = { vm.end(); onBack() }

    // 新句 / partial 到达时贴底：仅听是"瞥一眼"的界面，最新内容永远在拇指区上方
    LaunchedEffect(scroll.maxValue) { if (motion.reduced) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue) }

    GlassScaffold(
        background = c.systemBackground,
        topBar = {
            val (label, tone) = healthCapsule(ui.health, ui.voiceOut, live)
            SceneNavBar(
                title = ui.mode?.let { if (it.id == "M0") "M0 仅听" else "${it.id} ${it.name}" } ?: "M0 仅听",
                onBack = finish, backContentDescription = "返回实时",
                trailing = { SceneCapsule(label, tone = tone) },
            )
        },
        bottomBar = {
            SceneDock {
                SceneIconButton(SceneIcons.Mic, contentDescription = "速译一句", onClick = { vm.end(); onOpenQuickPhrase() }, size = 56.dp)   // 先结束本会话再切 M4（单例状态机 / 麦克风）
                PauseResumeButton(ui.state, onPause = vm::pause, onResume = vm::trigger)
                SceneIconButton(SceneIcons.Speaker, contentDescription = if (ui.voiceOut) "语音输出：开" else "语音输出：关", onClick = { vm.setVoiceOut(!ui.voiceOut) }, size = 56.dp, style = if (ui.voiceOut) ButtonStyle.Tinted else ButtonStyle.Gray)
                SceneIconButton(SceneIcons.Stop, contentDescription = "结束会话", onClick = finish, size = 56.dp, style = ButtonStyle.Destructive)
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(top = 104.dp, bottom = 140.dp, start = SceneSpacing.page, end = SceneSpacing.page),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.Bottom),
        ) {
            StatusRow(ui = ui, live = live)

            // 历史句淡出：越旧越淡（原型两条分别 0.45 / 0.7）
            val history = ui.history.takeLast(4)
            history.forEachIndexed { i, line ->
                val fade = (0.75f - 0.15f * (history.size - 1 - i)).coerceAtLeast(0.3f)
                HistoryLine(line, Modifier.alpha(fade))
            }

            val current = ui.current
            if (current != null) CurrentLine(current) else EmptyCurrent(live)

            if (ui.partial.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(SceneSpacing.xs)) {
                    SceneText("对方在说…", style = SceneTheme.type.footnote, color = c.secondaryLabel)
                    SceneText(ui.partial, style = SceneTheme.type.title3.copy(fontWeight = FontWeight.Normal), color = c.tertiaryLabel)
                }
            }

            EngineStatus(ui.engine, ui.error, onOpenModels)
            if (ui.mode?.id == "M0") PostureCard(ui.autoPosture, onEnter = { vm.switchMode("M1") })
            else SceneText("耳听·面屏 / 双屏对话页面稍后开放，先按仅听运行", Modifier.fillMaxWidth(), style = SceneTheme.type.caption1, color = c.secondaryLabel, textAlign = TextAlign.Center)
            SceneText(
                "震动：短-短 开始监听 · 短 译文就绪 · 长 方向切换",
                Modifier.fillMaxWidth(), style = SceneTheme.type.caption1, color = c.secondaryLabel, textAlign = TextAlign.Center,
            )
        }
    }
}

/** 状态行：脉冲点 + 「进行中 · 正在听对方」+ 「译文 → 当前输出 · 耳语档」。 */
@Composable
private fun StatusRow(ui: LiveUiState, live: Boolean) {
    val c = SceneTheme.colors
    val state = m0StateLabel(ui.state)
    val hint = ui.hint.takeIf { it.isNotBlank() && it != state }
    val destination = when {
        !ui.voiceOut -> "译文 → 屏幕 · 语音关"
        ui.health.tts == "无" || ui.health.tts == "失败" -> "译文 → 屏幕 · 无语音"
        else -> "译文 → ${routeName(ui.route?.output)} · 耳语档"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically) {
        PulseDot(active = live)
        SceneText(if (hint == null) state else "$state · $hint", Modifier.weight(1f), style = SceneTheme.type.footnote, color = c.secondaryLabel)
        SceneText(destination, style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1)
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

/** 历史句：方向小字 + 译文 17 pt + 原文 13 pt 次要色。 */
@Composable
private fun HistoryLine(line: LiveLine, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LineHeader(line, color = c.secondaryLabel)
        SceneText(line.translation ?: line.text, style = SceneTheme.type.body, color = c.label)
        if (line.translation != null) SceneText(line.text, style = SceneTheme.type.footnote, color = c.secondaryLabel)
    }
}

/** 当前句：方向 + 语言对 + 翻译胶囊 → 译文 28 pt → 原文 15 pt。 */
@Composable
private fun CurrentLine(line: LiveLine) {
    val c = SceneTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LineHeader(line, color = c.onTintSoft, showLangs = true)
        SceneText(line.translation ?: line.text, style = SceneTheme.type.title1, color = c.label)
        when {
            line.translation != null -> SceneText(line.text, style = SceneTheme.type.subheadline, color = c.secondaryLabel)
            line.mtReason != null -> SceneText(line.mtReason, style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        }
    }
}

/** 还没有句子时占住当前句的位置，避免版面跳动。 */
@Composable
private fun EmptyCurrent(live: Boolean) {
    SceneText(
        if (live) "等对方开口，译文会出现在这里" else "开始后，对方的话会出现在这里",
        style = SceneTheme.type.title3.copy(fontWeight = FontWeight.Normal), color = SceneTheme.colors.tertiaryLabel,
    )
}

/** 一句的方向行：「对方 → 我」/「我 → 对方 · M0 下对方听不到译文」+ 降级 / 云翻译胶囊 + 播放中标记。 */
@Composable
private fun LineHeader(line: LiveLine, color: Color, showLangs: Boolean = false) {
    val c = SceneTheme.colors
    val direction = if (line.speaker == Speaker.ME) "我 → 对方 · M0 下对方听不到译文"
    else if (showLangs) "对方 → 我 · ${Lang.displayName(line.srcLang)} → ${Lang.displayName(line.tgtLang)}" else "对方 → 我"
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SceneText(direction, style = SceneTheme.type.footnote, color = color)
        when {
            line.translation == null && line.mtDegraded -> SceneCapsule("未译", tone = CapsuleTone.Destructive)
            line.mtDegraded -> SceneCapsule("降级", tone = CapsuleTone.Warning)
            line.translation != null && line.mtLatencyMs != null && line.mtEngine?.startsWith("none") != true ->
                SceneCapsule("云翻译 ${tenths(line.mtLatencyMs)} s", tone = CapsuleTone.Tint)
        }
        if (line.tts == PlaybackStatus.PLAYING) {
            SceneIcon(SceneIcons.Speaker, contentDescription = null, size = 14.dp, tint = c.tint)
            SceneText("播放中", style = SceneTheme.type.caption2, color = c.tint)
        }
    }
}

/** 引擎状态：Error → 原因 + 「去下载模型」；Loading → 装载中；另附采集错误。 */
@Composable
private fun EngineStatus(engine: LocalEngineState, error: String?, onOpenModels: () -> Unit) {
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

/** 青绿卡「竖起手机朝向对方 → M1」：可点手动进入；姿态自动检测开着时右侧显示「自动」，否则「点此进入」。 */
@Composable
private fun PostureCard(autoPosture: Boolean, onEnter: () -> Unit) {
    val c = SceneTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(SceneRadius.m)).background(c.tintSoft)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onEnter).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SceneIcon(SceneIcons.Rotate, contentDescription = null, size = 22.dp, tint = c.onTintSoft)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                SceneText("竖起手机朝向对方 → M1 耳听 · 面屏", style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = c.onTintSoft)
                SceneText("姿态自动检测，不重启音频；对方看半屏大字", style = SceneTheme.type.caption1, color = c.onTintSoft)
            }
        }
        SceneText(if (autoPosture) "自动 · 或点此" else "点此进入", style = SceneTheme.type.caption1, color = c.secondaryLabel)
    }
}

/** dock 主钮 72 pt：Live → 暂停；Idle / Paused → 开始 / 继续；Arming / Ending 禁用。 */
@Composable
private fun PauseResumeButton(state: LiveState, onPause: () -> Unit, onResume: () -> Unit) {
    val (label, icon, enabled) = when (state) {
        is LiveState.Live -> Triple("暂停", SceneIcons.Pause, true)
        LiveState.Idle -> Triple("开始", SceneIcons.Play, true)
        LiveState.Arming -> Triple("准备中", SceneIcons.Play, false)
        LiveState.Ending -> Triple("结束中", SceneIcons.Stop, false)
        else -> Triple("继续", SceneIcons.Play, true)
    }
    SceneButton(
        onClick = { if (state is LiveState.Live) onPause() else onResume() },
        modifier = Modifier.size(72.dp), style = ButtonStyle.Prominent, enabled = enabled, height = 72.dp, contentPadding = 0.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SceneIcon(icon, contentDescription = null, size = 26.dp)
            SceneText(label, style = SceneTheme.type.caption1.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

/** 导航栏健康胶囊：翻译健康为主；语音异常时并入（缩短翻译文案，避免与标题胶囊重叠）。 */
private fun healthCapsule(h: PipelineHealth, voiceOut: Boolean, live: Boolean): Pair<String, CapsuleTone> {
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

private fun m0StateLabel(s: LiveState): String = when (s) {
    LiveState.Idle -> "待机"; LiveState.Arming -> "准备中"; is LiveState.Live -> "进行中"; is LiveState.Paused -> "已暂停"
    LiveState.NeedForeground -> "需要前台"; LiveState.Degraded -> "降级"; LiveState.Ending -> "结束中"
}

/** 输出设备的中文名（仅展示；App 不区分耳机与扬声器）。 */
private fun routeName(r: AudioRoute?): String = when (r) {
    AudioRoute.BluetoothA2dp, AudioRoute.BluetoothHfp -> "蓝牙耳机"
    AudioRoute.Wired -> "有线耳机"
    AudioRoute.Speaker -> "扬声器"
    AudioRoute.BuiltIn -> "听筒"
    AudioRoute.None, null -> "耳机"
}

/** 毫秒 → 一位小数的秒（common 无 String.format）。 */
private fun tenths(ms: Long): String { val t = (ms + 50) / 100; return "${t / 10}.${t % 10}" }
