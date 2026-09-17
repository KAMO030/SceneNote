package dev.scenenote.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 状态胶囊：档位（混合 / 离线 · 基础质量）、方向、平台标记、v1.1 标记。
 * 07 篇 §7.14："不靠颜色单独传达状态"——胶囊同时用文字（和可选图标），颜色只是语气。
 */
enum class CapsuleTone { Tint, Gray, Warning, Destructive }

@Composable
fun SceneCapsule(
    text: String,
    modifier: Modifier = Modifier,
    tone: CapsuleTone = CapsuleTone.Gray,
    icon: ImageVector? = null,
) {
    val c = SceneTheme.colors
    val (bg, fg) = when (tone) {
        CapsuleTone.Tint -> c.tintSoft to c.onTintSoft
        CapsuleTone.Gray -> c.fill to c.label
        CapsuleTone.Warning -> c.warningSoft to c.onWarningSoft
        CapsuleTone.Destructive -> c.destructiveSoft to c.onDestructiveSoft
    }
    Row(
        modifier = modifier.background(bg, CircleShape).padding(horizontal = 9.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) SceneIcon(icon, contentDescription = null, size = 12.dp, tint = fg)
        SceneText(text, style = SceneTheme.type.caption1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}
