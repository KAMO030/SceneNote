package dev.scenenote.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.AlertAction
import dev.scenenote.core.designsystem.ButtonStyle
import dev.scenenote.core.designsystem.CapsuleTone
import dev.scenenote.core.designsystem.GlassScaffold
import dev.scenenote.core.designsystem.MenuItem
import dev.scenenote.core.designsystem.SceneAlert
import dev.scenenote.core.designsystem.SceneButton
import dev.scenenote.core.designsystem.SceneCapsule
import dev.scenenote.core.designsystem.SceneCard
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneDock
import dev.scenenote.core.designsystem.SceneGlassCapsuleButton
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIconButton
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneMenu
import dev.scenenote.core.designsystem.SceneNavBar
import dev.scenenote.core.designsystem.SceneRow
import dev.scenenote.core.designsystem.SceneRowIcon
import dev.scenenote.core.designsystem.SceneSectionFooter
import dev.scenenote.core.designsystem.SceneSectionHeader
import dev.scenenote.core.designsystem.SceneSegmentedControl
import dev.scenenote.core.designsystem.SceneSheet
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneTab
import dev.scenenote.core.designsystem.SceneTabBar
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.designsystem.SceneToggle
import dev.scenenote.core.designsystem.SceneVerticalSpace

/**
 * 设计系统预览页（`scenenote://gallery`）：把 core/design-system 的每个组件在真机上摆一遍，
 * 用于 07 篇 §7.14 的验收矩阵（深色 × Dynamic Type 最大字号 × 提高对比度 × 降低透明度 × 减少动态）。
 * 内容故意做长，好让它从玻璃 Tab 栏 / dock 下面滚过去。
 */
@Composable
fun DesignSystemGallery(onBack: () -> Unit) {
    val c = SceneTheme.colors
    var tab by remember { mutableIntStateOf(0) }
    var seg by remember { mutableIntStateOf(1) }
    var toggle by remember { mutableStateOf(true) }
    var toggle2 by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dockMode by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        GlassScaffold(
            topBar = {
                SceneNavBar(
                    title = "设计系统",
                    onBack = onBack,
                    trailing = {
                        Box {
                            SceneIconButton(SceneIcons.Ellipsis, contentDescription = "更多", onClick = { menu = true }, glass = true)
                            SceneMenu(
                                expanded = menu, onDismissRequest = { menu = false },
                                items = listOf(
                                    MenuItem("显示 dock 而非 Tab 栏", onClick = { dockMode = true }, checked = dockMode),
                                    MenuItem("显示 Tab 栏", onClick = { dockMode = false }, checked = !dockMode),
                                    MenuItem("弹出 Alert", onClick = { alert = true }),
                                    MenuItem("删除示例", onClick = { alert = true }, icon = SceneIcons.Close, destructive = true),
                                ),
                            )
                        }
                    },
                )
            },
            bottomBar = {
                if (dockMode) {
                    SceneDock {
                        SceneIconButton(SceneIcons.Mic, contentDescription = "速译一句", onClick = {}, size = 56.dp)
                        SceneButton(onClick = {}, style = ButtonStyle.Prominent, height = 72.dp, modifier = Modifier.size(72.dp), contentPadding = 0.dp) {
                            SceneIcon(SceneIcons.Pause, contentDescription = "暂停", size = 26.dp)
                        }
                        SceneIconButton(SceneIcons.Stop, contentDescription = "结束", onClick = {}, size = 56.dp, style = ButtonStyle.Destructive)
                    }
                } else {
                    SceneTabBar(
                        tabs = listOf(
                            SceneTab("场景", SceneIcons.Scenes), SceneTab("实时", SceneIcons.Waveform),
                            SceneTab("资料库", SceneIcons.Library), SceneTab("设置", SceneIcons.Sliders),
                        ),
                        selectedIndex = tab, onSelect = { tab = it },
                    )
                }
            },
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 104.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
            ) {
                Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SceneText("场记", style = SceneTheme.type.largeTitle)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SceneCapsule("混合档 · 端侧识别 + 云翻译", tone = CapsuleTone.Tint)
                        SceneCapsule("零 Key 也能用")
                    }
                }

                Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SceneText("排版刻度", style = SceneTheme.type.title3)
                    SceneText("Title 1 · 28", style = SceneTheme.type.title1)
                    SceneText("Title 2 · 22", style = SceneTheme.type.title2)
                    SceneText("Headline · 17 semibold", style = SceneTheme.type.headline)
                    SceneText("Body · 17 正文，最小 11", style = SceneTheme.type.body)
                    SceneText("Subheadline · 15", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
                    SceneText("Footnote · 13", style = SceneTheme.type.footnote, color = c.secondaryLabel)
                    SceneText("Caption 2 · 11", style = SceneTheme.type.caption2, color = c.secondaryLabel)
                }

                Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SceneText("分段控件 · 屏外 / 屏内", style = SceneTheme.type.title3)
                    SceneSegmentedControl(listOf("离线", "混合", "在线", "一体化"), seg, { seg = it })
                }

                SceneCard(outline = c.tint) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SceneText("面对面对话", style = SceneTheme.type.title2)
                        SceneCapsule("旗舰 · 置顶", tone = CapsuleTone.Tint)
                    }
                    SceneText("戴上耳机就能听；掏出手机对方看半屏", style = SceneTheme.type.subheadline, color = c.secondaryLabel)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SceneButton("开始仅听", onClick = {}, style = ButtonStyle.Prominent, modifier = Modifier.weight(1f))
                        SceneButton("速译一句", onClick = {}, style = ButtonStyle.Gray)
                    }
                }

                Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SceneText("按钮四档 + 黄标 / 破坏性", style = SceneTheme.type.title3)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SceneButton("Plain", onClick = {}, style = ButtonStyle.Plain)
                        SceneButton("Gray", onClick = {}, style = ButtonStyle.Gray)
                        SceneButton("Tinted", onClick = {}, style = ButtonStyle.Tinted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SceneButton("Prominent", onClick = {}, style = ButtonStyle.Prominent, icon = SceneIcons.Share)
                        SceneButton("外放 · 黄标", onClick = {}, style = ButtonStyle.Warning, icon = SceneIcons.Speaker)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SceneButton("删除这张卡", onClick = { alert = true }, style = ButtonStyle.Destructive)
                        SceneButton("禁用", onClick = {}, enabled = false)
                        SceneGlassCapsuleButton("完成", onClick = {}, prominentText = true)
                    }
                }

                Column {
                    SceneSectionHeader("Inset grouped 列表")
                    SceneGroup {
                        SceneRow("Key 钱包", value = "百炼 · 已配置", chevron = true, leading = { SceneRowIcon(SceneIcons.Key, c.tint) }, onClick = { sheet = true })
                        SceneDivider(inset = 57.dp)
                        SceneRow("默认隐私档", subtitle = "录音不出手机，只有文字上云", value = "仅文本上云", chevron = true, leading = { SceneRowIcon(SceneIcons.Lock, Color(0xFF8E8E93)) }, onClick = {})
                        SceneDivider(inset = 57.dp)
                        SceneRow("Apple Translation", subtitle = "v1.1 可选插件，默认关", leading = { SceneRowIcon(SceneIcons.Sparkle, Color(0xFFAF52DE)) }, trailing = { SceneToggle(toggle, { toggle = it }) })
                        SceneDivider(inset = 57.dp)
                        SceneRow("姿态自动进 M1", leading = { SceneRowIcon(SceneIcons.Rotate, Color(0xFFFF9500)) }, trailing = { SceneToggle(toggle2, { toggle2 = it }) })
                        SceneDivider(inset = 57.dp)
                        SceneRow("粤语包", leading = { SceneRowIcon(SceneIcons.Waveform, Color(0xFF2E9E4F)) }, trailing = {
                            SceneButton("下载 · 120 MB", onClick = {}, style = ButtonStyle.Tinted, height = 32.dp)
                        })
                    }
                    SceneSectionFooter("四档只约束互联网出站；任何出站都记入去向账本。")
                }

                Column(Modifier.padding(horizontal = SceneSpacing.page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SceneText("胶囊 · 不靠颜色单独传达状态", style = SceneTheme.type.title3)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SceneCapsule("混合档 · 正常", tone = CapsuleTone.Tint, icon = SceneIcons.Check)
                        SceneCapsule("离线 · 基础质量")
                        SceneCapsule("外放 · 黄标", tone = CapsuleTone.Warning, icon = SceneIcons.Speaker)
                        SceneCapsule("未译", tone = CapsuleTone.Destructive)
                    }
                }

                // 对方半屏示例：内容层，不透明，≥ 28 pt
                Column(
                    Modifier.padding(horizontal = SceneSpacing.page).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                        .background(if (c.isDark) c.secondarySystemBackground else Color(0xFFF8F8FA)).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(c.tint))
                        SceneText("Listening…", style = SceneTheme.type.subheadline, color = c.onTintSoft)
                    }
                    SceneText("Where is the nearest subway station?", style = SceneTheme.type.facingScreen)
                    SceneText("对方半屏 · 旋转 180° · 最亮不锁屏", style = SceneTheme.type.footnote, color = c.secondaryLabel)
                }

                // 填充高度，确保有内容滚到玻璃下面
                repeat(6) { i ->
                    SceneGroup {
                        SceneRow("滚动内容 ${i + 1}", subtitle = "从玻璃 Tab 栏 / dock 下面滚过去时会被模糊 + 提饱和", value = "${(i + 1) * 12} KB")
                    }
                }
                Spacer(Modifier.height(SceneSpacing.l))
            }
        }

        SceneSheet(visible = sheet, onDismiss = { sheet = false }, title = "Key 钱包", onDone = { sheet = false }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(SceneSpacing.l)) {
                SceneSectionHeader("厂商 · 只用你自己的 Key 直连")
                SceneGroup {
                    SceneRow("阿里云百炼", value = "已配置", trailing = { SceneCapsule("翻译 · 润色", tone = CapsuleTone.Tint) })
                    SceneDivider()
                    SceneRow("Anthropic", value = "未配置", chevron = true, onClick = {})
                    SceneDivider()
                    SceneRow("OpenAI 兼容端点", value = "未配置", chevron = true, onClick = {})
                }
                SceneSectionFooter("Key 只存在本机 Keychain / Keystore，不备份、不同步；App 不内置任何 Key。")
                SceneVerticalSpace()
            }
        }
    }

    if (alert) {
        SceneAlert(
            title = "删除这张场景卡？",
            message = "会进入 30 天回收站，可撤销。",
            actions = listOf(AlertAction("取消", onClick = { alert = false }, isDefault = true), AlertAction("删除", onClick = { alert = false }, destructive = true)),
            onDismissRequest = { alert = false },
        )
    }
}
