package dev.scenenote.ui.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.AlertAction
import dev.scenenote.core.designsystem.CapsuleTone
import dev.scenenote.core.designsystem.SceneAlert
import dev.scenenote.core.designsystem.SceneCapsule
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneGroup
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneRadius
import dev.scenenote.core.designsystem.SceneRow
import dev.scenenote.core.designsystem.SceneSectionFooter
import dev.scenenote.core.designsystem.SceneSectionHeader
import dev.scenenote.core.designsystem.SceneSheet
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.settings.Providers
import dev.scenenote.translate.KeyTestResult

/** 消费闸门步进：每次 ± ¥5，0 = 不限。 */
private const val LIMIT_STEP = 5.0

/**
 * Key 钱包 sheet（原型 KeyWallet.dc.html，14 篇 §1 ④）：厂商列表 → API Key / 端点 / 测试连接 → 消费闸门 → 页脚。
 * 测试结果行内展示（青绿 ok / 红色错误），不弹窗；只有清除 Key 这类不可撤销动作才用 Alert。
 * 「完成」提交未保存的草稿再关闭，「取消」丢弃草稿。
 */
@Composable
fun BoxScope.KeyWalletSheet(visible: Boolean, ui: SettingsUiState, vm: SettingsViewModel, onDismiss: () -> Unit) {
    val c = SceneTheme.colors
    var selectedId by remember { mutableStateOf(ui.providers.firstOrNull()?.id.orEmpty()) }
    val p = ui.providers.firstOrNull { it.id == selectedId } ?: ui.providers.firstOrNull()
    var keyDraft by remember(selectedId) { mutableStateOf("") }
    var baseDraft by remember(selectedId, p?.baseUrl) { mutableStateOf(p?.baseUrl.orEmpty()) }
    var confirmClear by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current

    fun saveKey() {
        val prov = p ?: return
        val k = keyDraft.trim()
        if (k.isNotEmpty()) vm.setKey(prov.id, k)
        keyDraft = ""
        focus.clearFocus()
    }
    fun saveBaseUrl() {
        val prov = p ?: return
        if (baseDraft.trim() != prov.baseUrl.orEmpty()) vm.setBaseUrl(prov.id, baseDraft)
        focus.clearFocus()
    }
    fun paste() {
        val prov = p ?: return
        val t = clipboard.getText()?.text?.trim().orEmpty()
        if (t.isNotEmpty()) { vm.setKey(prov.id, t); keyDraft = "" }
    }

    SceneSheet(
        visible = visible,
        onDismiss = { keyDraft = ""; baseDraft = p?.baseUrl.orEmpty(); focus.clearFocus(); onDismiss() },
        title = "Key 钱包",
        onDone = { saveKey(); saveBaseUrl(); onDismiss() },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(top = 8.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
        ) {
            // ---- 厂商 ----
            Column {
                SceneSectionHeader("厂商 · 只用你自己的 Key 直连")
                SceneGroup {
                    ui.providers.forEachIndexed { i, row ->
                        if (i > 0) SceneDivider()
                        val on = row.id == p?.id
                        val testable = row.id == Providers.bailian.id
                        Box(Modifier.background(if (on) c.tintSoft else c.secondaryGroupedBackground)) {
                            SceneRow(
                                row.name,
                                value = if (row.masked != null) "已配置" else "未配置",
                                chevron = !on,
                                titleColor = if (on) c.onTintSoft else c.label,
                                trailing = { if (testable) SceneCapsule("翻译 · 润色", tone = CapsuleTone.Tint) },
                                onClick = { focus.clearFocus(); selectedId = row.id },
                            )
                        }
                    }
                }
                SceneSectionFooter("MVP 先只接百炼（qwen-mt / qwen-plus）；其余端点可先存 Key，I3 之后开放直连与测试。")
            }

            // ---- 选中厂商：Key / 端点 / 测试 ----
            if (p != null) {
                val host = Providers.byId(p.id)?.host.orEmpty()
                val canTest = p.id == Providers.bailian.id && p.masked != null && ui.testing == null
                val testing = ui.testing == p.id
                val result = ui.keyTest[p.id]
                Column {
                    SceneSectionHeader(p.name)
                    SceneGroup {
                        // API Key 行：密文输入 + 粘贴 / 保存
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = SceneSize.rowMinHeight).padding(horizontal = SceneSpacing.row, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SceneText("API Key", style = SceneTheme.type.subheadline, color = c.label)
                            InlineField(
                                value = keyDraft, onValueChange = { keyDraft = it },
                                placeholder = p.masked ?: "粘贴或输入 Key", secure = true,
                                modifier = Modifier.weight(1f), onDone = ::saveKey,
                            )
                            if (keyDraft.isBlank()) SmallPill("粘贴", onClick = ::paste) else SmallPill("保存", onClick = ::saveKey, tinted = true)
                        }
                        SceneDivider()
                        // 端点：需要 Base URL 的厂商必填，其余可选（留空 = 官方端点）
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = SceneSize.rowMinHeight).padding(horizontal = SceneSpacing.row, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SceneText(if (p.needsBaseUrl) "Base URL" else "端点", style = SceneTheme.type.subheadline, color = c.label)
                            InlineField(
                                value = baseDraft, onValueChange = { baseDraft = it },
                                placeholder = if (p.needsBaseUrl) "https://…/v1（必填）" else "$host（默认，可选填）",
                                modifier = Modifier.weight(1f), onDone = ::saveBaseUrl,
                            )
                        }
                        SceneDivider()
                        // 测试连接：只有百炼可测；testing 时转圈文字
                        TestRow(
                            enabled = canTest, testing = testing, tested = result != null,
                            disabledNote = when {
                                p.id != Providers.bailian.id -> "测试连接 · I3 只接百炼"
                                p.masked == null -> "测试连接 · 先填写 Key"
                                else -> null
                            },
                            onClick = { vm.testKey(p.id) },
                        )
                        if (p.masked != null) {
                            SceneDivider()
                            SceneRow("清除 Key", titleColor = c.destructive, onClick = { confirmClear = true })
                        }
                    }
                    TestResultFooter(result)
                }
            }

            // ---- 消费闸门 ----
            Column {
                SceneSectionHeader("消费闸门")
                SceneGroup {
                    SceneRow("每月上限", trailing = {
                        StepButton("−", contentDescription = "减少", enabled = ui.monthlyLimit > 0.0) { vm.setMonthlyLimit((ui.monthlyLimit - LIMIT_STEP).coerceAtLeast(0.0)) }
                        SceneText(fmtLimit(ui.monthlyLimit), Modifier.widthIn(min = 48.dp), style = SceneTheme.type.body, color = c.label, textAlign = TextAlign.Center, maxLines = 1)
                        StepButton("+", contentDescription = "增加") { vm.setMonthlyLimit(ui.monthlyLimit + LIMIT_STEP) }
                    })
                    SceneDivider()
                    SceneRow("超限后", value = "自动切回仅本机")
                }
                SceneSectionFooter("按厂商公开价换算，仅供参考；0 = 不限。超限不弹窗，会话里胶囊变为「离线 · 基础质量」。")
            }

            // ---- 页脚 ----
            Column(verticalArrangement = Arrangement.spacedBy(SceneSpacing.xs)) {
                SceneText(
                    "Key 只存在本机 Keychain / Keystore，不备份、不同步；App 不内置任何 Key，也没有我们的服务器。",
                    Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.page + SceneSpacing.row),
                    style = SceneTheme.type.footnote, color = c.secondaryLabel, textAlign = TextAlign.Center,
                )
                if (p != null && p.keyHint.isNotBlank()) {
                    SceneText(
                        "获取 ${p.name} Key：${p.keyHint}",
                        Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.page + SceneSpacing.row),
                        style = SceneTheme.type.footnote, color = c.secondaryLabel, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    if (confirmClear && p != null) {
        SceneAlert(
            title = "清除 ${p.name} 的 Key？",
            message = "会从本机 Keychain / Keystore 删除，云端翻译回到零 Key 状态。",
            actions = listOf(
                AlertAction("取消", onClick = { confirmClear = false }, isDefault = true),
                AlertAction("清除", onClick = { vm.setKey(p.id, ""); confirmClear = false }, destructive = true),
            ),
            onDismissRequest = { confirmClear = false },
        )
    }
}

/** 圆角灰底单行输入框（BasicTextField 自包一层，不用 material3）：等宽字、可密文、回车提交。 */
@Composable
private fun InlineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secure: Boolean = false,
    onDone: () -> Unit = {},
) {
    val c = SceneTheme.colors
    val mono = SceneTheme.type.subheadline.copy(fontFamily = FontFamily.Monospace)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.clip(RoundedCornerShape(SceneRadius.s)).background(c.fill).padding(horizontal = 12.dp, vertical = 9.dp),
        textStyle = mono.copy(color = c.label),
        singleLine = true,
        visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secure) KeyboardType.Password else KeyboardType.Uri, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        cursorBrush = SolidColor(c.tint),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) SceneText(placeholder, style = mono, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

/** 行内小胶囊按钮（粘贴 / 保存）：视觉 32 dp，触控区 ≥ 44 dp。 */
@Composable
private fun SmallPill(text: String, onClick: () -> Unit, tinted: Boolean = false, enabled: Boolean = true) {
    val c = SceneTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .defaultMinSize(minWidth = SceneSize.touchTarget, minHeight = SceneSize.touchTarget)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.background(if (tinted) c.tintSoft else c.fill, CircleShape).padding(horizontal = 12.dp, vertical = 7.dp)) {
            SceneText(text, style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = if (tinted) c.onTintSoft else c.label, maxLines = 1)
        }
    }
}

/** 步进圆钮（− / +）：视觉 32 dp 圆，触控区 44 dp。 */
@Composable
private fun StepButton(symbol: String, contentDescription: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = SceneTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(SceneSize.touchTarget)
            .clip(CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.fill), contentAlignment = Alignment.Center) {
            SceneText(symbol, style = SceneTheme.type.title3, color = c.label)
        }
    }
}

/** 「测试连接」整行按钮：青绿居中文字；testing 时转圈 + 灰字；不可测时灰字注明原因。 */
@Composable
private fun TestRow(enabled: Boolean, testing: Boolean, tested: Boolean, disabledNote: String?, onClick: () -> Unit) {
    val c = SceneTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled && !testing, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = SceneSize.rowMinHeight)
            .padding(horizontal = SceneSpacing.row, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            testing -> { Spinner(); SceneText("正在测试连接…", style = SceneTheme.type.headline, color = c.secondaryLabel) }
            disabledNote != null -> SceneText(disabledNote, style = SceneTheme.type.body, color = c.secondaryLabel)
            else -> SceneText(if (tested) "再次测试连接" else "测试连接", style = SceneTheme.type.headline, color = c.tint)
        }
    }
}

/** 测试结果页脚：未测 → 说明；ok → 青绿对勾一行（首字延迟 / 模型 / 译文 / 估价）；失败 → 红字原因。 */
@Composable
private fun TestResultFooter(r: KeyTestResult?) {
    val c = SceneTheme.colors
    when {
        r == null -> SceneSectionFooter("会发一条 10 字以内的测试翻译，计入去向账本。")
        r.ok -> Row(
            Modifier.padding(start = SceneSpacing.page + SceneSpacing.row, end = SceneSpacing.page, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top,
        ) {
            SceneIcon(SceneIcons.Check, contentDescription = "已连通", size = 14.dp, tint = c.onTintSoft, modifier = Modifier.padding(top = 2.dp))
            val cost = r.estCost?.let { " · 估 ${fmtCost(it)}" }.orEmpty()
            val sample = r.sample.takeIf { it.isNotBlank() }?.let { " · 「$it」" }.orEmpty()
            SceneText(
                "已连通 · 首字 ${fmtSeconds(r.latencyMs)} · ${r.model.ifBlank { "qwen-mt" }} 可用$sample$cost · Key 已存本机 Keychain / Keystore",
                style = SceneTheme.type.footnote, color = c.onTintSoft,
            )
        }
        else -> SceneSectionFooter("连接失败 · ${r.message}", color = c.destructive)
    }
}

/** 转圈：16 dp 青绿弧线；Reduce Motion 时静止。 */
@Composable
private fun Spinner(size: Dp = 16.dp) {
    val c = SceneTheme.colors
    val reduced = SceneTheme.motion.reduced
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    Canvas(Modifier.size(size)) {
        drawArc(c.tint, startAngle = if (reduced) 0f else angle, sweepAngle = 270f, useCenter = false, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun fmtLimit(v: Double): String = if (v <= 0.0) "不限" else "¥ ${fmtNumber(v)}"

/** 通用数字：整数不带小数，否则保留两位。 */
private fun fmtNumber(v: Double): String {
    val i = v.toLong()
    return if (v == i.toDouble()) i.toString() else "${(v * 100).toLong() / 100.0}"
}

private fun fmtSeconds(ms: Long): String = "${ms / 1000}.${((ms % 1000) / 10).toString().padStart(2, '0')} s"

private fun fmtCost(v: Double): String = if (v < 0.01) "< ¥ 0.01" else "¥ ${fmtNumber(v)}"
