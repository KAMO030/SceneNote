package dev.scenenote.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.LocalGlassBackdrop
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneRow
import dev.scenenote.core.designsystem.SceneRowIcon
import dev.scenenote.core.designsystem.SceneSectionFooter
import dev.scenenote.core.designsystem.SceneSectionHeader
import dev.scenenote.core.designsystem.SceneSegmentedControl
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.designsystem.SceneToggle
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.PrivacyMode
import dev.scenenote.core.platform.PlatformInfo
import dev.scenenote.core.platform.isAndroid
import dev.scenenote.core.platform.isIos
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.tts.TtsPreference
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** 设置行 29 pt 图标底色：对应 iOS 系统色（蓝 / 靛 / 灰 / 绿 / 橙），深浅色一致；青绿用 SceneTheme.colors.tint。 */
private object RowIconColor {
    val Blue = Color(0xFF3478F6)
    val Indigo = Color(0xFF5856D6)
    val Gray = Color(0xFF8E8E93)
    val Green = Color(0xFF2E9E4F)
    val Orange = Color(0xFFFF9500)
}

/** 一次性提示 key：还没填 Key 时，翻译 Key 组下方给一句引导；打开过 sheet 就不再显示。 */
private const val HINT_KEY = "settings.key"

/** 语言候选：本机识别覆盖的四种 + 云端可译的日 / 韩。 */
private val LANG_OPTIONS = listOf(Lang.ZH_CN, Lang.YUE_HK, Lang.ZH_SICHUAN, Lang.EN, Lang.JA, Lang.KO)

private val PRIVACY_OPTIONS: List<PrivacyMode> = listOf(PrivacyMode.Locked, PrivacyMode.LocalWithPerSegmentConsent, PrivacyMode.TextOnlyCloud, PrivacyMode.AudioCloud)

private fun privacyLabel(m: PrivacyMode): String = when (m) {
    PrivacyMode.Locked -> "不联网"
    PrivacyMode.LocalWithPerSegmentConsent -> "每次询问"
    PrivacyMode.TextOnlyCloud -> "只发文字"
    PrivacyMode.AudioCloud -> "文字和录音"
}

private fun ttsLabel(p: TtsPreference): String = when (p) {
    TtsPreference.AUTO -> "自动"
    TtsPreference.SYSTEM -> "系统"
    TtsPreference.LOCAL -> "本机"
    TtsPreference.OFF -> "关"
}

/** 翻译模型三档（用户只看到中文档名，不看模型名与价格）：速度 = qwen-mt-flash（默认）/ 质量 = qwen-mt-plus / 最快 = qwen-mt-lite。 */
private data class MtTier(val label: String, val model: String)

private val MT_TIERS = listOf(MtTier("速度", "qwen-mt-flash"), MtTier("质量", "qwen-mt-plus"), MtTier("最快", "qwen-mt-lite"))

private fun mtTierOf(model: String): MtTier = MT_TIERS.firstOrNull { it.model == model } ?: MT_TIERS.first()

/**
 * 设置 Tab：inset grouped 分组列表 + 翻译 Key sheet。
 * 四 Tab 壳提供玻璃 Tab 栏，内容自己留 top / bottom 140；所有多选一都是行内展开打勾，不弹窗。
 * 文案遵守 docs/15：一组 ≤ 1 说明、≤ 1 页脚；只渲染当前平台的入口行。
 */
@Composable
fun SettingsTab(onOpenModels: () -> Unit, onOpenSelfTest: () -> Unit, onOpenGallery: () -> Unit, onOpenOnboarding: () -> Unit, openKeyOnEnter: Boolean = false) {
    val vm: SettingsViewModel = koinViewModel()
    LaunchedEffect(Unit) { vm.refresh() }   // 实时 Tab 可能直接改过语言对
    val ui by vm.ui.collectAsState()
    val settings = koinInject<AppSettings>()
    val c = SceneTheme.colors
    val walletState = remember { mutableStateOf(openKeyOnEnter) }   // 新手引导「去填 Key」直接打开 sheet
    var wallet by walletState
    var keyHint by remember { mutableStateOf(!settings.hintSeen(HINT_KEY)) }
    // 翻译 Key sheet 交给壳的覆盖层，盖在玻璃 Tab 栏之上；不在壳里时内联渲染
    val overlayHost = dev.scenenote.ui.LocalShellOverlay.current
    val overlayContent: @Composable BoxScope.() -> Unit = { KeyWalletOverlay(vm, walletState) }
    DisposableEffect(overlayHost) {
        overlayHost?.value = overlayContent
        onDispose { overlayHost?.value = null }
    }
    // 当前展开的行内选项组（一次只展开一个），null = 全部收起
    var expanded by remember { mutableStateOf<String?>(null) }
    fun toggle(key: String) { expanded = if (expanded == key) null else key }
    fun openWallet() {
        if (keyHint) { settings.markHintSeen(HINT_KEY); keyHint = false }
        wallet = true
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 6.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
        ) {
            SceneText("设置", Modifier.padding(horizontal = SceneSpacing.page), style = SceneTheme.type.largeTitle)

            // ---- 翻译 Key ----
            Column {
                SceneSectionHeader("翻译 Key")
                val configured = ui.providers.any { it.masked != null }
                SceneGroup {
                    SceneRow(
                        "填写 Key", value = if (configured) "已填写" else "未填写", chevron = true,
                        leading = { SceneRowIcon(SceneIcons.Key, c.tint) }, onClick = ::openWallet,
                    )
                    SceneDivider(inset = 57.dp)
                    SceneRow(
                        "翻译模型", value = mtTierOf(ui.mtModel).label, chevron = true,
                        leading = { RowGlyph("文", RowIconColor.Indigo) }, onClick = { toggle("mt") },
                    )
                    InlineOptions(expanded == "mt", MT_TIERS, mtTierOf(ui.mtModel), label = { it.label }) { vm.setMtModel(it.model); expanded = null }
                }
                if (!configured && keyHint) SceneSectionFooter("还没有 Key？点上面一行，三步填好")
            }

            // ---- 联网权限 ----
            Column {
                SceneSectionHeader("联网权限")
                SceneGroup {
                    SceneRow(
                        "默认", value = privacyLabel(ui.privacy), chevron = true,
                        leading = { SceneRowIcon(SceneIcons.Lock, RowIconColor.Gray) }, onClick = { toggle("privacy") },
                    )
                    InlineOptions(expanded == "privacy", PRIVACY_OPTIONS, ui.privacy, label = { privacyLabel(it) }) { vm.setPrivacy(it); expanded = null }
                }
                SceneSectionFooter("录音只在你选「文字和录音」时上传")
            }

            // ---- 语音包 ----
            Column {
                SceneSectionHeader("语音包")
                SceneGroup {
                    SceneRow("管理语音包", chevron = true, leading = { SceneRowIcon(SceneIcons.Waveform, RowIconColor.Green) }, onClick = onOpenModels)
                }
            }

            // ---- 朗读 ----
            Column {
                SceneSectionHeader("朗读")
                SceneGroup {
                    SceneRow("译文朗读", leading = { SceneRowIcon(SceneIcons.Speaker, c.tint) })
                    Box(Modifier.padding(start = SceneSpacing.row, end = SceneSpacing.row, bottom = 12.dp)) {
                        SceneSegmentedControl(
                            options = TtsPreference.entries.map(::ttsLabel),
                            selectedIndex = TtsPreference.entries.indexOf(ui.ttsPreference).coerceAtLeast(0),
                            onSelect = { vm.setTtsPreference(TtsPreference.entries[it]) },
                        )
                    }
                }
            }

            // ---- 面对面对话 ----
            Column {
                SceneSectionHeader("面对面对话")
                SceneGroup {
                    SceneRow(
                        "举起手机自动切到面屏",
                        leading = { RowGlyph("举", c.tint) },
                        trailing = { SceneToggle(ui.autoPosture, vm::setAutoPosture) },
                    )
                    SceneDivider(inset = 57.dp)
                    SceneRow(
                        "自动分辨谁在说",
                        leading = { SceneRowIcon(SceneIcons.Swap, RowIconColor.Indigo) },
                        trailing = { SceneToggle(ui.directionAuto, vm::setDirectionAuto) },
                    )
                    SceneDivider(inset = 57.dp)
                    SceneRow(
                        "进入面屏时播一句提示",
                        leading = { SceneRowIcon(SceneIcons.Speaker, RowIconColor.Orange) },
                        trailing = { SceneToggle(ui.politeOpener, vm::setPoliteOpener) },
                    )
                }
            }

            // ---- 语言 ----
            Column {
                SceneSectionHeader("语言")
                SceneGroup {
                    SceneRow(
                        "我的", value = Lang.displayName(ui.myLang), chevron = true,
                        leading = { SceneRowIcon(SceneIcons.Mic, RowIconColor.Blue) }, onClick = { toggle("my") },
                    )
                    InlineOptions(expanded == "my", LANG_OPTIONS, ui.myLang, label = { Lang.displayName(it) }) { vm.setMyLang(it); expanded = null }
                    SceneDivider(inset = 57.dp)
                    SceneRow(
                        "对方", value = Lang.displayName(ui.otherLang), chevron = true,
                        leading = { SceneRowIcon(SceneIcons.Headphones, RowIconColor.Blue) }, onClick = { toggle("other") },
                    )
                    InlineOptions(expanded == "other", LANG_OPTIONS, ui.otherLang, label = { Lang.displayName(it) }) { vm.setOtherLang(it); expanded = null }
                }
                SceneSectionFooter("日语、韩语需要翻译 Key")
            }

            // ---- 入口（只渲染当前平台的那一行）----
            Column {
                SceneSectionHeader("入口")
                SceneGroup {
                    if (PlatformInfo.isIos) {
                        SceneRow("快捷指令 / 操作按钮", subtitle = "设置 → 操作按钮 → 快捷指令", leading = { RowGlyph("◎", RowIconColor.Orange) })
                        SceneDivider(inset = 57.dp)
                    }
                    if (PlatformInfo.isAndroid) {
                        SceneRow("快捷设置磁贴", subtitle = "下拉快捷设置 → 编辑 → 拖入", leading = { SceneRowIcon(SceneIcons.Scenes, RowIconColor.Orange) })
                        SceneDivider(inset = 57.dp)
                    }
                    SceneRow("耳机按键", subtitle = "单击暂停 · 双击跳过 · 三击重播", leading = { SceneRowIcon(SceneIcons.Headphones, RowIconColor.Orange) })
                }
            }

            // ---- 诊断 ----
            Column {
                SceneSectionHeader("更多")
                SceneGroup {
                    SceneRow("诊断", chevron = true, leading = { SceneRowIcon(SceneIcons.Mic, RowIconColor.Gray) }, onClick = onOpenSelfTest)
                    SceneDivider(inset = 57.dp)
                    // 设计系统预览是开发者页（含内部术语），不进设置；深链 scenenote://gallery 仍可进
                    SceneRow("新手引导", chevron = true, leading = { SceneRowIcon(SceneIcons.Play, RowIconColor.Gray) }, onClick = onOpenOnboarding)
                }
            }
        }

        if (overlayHost == null) CompositionLocalProvider(LocalGlassBackdrop provides null) {
            KeyWalletSheet(visible = wallet, ui = ui, vm = vm, onDismiss = { wallet = false })
        }
    }
}

/** 壳覆盖层里的翻译 Key sheet：自己收集 ui，开关状态由设置页持有。 */
@Composable
private fun BoxScope.KeyWalletOverlay(vm: SettingsViewModel, open: MutableState<Boolean>) {
    val ui by vm.ui.collectAsState()
    CompositionLocalProvider(LocalGlassBackdrop provides null) {
        KeyWalletSheet(visible = open.value, ui = ui, vm = vm, onDismiss = { open.value = false })
    }
}

/** 行内单选组（「四选一打勾」样式）：点父行展开，选中项右侧打勾，不弹窗；Reduce Motion 时瞬开。 */
@Composable
private fun <T> InlineOptions(
    expanded: Boolean,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val c = SceneTheme.colors
    val ms = SceneTheme.motion.normalMs
    AnimatedVisibility(expanded, enter = expandVertically(tween(ms)) + fadeIn(tween(ms)), exit = shrinkVertically(tween(ms)) + fadeOut(tween(ms))) {
        Column {
            options.forEach { o ->
                val on = o == selected
                SceneDivider(inset = 57.dp)
                SceneRow(
                    label(o),
                    leading = { Spacer(Modifier.width(29.dp)) },
                    trailing = { if (on) SceneIcon(SceneIcons.Check, contentDescription = "已选", size = 18.dp, tint = c.tint) },
                    onClick = { onSelect(o) },
                )
            }
        }
    }
}

/** SceneRowIcon 的文字版：SceneIcons 没有的符号用一个字代替（29 pt、7 pt 圆角、白字）。 */
@Composable
private fun RowGlyph(text: String, background: Color) {
    Box(Modifier.size(29.dp).clip(RoundedCornerShape(7.dp)).background(background), contentAlignment = Alignment.Center) {
        SceneText(text, style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
    }
}
