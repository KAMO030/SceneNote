package dev.scenenote.ui.home

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.CapsuleTone
import dev.scenenote.core.designsystem.LocalGlassBackdrop
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneCapsule
import dev.scenenote.core.designsystem.SceneCard
import dev.scenenote.core.designsystem.SceneGlassCapsuleButton
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneRadius
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.ScenePreset
import dev.scenenote.core.model.Scenes
import dev.scenenote.core.platform.isAndroid
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.core.settings.KeyWallet
import org.koin.compose.koinInject

/**
 * 场景 Tab：一按进对的场景。
 * 大标题 + 一枚状态胶囊 → 旗舰卡「面对面对话」→ 屏内字幕卡 → 会议卡；右上玻璃胶囊「新手引导」→ 引导页。
 * 文案遵循 docs/15：每卡一行说明、不出现工程词、与当前平台无关的行不渲染。内容层从壳的玻璃 Tab 栏下面滚过（底部留 140 dp）。
 */
@Composable
fun HomeTab(onOpenScene: (String) -> Unit, onOpenOnboarding: () -> Unit, onOpenLiveTab: () -> Unit) {
    val wallet = koinInject<KeyWallet>()
    val settings = koinInject<AppSettings>()
    // Key 与语言对在设置 / 翻译 Key 里改；切回本 Tab 时重新组合即刷新，这里不做流式订阅
    val hasKey = remember(wallet) { wallet.anyKey() }
    val myLang = remember(settings) { settings.myLang }
    val otherLang = remember(settings) { settings.otherLang }
    // 大标题紧贴状态栏（状态栏 + 6），右上胶囊与其并排
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
        }
        // 右上玻璃胶囊「新手引导」。本 Tab 的内容已被壳录进玻璃取样层，这里不能再取样自己，故关掉 backdrop 走高填充回退
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            SceneGlassCapsuleButton(
                "新手引导", onClick = onOpenOnboarding, icon = SceneIcons.Headphones,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = SceneSpacing.m),
            )
        }
    }
}

/** 大标题「场记」+ 一枚状态胶囊：有 Key「云翻译已连接」/ 无 Key「离线」。 */
@Composable
private fun HomeHeader(hasKey: Boolean) {
    Column(Modifier.padding(start = SceneSpacing.page, end = SceneSpacing.page, bottom = SceneSpacing.xs), verticalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
        // 右侧留出「新手引导」胶囊的位置，避免大字号下标题钻到胶囊底下
        SceneText("场记", Modifier.padding(end = 140.dp), style = SceneTheme.type.largeTitle)
        ChipRow {
            if (hasKey) SceneCapsule("云翻译已连接", tone = CapsuleTone.Tint) else SceneCapsule("离线")
        }
    }
}

/** 旗舰卡「面对面对话」：标题 + 一行说明 + 语言对（点击 → 实时 Tab）+「开始仅听」/「速译」。 */
@Composable
private fun FlagshipCard(scene: ScenePreset, myLang: String, otherLang: String, onOpenScene: (String) -> Unit, onOpenLiveTab: () -> Unit) {
    val c = SceneTheme.colors
    SceneCard(outline = c.tint) {
        SceneText(scene.name, style = SceneTheme.type.title2)
        SceneText("对方说话，你在耳机里听译文", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
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
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneButton("开始仅听", onClick = { onOpenScene(Scenes.listenOnly.id) }, style = ButtonStyle.Prominent, modifier = Modifier.weight(1f))
            SceneButton("速译", onClick = { onOpenScene(Scenes.quickPhrase.id) }, style = ButtonStyle.Gray, modifier = Modifier.width(96.dp))
        }
    }
}

/** 视频字幕卡：整卡可点 → screen_file。语言对一枚胶囊。 */
@Composable
private fun ScreenSubtitleCard(scene: ScenePreset, onClick: () -> Unit) {
    val c = SceneTheme.colors
    val source = scene.langChips.firstOrNull { it.default }?.tag ?: Lang.EN
    val target = scene.translationTargets.firstOrNull() ?: Lang.ZH_CN
    SceneCard(onClick = onClick) {
        CardTitleRow(title = "视频字幕")
        SceneText("相册里的视频，边看边出字幕", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        ChipRow {
            SceneCapsule("${Lang.displayName(source)} → ${Lang.displayName(target)}")
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
        SceneText("一按即录，十分钟出纪要", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        ChipRow {
            SceneCapsule(Lang.displayName(source))
            SceneCapsule("录音不出手机")
        }
    }
}

/** 卡片标题行：20 pt 标题 + 右侧 chevron（整卡可点的卡用）。 */
@Composable
private fun CardTitleRow(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
        SceneText(title, style = SceneTheme.type.title3)
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
