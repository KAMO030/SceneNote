package dev.scenenote.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Apple 四档按钮（`buttons.md`）：plain 无底 / gray 灰底 / tinted 淡色底 / prominent 着色底。
 * 一个视图最多 1–2 个 prominent；黄标（外放）与破坏性动作有专门的语气。iOS 26 起默认胶囊形。
 */
enum class ButtonStyle { Plain, Gray, Tinted, Prominent, Warning, Destructive }

@Composable
fun SceneButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Gray,
    enabled: Boolean = true,
    height: Dp = SceneSize.buttonHeight,
    shape: Shape = CircleShape,
    contentPadding: Dp = 20.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val c = SceneTheme.colors
    val (bg, fg) = when (style) {
        ButtonStyle.Plain -> Color.Transparent to c.tint
        ButtonStyle.Gray -> c.fill to c.label
        ButtonStyle.Tinted -> c.tintSoft to c.onTintSoft
        ButtonStyle.Prominent -> c.tint to c.onTint
        ButtonStyle.Warning -> c.warningSoft to c.onWarningSoft
        ButtonStyle.Destructive -> c.destructiveSoft to c.onDestructiveSoft
    }
    val interaction = remember { MutableInteractionSource() }
    val prominentShadow = style == ButtonStyle.Prominent && enabled
    Row(
        modifier = modifier
            .then(if (prominentShadow) Modifier.shadow(6.dp, shape, clip = false, ambientColor = c.tint.copy(alpha = 0.3f), spotColor = c.tint.copy(alpha = 0.3f)) else Modifier)
            .clip(shape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .defaultMinSize(minHeight = height)
            .height(height)
            .padding(horizontal = contentPadding),
        horizontalArrangement = Arrangement.spacedBy(SceneSpacing.s, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProvideContentColor(fg, SceneTheme.type.headline) { content() }
    }
}

/** 文字按钮的便捷版。 */
@Composable
fun SceneButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Gray,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = SceneSize.buttonHeight,
) {
    SceneButton(onClick = onClick, modifier = modifier, style = style, enabled = enabled, height = height) {
        if (icon != null) SceneIcon(icon, contentDescription = null, size = 20.dp)
        SceneText(text, style = SceneTheme.type.headline)
    }
}

/**
 * 圆形图标按钮（44 pt）：导航栏 / dock 里的次要动作。`glass = true` 时是玻璃底（要在 [GlassScaffold] 内）。
 * 图标按钮必须给 [contentDescription]（VoiceOver / TalkBack）。
 */
@Composable
fun SceneIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = SceneSize.glassButton,
    style: ButtonStyle = ButtonStyle.Gray,
    glass: Boolean = false,
    enabled: Boolean = true,
) {
    val c = SceneTheme.colors
    val (bg, fg) = when (style) {
        ButtonStyle.Plain -> Color.Transparent to c.label
        ButtonStyle.Gray -> c.fill to c.label
        ButtonStyle.Tinted -> c.tintSoft to c.onTintSoft
        ButtonStyle.Prominent -> c.tint to c.onTint
        ButtonStyle.Warning -> c.warningSoft to c.onWarningSoft
        ButtonStyle.Destructive -> c.destructiveSoft to c.onDestructiveSoft
    }
    val interaction = remember { MutableInteractionSource() }
    val base = if (glass) modifier.glass(CircleShape, elevation = 8.dp) else modifier.clip(CircleShape).background(bg)
    Box(
        modifier = base
            .size(size)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        SceneIcon(icon, contentDescription = contentDescription, tint = if (glass) c.label else fg, size = 22.dp)
    }
}
