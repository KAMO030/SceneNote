package dev.scenenote.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.AlertAction
import dev.scenenote.core.designsystem.LocalGlassBackdrop
import dev.scenenote.core.designsystem.SceneAlert
import dev.scenenote.core.platform.AppLocale
import dev.scenenote.core.platform.UiLanguages
import dev.scenenote.core.platform.uiLanguageEndonym
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.ui.common.InlineOptions
import dev.scenenote.core.designsystem.AccentSpec
import dev.scenenote.core.designsystem.Accents
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
import dev.scenenote.shared.resources.*
import dev.scenenote.ui.i18n.langName
import org.jetbrains.compose.resources.StringResource
import dev.scenenote.core.i18n.stringResource
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

fun privacyLabelRes(m: PrivacyMode): StringResource = when (m) {
    PrivacyMode.Locked -> Res.string.privacy_locked
    PrivacyMode.LocalWithPerSegmentConsent -> Res.string.privacy_consent
    PrivacyMode.TextOnlyCloud -> Res.string.privacy_text
    PrivacyMode.AudioCloud -> Res.string.privacy_audio
}

@Composable fun privacyLabel(m: PrivacyMode): String = stringResource(privacyLabelRes(m))

@Composable
private fun ttsLabel(p: TtsPreference): String = stringResource(when (p) {
    TtsPreference.AUTO -> Res.string.settings_tts_auto
    TtsPreference.SYSTEM -> Res.string.settings_tts_system
    TtsPreference.LOCAL -> Res.string.settings_tts_local
    TtsPreference.OFF -> Res.string.settings_tts_off
})

/** 翻译模型三档（用户只看到中文档名，不看模型名与价格）：速度 = qwen-mt-flash（默认）/ 质量 = qwen-mt-plus / 最快 = qwen-mt-lite。 */
private data class MtTier(val label: StringResource, val model: String)

private val MT_TIERS = listOf(MtTier(Res.string.settings_mt_speed, "qwen-mt-flash"), MtTier(Res.string.settings_mt_quality, "qwen-mt-plus"), MtTier(Res.string.settings_mt_fastest, "qwen-mt-lite"))

private fun mtTierOf(model: String): MtTier = MT_TIERS.firstOrNull { it.model == model } ?: MT_TIERS.first()

/**
 * 设置 Tab：inset grouped 分组列表 + 翻译 Key sheet。
 * 四 Tab 壳提供玻璃 Tab 栏，内容自己留 top / bottom 140；所有多选一都是行内展开打勾，不弹窗。
 * 文案遵守 docs/15：一组 ≤ 1 说明、≤ 1 页脚；只渲染当前平台的入口行。
 */
@Composable
fun SettingsTab(onOpenModels: () -> Unit, onOpenSelfTest: () -> Unit, onOpenGallery: () -> Unit, onOpenOnboarding: () -> Unit, onOpenLedger: () -> Unit = {}, onOpenGlossary: () -> Unit = {}, openKeyOnEnter: Boolean = false) {
    val vm: SettingsViewModel = koinViewModel()
    LaunchedEffect(Unit) { vm.refresh() }   // 实时 Tab 可能直接改过语言对
    val ui by vm.ui.collectAsState()
    val settings = koinInject<AppSettings>()
    val c = SceneTheme.colors
    val walletState = remember { mutableStateOf(openKeyOnEnter) }   // 新手引导「去填 Key」直接打开 sheet
    var wallet by walletState
    var keyHint by remember { mutableStateOf(!settings.hintSeen(HINT_KEY)) }
    // 界面语言：两端都是设完就地换（AppLocale.set 里 bump 一次重组）；哪天有平台做不到就靠 appliesImmediately 弹说明
    val appLocale = koinInject<AppLocale>()
    var uiLang by remember { mutableStateOf(appLocale.override) }
    var restartNotice by remember { mutableStateOf(false) }
    // 翻译 Key sheet 交给壳的覆盖层，盖在玻璃 Tab 栏之上；不在壳里时内联渲染
    val overlayHost = dev.scenenote.ui.LocalShellOverlay.current
    val overlayContent: @Composable BoxScope.() -> Unit = { KeyWalletOverlay(vm, walletState) }
    DisposableEffect(overlayHost) {
        overlayHost?.value = overlayContent
        // Tab 切换有过渡动画，旧 Tab 退场晚于新 Tab 进场：只清自己挂上去的，别把新 Tab 的清掉
        onDispose { if (overlayHost?.value === overlayContent) overlayHost.value = null }
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
            SceneText(stringResource(Res.string.tab_settings), Modifier.padding(horizontal = SceneSpacing.page), style = SceneTheme.type.largeTitle)

            // ---- 翻译 Key ----
            Column {
                SceneSectionHeader(stringResource(Res.string.settings_key_header))
                val configured = ui.providers.any { it.masked != null }
                SceneGroup {
                    SceneRow(
                        stringResource(Res.string.settings_key_row), value = stringResource(if (configured) Res.string.settings_key_filled else Res.string.settings_key_empty), chevron = true,
                        leading = { SceneRowIcon(SceneIcons.Key, c.tint) }, onClick = ::openWallet,
                    )
                    SceneDivider(inset = 57.dp)
                    SceneRow(
                        stringResource(Res.string.settings_mt_model), value = stringResource(mtTierOf(ui.mtModel).label), chevron = true,
                        leading = { RowGlyph(stringResource(Res.string.settings_glyph_mt), RowIconColor.Indigo) }, onClick = { toggle("mt") },
                    )
                    InlineOptions(expanded == "mt", MT_TIERS, mtTierOf(ui.mtModel), label = { stringResource(it.label) }) { vm.setMtModel(it.model); expanded = null }
                }
                if (!configured && keyHint) SceneSectionFooter(stringResource(Res.string.settings_key_hint))
            }

            // ---- 联网权限 ----
            Column {
                SceneSectionHeader(stringResource(Res.string.settings_net_header))
                SceneGroup {
                    SceneRow(
                        stringResource(Res.string.settings_net_default), value = privacyLabel(ui.privacy), chevron = true,
                        leading = { SceneRowIcon(SceneIcons.Lock, RowIconColor.Gray) }, onClick = { toggle("privacy") },
                    )
                    InlineOptions(expanded == "privacy", PRIVACY_OPTIONS, ui.privacy, label = { privacyLabel(it) }) { vm.setPrivacy(it); expanded = null }
                    SceneDivider(inset = 57.dp)
                    SceneRow(stringResource(Res.string.settings_ledger), chevron = true, leading = { RowGlyph(stringResource(Res.string.settings_glyph_ledger), RowIconColor.Gray) }, onClick = onOpenLedger)
                }
                SceneSectionFooter(stringResource(Res.string.settings_net_footer))
            }

            // ---- 语音包 ----
            Column {
                SceneSectionHeader(stringResource(Res.string.settings_packs_header))
                SceneGroup {
                    SceneRow(stringResource(Res.string.settings_packs_manage), chevron = true, leading = { SceneRowIcon(SceneIcons.Waveform, RowIconColor.Green) }, onClick = onOpenModels)
                }
            }

            // ---- 朗读 ----
            Column {
                SceneSectionHeader(stringResource(Res.string.settings_tts_header))
                SceneGroup {
                    SceneRow(stringResource(Res.string.settings_tts_row), leading = { SceneRowIcon(SceneIcons.Speaker, c.tint) })
                    Box(Modifier.padding(start = SceneSpacing.row, end = SceneSpacing.row, bottom = 12.dp)) {
                        SceneSegmentedControl(
                            options = TtsPreference.entries.map { ttsLabel(it) },
                            selectedIndex = TtsPreference.entries.indexOf(ui.ttsPreference).coerceAtLeast(0),
                            onSelect = { vm.setTtsPreference(TtsPreference.entries[it]) },
                        )
                    }
                }
            }

            // ---- 面对面对话 ----
            Column {
                SceneSectionHeader(stringResource(Res.string.scene_live_talk))
                SceneGroup {
                    SceneRow(
                        stringResource(Res.string.settings_auto_posture),
                        leading = { RowGlyph(stringResource(Res.string.settings_glyph_posture), c.tint) },
                        trailing = { SceneToggle(ui.autoPosture, vm::setAutoPosture) },
                    )
                    SceneDivider(inset = 57.dp)
                    SceneRow(
                        stringResource(Res.string.settings_direction_auto),
                        leading = { SceneRowIcon(SceneIcons.Swap, RowIconColor.Indigo) },
                        trailing = { SceneToggle(ui.directionAuto, vm::setDirectionAuto) },
                    )
                    SceneDivider(inset = 57.dp)
                    SceneRow(
                        stringResource(Res.string.settings_polite_opener),
                        leading = { SceneRowIcon(SceneIcons.Speaker, RowIconColor.Orange) },
                        trailing = { SceneToggle(ui.politeOpener, vm::setPoliteOpener) },
                    )
                }
            }

            // ---- 语言 ----
            Column {
                SceneSectionHeader(stringResource(Res.string.settings_lang_header))
                SceneGroup {
                    SceneRow(
                        stringResource(Res.string.settings_lang_mine), value = langName(ui.myLang), chevron = true,
                        leading = { SceneRowIcon(SceneIcons.Mic, RowIconColor.Blue) }, onClick = { toggle("my") },
                    )
                    InlineOptions(expanded == "my", LANG_OPTIONS, ui.myLang, label = { langName(it) }) { vm.setMyLang(it); expanded = null }
                    SceneDivider(inset = 57.dp)
                    SceneRow(
                        stringResource(Res.string.common_other), value = langName(ui.otherLang), chevron = true,
                        leading = { SceneRowIcon(SceneIcons.Headphones, RowIconColor.Blue) }, onClick = { toggle("other") },
                    )
                    InlineOptions(expanded == "other", LANG_OPTIONS, ui.otherLang, label = { langName(it) }) { vm.setOtherLang(it); expanded = null }
                    SceneDivider(inset = 57.dp)
                    SceneRow(
                        stringResource(Res.string.settings_ui_language), value = uiLanguageLabel(uiLang), chevron = true,
                        leading = { RowGlyph("A", RowIconColor.Green) }, onClick = { toggle("ui_lang") },
                    )
                    InlineOptions(expanded == "ui_lang", listOf<String?>(null) + UiLanguages, uiLang, label = { uiLanguageLabel(it) }) { tag ->
                        expanded = null
                        if (tag != uiLang) { uiLang = tag; appLocale.set(tag); if (!appLocale.appliesImmediately) restartNotice = true }
                    }
                    SceneDivider(inset = 57.dp)
                    SceneRow(stringResource(Res.string.settings_glossary), chevron = true, leading = { RowGlyph(stringResource(Res.string.settings_glyph_glossary), RowIconColor.Indigo) }, onClick = onOpenGlossary)
                }
                SceneSectionFooter(stringResource(Res.string.settings_lang_footer))
            }

            // ---- 入口（只渲染当前平台的那一行）----
            Column {
                SceneSectionHeader(stringResource(Res.string.settings_entries_header))
                SceneGroup {
                    if (PlatformInfo.isIos) {
                        SceneRow(stringResource(Res.string.settings_entry_ios), subtitle = stringResource(Res.string.settings_entry_ios_sub), leading = { RowGlyph("◎", RowIconColor.Orange) })
                        SceneDivider(inset = 57.dp)
                    }
                    if (PlatformInfo.isAndroid) {
                        SceneRow(stringResource(Res.string.settings_entry_tile), subtitle = stringResource(Res.string.settings_entry_tile_sub), leading = { SceneRowIcon(SceneIcons.Scenes, RowIconColor.Orange) })
                        SceneDivider(inset = 57.dp)
                    }
                    SceneRow(stringResource(Res.string.settings_entry_headset), subtitle = stringResource(Res.string.settings_entry_headset_sub), leading = { SceneRowIcon(SceneIcons.Headphones, RowIconColor.Orange) })
                }
            }

            // ---- 外观 ----
            Column {
                SceneSectionHeader(stringResource(Res.string.settings_appearance_header))
                val accentId by settings.accent.collectAsState()
                val accent = Accents.byId(accentId)
                SceneGroup {
                    SceneRow(stringResource(Res.string.settings_accent), value = accentName(accent), leading = { SceneRowIcon(SceneIcons.Sparkle, c.tint) })
                    SceneDivider(inset = 57.dp)
                    AccentSwatches(current = accent, onSelect = { settings.setAccent(it.id) })
                }
            }

            // ---- 诊断 ----
            Column {
                SceneSectionHeader(stringResource(Res.string.settings_more_header))
                SceneGroup {
                    SceneRow(stringResource(Res.string.settings_diagnostics), chevron = true, leading = { SceneRowIcon(SceneIcons.Mic, RowIconColor.Gray) }, onClick = onOpenSelfTest)
                    SceneDivider(inset = 57.dp)
                    // 设计系统预览是开发者页（含内部术语），不进设置；深链 scenenote://gallery 仍可进
                    SceneRow(stringResource(Res.string.home_onboarding), chevron = true, leading = { SceneRowIcon(SceneIcons.Play, RowIconColor.Gray) }, onClick = onOpenOnboarding)
                }
            }
        }

        // 不在壳里时内联渲染：sheet 处在取样层内部，不能取样自己，关掉 backdrop 走不透明回退
        if (overlayHost == null) CompositionLocalProvider(LocalGlassBackdrop provides null) {
            KeyWalletSheet(visible = wallet, ui = ui, vm = vm, onDismiss = { wallet = false })
        }
    }

    if (restartNotice) {
        SceneAlert(
            title = stringResource(Res.string.settings_ui_language_restart_title),
            message = stringResource(Res.string.settings_ui_language_restart_message),
            actions = listOf(
                AlertAction(stringResource(Res.string.common_ok), onClick = { restartNotice = false }, isDefault = true),
                AlertAction(stringResource(Res.string.settings_ui_language_quit), onClick = { restartNotice = false; appLocale.exitApp() }, destructive = true),
            ),
            onDismissRequest = { restartNotice = false },
        )
    }
}

/** 界面语言一行的值：跟随系统 / 各语言本族名。 */
@Composable
private fun uiLanguageLabel(tag: String?): String = if (tag == null) stringResource(Res.string.settings_ui_language_system) else uiLanguageEndonym(tag)

/** 壳覆盖层里的翻译 Key sheet：自己收集 ui，开关状态由设置页持有；backdrop 由壳提供，能取样内容层做模糊。 */
@Composable
private fun BoxScope.KeyWalletOverlay(vm: SettingsViewModel, open: MutableState<Boolean>) {
    val ui by vm.ui.collectAsState()
    KeyWalletSheet(visible = open.value, ui = ui, vm = vm, onDismiss = { open.value = false })
}


@Composable
private fun accentName(a: AccentSpec): String = when (a.id) {
    "pink" -> stringResource(Res.string.accent_pink); "teal" -> stringResource(Res.string.accent_teal); "blue" -> stringResource(Res.string.accent_blue)
    "purple" -> stringResource(Res.string.accent_purple); "orange" -> stringResource(Res.string.accent_orange); "green" -> stringResource(Res.string.accent_green)
    else -> a.name
}

/** 主题色色板：一排 34 pt 圆点（浅 / 深色各取当前模式的 tint），选中的画勾 + 外圈；点一下立即全 App 换色。 */
@Composable
private fun AccentSwatches(current: AccentSpec, onSelect: (AccentSpec) -> Unit) {
    val c = SceneTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.row, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Accents.all.forEach { a ->
            val colors = if (c.isDark) a.dark else a.light
            val tint = Color(colors.tint)
            val on = current.id == a.id
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(34.dp)
                    .then(if (on) Modifier.border(2.dp, tint, CircleShape).padding(4.dp) else Modifier.padding(4.dp))
                    .clip(CircleShape)
                    .background(tint)
                    .clickable(interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = { onSelect(a) })
                    .semantics { contentDescription = a.name; selected = on },
                contentAlignment = Alignment.Center,
            ) {
                if (on) SceneIcon(SceneIcons.Check, contentDescription = null, size = 16.dp, tint = Color(colors.onTint))
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
