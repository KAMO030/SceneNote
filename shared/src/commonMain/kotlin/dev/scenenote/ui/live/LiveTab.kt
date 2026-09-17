package dev.scenenote.ui.live

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.MenuItem
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneIconButton
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneMenu
import dev.scenenote.core.designsystem.SceneRadius
import dev.scenenote.core.designsystem.SceneRow
import dev.scenenote.core.designsystem.SceneSectionFooter
import dev.scenenote.core.designsystem.SceneSectionHeader
import dev.scenenote.core.designsystem.SceneSegmentedControl
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.Scenes
import dev.scenenote.core.platform.PlatformInfo
import dev.scenenote.core.platform.isAndroid
import dev.scenenote.core.platform.isIos
import dev.scenenote.core.settings.AppSettings
import org.koin.compose.koinInject

/** 语言对卡的候选语言。 */
private val LangCandidates = listOf(Lang.ZH_CN, Lang.EN, Lang.YUE_HK, Lang.ZH_SICHUAN, Lang.JA, Lang.KO)

/** 首次进入实时 Tab 的一次性轻提示（docs/15 §2）。 */
private const val HINT_LIVE_TAB = "live_tab"

/** 主按钮高度 56 + 与 Tab 栏的 14 dp 间隙；内容底部在 140 dp 之外再多留这一段。 */
private val FloatButtonHeight = 56.dp
private val FloatButtonGap = 14.dp

/**
 * 实时 Tab：大标题 + 屏外 / 屏内分段 → 语言对卡 →「怎么用」四行；屏内只留「视频字幕」。
 * 主按钮「开始仅听 / 选择视频」悬浮在 Tab 栏上方拇指区。分段是本地状态，语言对直接读写 [AppSettings]。
 */
@Composable
fun LiveTab(onStart: (sceneId: String, mode: String) -> Unit) {
    val c = SceneTheme.colors
    val settings = koinInject<AppSettings>()
    var segment by rememberSaveable { mutableStateOf(0) } // 0 屏外 · 面对面 / 1 屏内 · 视频
    val screenIn = segment == 1
    var myLang by remember { mutableStateOf(settings.myLang) }
    var otherLang by remember { mutableStateOf(settings.otherLang) }
    // 一次性提示：本次组合内可见，离开后不再出现
    val showHint = remember { !settings.hintSeen(HINT_LIVE_TAB) }
    LaunchedEffect(Unit) { if (showHint) settings.markHintSeen(HINT_LIVE_TAB) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 6.dp, bottom = 140.dp + FloatButtonHeight + FloatButtonGap),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
        ) {
            Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SceneText("实时", style = SceneTheme.type.largeTitle)
                SceneSegmentedControl(listOf("屏外 · 面对面", "屏内 · 视频"), segment, onSelect = { segment = it })
            }

            if (!screenIn) {
                Column {
                    LangPairCard(
                        myLang = myLang, otherLang = otherLang,
                        onMyLang = { myLang = it; settings.myLang = it },
                        onOtherLang = { otherLang = it; settings.otherLang = it },
                        onSwap = {
                            val m = myLang; myLang = otherLang; otherLang = m
                            settings.myLang = myLang; settings.otherLang = otherLang
                        },
                    )
                    if (showHint) SceneSectionFooter("戴上耳机，手机放胸前，对方说话你听译文")
                }

                Column {
                    SceneSectionHeader("怎么用")
                    SceneGroup {
                        SceneRow("仅听", subtitle = "对方说话，你在耳机里听", chevron = true, onClick = { onStart(Scenes.listenOnly.id, "") })
                        SceneDivider()
                        SceneRow("面屏", subtitle = "掏出手机，对方看半屏", chevron = true, onClick = { onStart(Scenes.liveTalk.id, "M1") })
                        SceneDivider()
                        SceneRow("双屏", subtitle = "你一半我一半，轮流说", chevron = true, onClick = { onStart(Scenes.liveTalk.id, "M3") })
                        SceneDivider()
                        SceneRow("速译", subtitle = "说一句，大字给对方看", chevron = true, onClick = { onStart(Scenes.quickPhrase.id, "") })
                    }
                }
            } else {
                Column {
                    SceneGroup {
                        SceneRow("视频字幕", subtitle = "相册里的视频，边看边出字幕", chevron = true, onClick = { onStart(Scenes.screenFile.id, "") })
                    }
                    if (PlatformInfo.isIos) SceneSectionFooter("其他 App 里的视频，先存到相册再选")
                }
            }
        }

        // 主按钮：固定在 Tab 栏（8 + 66 dp）上方 14 dp 的拇指区，内容从它下面滚过。
        SceneButton(
            text = if (screenIn) "选择视频" else "开始仅听",
            onClick = { onStart(if (screenIn) Scenes.screenFile.id else Scenes.listenOnly.id, "") },
            style = ButtonStyle.Prominent,
            height = FloatButtonHeight,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = SceneSpacing.page, end = SceneSpacing.page, bottom = 8.dp + SceneSize.tabBar + FloatButtonGap)
                .fillMaxWidth(),
        )
    }
}

/** 语言对卡：我说 / 交换 / 对方说，点语言弹菜单选候选。 */
@Composable
private fun LangPairCard(myLang: String, otherLang: String, onMyLang: (String) -> Unit, onOtherLang: (String) -> Unit, onSwap: () -> Unit) {
    var picking by remember { mutableStateOf<Int?>(null) } // 0 我说 / 1 对方说
    SceneGroup {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp).padding(horizontal = SceneSpacing.row, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LangPicker("我说", myLang, onClick = { picking = 0 })
                SceneIconButton(SceneIcons.Swap, contentDescription = "交换语言", onClick = onSwap, style = ButtonStyle.Tinted)
                LangPicker("对方说", otherLang, onClick = { picking = 1 })
            }
            // 菜单锚在整行右上角（Popup 不会自动避让屏幕边缘，锚在单侧会出界）。
            val current = if (picking == 0) myLang else otherLang
            SceneMenu(
                expanded = picking != null, onDismissRequest = { picking = null },
                items = LangCandidates.map { tag ->
                    MenuItem(Lang.displayName(tag), onClick = { if (picking == 0) onMyLang(tag) else onOtherLang(tag) }, checked = tag == current)
                },
            )
        }
    }
}

/** 语言对卡里的一侧：12 pt 标签 + 20 pt 语言名，整块可点（≥ 48 dp）。 */
@Composable
private fun LangPicker(label: String, tag: String, onClick: () -> Unit) {
    val c = SceneTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .clip(RoundedCornerShape(SceneRadius.s))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .defaultMinSize(minWidth = 72.dp, minHeight = SceneSize.touchTarget)
            .padding(horizontal = SceneSpacing.s, vertical = SceneSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        SceneText(label, style = SceneTheme.type.caption1, color = c.secondaryLabel)
        SceneText(Lang.displayName(tag), style = SceneTheme.type.title3, maxLines = 1)
    }
}

/** 未落地场景的占位会话页（会议 / 屏内字幕）。 */
@Composable
fun PlaceholderSessionScreen(title: String, note: String, onBack: () -> Unit) {
    dev.scenenote.core.designsystem.GlassScaffold(topBar = { dev.scenenote.core.designsystem.SceneNavBar(title = title, onBack = onBack) }) {
        Column(Modifier.fillMaxSize().padding(top = 104.dp, start = SceneSpacing.page, end = SceneSpacing.page)) {
            SceneText(note, style = SceneTheme.type.subheadline, color = SceneTheme.colors.secondaryLabel)
        }
    }
}
