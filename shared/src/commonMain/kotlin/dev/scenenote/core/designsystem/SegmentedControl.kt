package dev.scenenote.core.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 分段控件（`segmented-controls.md`）：多选一用它或菜单，不用单选按钮组。
 * 选中的白色胶囊滑动过去；Reduce Motion 时瞬移。
 */
@Composable
fun SceneSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = SceneTheme.colors
    val motion = SceneTheme.motion
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(CircleShape)
            .background(c.fill)
            .padding(3.dp),
    ) {
        val count = options.size.coerceAtLeast(1)
        val segWidth = maxWidth / count
        val target = segWidth * selectedIndex.coerceIn(0, count - 1)
        val x by animateDpAsState(targetValue = target, animationSpec = tween(motion.normalMs), label = "segment")
        Box(
            Modifier
                .offset(x = x)
                .width(segWidth)
                .fillMaxHeight()
                .shadow(2.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(if (c.isDark) c.secondarySystemBackground.copy(alpha = 0.9f) else c.systemBackground),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .semantics { this.selected = selected }
                        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    SceneText(
                        label,
                        style = SceneTheme.type.footnote.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                        color = c.label,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
