package dev.scenenote.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

data class MenuItem(val text: String, val onClick: () -> Unit, val icon: ImageVector? = null, val destructive: Boolean = false, val checked: Boolean = false)

/**
 * 菜单（`menus.md`）：多选一或一组相关动作；锚在触发控件下方。玻璃材质（弹层属于功能层）。
 * 选中项右侧打勾——不靠颜色单独传达状态。
 */
@Composable
fun SceneMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    items: List<MenuItem>,
    offset: DpOffset = DpOffset(0.dp, 8.dp),
) {
    if (!expanded) return
    val c = SceneTheme.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    val intOffset = with(density) { androidx.compose.ui.unit.IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
    Popup(alignment = Alignment.TopEnd, offset = intOffset, onDismissRequest = onDismissRequest, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .width(250.dp)
                .shadow(16.dp, RoundedCornerShape(SceneRadius.m), clip = false)
                .clip(RoundedCornerShape(SceneRadius.m))
                .background(c.secondarySystemBackground.copy(alpha = 0.97f)),
        ) {
            items.forEachIndexed { i, item ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                val interaction = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current) { item.onClick(); onDismissRequest() }
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SceneText(item.text, Modifier.weight(1f), style = SceneTheme.type.body, color = if (item.destructive) c.destructive else c.label, maxLines = 1)
                    if (item.checked) SceneIcon(SceneIcons.Check, contentDescription = "已选", size = 18.dp, tint = c.label)
                    if (item.icon != null) SceneIcon(item.icon, contentDescription = null, size = 20.dp, tint = if (item.destructive) c.destructive else c.label)
                }
            }
        }
    }
}
