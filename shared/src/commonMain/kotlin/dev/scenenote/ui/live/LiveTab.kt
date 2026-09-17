package dev.scenenote.ui.live

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
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
import dev.scenenote.core.designsystem.CapsuleTone
import dev.scenenote.core.designsystem.MenuItem
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneCapsule
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
import dev.scenenote.core.model.ModeSpecs
import dev.scenenote.core.model.Scenes
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.core.settings.KeyWallet
import org.koin.compose.koinInject

/** 语言对卡的候选语言（MVP 端侧覆盖四种 + 日 / 韩需在线档 + Key）。 */
private val LangCandidates = listOf(Lang.ZH_CN, Lang.EN, Lang.YUE_HK, Lang.ZH_SICHUAN, Lang.JA, Lang.KO)

/** 档位分段：MVP 只开放前两档（02 篇：「在线」v1.1、「一体化」v2）。 */
private val TierOptions = listOf("离线", "混合", "在线", "一体化")
private const val TIER_OFFLINE = 0
private const val TIER_HYBRID = 1

/** 主按钮高度 56 + 与 Tab 栏的 14 dp 间隙；内容底部在 140 dp 之外再多留这一段。 */
private val FloatButtonHeight = 56.dp
private val FloatButtonGap = 14.dp

/**
 * 实时 Tab（14 篇 §1 ① `Live.dc.html`）：大标题 + 屏外 / 屏内分段 → 语言对卡 → 档位 → 升级阶梯列表 → 页脚；
 * 主按钮「开始仅听 / 选择视频」悬浮在 Tab 栏上方拇指区。分段与档位是本地状态，语言对直接读写 [AppSettings]。
 */
@Composable
fun LiveTab(onStart: (String) -> Unit) {
    val c = SceneTheme.colors
    val settings = koinInject<AppSettings>()
    val wallet = koinInject<KeyWallet>()
    var segment by rememberSaveable { mutableStateOf(0) } // 0 屏外 · 面对面 / 1 屏内 · 视频
    val screenIn = segment == 1
    val hasKey = remember { wallet.anyKey() }
    var tier by rememberSaveable { mutableStateOf(if (hasKey) TIER_HYBRID else TIER_OFFLINE) }
    var myLang by remember { mutableStateOf(settings.myLang) }
    var otherLang by remember { mutableStateOf(settings.otherLang) }

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
                LangPairCard(
                    myLang = myLang, otherLang = otherLang,
                    onMyLang = { myLang = it; settings.myLang = it },
                    onOtherLang = { otherLang = it; settings.otherLang = it },
                    onSwap = {
                        val m = myLang; myLang = otherLang; otherLang = m
                        settings.myLang = myLang; settings.otherLang = otherLang
                    },
                )

                Column {
                    SceneSectionHeader("档位")
                    // SceneSegmentedControl 暂无逐项禁用：后两档点了不生效，页脚说明原因。
                    SceneSegmentedControl(TierOptions, tier, onSelect = { if (it <= TIER_HYBRID) tier = it }, modifier = Modifier.padding(horizontal = SceneSpacing.page))
                    SceneSectionFooter(
                        when (tier) {
                            TIER_HYBRID -> "混合 = 端侧识别 + 云翻译（百炼 qwen-mt）+ 系统语音；云翻译 1.5 s 超时自动降级（端侧翻译包稍后开放，现阶段降级为只出原文）。" +
                                if (hasKey) "" else "\n尚未配置 Key（设置 → Key 钱包）。"
                            else -> "离线 = 端侧识别 + 端侧翻译（中 ⇄ 英，翻译包稍后开放）+ 系统 / 端侧语音；不出手机，标「离线 · 基础质量」。"
                        } + "\n在线 / 一体化暂不可选（v1.1 起）。",
                    )
                }

                Column {
                    SceneSectionHeader("升级阶梯 · 每级只多一个动作，随时退回")
                    SceneGroup {
                        ModeRow("M0", onClick = { onStart(Scenes.listenOnly.id) })
                        SceneDivider()
                        ModeRow("M1", onClick = { onStart(Scenes.liveTalk.id) })
                        SceneDivider()
                        ModeRow("M3", onClick = { onStart(Scenes.liveTalk.id) })
                        SceneDivider()
                        ModeRow("M4", tone = CapsuleTone.Gray, onClick = { onStart(Scenes.quickPhrase.id) })
                    }
                    SceneSectionFooter("外放类（M6 / M7）标黄，只当降级选项，不在主入口。耳机单击 / Action Button / 磁贴同样能触发。")
                }
            } else {
                Column {
                    SceneSectionHeader("两端可用")
                    SceneGroup {
                        SceneRow(
                            "文件字幕", subtitle = "相册 / 文件 / 直链 MP4，首条字幕约 3–5 s",
                            leading = { ModeCapsule("S4", CapsuleTone.Tint) }, chevron = true,
                            onClick = { onStart(Scenes.screenFile.id) },
                        )
                        SceneDivider()
                        // S8 = M0 媒体档，I6 落地；未接前禁用。
                        SceneRow(
                            "外放旁听", subtitle = "电视 / 电脑 / 别人手机；= 仅听 · 媒体档",
                            leading = { ModeCapsule("S8", CapsuleTone.Tint) }, trailing = { SceneCapsule("稍后开放") },
                            titleColor = c.secondaryLabel,
                        )
                    }
                }
                Column {
                    SceneSectionHeader("Android")
                    SceneGroup {
                        // S1 系统音频抓取（MediaProjection + PiP 字幕条）I6 落地；未接前禁用。
                        SceneRow(
                            "系统字幕", subtitle = "下拉磁贴 → 投屏弹窗「开始」→ 画中画字幕条",
                            leading = { ModeCapsule("S1", CapsuleTone.Tint) },
                            trailing = { SceneCapsule("冷启动 3 步"); SceneCapsule("稍后开放") },
                            titleColor = c.secondaryLabel,
                        )
                        SceneDivider()
                        // S3 OCR 取词延后到 v1.1（13 篇 §0）。
                        SceneRow(
                            "屏幕取词", subtitle = "硬字幕 / 界面文字原位覆盖（手动截帧）",
                            leading = { ModeCapsule("S3", CapsuleTone.Gray) }, trailing = { SceneCapsule("v1.1") },
                            titleColor = c.secondaryLabel,
                        )
                    }
                    SceneSectionFooter("iOS 抓不到其他 App 的声音：其他 App 里正在播的视频请先保存到相册，再用文件字幕。Netflix 等 DRM 平台不可用。")
                }
            }
        }

        // 主按钮：固定在 Tab 栏（8 + 66 dp）上方 14 dp 的拇指区，内容从它下面滚过。
        SceneButton(
            text = if (screenIn) "选择视频" else "开始仅听",
            onClick = { onStart(if (screenIn) Scenes.screenFile.id else Scenes.listenOnly.id) },
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

/** 语言对卡（Live.dc.html 屏外分段第一组）：我说 / 交换 / 对方说，点语言弹菜单选候选；下方一行边界说明。 */
@Composable
private fun LangPairCard(myLang: String, otherLang: String, onMyLang: (String) -> Unit, onOtherLang: (String) -> Unit, onSwap: () -> Unit) {
    val c = SceneTheme.colors
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
        SceneDivider()
        SceneText(
            "自动判向仅支持中 ⇄ 英；粤语 / 四川话走单向仅听；日 / 韩 / 欧语需在线档 + Key",
            Modifier.padding(horizontal = SceneSpacing.row, vertical = 9.dp),
            style = SceneTheme.type.footnote, color = c.secondaryLabel,
        )
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

/** 升级阶梯一行：编号胶囊 + ModeSpec 名称 / 摘要 + chevron。 */
@Composable
private fun ModeRow(modeId: String, tone: CapsuleTone = CapsuleTone.Tint, onClick: () -> Unit) {
    val spec = ModeSpecs.byId(modeId)
    SceneRow(spec.name, subtitle = spec.summary, leading = { ModeCapsule(spec.id, tone) }, chevron = true, onClick = onClick)
}

/** 行首的模式编号胶囊（原型 `.cap` min-width 34）。 */
@Composable
private fun ModeCapsule(id: String, tone: CapsuleTone) {
    SceneCapsule(id, modifier = Modifier.widthIn(min = 34.dp), tone = tone)
}

/** 未落地场景的占位会话页（会议 I5 / 屏内字幕 I6）。 */
@Composable
fun PlaceholderSessionScreen(title: String, note: String, onBack: () -> Unit) {
    dev.scenenote.core.designsystem.GlassScaffold(topBar = { dev.scenenote.core.designsystem.SceneNavBar(title = title, onBack = onBack) }) {
        Column(Modifier.fillMaxSize().padding(top = 104.dp, start = SceneSpacing.page, end = SceneSpacing.page)) {
            SceneText(note, style = SceneTheme.type.subheadline, color = SceneTheme.colors.secondaryLabel)
        }
    }
}
