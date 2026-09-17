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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.CapsuleTone
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneCapsule
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneDock
import dev.scenenote.core.designsystem.SceneGlassCapsuleButton
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneRadius
import dev.scenenote.core.designsystem.SceneRow
import dev.scenenote.core.designsystem.SceneSectionFooter
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme

private const val PAGE_COUNT = 3

/**
 * 出门预热引导（原型 Onboarding.dc.html，14 篇 §1 ④）：三页 = 三姿态 → 零 Key 能 / 不能清单 → 入口绑定表。
 * 右上「跳过」玻璃胶囊；dock 里页点 + 「继续」/「开始使用」。
 */
@Composable
fun OnboardingScreen(initialPage: Int, onDone: () -> Unit, onOpenModels: () -> Unit) {
    val c = SceneTheme.colors
    var page by rememberSaveable { mutableStateOf(initialPage.coerceIn(0, PAGE_COUNT - 1)) }
    val last = page == PAGE_COUNT - 1
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
                    onClick = { if (last) onDone() else page = (page + 1).coerceAtMost(PAGE_COUNT - 1) },
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
                    0 -> PosturePage()
                    1 -> ZeroKeyPage(onOpenModels)
                    else -> EntryBindingPage()
                }
            }
        }
    }
}

/** 第 1 页：手机放这三处之一（胸前口袋 / 手持胸前 / 挂绳）+ 黄标提醒。 */
@Composable
private fun PosturePage() {
    val c = SceneTheme.colors
    Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(SceneSpacing.m)) {
        SceneText("手机放这三处之一，\n不用举、不用看屏幕", style = SceneTheme.type.title1)
        SceneText("M0 仅听用的是手机内置麦克风。对方开口后耳机单击，震动两短确认，译文进耳机。", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PostureCard(Posture.Pocket, "胸前口袋", Modifier.weight(1f))
            PostureCard(Posture.Hand, "手持胸前", Modifier.weight(1f))
            PostureCard(Posture.Lanyard, "挂绳", Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(SceneRadius.m)).background(c.warningSoft).padding(horizontal = 12.dp, vertical = 10.dp)) {
            SceneText("裤兜隔着布料、朝向不对，能不能用要实测；我们只承诺「不用举手机、不用看屏幕」。冷启动或被打断后需要解锁点一下。", style = SceneTheme.type.footnote, color = c.onWarningSoft)
        }
    }
}

private enum class Posture { Pocket, Hand, Lanyard }

/** 姿态卡：浅灰底 18 dp 圆角，简笔示意 + 13 pt 标签。 */
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

/** 简笔示意（原型 64×64 视口的 SVG 路径按比例重画）：手机 = 着色圆角矩形；口袋 / 手 / 挂绳 = 单色描边。 */
@Composable
private fun PostureIllustration(posture: Posture) {
    val c = SceneTheme.colors
    Canvas(Modifier.size(72.dp)) {
        val u = size.width / 64f
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun phone(x: Float, y: Float, h: Float) = drawRoundRect(c.tint, Offset(x * u, y * u), Size(12f * u, h * u), CornerRadius(3f * u))
        when (posture) {
            Posture.Pocket -> {
                // 衣襟轮廓（浅）→ 手机 → 口袋盖住下半截
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

/** 第 2 页：不填 Key 也能用，但要说清楚——清单与当前真实能力一致（I2/I3：端侧识别 + 端侧中英语音 + 规则整理）。 */
@Composable
private fun ZeroKeyPage(onOpenModels: () -> Unit) {
    val c = SceneTheme.colors
    Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(SceneSpacing.m)) {
        SceneText("不填 Key 也能用，\n但要说清楚", style = SceneTheme.type.title1)
        Column(verticalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneText("零 Key 能做", style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.onTintSoft)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CapabilityLine(true, "录、转写：普通话 / 英文 / 四川话，端侧识别，完全离线")
                CapabilityLine(true, "中文、英文朗读：端侧语音包，出声不联网")
                CapabilityLine(true, "规则整理、字幕导出、分享、时间轴要点")
            }
            SceneText("零 Key 做不到", Modifier.padding(top = 6.dp), style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.onDestructiveSoft)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CapabilityLine(false, "云端翻译与大模型润色：要填你自己的 Key")
                CapabilityLine(false, "粤语真正翻成普通话")
                CapabilityLine(false, "上海话 / 闽南语识别")
                CapabilityLine(false, "中英以外的语言对（日 / 韩 / 欧语要在线档 + Key）")
            }
        }
        // 端侧识别 / 语音包不随包内置，按需下载
        SceneButton(onClick = onOpenModels, style = ButtonStyle.Plain, contentPadding = 0.dp) {
            SceneText("下载语言包", style = SceneTheme.type.headline)
            SceneIcon(SceneIcons.ChevronRight, contentDescription = null, size = 16.dp)
        }
    }
}

/** 清单行：对勾（能）/ 叉（不能）+ 15 pt 文字；状态靠符号传达，颜色只是语气。 */
@Composable
private fun CapabilityLine(can: Boolean, text: String) {
    val c = SceneTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.Top) {
        SceneIcon(
            if (can) SceneIcons.Check else SceneIcons.Close,
            contentDescription = if (can) "能" else "不能",
            modifier = Modifier.padding(top = 2.dp), size = 18.dp,
            tint = if (can) c.tint else c.onDestructiveSoft,
        )
        SceneText(text, style = SceneTheme.type.subheadline)
    }
}

/** 第 3 页：把「面对面对话」绑到一个物理入口——耳机媒体键 / 快捷指令 / 磁贴在 I4 接入，这里只展示（禁用态）。 */
@Composable
private fun EntryBindingPage() {
    val c = SceneTheme.colors
    Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(SceneSpacing.m)) {
        SceneText("把「面对面对话」\n绑到一个物理入口", style = SceneTheme.type.title1)
        SceneText("目标是从「想用」到「出结果」只被迫点 1 下。待机时耳机按键不接管——你听音乐时单击控制的还是音乐。", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
    }
    Column {
        SceneGroup(background = c.secondarySystemBackground) {
            EntryRow("耳机单击", "开始 / 暂停", subtitle = "会话中生效", tag = "稍后开放")
            SceneDivider()
            EntryRow("快捷指令 / Action Button", "面对面对话", tag = "稍后开放")
            SceneDivider()
            EntryRow("Action Button 长按", "速译一句", tag = "稍后开放")
            SceneDivider()
            EntryRow("Android 磁贴", "仅听 · 屏内翻译", tag = "稍后开放")
            SceneDivider()
            EntryRow("手表软开关", "仅听", tag = "v1.1")
        }
        SceneSectionFooter("入口绑定稍后开放，现在还不能改。耳机双击 = 跳过当前译文（对话模式 = 翻转上一句重译），三击 = 重播上一句。")
    }
}

/** 入口绑定行（禁用态）：标题灰字 + 里程碑胶囊 + 绑定动作。 */
@Composable
private fun EntryRow(title: String, action: String, tag: String, subtitle: String? = null) {
    val c = SceneTheme.colors
    SceneRow(
        title, subtitle = subtitle, titleColor = c.secondaryLabel,
        trailing = {
            SceneCapsule(tag, tone = CapsuleTone.Gray)
            SceneText(action, style = SceneTheme.type.body, color = c.secondaryLabel, maxLines = 1)
        },
    )
}
