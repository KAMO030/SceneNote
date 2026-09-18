package dev.scenenote.ui.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import dev.scenenote.shared.resources.*
import dev.scenenote.core.i18n.stringResource
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 3

/**
 * 新手引导（docs/15 §2）：① 怎么用 → ② 要不要连云端翻译 → ③ 入口（按平台）。
 * 三页可左右滑（HIG `gestures.md`：滑动是分页的标准手势），也能用 dock 的「继续」走；
 * 走过头能用导航栏返回退回上一页。右上「跳过」只在非末页出现——末页的「开始使用」就是出口。
 */
@Composable
fun OnboardingScreen(initialPage: Int, onDone: () -> Unit, onOpenModels: () -> Unit, onOpenKey: () -> Unit = {}) {
    val c = SceneTheme.colors
    val reduced = SceneTheme.motion.reduced
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, PAGE_COUNT - 1)) { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    val last = page == PAGE_COUNT - 1
    val goTo: (Int) -> Unit = { target ->
        scope.launch { if (reduced) pager.scrollToPage(target) else pager.animateScrollToPage(target) }
    }
    GlassScaffold(
        background = c.systemBackground,
        topBar = {
            SceneNavBar(
                onBack = if (page > 0) ({ goTo(page - 1) }) else null,
                trailing = if (last) null else ({ SceneGlassCapsuleButton(stringResource(Res.string.onb_skip), onClick = onDone) }),
            )
        },
        bottomBar = {
            SceneDock(height = 72.dp) {
                PageDots(page, Modifier.padding(start = 4.dp))
                Spacer(Modifier.width(12.dp))
                SceneButton(
                    stringResource(if (last) Res.string.onb_get_started else Res.string.onb_continue),
                    onClick = { if (last) onDone() else goTo(page + 1) },
                    style = ButtonStyle.Prominent, height = 56.dp, modifier = Modifier.weight(1f),
                )
            }
        },
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { p ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 104.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(SceneSpacing.m),
            ) {
                when (p) {
                    0 -> HowToPage()
                    1 -> KeyPage(onOpenModels = onOpenModels, onOpenKey = onOpenKey)
                    else -> EntryPage()
                }
            }
        }
    }
}

/**
 * 页码指示：当前页是拉长的胶囊，其余是圆点——形状 + 颜色两个通道，
 * 不靠颜色单独表意（`accessibility.md` › Vision）。整组合成一个「第 N 页，共 M 页」读给读屏。
 * 未选中用 tertiaryLabel 而不是 separator：separator 压在玻璃 dock 上只有 1.4:1，几乎看不见。
 */
@Composable
private fun PageDots(page: Int, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    val label = stringResource(Res.string.onb_page_of, page + 1, PAGE_COUNT)
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(PAGE_COUNT) { i ->
            val on = i == page
            val w by animateDpAsState(if (on) 20.dp else 8.dp, tween(SceneTheme.motion.fastMs), label = "dot")
            Box(Modifier.size(width = w, height = 8.dp).clip(CircleShape).background(if (on) c.tint else c.tertiaryLabel))
        }
    }
}

// ---------- ① 怎么用 ----------

@Composable
private fun HowToPage() {
    val c = SceneTheme.colors
    Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(SceneSpacing.m)) {
        SceneText(stringResource(Res.string.onb_howto_title), style = SceneTheme.type.title1)
        SceneText(stringResource(Res.string.onb_howto_sub), style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PostureCard(Posture.Pocket, stringResource(Res.string.onb_posture_pocket), Modifier.weight(1f).fillMaxHeight())
            PostureCard(Posture.Hand, stringResource(Res.string.onb_posture_hand), Modifier.weight(1f).fillMaxHeight())
            PostureCard(Posture.Lanyard, stringResource(Res.string.onb_posture_lanyard), Modifier.weight(1f).fillMaxHeight())
        }
    }
}

private enum class Posture { Pocket, Hand, Lanyard }

/** 图示卡：浅灰底 18 dp 圆角，简笔示意 + 13 pt 标签；示意图是装饰，读屏只念标签。 */
@Composable
private fun PostureCard(posture: Posture, label: String, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(c.secondarySystemBackground)
            .padding(horizontal = 8.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SceneSpacing.s),
    ) {
        PostureIllustration(posture)
        SceneText(label, style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
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
                drawRect(c.secondarySystemBackground, Offset(14f * u, 30f * u), Size(36f * u, 24f * u))
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

// ---------- ② 要不要连云端翻译 ----------

/**
 * 两张等高的能力卡 + 两个次级动作。页面上唯一的 prominent 按钮是 dock 里的「继续」——
 * 连云端和下载语音包都是可以以后再做的设置（`onboarding.md` › Additional requests：把非必需的设置流程往后放），
 * 所以它们用 tinted / gray，不跟「继续」抢（`buttons.md` › Style：一屏最多一两个 prominent）。
 * 原来的「稍后」和「继续」是同一件事，删掉。
 */
@Composable
private fun KeyPage(onOpenModels: () -> Unit, onOpenKey: () -> Unit) {
    Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(SceneSpacing.m)) {
        SceneText(stringResource(Res.string.onb_key_title), style = SceneTheme.type.title1)
        CapabilityCard(
            stringResource(Res.string.onb_without_key), SceneIcons.Check,
            listOf(stringResource(Res.string.onb_cap_zh), stringResource(Res.string.onb_cap_en), stringResource(Res.string.onb_cap_sichuan), stringResource(Res.string.onb_cap_offline_mt), stringResource(Res.string.onb_cap_local_tts)),
        )
        CapabilityCard(
            stringResource(Res.string.onb_with_key), SceneIcons.Sparkle,
            listOf(stringResource(Res.string.onb_cap_translate), stringResource(Res.string.onb_cap_more_langs)),
        )
        SceneButton(stringResource(Res.string.onb_go_key), onClick = onOpenKey, style = ButtonStyle.Tinted, modifier = Modifier.fillMaxWidth())
        SceneButton(stringResource(Res.string.onb_download_packs_first), onClick = onOpenModels, style = ButtonStyle.Gray, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * 能力卡：标题带一个图标，下面每行一个勾。整行通栏而不是左右对开两列——
 * 英 / 日 / 韩比中文长三成，半屏宽度下「中英互译」这类条目会折行甚至被切掉
 * （`accessibility.md` › Vision：文字要能放大）；通栏后每条一行放得下，两张卡也不会一高一矮。
 * maxLines = 2 只是超大字号时的兜底。
 */
@Composable
private fun CapabilityCard(title: String, icon: ImageVector, items: List<String>, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.secondarySystemBackground).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(SceneSpacing.s),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SceneIcon(icon, contentDescription = null, size = 16.dp, tint = c.tint)
            SceneText(title, style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.secondaryLabel)
        }
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                SceneIcon(SceneIcons.Check, contentDescription = null, size = 16.dp, tint = c.tint, modifier = Modifier.padding(top = 2.dp))
                SceneText(item, style = SceneTheme.type.subheadline, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        SceneText(stringResource(Res.string.onb_entry_title), style = SceneTheme.type.title1)
    }
    Column {
        SceneGroup(background = c.secondarySystemBackground) {
            if (ios) {
                SceneRow(stringResource(Res.string.onb_shortcuts), value = stringResource(Res.string.onb_shortcuts_value))
                SceneDivider()
                SceneRow(stringResource(Res.string.onb_action_button), value = stringResource(Res.string.scene_listen))
            } else {
                SceneRow(stringResource(Res.string.settings_entry_tile), value = stringResource(Res.string.onb_tile_value))
            }
            SceneDivider()
            SceneRow(stringResource(Res.string.settings_entry_headset), value = stringResource(Res.string.onb_headset_value))
        }
        SceneSectionFooter(stringResource(if (ios) Res.string.onb_footer_ios else Res.string.onb_footer_android, stringResource(Res.string.app_name)))
    }
}
