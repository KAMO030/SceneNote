package dev.scenenote.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.SceneDivider
import dev.scenenote.core.designsystem.SceneIcon
import dev.scenenote.core.designsystem.SceneIcons
import dev.scenenote.core.designsystem.SceneRadius
import dev.scenenote.core.designsystem.SceneRow
import dev.scenenote.core.designsystem.SceneSize
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.designsystem.SceneTheme

/** 行内展开的多选一（设置 / 场景编辑共用）：不弹窗，选项行打勾。 */
@Composable
fun <T> InlineOptions(
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

/** 圆角灰底单行输入框（不用 material3）：回车提交。 */
@Composable
fun InlineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    val c = SceneTheme.colors
    val style = SceneTheme.type.body
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.clip(RoundedCornerShape(SceneRadius.s)).background(c.fill).padding(horizontal = 12.dp, vertical = 9.dp),
        textStyle = style.copy(color = c.label),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onNext = { onDone() }),
        cursorBrush = SolidColor(c.tint),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) SceneText(placeholder, style = style, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

/** 行内小胶囊按钮：视觉 32 dp，触控区 ≥ 44 dp。 */
@Composable
fun SmallPill(text: String, onClick: () -> Unit, tinted: Boolean = false, enabled: Boolean = true, destructive: Boolean = false) {
    val c = SceneTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val bg = when { destructive -> c.destructiveSoft; tinted -> c.tintSoft; else -> c.fill }
    val fg = when { destructive -> c.onDestructiveSoft; tinted -> c.onTintSoft; else -> c.label }
    Box(
        Modifier
            .defaultMinSize(minWidth = SceneSize.touchTarget, minHeight = SceneSize.touchTarget)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.background(bg, CircleShape).padding(horizontal = 12.dp, vertical = 7.dp)) {
            SceneText(text, style = SceneTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
        }
    }
}
