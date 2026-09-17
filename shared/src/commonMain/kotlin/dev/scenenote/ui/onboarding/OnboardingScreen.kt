package dev.scenenote.ui.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneDock
import dev.scenenote.core.designsystem.SceneGlassCapsuleButton
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneRow
import dev.scenenote.core.designsystem.SceneSectionFooter
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.platform.PlatformInfo
import dev.scenenote.core.platform.isIos

private const val PAGE_COUNT = 3

/**
 * 新手引导（docs/15 §2）：① 怎么用 → ② 要不要填翻译 Key → ③ 入口（按平台）。
 * 右上「跳过」；dock 里页点 + 「继续」/「开始使用」。
 */
@Composable
fun OnboardingScreen(initialPage: Int, onDone: () -> Unit, onOpenModels: () -> Unit, onOpenKey: () -> Unit = {}) {
    val c = SceneTheme.colors
    var page by rememberSaveable { mutableStateOf(initialPage.coerceIn(0, PAGE_COUNT - 1)) }
    val last = page == PAGE_COUNT - 1
    val next = { page = (page + 1).coerceAtMost(PAGE_COUNT - 1) }
    GlassScaffold(
        background = c.systemBackground,
        topBar = { SceneNavBar(trailing = { SceneGlassCapsuleButton("跳过", onClick = onDone) }) },
        bottomBar = {
            SceneDock(height = 72.dp) {
                Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(PAGE_COUNT) { i -> Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == page) c.tint else c.separator)) }
                }
                Spacer(Modifier.width(12.dp))
                SceneButton(
                    if (last) "开始使用" else "继续",
                    onClick = { if (last) onDone() else next() },
                    style = ButtonStyle.Prominent, height = 56.dp, modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        Crossfade(targetState = page, animationSpec = tween(SceneTheme.motion.normalMs), label = "onboarding") { p ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 104.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(SceneSpacing.m),
            ) {
                when (p) {
                    0 -> HowToPage()
                    1 -> KeyPage(onOpenModels = onOpenModels, onLater = next, onOpenKey = onOpenKey)
                    else -> EntryPage()
                }
            }
        }
    }
}

// ---------- ① 怎么用 ----------

@Composable
private fun HowToPage() {
    val c = SceneTheme.colors
    Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(SceneSpacing.m)) {
        SceneText("戴上耳机，\n对方说话，你听译文", style = SceneTheme.type.title1)
        SceneText("手机放胸前，不用举、不用看", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PostureCard(Posture.Pocket, "胸前口袋", Modifier.weight(1f))
            PostureCard(Posture.Hand, "手持胸前", Modifier.weight(1f))
            PostureCard(Posture.Lanyard, "挂绳", Modifier.weight(1f))
        }
    }
}

private enum class Posture { Pocket, Hand, Lanyard }

/** 图示卡：浅灰底 18 dp 圆角，简笔示意 + 13 pt 标签。 */
@Composable
private fun PostureCard(posture: Posture, label: String, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(c.secondarySystemBackground).padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SceneSpacing.s),
    ) {
        PostureIllustration(posture)
        SceneText(label, style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** 简笔示意（64×64 视口按比例重画）：手机 = 着色圆角矩形；口袋 / 手 / 挂绳 = 单色描边。 */
@Composable
private fun PostureIllustration(posture: Posture) {
    val c = SceneTheme.colors
    Canvas(Modifier.size(72.dp)) {
        val u = size.width / 64f
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun phone(x: Float, y: Float, h: Float) = drawRoundRect(c.tint, Offset(x * u, y * u), Size(12f * u, h * u), CornerRadius(3f * u))
        when (posture) {
            Posture.Pocket -> {
                drawRect(c.tertiaryLabel, Offset(20f * u, 10f * u), Size(24f * u, 44f * u), style = stroke)
                phone(26f, 18f, 22f)
                drawRect(c.systemBackground, Offset(14f * u, 30f * u), Size(36f * u, 24f * u))
                drawRect(c.label, Offset(14f * u, 30f * u), Size(36f * u, 24f * u), style = stroke)
            }
            Posture.Hand -> {
                phone(26f, 14f, 24f)
                val hand = Path().apply {
                    moveTo(22f * u, 36f * u)
                    cubicTo(18f * u, 40f * u, 18f * u, 46f * u, 22f * u, 50f * u)
                    lineTo(42f * u, 50f * u)
                    cubicTo(46f * u, 46f * u, 46f * u, 40f * u, 42f * u, 36f * u)
                }
                drawPath(hand, c.label, style = stroke)
                listOf(26f to 36f, 32f to 34f, 38f to 36f).forEach { (x, y) ->
                    drawLine(c.label, Offset(x * u, y * u), Offset(x * u, (y - 4f) * u), strokeWidth = 2f * u, cap = StrokeCap.Round)
                }
            }
            Posture.Lanyard -> {
                val cord = Path().apply { moveTo(20f * u, 6f * u); lineTo(32f * u, 30f * u); lineTo(44f * u, 6f * u) }
                drawPath(cord, c.label, style = stroke)
                phone(26f, 30f, 24f)
            }
        }
    }
}

// ---------- ② 要不要填翻译 Key ----------

@Composable
private fun KeyPage(onOpenModels: () -> Unit, onLater: () -> Unit, onOpenKey: () -> Unit) {
    Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(SceneSpacing.m)) {
        SceneText("要不要填翻译 Key", style = SceneTheme.type.title1)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapabilityColumn(
                "不填也能", SceneIcons.Check,
                listOf("识别普通话", "识别英文", "识别四川话", "本机朗读"),
                Modifier.weight(1f),
            )
            CapabilityColumn(
                "填了才能", SceneIcons.Key,
                listOf("翻译", "更多语言"),
                Modifier.weight(1f),
            )
        }
        SceneButton("去填 Key", onClick = onOpenKey, style = ButtonStyle.Prominent, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneButton("稍后", onClick = onLater, style = ButtonStyle.Gray, modifier = Modifier.weight(1f))
            SceneButton("先下载语音包", onClick = onOpenModels, style = ButtonStyle.Tinted, modifier = Modifier.weight(1f))
        }
    }
}

/** 能力列：标题 + 每行一个符号和 ≤ 10 字的条目。 */
@Composable
private fun CapabilityColumn(title: String, icon: ImageVector, items: List<String>, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(c.secondarySystemBackground).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(SceneSpacing.s),
    ) {
        SceneText(title, style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.secondaryLabel)
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SceneIcon(icon, contentDescription = null, size = 16.dp, tint = c.tint)
                SceneText(item, style = SceneTheme.type.subheadline, maxLines = 1)
            }
        }
    }
}

// ---------- ③ 入口（按平台） ----------

@Composable
private fun EntryPage() {
    val c = SceneTheme.colors
    val ios = PlatformInfo.isIos
    Column(Modifier.padding(horizontal = SceneSpacing.page)) {
        SceneText("一键开始", style = SceneTheme.type.title1)
    }
    Column {
        SceneGroup(background = c.secondarySystemBackground) {
            if (ios) {
                SceneRow("快捷指令", value = "仅听 · 速译 · 面对面")
                SceneDivider()
                SceneRow("操作按钮", value = "仅听")
            } else {
                SceneRow("快捷设置磁贴", value = "仅听 · 速译")
            }
            SceneDivider()
            SceneRow("耳机按键", value = "开始 / 暂停")
        }
        SceneSectionFooter(
            if (ios) "设置 → 操作按钮 → 快捷指令，选「场记」"
            else "下拉通知栏 → 编辑磁贴，拖入「场记」",
        )
    }
}
