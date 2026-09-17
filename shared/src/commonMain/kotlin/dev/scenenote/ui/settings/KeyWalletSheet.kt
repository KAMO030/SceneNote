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
import dev.scenenote.core.designsystem.SceneAlert
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

/** 每月上限步进：每次 ± ¥5，0 = 不限。 */
private const val LIMIT_STEP = 5.0

/** 空 Key 时输入框上方的三步引导（≤ 3 行）。 */
private val KEY_STEPS = listOf("1  打开 bailian.console.aliyun.com", "2  新建 API Key", "3  复制粘贴到这里")

/**
 * 翻译 Key sheet：厂商一行（只接百炼）→ API Key / 端点 / 测试连接 → 每月上限 → 页脚。
 * 测试结果行内展示（青绿 ok / 红色错误），不弹窗；只有清除 Key 这类不可撤销动作才用 Alert。
 * 「完成」提交未保存的草稿再关闭，「取消」丢弃草稿。
 */
@Composable
fun BoxScope.KeyWalletSheet(visible: Boolean, ui: SettingsUiState, vm: SettingsViewModel, onDismiss: () -> Unit) {
    val c = SceneTheme.colors
    // 只显示百炼一行：其他厂商还没接，直接不渲染
    val p = ui.providers.firstOrNull { it.id == Providers.bailian.id }
    var keyDraft by remember { mutableStateOf("") }
    var baseDraft by remember(p?.baseUrl) { mutableStateOf(p?.baseUrl.orEmpty()) }
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
        title = "翻译 Key",
        onDone = { saveKey(); saveBaseUrl(); onDismiss() },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(top = 8.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(SceneSpacing.l),
        ) {
            // ---- 厂商（只有百炼）----
            if (p != null) {
                Column {
                    SceneSectionHeader("厂商")
                    SceneGroup {
                        SceneRow(p.name, value = if (p.masked != null) "已填写" else "未填写")
                    }
                }

                // ---- Key / 端点 / 测试 ----
                val testing = ui.testing == p.id
                val result = ui.keyTest[p.id]
                Column {
                    SceneSectionHeader("API Key")
                    SceneGroup {
                        // 空 Key：输入框上方三步引导
                        if (p.masked == null) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.row, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                KEY_STEPS.forEach { SceneText(it, style = SceneTheme.type.footnote, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            SceneDivider()
                        }
                        // API Key 行：密文输入 + 粘贴 / 保存
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = SceneSize.rowMinHeight).padding(horizontal = SceneSpacing.row, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SceneText("Key", style = SceneTheme.type.subheadline, color = c.label)
                            InlineField(
                                value = keyDraft, onValueChange = { keyDraft = it },
                                placeholder = p.masked ?: "粘贴或输入", secure = true,
                                modifier = Modifier.weight(1f), onDone = ::saveKey,
                            )
                            if (keyDraft.isBlank()) SmallPill("粘贴", onClick = ::paste) else SmallPill("保存", onClick = ::saveKey, tinted = true)
                        }
                        SceneDivider()
                        // 端点：留空 = 官方端点
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = SceneSize.rowMinHeight).padding(horizontal = SceneSpacing.row, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SceneText("端点", style = SceneTheme.type.subheadline, color = c.label)
                            InlineField(
                                value = baseDraft, onValueChange = { baseDraft = it },
                                placeholder = "默认，可不填",
                                modifier = Modifier.weight(1f), onDone = ::saveBaseUrl,
                            )
                        }
                        // 测试连接 / 清除 Key：只有填了 Key 才渲染
                        if (p.masked != null) {
                            SceneDivider()
                            TestRow(enabled = ui.testing == null, testing = testing, tested = result != null, onClick = { vm.testKey(p.id) })
                            SceneDivider()
                            SceneRow("清除 Key", titleColor = c.destructive, onClick = { confirmClear = true })
                        }
                    }
                    TestResultFooter(result)
                }
            }

            // ---- 每月上限 ----
            Column {
                SceneSectionHeader("每月上限")
                SceneGroup {
                    SceneRow("金额", trailing = {
                        StepButton("−", contentDescription = "减少", enabled = ui.monthlyLimit > 0.0) { vm.setMonthlyLimit((ui.monthlyLimit - LIMIT_STEP).coerceAtLeast(0.0)) }
                        SceneText(fmtLimit(ui.monthlyLimit), Modifier.widthIn(min = 48.dp), style = SceneTheme.type.body, color = c.label, textAlign = TextAlign.Center, maxLines = 1)
                        StepButton("+", contentDescription = "增加") { vm.setMonthlyLimit(ui.monthlyLimit + LIMIT_STEP) }
                    })
                    SceneDivider()
                    SceneRow("超限后", value = "停止联网翻译")
                }
            }

            // ---- 页脚 ----
            SceneText(
                "Key 只存在本机，不上传",
                Modifier.fillMaxWidth().padding(horizontal = SceneSpacing.page + SceneSpacing.row),
                style = SceneTheme.type.footnote, color = c.secondaryLabel, textAlign = TextAlign.Center,
            )
        }
    }

    if (confirmClear && p != null) {
        SceneAlert(
            title = "清除 Key？",
            message = "清除后将无法联网翻译。",
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

/** 「测试连接」整行按钮：青绿居中文字；testing 时转圈 + 灰字。 */
@Composable
private fun TestRow(enabled: Boolean, testing: Boolean, tested: Boolean, onClick: () -> Unit) {
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
        if (testing) {
            Spinner()
            SceneText("正在连接…", style = SceneTheme.type.headline, color = c.secondaryLabel)
        } else {
            SceneText(if (tested) "再测一次" else "测试连接", style = SceneTheme.type.headline, color = c.tint)
        }
    }
}

/** 测试结果页脚：未测 → 无；ok → 「已连接 · 0.4 s」；失败 → 红字一句。 */
@Composable
private fun TestResultFooter(r: KeyTestResult?) {
    val c = SceneTheme.colors
    when {
        r == null -> Unit
        r.ok -> Row(
            Modifier.padding(start = SceneSpacing.page + SceneSpacing.row, end = SceneSpacing.page, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            SceneIcon(SceneIcons.Check, contentDescription = "已连接", size = 14.dp, tint = c.onTintSoft)
            SceneText("已连接 · ${fmtSeconds(r.latencyMs)}", style = SceneTheme.type.footnote, color = c.onTintSoft, maxLines = 1)
        }
        else -> SceneSectionFooter("连接失败 · 检查 Key 或网络", color = c.destructive)   // 原始原因只进诊断
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

/** 毫秒 → 一位小数的秒（「0.4 s」）。 */
private fun fmtSeconds(ms: Long): String = "${ms / 1000}.${(ms % 1000) / 100} s"
