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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 弹窗按钮：破坏性动作红字；默认动作加粗。 */
data class AlertAction(val text: String, val onClick: () -> Unit, val destructive: Boolean = false, val isDefault: Boolean = false)

/**
 * Alert（`alerts.md`）：罕见、不在启动时出现、常见可撤销动作不弹窗；破坏性动作必须配 Cancel。
 * 场记里只有"删除场景卡""清空 Key"这类不可撤销动作才用它，其余反馈走胶囊 / 行内提示 / 震动。
 */
@Composable
fun SceneAlert(
    title: String,
    message: String? = null,
    actions: List<AlertAction>,
    onDismissRequest: () -> Unit,
) {
    val c = SceneTheme.colors
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(270.dp)
                .clip(RoundedCornerShape(SceneRadius.m))
                .background(c.secondarySystemBackground.copy(alpha = 0.97f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SceneText(title, style = SceneTheme.type.headline, color = c.label, textAlign = TextAlign.Center)
                if (message != null) SceneText(message, style = SceneTheme.type.footnote, color = c.label, textAlign = TextAlign.Center)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            if (actions.size == 2) {
                Row(Modifier.fillMaxWidth().height(44.dp)) {
                    AlertButton(actions[0], Modifier.weight(1f))
                    Box(Modifier.width(0.5.dp).height(44.dp).background(c.separator))
                    AlertButton(actions[1], Modifier.weight(1f))
                }
            } else {
                actions.forEachIndexed { i, a ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                    AlertButton(a, Modifier.fillMaxWidth().height(44.dp))
                }
            }
        }
    }
}

@Composable
private fun AlertButton(action: AlertAction, modifier: Modifier) {
    val c = SceneTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.height(44.dp).clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = action.onClick),
        contentAlignment = Alignment.Center,
    ) {
        SceneText(
            action.text,
            style = SceneTheme.type.body.copy(fontWeight = if (action.isDefault) FontWeight.SemiBold else FontWeight.Normal),
            color = if (action.destructive) c.destructive else c.tint,
        )
    }
}
