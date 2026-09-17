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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.scenenote.translate.BailianPricing
import dev.scenenote.tts.TtsPreference
import org.koin.compose.viewmodel.koinViewModel

/** 设置行 29 pt 图标底色：对应 iOS 系统色（蓝 / 靛 / 灰 / 绿 / 紫 / 橙），深浅色一致；青绿用 SceneTheme.colors.tint。 */
private object RowIconColor {
    val Blue = Color(0xFF3478F6)
    val Indigo = Color(0xFF5856D6)
    val Gray = Color(0xFF8E8E93)
    val Green = Color(0xFF2E9E4F)
    val Purple = Color(0xFFAF52DE)
    val Orange = Color(0xFFFF9500)
}

/** 语言候选：端侧覆盖的四种 + 云端可译的日 / 韩。 */
private val LANG_OPTIONS = listOf(Lang.ZH_CN, Lang.YUE_HK, Lang.ZH_SICHUAN, Lang.EN, Lang.JA, Lang.KO)

private val PRIVACY_OPTIONS: List<PrivacyMode> = listOf(PrivacyMode.Locked, PrivacyMode.LocalWithPerSegmentConsent, PrivacyMode.TextOnlyCloud, PrivacyMode.AudioCloud)

private fun privacyLabel(m: PrivacyMode): String = when (m) {
    PrivacyMode.Locked -> "锁定"
    PrivacyMode.LocalWithPerSegmentConsent -> "仅本机 + 逐段授权"
    PrivacyMode.TextOnlyCloud -> "仅文本上云"
    PrivacyMode.AudioCloud -> "音频上云"
}

private fun privacyNote(m: PrivacyMode): String = when (m) {
    PrivacyMode.Locked -> "不向第三方、局域网发任何请求"
    PrivacyMode.LocalWithPerSegmentConsent -> "默认不出站，按句临时授权"
    PrivacyMode.TextOnlyCloud -> "录音不出手机，只有文字上云"
    PrivacyMode.AudioCloud -> "音频与文字都可上云"
}

private fun ttsLabel(p: TtsPreference): String = when (p) {
    TtsPreference.AUTO -> "自动"
    TtsPreference.SYSTEM -> "系统"
    TtsPreference.LOCAL -> "端侧"
    TtsPreference.OFF -> "关闭"
}

/** 每百万 token 的价格说明（BailianPricing 公开价，仅供参考）。 */
private fun modelNote(model: String): String? = BailianPricing.mt[model]?.let { "输入 ¥${it.inputPerM} · 输出 ¥${it.outputPerM} / 百万 token" }

/**
 * 设置 Tab（原型 Settings.dc.html，14 篇 §1 ①）：inset grouped 分组列表 + Key 钱包 sheet。
 * 四 Tab 壳提供玻璃 Tab 栏，内容自己留 top 104 / bottom 140；所有多选一都是行内展开打勾，不弹窗。
 */
@Composable
fun SettingsTab(onOpenModels: () -> Unit, onOpenSelfTest: () -> Unit, onOpenGallery: () -> Unit, onOpenOnboarding: () -> Unit) {
    val vm: SettingsViewModel = koinViewModel()
    LaunchedEffect(Unit) { vm.refresh() }   // 实时 Tab 可能直接改过语言对
    val ui by vm.ui.collectAsState()
    val c = SceneTheme.colors
    val walletState = remember { mutableStateOf(false) }
    var wallet by walletState
    // Key 钱包 sheet 交给壳的覆盖层，盖在玻璃 Tab 栏之上（原型 KeyWallet）；不在壳里时内联渲染
    val overlayHost = dev.scenenote.ui.LocalShellOverlay.current
    val overlayContent: @Composable BoxScope.() -> Unit = { KeyWalletOverlay(vm, walletState) }
    DisposableEffect(overlayHost) {
        overlayHost?.value = overlayContent
        onDispose { overlayHost?.value = null }
    }
    // 当前展开的行内选项组（一次只展开一个），null = 全部收起
    var expanded by remember { mutableStateOf<String?>(null) }
    fun toggle(key: String) { expanded = if (expanded == key) null else key }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 6.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
        ) {
            SceneText("设置", Modifier.padding(horizontal = SceneSpacing.page), style = SceneTheme.type.largeTitle)

            // ---- 云端（自带 Key）----
            Column {
                SceneSectionHeader("云端（自带 Key）")
                SceneGroup {
                    val configured = ui.providers.firstOrNull { it.masked != null }
                    SceneRow(
                        "Key 钱包", value = configured?.let { "${it.name} · 已配置" } ?: "未配置", chevron = true,
                        leading = { SceneRowIcon(SceneIcons.Key, c.tint) }, onClick = { wallet = true },
                    )
                    SceneDivider(inset = 57.dp)
                    SceneRow(
                        "识别路由", subtitle = "优先云端 · 云端识别稍后开放", value = "端侧",
                        leading = { SceneRowIcon(SceneIcons.Waveform, RowIconColor.Blue) },
                    )
                    SceneDivider(inset = 57.dp)
                    SceneRow(
                        "翻译档位", value = ui.mtModel, chevron = true,
                        leading = { RowGlyph("文", RowIconColor.Indigo) }, onClick = { toggle("mt") },
                    )
                    InlineOptions<String>(expanded == "mt", BailianPricing.models, ui.mtModel, label = { it }, note = { modelNote(it) }) { vm.setMtModel(it); expanded = null }
                }
                SceneSectionFooter("路由三态：仅本机 / 自动 / 优先云端。默认端侧识别，只有文字上云润色、翻译。翻译档位按厂商公开价换算，仅供参考。")
            }

            // ---- 隐私 ----
            Column {
                SceneSectionHeader("隐私")
                SceneGroup {
                    SceneRow(
                        "默认隐私档", value = privacyLabel(ui.privacy), chevron = true,
                        leading = { SceneRowIcon(SceneIcons.Lock, RowIconColor.Gray) }, onClick = { toggle("privacy") },
                    )
                    InlineOptions<PrivacyMode>(expanded == "privacy", PRIVACY_OPTIONS, ui.privacy, label = { privacyLabel(it) }, note = { privacyNote(it) }) { vm.setPrivacy(it); expanded = null }
                    SceneDivider(inset = 57.dp)
                    SceneRow(
                        "去向账本", subtitle = "每条数据去了哪 · 页面稍后开放", value = "稍后开放",
                        leading = { SceneRowIcon(SceneIcons.Doc, RowIconColor.Gray) },
                    )
                }
                SceneSectionFooter("四档：锁定 / 仅本机 + 逐段授权 / 仅文本上云 / 音频上云。录音上云只在你明确选择时发生。")
            }

            // ---- 模型与语言包 ----
            Column {
                SceneSectionHeader("模型与语言包")
                SceneGroup {
                    SceneRow(
                        "端侧模型与语言包", subtitle = "VAD、流式识别、定稿、翻译语言对；按需下载", chevron = true,
                        leading = { SceneRowIcon(SceneIcons.Waveform, RowIconColor.Green) }, onClick = onOpenModels,
                    )
                }
                SceneSectionFooter("端侧翻译语言对按需下载，不计入首装体积；未下载时该句标「未译」并提示下载。")
            }

            // ---- 语音输出 ----
            Column {
                SceneSectionHeader("语音输出")
                SceneGroup {
                    SceneRow("译文朗读", value = ttsLabel(ui.ttsPreference), leading = { SceneRowIcon(SceneIcons.Speaker, c.tint) })
                    Box(Modifier.padding(start = SceneSpacing.row, end = SceneSpacing.row, bottom = 12.dp)) {
                        SceneSegmentedControl(
                            options = TtsPreference.entries.map(::ttsLabel),
                            selectedIndex = TtsPreference.entries.indexOf(ui.ttsPreference).coerceAtLeast(0),
                            onSelect = { vm.setTtsPreference(TtsPreference.entries[it]) },
                        )
                    }
                }
                SceneSectionFooter("自动 = 系统语音优先、端侧兜底；关闭后译文只显示不朗读。")
            }

            // ---- 面对面对话（旗舰场景 M0 → M1 → M3 的三个开关；规格 §3.1–3.3）----
            Column {
                SceneSectionHeader("面对面对话")
                SceneGroup {
                    // 姿态：竖直 + 屏幕朝外 + 静止 → 从仅听自动切到耳听·面屏（对方半屏）
                    SceneRow(
                        "竖起手机自动进耳听·面屏", subtitle = "竖直、未遮挡并静止 1.5 s 时切换（分不清屏幕朝向）",
                        leading = { RowGlyph("姿", c.tint) },
                        trailing = { SceneToggle(ui.autoPosture, vm::setAutoPosture) },
                    )
                    SceneDivider(inset = 57.dp)
                    // 判向：开 = 输出脚本 + 声纹自动判谁在说；关 = 固定对方 → 我（单工）
                    SceneRow(
                        "自动判向", subtitle = if (ui.directionAuto) "按输出脚本 + 声纹判断谁在说" else "已固定：对方 → 我",
                        leading = { SceneRowIcon(SceneIcons.Swap, RowIconColor.Indigo) },
                        trailing = { SceneToggle(ui.directionAuto, vm::setDirectionAuto) },
                    )
                    SceneDivider(inset = 57.dp)
                    // 开场白：默认不外放，录音告知由对方半屏礼貌卡完成；打开后进 M1 时以 −6 dB 播一句对方语言
                    SceneRow(
                        "进入耳听·面屏时播一句开场白", subtitle = "−6 dB · 默认关",
                        leading = { SceneRowIcon(SceneIcons.Speaker, RowIconColor.Orange) },
                        trailing = { SceneToggle(ui.politeOpener, vm::setPoliteOpener) },
                    )
                }
                SceneSectionFooter("姿态检测只在 App 前台且屏幕已点亮时生效；两人同说中文时建议关闭自动判向，固定为对方 → 我。")
            }

            // ---- 系统翻译插件（v1.1）----
            Column {
                SceneSectionHeader("系统翻译插件（v1.1）")
                SceneGroup {
                    SceneRow("Apple Translation", subtitle = "v1.1", leading = { SceneRowIcon(SceneIcons.Sparkle, RowIconColor.Purple) }, trailing = { SceneToggle(false, {}, enabled = false) })
                    SceneDivider(inset = 57.dp)
                    SceneRow("ML Kit Translation", subtitle = "v1.1", leading = { SceneRowIcon(SceneIcons.Sparkle, RowIconColor.Purple) }, trailing = { SceneToggle(false, {}, enabled = false) })
                }
                SceneSectionFooter("默认关闭；不支持粤语。两端能力不依赖系统 API。")
            }

            // ---- 语言 ----
            Column {
                SceneSectionHeader("语言")
                SceneGroup {
                    SceneRow(
                        "我的语言", value = Lang.displayName(ui.myLang), chevron = true,
                        leading = { SceneRowIcon(SceneIcons.Mic, RowIconColor.Blue) }, onClick = { toggle("my") },
                    )
                    InlineOptions<String>(expanded == "my", LANG_OPTIONS, ui.myLang, label = { Lang.displayName(it) }) { vm.setMyLang(it); expanded = null }
                    SceneDivider(inset = 57.dp)
                    SceneRow(
                        "对方语言", value = Lang.displayName(ui.otherLang), chevron = true,
                        leading = { SceneRowIcon(SceneIcons.Headphones, RowIconColor.Blue) }, onClick = { toggle("other") },
                    )
                    InlineOptions<String>(expanded == "other", LANG_OPTIONS, ui.otherLang, label = { Lang.displayName(it) }) { vm.setOtherLang(it); expanded = null }
                }
                SceneSectionFooter("端侧识别覆盖普通话 / 英语 / 粤语 / 四川话；日语、韩语需云端翻译。场景卡可单独覆盖语言对。")
            }

            // ---- 入口（占位）----
            Column {
                SceneSectionHeader("入口")
                SceneGroup {
                    SceneRow("控制中心 / Action Button", subtitle = "iOS：设置 → 操作按钮 → 快捷指令 → 「开始仅听」", value = "快捷指令", leading = { RowGlyph("◎", RowIconColor.Orange) })
                    SceneDivider(inset = 57.dp)
                    SceneRow("磁贴 / 小组件", subtitle = "Android：下拉快捷设置 → 编辑 → 拖入「仅听」「速译一句」", value = "磁贴", leading = { SceneRowIcon(SceneIcons.Scenes, RowIconColor.Orange) })
                    SceneDivider(inset = 57.dp)
                    SceneRow("耳机按键（仅会话中）", subtitle = "单击暂停 / 继续 · 双击跳过 · 三击重播上一句", value = "会话中自动接管", leading = { SceneRowIcon(SceneIcons.Headphones, RowIconColor.Orange) })
                }
                SceneSectionFooter("入口绑定稍后开放。")
            }

            // ---- 诊断 ----
            Column {
                SceneSectionHeader("诊断")
                SceneGroup {
                    SceneRow("录音自检", value = "电平 · 路由 · 回放", chevron = true, leading = { SceneRowIcon(SceneIcons.Mic, RowIconColor.Gray) }, onClick = onOpenSelfTest)
                    SceneDivider(inset = 57.dp)
                    SceneRow("设计系统预览", value = "组件 · 玻璃", chevron = true, leading = { SceneRowIcon(SceneIcons.Sliders, RowIconColor.Gray) }, onClick = onOpenGallery)
                    SceneDivider(inset = 57.dp)
                    SceneRow("出门预热", value = "姿态 · 零 Key · 入口", chevron = true, leading = { SceneRowIcon(SceneIcons.Play, RowIconColor.Gray) }, onClick = onOpenOnboarding)
                }
                SceneSectionFooter("没有账号、没有服务器、不内置任何 Key。不提供 App 内外观开关，跟随系统深浅色；降低透明度 / 减少动态时玻璃回退为不透明。")
            }
        }

        if (overlayHost == null) CompositionLocalProvider(LocalGlassBackdrop provides null) {
            KeyWalletSheet(visible = wallet, ui = ui, vm = vm, onDismiss = { wallet = false })
        }
    }
}

/** 壳覆盖层里的 Key 钱包：自己收集 ui，开关状态由设置页持有。 */
@Composable
private fun BoxScope.KeyWalletOverlay(vm: SettingsViewModel, open: MutableState<Boolean>) {
    val ui by vm.ui.collectAsState()
    CompositionLocalProvider(LocalGlassBackdrop provides null) {
        KeyWalletSheet(visible = open.value, ui = ui, vm = vm, onDismiss = { open.value = false })
    }
}

/** 行内单选组（原型 SceneEdit 的「四选一打勾」样式）：点父行展开，选中项右侧打勾，不弹窗；Reduce Motion 时瞬开。 */
@Composable
private fun <T> InlineOptions(
    expanded: Boolean,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    note: ((T) -> String?)? = null,
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
                    label(o), subtitle = note?.invoke(o),
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
