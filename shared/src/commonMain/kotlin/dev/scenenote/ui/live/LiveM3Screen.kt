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
import androidx.compose.ui.text.style.TextAlign
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
import dev.scenenote.core.designsystem.SceneIcon
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
import dev.scenenote.core.model.PlaybackStatus
import dev.scenenote.core.model.Speaker
import dev.scenenote.core.platform.ThermalLevel
import dev.scenenote.live.LiveLine
import dev.scenenote.live.Phrases
import dev.scenenote.live.PipelineHealth
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 每个半屏最多显示的气泡数（更早的句子仍在会话里，结束页可见）。 */
private const val MaxBubbles = 8

/**
 * M3 双屏对话（原型 LiveM3.dc.html，14 篇 §1 ② / §2「深色仅用于 M3 双屏」）：无耳机，两人各看各的半屏。
 * - 常驻深色：整页套 `SceneTheme(dark = true)` 用深色令牌，不写死颜色（与 M4 大字卡同做法）。
 * - 上半屏我方视角（我的语言）：我说的原文靠右灰泡；对方 → 我 的译文靠左青绿泡 22 pt；最新 partial 虚线灰泡。
 * - 中央玻璃胶囊（功能层，唯一的玻璃）：判向状态 / 固定建议 + 翻转 + 外放黄标（默认关）+ 回 M0 + 结束。
 * - 下半屏 rotate(180°) 对方视角（对方语言）：对方说的原文靠右；我 → 对方 的译文靠左 24 pt。对方只看不碰。
 * - 判向待定（两句防抖沿用旧方向）→ 虚线气泡 + 「?」；气泡左右滑 > 60 dp = 翻转重译（规格 §3.2 纠错）。
 * - M3 是文本模式：默认不出声；外放只作显式黄标降级（规格 §6.2）。
 */
@Composable
fun LiveM3Screen(vm: LiveViewModel, onBack: () -> Unit, onOpenModels: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val finish: () -> Unit = { vm.end(); onBack() }

    SceneTheme(dark = true, typography = SceneTheme.type, accessibility = SceneTheme.a11y) {
        val c = SceneTheme.colors
        GlassScaffold(
            background = c.systemBackground,
            topBar = {
                val (label, tone) = m3HealthCapsule(ui.health, ui.speakerOut)
                SceneNavBar(
                    title = "M3 双屏对话",
                    onBack = { vm.switchMode("M0") }, backContentDescription = "回 M0",
                    trailing = { SceneCapsule(label, tone = tone) },
                )
            },
            // 中央胶囊放在功能层槽位里：它要取样内容层做玻璃，不能自己也被录进内容层
            bottomBar = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CenterCapsule(ui = ui, vm = vm, onFinish = finish)
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

/** 头行（我 · 普通话 / M3 双屏 / 输出状态）→ 引擎 / 会话提示 → 气泡列（贴底，新句到达自动滚到底）。 */
@Composable
private fun MyHalf(ui: LiveUiState, onFlip: (String) -> Unit, onOpenModels: () -> Unit, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            SpeakingDot(active = ui.speaking)
            SceneText("我 · ${Lang.displayName(ui.myLang)}", style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1)
            SceneCapsule("M3 双屏", tone = CapsuleTone.Tint)
            SceneText(
                outputStatus(ui), Modifier.weight(1f), style = SceneTheme.type.caption1,
                color = if (ui.speakerOut) c.onWarningSoft else c.secondaryLabel, textAlign = TextAlign.End, maxLines = 1,
            )
        }
        EngineNotice(ui, onOpenModels)
        BubbleColumn(Modifier.weight(1f).fillMaxWidth()) {
            ui.lines.takeLast(MaxBubbles).forEach { line ->
                key(line.id) {
                    if (line.speaker == Speaker.ME) MyHalfMeBubble(line, onFlip = { onFlip(line.id) })
                    else MyHalfOtherBubble(line, onFlip = { onFlip(line.id) })
                }
            }
            if (ui.partial.isNotBlank()) {
                Bubble(side = BubbleSide.Left, tag = "正在说 · 句尾判向", text = ui.partial, textStyle = partialStyle(), textColor = c.secondaryLabel, dashed = true)
            }
            if (ui.lines.isEmpty() && ui.partial.isBlank()) {
                SceneText(
                    if (ui.state is LiveState.Live) "把手机放在两人之间，任一方开口即可" else "开始后，两边的话会各自出现在对应半屏",
                    style = SceneTheme.type.subheadline, color = c.tertiaryLabel,
                )
            }
        }
    }
}

/** 我说的话（右侧灰泡）：原文 17 pt；小标「已译到对方半屏」/「翻译中…」/ 未译原因。 */
@Composable
private fun MyHalfMeBubble(line: LiveLine, onFlip: () -> Unit) {
    val c = SceneTheme.colors
    val state = when {
        line.translation != null -> "已译到对方半屏"
        line.mtDegraded -> "未译"
        else -> "翻译中…"
    }
    Bubble(
        side = BubbleSide.Right,
        tag = "我说 · $state" + if (line.dirTentative) " · 判向待定 ?" else "",
        text = line.text, textStyle = SceneTheme.type.body, textColor = c.label,
        dashed = line.dirTentative,
        badges = lineBadges(line),
        onFlip = onFlip,
    )
}

/** 对方 → 我（左侧青绿泡）：译文 22 pt + 原文 13 pt；缺译文时显示原文 + 原因胶囊。 */
@Composable
private fun MyHalfOtherBubble(line: LiveLine, onFlip: () -> Unit) {
    val c = SceneTheme.colors
    Bubble(
        side = BubbleSide.Left, tinted = true,
        tag = "对方 → 我 · ${Lang.displayName(line.srcLang)} → ${Lang.displayName(line.tgtLang)}" + if (line.dirTentative) " · 置信度低，沿用上一方向 ?" else "",
        text = line.translation ?: line.text, textStyle = translationStyle(22.sp, 28.sp), textColor = c.label,
        sub = line.text.takeIf { line.translation != null },
        dashed = line.dirTentative,
        badges = lineBadges(line),
        onFlip = onFlip,
    )
}

/** 一行的小胶囊：缺译文 → 原因（降级红 / 其余灰）；已译但降级 → 「降级」；被打断 → 「未播完」；外放播放中 → 「播放中」。 */
private fun lineBadges(line: LiveLine): List<Badge> = buildList {
    when {
        line.translation == null && line.mtDegraded -> add(Badge(line.mtReason ?: "未译", CapsuleTone.Destructive))
        line.translation == null -> add(Badge(line.mtReason ?: "翻译中…", CapsuleTone.Gray))
        line.mtDegraded -> add(Badge("降级", CapsuleTone.Warning))
    }
    if (line.interrupted) add(Badge("未播完", CapsuleTone.Gray))
    if (line.tts == PlaybackStatus.PLAYING) add(Badge("播放中", CapsuleTone.Tint, icon = true))
}

/** 头行右侧的输出状态：默认「无耳机 · 无 TTS · 常驻深色」（原型）；黄标外放开着时改为外放状态（无语音时并入提示）。 */
private fun outputStatus(ui: LiveUiState): String = when {
    !ui.speakerOut -> "无耳机 · 无 TTS · 常驻深色"
    ui.health.tts == "无" || ui.health.tts == "失败" -> "外放 · 黄标 · 无可用语音"
    else -> "外放 · 黄标 · 常驻深色"
}

/** 引擎 / 会话状态的行内提示（不弹窗）：Error → 红字 + 「去下载模型」；装载中灰字；非 Live 的状态机提示；采集错误。 */
@Composable
private fun EngineNotice(ui: LiveUiState, onOpenModels: () -> Unit) {
    val c = SceneTheme.colors
    when (val e = ui.engine) {
        is LocalEngineState.Error -> Column(verticalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneText("端侧引擎不可用：${e.reason}", style = SceneTheme.type.footnote, color = c.destructive)
            SceneButton("去下载模型", onClick = onOpenModels, style = ButtonStyle.Tinted, height = SceneSize.glassButton)
        }
        LocalEngineState.Loading -> SceneText("端侧模型装载中…", style = SceneTheme.type.footnote, color = c.secondaryLabel)
        else -> {}
    }
    if (ui.state != LiveState.Idle && ui.state !is LiveState.Live && ui.hint.isNotBlank()) {
        SceneText(ui.hint, style = SceneTheme.type.footnote, color = c.secondaryLabel)
    }
    ui.error?.let { SceneText(it, style = SceneTheme.type.footnote, color = c.destructive) }
}

// ---------- 下半屏：对方视角（旋转 180°，对方语言）----------

/** 头行（Them · English / rotated 180° · they read, never touch）→ 气泡列。不接手势：对方只看不碰。 */
@Composable
private fun TheirHalf(ui: LiveUiState, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    val w = theirWords(ui.otherLang)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            SceneText("${w.them} · ${nativeName(ui.otherLang)}", style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1)
            SceneText(w.note, Modifier.weight(1f), style = SceneTheme.type.caption1, color = c.secondaryLabel, textAlign = TextAlign.End, maxLines = 1)
        }
        BubbleColumn(Modifier.weight(1f).fillMaxWidth()) {
            ui.lines.takeLast(MaxBubbles).forEach { line ->
                key(line.id) {
                    if (line.speaker == Speaker.OTHER) {
                        // 对方自己说的话（右侧灰泡）：原文 17 pt
                        Bubble(
                            side = BubbleSide.Right,
                            tag = w.theySaid + if (line.dirTentative) " ?" else "",
                            text = line.text, textStyle = SceneTheme.type.body, textColor = c.label,
                            dashed = line.dirTentative,
                        )
                    } else {
                        // 我 → 对方（左侧青绿泡）：译文 24 pt（≥ 22）；缺译文时显示原文 + 对方语言的原因胶囊
                        Bubble(
                            side = BubbleSide.Left, tinted = true,
                            tag = "${w.meToThem} · ${nativeName(line.srcLang)} → ${nativeName(line.tgtLang)}" + if (line.dirTentative) " ?" else "",
                            text = line.translation ?: line.text, textStyle = translationStyle(24.sp, 30.sp), textColor = c.label,
                            dashed = line.dirTentative,
                            badges = when {
                                line.translation == null && line.mtDegraded -> listOf(Badge(w.notTranslated, CapsuleTone.Destructive))
                                line.translation == null -> listOf(Badge(w.translating, CapsuleTone.Gray))
                                else -> emptyList()
                            },
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
private class TheirWords(val them: String, val note: String, val theySaid: String, val meToThem: String, val translating: String, val notTranslated: String)

private fun theirWords(lang: String): TheirWords = when (lang.substringBefore('-')) {
    "zh", "yue", "wuu", "nan" -> TheirWords("对方", "已旋转 180° · 只看不碰", "对方说", "我 → 对方", "翻译中…", "未译")
    "ja" -> TheirWords("相手", "180° 回転 · 見るだけで触らない", "相手の発言", "私 → 相手", "翻訳中…", "未翻訳")
    "ko" -> TheirWords("상대", "180° 회전 · 보기만 하세요", "상대의 말", "나 → 상대", "번역 중…", "번역 안 됨")
    else -> TheirWords("Them", "rotated 180° · they read, never touch", "They said", "Me → them", "Translating…", "Not translated")
}

/** 语言的本族名（给对方半屏看）：普通话 / English / 日本語 / 한국어。 */
private fun nativeName(tag: String): String = when (tag) {
    Lang.ZH_CN -> "普通话"; Lang.YUE_HK -> "粵語"; Lang.ZH_SICHUAN -> "四川话"; Lang.WUU -> "上海话"; Lang.NAN -> "闽南语"
    Lang.EN -> "English"; Lang.JA -> "日本語"; Lang.KO -> "한국어"; else -> Lang.displayName(tag)
}

// ---------- 中央玻璃胶囊 ----------

/**
 * 判向状态（可点：建议固定 → 固定为 对方 → 我；已固定 → 恢复自动）+ 翻转上一句 + 外放黄标（默认关）+ 回 M0 + 结束。
 * 64 dp 高、圆角胶囊、左右 12 dp 边距（原型 `.glass` 条）。
 */
@Composable
private fun CenterCapsule(ui: LiveUiState, vm: LiveViewModel, onFinish: () -> Unit) {
    val c = SceneTheme.colors
    val fixed = ui.fixedDirection
    val title = when {
        fixed != null -> "已固定：${if (fixed == Speaker.OTHER) "对方 → 我" else "我 → 对方"}"
        ui.suggestFixed -> "连续纠正 3 次 · 固定方向？"
        else -> "自动判向" + (ui.current?.let { if (it.speaker == Speaker.ME) " · 我 → 对方" else " · 对方 → 我" } ?: "")
    }
    val hint = when {
        fixed != null -> "点此恢复自动判向"
        ui.suggestFixed -> "点此固定为 对方 → 我（退化单工）"
        else -> "气泡左右滑 = 翻转重译 · 误判 3 次建议固定方向"
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
        // 判向状态：建议固定 / 已固定时是一枚可点的小胶囊（黄标 / 青绿），自动时只是文字
        val dirBg = when { ui.suggestFixed -> c.warningSoft; fixed != null -> c.tintSoft; else -> Color.Transparent }
        val titleColor = when { ui.suggestFixed -> c.onWarningSoft; fixed != null -> c.onTintSoft; else -> c.onTintSoft }
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
            SceneText(hint, style = SceneTheme.type.caption2, color = if (ui.suggestFixed) c.onWarningSoft else c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SceneIconButton(SceneIcons.Rotate, contentDescription = "翻转上一句方向并重译", onClick = vm::flipLast, size = 48.dp, style = ButtonStyle.Gray, enabled = ui.current != null)
        // 外放黄标：默认关；开 = 对方 → 我 的译文也出声（规格 §6.2 外放只作显式黄标降级）
        SceneIconButton(
            SceneIcons.Speaker, contentDescription = if (ui.speakerOut) "朗读给对方：开（外放，黄标）" else "朗读给对方：关（外放，黄标）",
            onClick = { vm.setSpeakerOut(!ui.speakerOut) }, size = 48.dp, style = if (ui.speakerOut) ButtonStyle.Warning else ButtonStyle.Gray,
        )
        SceneButton(onClick = { vm.switchMode("M0") }, style = ButtonStyle.Gray, height = 48.dp, contentPadding = 12.dp) {
            SceneText("回 M0", style = SceneTheme.type.caption1.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
        SceneIconButton(SceneIcons.Stop, contentDescription = "结束会话", onClick = onFinish, size = 48.dp, style = ButtonStyle.Destructive)
    }
}

/** 导航栏健康胶囊：热 / 电阶梯优先，其次翻译健康，外放开着时并入语音异常。 */
private fun m3HealthCapsule(h: PipelineHealth, speakerOut: Boolean): Pair<String, CapsuleTone> = when {
    h.thermal == ThermalLevel.CRITICAL -> "过热 · 只出字" to CapsuleTone.Destructive
    h.thermal >= ThermalLevel.SERIOUS || h.lowBattery -> (if (h.lowBattery) "低电 · 降频" else "发热 · 降频") to CapsuleTone.Warning
    h.mt == "unavailable" -> "无翻译" to CapsuleTone.Destructive
    h.mt == "fallback" -> "已降级" to CapsuleTone.Warning
    h.mt == "slow" -> "云端慢" to CapsuleTone.Gray
    speakerOut && (h.tts == "无" || h.tts == "失败") -> "外放 · 无语音" to CapsuleTone.Warning
    h.thermal == ThermalLevel.FAIR -> "混合档 · 省算力" to CapsuleTone.Tint
    else -> "混合档 · 正常" to CapsuleTone.Tint
}

// ---------- 气泡 ----------

private enum class BubbleSide { Left, Right }

private class Badge(val text: String, val tone: CapsuleTone, val icon: Boolean = false)

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
 * - [dashed]：判向待定或 partial，虚线描边、无实底。
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
    val shape = if (side == BubbleSide.Right) RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
    else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
    val bg = when { dashed -> c.systemBackground; tinted -> c.tintSoft; else -> c.secondarySystemBackground }
    val tagColor = if (tinted && !dashed) c.onTintSoft else c.secondaryLabel
    val align = if (side == BubbleSide.Right) Alignment.End else Alignment.Start
    Column(
        Modifier
            .fillMaxWidth(if (side == BubbleSide.Right) 0.86f else 0.9f)
            .wrapContentWidth(align)
            .then(if (onFlip != null) Modifier.swipeToFlip(onFlip).semantics { customActions = listOf(CustomAccessibilityAction("翻转方向并重译") { onFlip(); true }) } else Modifier)   // 屏幕阅读器可翻转任一句
            .clip(shape)
            .background(bg)
            .then(if (dashed) Modifier.dashedOutline(shape, c.tertiaryLabel) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SceneText(tag, style = SceneTheme.type.caption2, color = tagColor)
            badges.forEach { b ->
                if (b.icon) SceneIcon(SceneIcons.Speaker, contentDescription = null, size = 12.dp, tint = c.tint)
                SceneCapsule(b.text, tone = b.tone)
            }
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

/** 虚线描边（判向待定 / partial）：沿气泡自身的不对称圆角轮廓画，内缩半个线宽避免被 clip 吃掉。 */
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

/** 头行的「在听」点：VAD 判定有人在说时青绿呼吸，否则灰点（Reduce Motion 时静止）。 */
@Composable
private fun SpeakingDot(active: Boolean) {
    val c = SceneTheme.colors
    val pulse = if (active && !SceneTheme.motion.reduced) {
        rememberInfiniteTransition(label = "speaking")
            .animateFloat(0.5f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "alpha").value
    } else 1f
    Box(Modifier.size(8.dp).alpha(pulse).clip(CircleShape).background(if (active) c.tint else c.tertiaryLabel))
}
