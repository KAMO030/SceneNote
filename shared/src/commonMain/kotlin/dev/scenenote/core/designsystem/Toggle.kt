package dev.scenenote.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 开关（`toggles.md`）：只用于二态；状态不只靠颜色——滑块位置本身就是状态，Increase Contrast 下轨道加描边。
 * 尺寸 51×31（iOS UISwitch），触控区外扩到 48 dp。
 */
@Composable
fun SceneToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = SceneTheme.colors
    val motion = SceneTheme.motion
    val track by animateColorAsState(if (checked) c.tint else c.fill, tween(motion.fastMs), label = "track")
    val knobX by animateDpAsState(if (checked) 20.dp else 0.dp, tween(motion.fastMs), label = "knob")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .toggleable(value = checked, interactionSource = interaction, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(width = 51.dp, height = 31.dp)
                .clip(CircleShape)
                .background(track)
                .then(if (SceneTheme.a11y.increaseContrast) Modifier.border(1.dp, c.label.copy(alpha = 0.5f), CircleShape) else Modifier),
        )
        Box(
            Modifier
                .padding(start = 2.dp)
                .offset(x = knobX)
                .size(27.dp)
                .shadow(3.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(androidx.compose.ui.graphics.Color.White),
        )
    }
}
