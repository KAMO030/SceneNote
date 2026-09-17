package dev.scenenote.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------- inset grouped 列表（设置、Key 钱包、去向账本）----------

/** 分组标题：13 pt 次级色，左对齐到行内距。 */
@Composable
fun SceneSectionHeader(text: String, modifier: Modifier = Modifier) {
    SceneText(text, modifier.padding(start = SceneSpacing.page + SceneSpacing.row, end = SceneSpacing.page, bottom = 6.dp), style = SceneTheme.type.footnote, color = SceneTheme.colors.secondaryLabel)
}

/** 分组页脚说明文字（HIG：用页脚解释，不用弹窗）。 */
@Composable
fun SceneSectionFooter(text: String, modifier: Modifier = Modifier, color: Color = SceneTheme.colors.secondaryLabel) {
    SceneText(text, modifier.padding(start = SceneSpacing.page + SceneSpacing.row, end = SceneSpacing.page, top = 6.dp), style = SceneTheme.type.footnote, color = color)
}

/** 一组圆角卡片容器；行之间用 [SceneDivider]。 */
@Composable
fun SceneGroup(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    background: Color = SceneTheme.colors.secondaryGroupedBackground,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .padding(horizontal = SceneSpacing.page)
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(background),
        content = content,
    )
}

/** 行内分隔线：0.5 dp，默认从行内距开始（有前导图标时传 [inset] = 57.dp）。 */
@Composable
fun SceneDivider(inset: Dp = SceneSpacing.row) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(0.5.dp).background(SceneTheme.colors.separator))
}

/**
 * 列表行：前导（图标 / 色块）+ 标题 / 副标题 + 尾随（值、胶囊、开关、chevron）。
 * 最小高度 46 dp；点击态用系统 ripple；`onClick` 为空时不可点。
 */
@Composable
fun SceneRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    value: String? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    titleColor: Color = SceneTheme.colors.label,
) {
    val c = SceneTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = SceneSize.rowMinHeight)
            .padding(horizontal = SceneSpacing.row, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SceneText(title, style = SceneTheme.type.body, color = titleColor)
            if (subtitle != null) SceneText(subtitle, style = SceneTheme.type.footnote, color = c.secondaryLabel)
        }
        if (value != null) SceneText(value, style = SceneTheme.type.body, color = c.secondaryLabel, maxLines = 1)
        if (trailing != null) trailing()
        if (chevron) SceneIcon(SceneIcons.ChevronRight, contentDescription = null, size = 16.dp, tint = c.tertiaryLabel)
    }
}

/** 设置行前导的彩色圆角方块图标（29 pt，7 pt 圆角，白色符号）。 */
@Composable
fun SceneRowIcon(icon: ImageVector, background: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(29.dp).clip(RoundedCornerShape(7.dp)).background(background), contentAlignment = Alignment.Center) {
        SceneIcon(icon, contentDescription = null, size = 18.dp, tint = Color.White)
    }
}

/** 内容层卡片（场景卡、对话卡片）：22 dp 圆角、18 dp 内距，不透明。 */
@Composable
fun SceneCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    background: Color = SceneTheme.colors.secondaryGroupedBackground,
    radius: Dp = 22.dp,
    outline: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .padding(horizontal = SceneSpacing.page)
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .then(if (outline != null) Modifier.border(1.5.dp, outline, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick) else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun SceneVerticalSpace(height: Dp = SceneSpacing.l) { Spacer(Modifier.height(height)) }
