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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.BoxScope
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
import dev.scenenote.core.scene.CustomScene
import dev.scenenote.core.scene.SceneStore
import dev.scenenote.ui.LocalShellOverlay
import dev.scenenote.ui.i18n.langName
import dev.scenenote.ui.i18n.title
import dev.scenenote.ui.scene.SceneEditSheet
import dev.scenenote.shared.resources.*
import dev.scenenote.core.i18n.stringResource
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
    // 我的场景（复制一张再改）：sheet 交给壳的覆盖层，盖在玻璃 Tab 栏之上
    val store = koinInject<SceneStore>()
    val custom by store.custom.collectAsState()
    val editState = remember { mutableStateOf<Pair<Boolean, CustomScene?>>(false to null) }
    val overlayHost = LocalShellOverlay.current
    val overlayContent: @Composable BoxScope.() -> Unit = { SceneEditOverlay(store, editState) }
    DisposableEffect(overlayHost) {
        overlayHost?.value = overlayContent
        // Tab 切换有过渡动画，旧 Tab 退场晚于新 Tab 进场：只清自己挂上去的，别把新 Tab 的清掉
        onDispose { if (overlayHost?.value === overlayContent) overlayHost.value = null }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = statusTop + 6.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeHeader(hasKey = hasKey)
            FlagshipCard(scene = Scenes.liveTalk, myLang = myLang, otherLang = otherLang, onOpenScene = onOpenScene, onOpenLiveTab = onOpenLiveTab)
            ScreenSubtitleCard(scene = Scenes.screenFile, onClick = { onOpenScene(Scenes.screenFile.id) })
            MeetingCard(scene = Scenes.meeting, onClick = { onOpenScene(Scenes.meeting.id) })
            custom.forEach { cs -> CustomSceneCard(cs, onOpen = { onOpenScene(cs.id) }, onEdit = { editState.value = true to cs }) }
            Box(Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.page), contentAlignment = Alignment.Center) {
                SceneButton(stringResource(Res.string.home_copy_scene), onClick = { editState.value = true to null }, style = ButtonStyle.Plain)
            }
        }
        // 不在壳里时内联渲染：sheet 处在取样层内部，不能取样自己，关掉 backdrop 走不透明回退
        if (overlayHost == null) CompositionLocalProvider(LocalGlassBackdrop provides null) { SceneEditOverlay(store, editState) }
        // 右上玻璃胶囊「新手引导」。本 Tab 的内容已被壳录进玻璃取样层，这里不能再取样自己，故关掉 backdrop 走高填充回退
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            SceneGlassCapsuleButton(
                stringResource(Res.string.home_onboarding), onClick = onOpenOnboarding, icon = SceneIcons.Headphones,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = SceneSpacing.m),
            )
        }
    }
}

@Composable
private fun BoxScope.SceneEditOverlay(store: SceneStore, state: androidx.compose.runtime.MutableState<Pair<Boolean, CustomScene?>>) {
    val (open, editing) = state.value
    SceneEditSheet(visible = open, store = store, editing = editing, onDismiss = { state.value = false to editing })
}

/** 我的场景卡（复制一张再改的副本）：整卡可点开始；右上「改」进编辑。 */
@Composable
private fun CustomSceneCard(cs: CustomScene, onOpen: () -> Unit, onEdit: () -> Unit) {
    val c = SceneTheme.colors
    val base = Scenes.byId(cs.baseId)
    SceneCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneText(cs.name, style = SceneTheme.type.title3, modifier = Modifier.weight(1f))
            SceneText(stringResource(Res.string.home_edit), Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onEdit).padding(4.dp), style = SceneTheme.type.subheadline, color = c.tint)
        }
        SceneText(stringResource(Res.string.home_based_on, base?.title() ?: cs.baseId), style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        ChipRow {
            SceneCapsule(stringResource(Res.string.common_me_lang, langName(cs.myLang)))
            SceneCapsule(stringResource(Res.string.common_other_lang, langName(cs.otherLang)))
        }
    }
}

/** 大标题「场记」+ 一枚状态胶囊：有 Key「云翻译已连接」/ 无 Key「离线」。 */
@Composable
private fun HomeHeader(hasKey: Boolean) {
    Column(Modifier.padding(start = SceneSpacing.page, end = SceneSpacing.page, bottom = SceneSpacing.xs), verticalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
        // 右侧留出「新手引导」胶囊的位置，避免大字号下标题钻到胶囊底下
        SceneText(stringResource(Res.string.app_name), Modifier.padding(end = 140.dp), style = SceneTheme.type.largeTitle)
        ChipRow {
            if (hasKey) SceneCapsule(stringResource(Res.string.home_cloud_connected), tone = CapsuleTone.Tint) else SceneCapsule(stringResource(Res.string.home_offline))
        }
    }
}

/** 旗舰卡「面对面对话」：标题 + 一行说明 + 语言对（点击 → 实时 Tab）+「开始仅听」/「速译」。 */
@Composable
private fun FlagshipCard(scene: ScenePreset, myLang: String, otherLang: String, onOpenScene: (String) -> Unit, onOpenLiveTab: () -> Unit) {
    val c = SceneTheme.colors
    SceneCard(outline = c.tint) {
        SceneText(scene.title(), style = SceneTheme.type.title2)
        SceneText(stringResource(Res.string.home_flagship_desc), style = SceneTheme.type.subheadline, color = c.secondaryLabel)
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
            SceneCapsule(stringResource(Res.string.common_me_lang, langName(myLang)))
            SceneText("⇄", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
            SceneCapsule(stringResource(Res.string.common_other_lang, langName(otherLang)))
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s)) {
            SceneButton(stringResource(Res.string.home_start_listen), onClick = { onOpenScene(Scenes.listenOnly.id) }, style = ButtonStyle.Prominent, modifier = Modifier.weight(1f))
            SceneButton(stringResource(Res.string.home_quick_phrase), onClick = { onOpenScene(Scenes.quickPhrase.id) }, style = ButtonStyle.Gray, modifier = Modifier.width(96.dp))
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
        CardTitleRow(title = scene.title())
        SceneText(stringResource(Res.string.home_video_desc), style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        ChipRow {
            SceneCapsule(stringResource(Res.string.common_lang_pair, langName(source), langName(target)))
        }
    }
}

/** 会议卡：整卡可点 → meeting。 */
@Composable
private fun MeetingCard(scene: ScenePreset, onClick: () -> Unit) {
    val c = SceneTheme.colors
    val source = scene.langChips.firstOrNull { it.default }?.tag ?: Lang.ZH_CN
    SceneCard(onClick = onClick) {
        CardTitleRow(title = scene.title())
        SceneText(stringResource(Res.string.home_meeting_desc), style = SceneTheme.type.subheadline, color = c.secondaryLabel)
        ChipRow {
            SceneCapsule(langName(source))
            SceneCapsule(stringResource(Res.string.home_audio_stays_local))
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
