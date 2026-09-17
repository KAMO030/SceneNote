package dev.scenenote.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.CapsuleTone
import dev.scenenote.core.designsystem.LocalGlassBackdrop
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneCapsule
import dev.scenenote.core.designsystem.SceneCard
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneGlassCapsuleButton
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIconButton
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneRadius
import dev.scenenote.core.designsystem.SceneRow
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.ScenePreset
import dev.scenenote.core.model.Scenes
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.core.settings.KeyWallet
import org.koin.compose.koinInject

/**
 * 场景 Tab（14 篇 §1 ① `Home.dc.html`）：一按进对的场景。
 * 大标题 + 状态胶囊 → 旗舰卡「面对面对话」→ 屏内字幕卡 → 会议卡 → 口述成稿（v1.1）→ 方言家庭（v1.1）→「更多场景」折叠 → 页脚红线文案；
 * 右上玻璃胶囊「出门预热」→ 引导页。内容层从壳的玻璃 Tab 栏下面滚过（底部留 140 dp）。
 */
@Composable
fun HomeTab(onOpenScene: (String) -> Unit, onOpenOnboarding: () -> Unit, onOpenLiveTab: () -> Unit) {
    val c = SceneTheme.colors
    val wallet = koinInject<KeyWallet>()
    val settings = koinInject<AppSettings>()
    // Key 与语言对在设置 / Key 钱包里改；切回本 Tab 时重新组合即刷新，这里不做流式订阅
    val hasKey = remember(wallet) { wallet.anyKey() }
    val myLang = remember(settings) { settings.myLang }
    val otherLang = remember(settings) { settings.otherLang }
    var dictOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    // 原型 `.scroll` 对 Home 覆盖为 padding-top 60 = 状态栏 54 + 6：大标题紧贴状态栏，右上胶囊与其并排
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = statusTop + 6.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeHeader(hasKey = hasKey)
            FlagshipCard(scene = Scenes.liveTalk, myLang = myLang, otherLang = otherLang, onOpenScene = onOpenScene, onOpenLiveTab = onOpenLiveTab)
            ScreenSubtitleCard(scene = Scenes.screenFile, onClick = { onOpenScene(Scenes.screenFile.id) })
            MeetingCard(scene = Scenes.meeting, onClick = { onOpenScene(Scenes.meeting.id) })
            DictationCard(open = dictOpen, onToggle = { dictOpen = !dictOpen })
            DialectFamilyCard()
            Column {
                MoreScenesToggle(open = moreOpen, onToggle = { moreOpen = !moreOpen })
                AnimatedVisibility(visible = moreOpen, enter = revealEnter(), exit = revealExit()) { MoreScenesGroup(Modifier.padding(top = 12.dp)) }
            }
            SceneText(
                "没有后端、不内置任何 Key。识别与朗读可完全离线；零 Key 的中 ⇄ 英端侧翻译包稍后开放，现阶段翻译要填自己的 Key。",
                Modifier.padding(horizontal = SceneSpacing.page + SceneSpacing.xs),
                style = SceneTheme.type.footnote, color = c.secondaryLabel,
            )
        }
        // 原型 `.navg`：右上玻璃胶囊「出门预热」。本 Tab 的内容已被壳录进玻璃取样层，这里不能再取样自己，故关掉 backdrop 走高填充回退
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            SceneGlassCapsuleButton(
                "出门预热", onClick = onOpenOnboarding, icon = SceneIcons.Headphones,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = SceneSpacing.m),
            )
        }
    }
}

/** 展开 / 收起动效：跟随 Reduce Motion（时长归零）。 */
@Composable
private fun revealEnter() = fadeIn(tween(SceneTheme.motion.normalMs)) + expandVertically(tween(SceneTheme.motion.normalMs))

@Composable
private fun revealExit() = fadeOut(tween(SceneTheme.motion.normalMs)) + shrinkVertically(tween(SceneTheme.motion.normalMs))

/** 大标题「场记」+ 状态胶囊：有 Key = 混合档（端侧识别 + 云翻译）；零 Key = 端侧 NMT 的离线基础质量。 */
@Composable
private fun HomeHeader(hasKey: Boolean) {
    Column(Modifier.padding(start = SceneSpacing.page, end = SceneSpacing.page, bottom = SceneSpacing.xs), verticalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
        // 右侧留出「出门预热」胶囊的位置，避免大字号下标题钻到胶囊底下
        SceneText("场记", Modifier.padding(end = 140.dp), style = SceneTheme.type.largeTitle)
        ChipRow {
            if (hasKey) {
                SceneCapsule("混合档 · 端侧识别 + 云翻译", tone = CapsuleTone.Tint)
                SceneCapsule("零 Key 也能用")
            } else {
                SceneCapsule("零 Key 也能用", tone = CapsuleTone.Tint)
                SceneCapsule("离线 · 基础质量")
            }
        }
    }
}

/** 旗舰卡「面对面对话」：青绿描边；「开始仅听」prominent → listen，「速译一句」gray → quick_phrase；语言对行 → 实时 Tab。 */
@Composable
private fun FlagshipCard(scene: ScenePreset, myLang: String, otherLang: String, onOpenScene: (String) -> Unit, onOpenLiveTab: () -> Unit) {
    val c = SceneTheme.colors
    SceneCard(outline = c.tint) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneText(scene.name, style = SceneTheme.type.title2)
            SceneCapsule("旗舰 · 置顶", tone = CapsuleTone.Tint)
            Spacer(Modifier.weight(1f))
            // 「···」→ 场景编辑 sheet（I7 落地），先禁用
            SceneIconButton(SceneIcons.Ellipsis, contentDescription = "场景编辑（稍后开放）", onClick = {}, enabled = false)
        }
        SceneText(scene.tagline, style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SceneRadius.s))
                .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onOpenLiveTab)
                .defaultMinSize(minHeight = SceneSize.navBar)
                .padding(horizontal = SceneSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SceneCapsule("我 · ${Lang.displayName(myLang)}")
            SceneText("⇄", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
            SceneCapsule("对方 · ${Lang.displayName(otherLang)}")
            Spacer(Modifier.weight(1f))
            SceneText("M0 → M1 → M3", style = SceneTheme.type.caption1, color = c.secondaryLabel, maxLines = 1)
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneButton("开始仅听", onClick = { onOpenScene(Scenes.listenOnly.id) }, style = ButtonStyle.Prominent, modifier = Modifier.weight(1f))
            SceneButton("速译一句", onClick = { onOpenScene(Scenes.quickPhrase.id) }, style = ButtonStyle.Gray, modifier = Modifier.width(116.dp))
        }
        SceneText("耳机单击 · Action Button · 磁贴 也能触发 · 被迫点击 1", style = SceneTheme.type.caption1, color = c.secondaryLabel)
        SceneText("「···」场景编辑稍后开放", style = SceneTheme.type.caption1, color = c.tertiaryLabel)
    }
}

/** 屏内字幕卡：整卡可点 → screen_file（ScreenIntake）。 */
@Composable
private fun ScreenSubtitleCard(scene: ScenePreset, onClick: () -> Unit) {
    val c = SceneTheme.colors
    val source = scene.langChips.firstOrNull { it.default }?.tag ?: Lang.EN
    val target = scene.translationTargets.firstOrNull() ?: Lang.ZH_CN
    SceneCard(onClick = onClick) {
        CardTitleRow(title = "屏内字幕") { SceneCapsule("旗舰", tone = CapsuleTone.Tint) }
        SceneText(scene.tagline, style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        ChipRow {
            SceneCapsule(Lang.displayName(source))
            SceneText("→", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
            SceneCapsule(Lang.displayName(target))
            SceneCapsule("Android · 系统字幕磁贴")
        }
    }
}

/** 会议卡：整卡可点 → meeting。 */
@Composable
private fun MeetingCard(scene: ScenePreset, onClick: () -> Unit) {
    val c = SceneTheme.colors
    val source = scene.langChips.firstOrNull { it.default }?.tag ?: Lang.ZH_CN
    SceneCard(onClick = onClick) {
        CardTitleRow(title = scene.name)
        SceneText(scene.tagline, style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        ChipRow {
            SceneCapsule(Lang.displayName(source))
            SceneCapsule("商务风")
            SceneCapsule("录音不出手机")
        }
    }
}

/** 口述成稿（v1.1）：标题行可展开三子卡（发微信 / 写邮件 / 发社媒），子卡未接能力，只展示。 */
@Composable
private fun DictationCard(open: Boolean, onToggle: () -> Unit) {
    val c = SceneTheme.colors
    val interaction = remember { MutableInteractionSource() }
    SceneCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SceneRadius.s))
                .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onToggle)
                .defaultMinSize(minHeight = SceneSize.navBar),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s),
        ) {
            SceneText("口述成稿", style = SceneTheme.type.title3)
            SceneCapsule("v1.1")
            Spacer(Modifier.weight(1f))
            SceneText(if (open) "收起" else "展开三子卡", style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = c.tint)
        }
        // 说明 + 折叠区放同一个 Column：收起时 AnimatedVisibility 尺寸为零，不会在卡片 spacedBy 里多占一段间距
        Column {
            SceneText("说比打字快；风格由入口决定，不猜你要发到哪", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
            AnimatedVisibility(visible = open, enter = revealEnter(), exit = revealExit()) {
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.alpha(0.6f), horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
                        DictationSubCard("发微信", "口语 · 复制到剪贴板", Modifier.weight(1f))
                        DictationSubCard("写邮件", "正式 · 段落", Modifier.weight(1f))
                        DictationSubCard("发社媒", "短句 + 话题标签", Modifier.weight(1f))
                    }
                    SceneText("v1.1 提供，当前不可用", style = SceneTheme.type.caption1, color = c.tertiaryLabel)
                }
            }
        }
    }
}

/** 口述成稿的入口子卡（原型 `#F2F2F7` 14 px 圆角小卡）：不可点。 */
@Composable
private fun DictationSubCard(title: String, note: String, modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(SceneRadius.m)).background(c.fill).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(SceneSpacing.xs),
    ) {
        SceneText(title, style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold))
        SceneText(note, style = SceneTheme.type.caption1, color = c.secondaryLabel)
    }
}

/** 方言家庭（v1.1）：黄标「仅本机 + 逐段授权」；未接能力，只展示。 */
@Composable
private fun DialectFamilyCard() {
    val c = SceneTheme.colors
    SceneCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneText("方言家庭", style = SceneTheme.type.title3)
            SceneCapsule("v1.1")
        }
        SceneText("录长辈或导入微信语音 → 留言卡（方言原文 + 普通话 + 音频）发回群里，长辈不用装 App", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        ChipRow {
            SceneCapsule(Lang.displayName(Lang.ZH_SICHUAN))
            SceneCapsule(Lang.displayName(Lang.YUE_HK))
            SceneText("→", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
            SceneCapsule(Lang.displayName(Lang.ZH_CN))
            SceneCapsule("仅本机 + 逐段授权", tone = CapsuleTone.Warning)
        }
    }
}

/** 「更多场景 / 收起更多场景」文字按钮（原型 padding 8 / 24，左对齐）。 */
@Composable
private fun MoreScenesToggle(open: Boolean, onToggle: () -> Unit) {
    val c = SceneTheme.colors
    SceneButton(onClick = onToggle, style = ButtonStyle.Plain, modifier = Modifier.padding(start = SceneSpacing.xs), contentPadding = SceneSpacing.l) {
        SceneText(if (open) "收起更多场景" else "更多场景", style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold))
        SceneText("讲座旁听 · 课堂 · 采访 · 客服", style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1)
    }
}

/** 更多场景列表：三张 v1.1 预设 + 「复制一张再改…」（场景编辑 sheet 在 I7 落地，先不可点）。 */
@Composable
private fun MoreScenesGroup(modifier: Modifier = Modifier) {
    val c = SceneTheme.colors
    SceneGroup(modifier) {
        SceneRow("讲座旁听", trailing = { SceneCapsule("v1.1"); SceneText("M0 + 学术", style = SceneTheme.type.body, color = c.secondaryLabel, maxLines = 1) })
        SceneDivider()
        SceneRow("课堂", trailing = { SceneCapsule("v1.1"); SceneText("仅本机 · 省电", style = SceneTheme.type.body, color = c.secondaryLabel, maxLines = 1) })
        SceneDivider()
        SceneRow("采访", trailing = { SceneCapsule("v1.1"); SceneText("锁定 · 不出手机", style = SceneTheme.type.body, color = c.secondaryLabel, maxLines = 1) })
        SceneDivider()
        SceneRow("复制一张再改…", subtitle = "场景编辑稍后开放", value = "自定义场景", titleColor = c.secondaryLabel)
    }
}

/** 卡片标题行：20 pt 标题 + 可选胶囊 + 右侧 chevron（整卡可点的卡用）。 */
@Composable
private fun CardTitleRow(title: String, badge: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
        SceneText(title, style = SceneTheme.type.title3)
        if (badge != null) badge()
        Spacer(Modifier.weight(1f))
        SceneIcon(SceneIcons.ChevronRight, contentDescription = null, size = 16.dp, tint = SceneTheme.colors.tertiaryLabel)
    }
}

/** 胶囊行：放不下时换行（Dynamic Type 大字号下不截断）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
