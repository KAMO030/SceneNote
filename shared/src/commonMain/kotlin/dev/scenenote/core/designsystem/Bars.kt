package dev.scenenote.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ---------- 功能层：导航栏 / 标签栏 / 工具栏 dock（全部玻璃，内容从下面滚过）----------

/**
 * 透明导航栏 + 圆形玻璃按钮（iOS 26 样式）：左返回、中标题胶囊、右动作。
 * 只放当前视图的动作；Done / 主要动作用 prominent，其余单色（`toolbars.md`）。
 */
@Composable
fun SceneNavBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = "返回",
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = SceneSpacing.m)
            .height(SceneSize.navBar),
    ) {
        Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) SceneIconButton(SceneIcons.ChevronLeft, contentDescription = backContentDescription, onClick = onBack, glass = true)
            if (leading != null) leading()
        }
        if (title != null) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .glass(CircleShape, elevation = 6.dp)
                    .height(36.dp)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                SceneText(title, style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = SceneTheme.colors.label, maxLines = 1)
            }
        }
        if (trailing != null) {
            Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
}

/** 玻璃胶囊文字按钮（导航栏的「完成」「取消」「资料库」）。 */
@Composable
fun SceneGlassCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominentText: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = SceneTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .glass(CircleShape, elevation = 6.dp)
            .height(SceneSize.glassButton)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) SceneIcon(icon, contentDescription = null, size = 18.dp, tint = c.label)
        SceneText(text, style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = if (prominentText) c.tint else c.label, maxLines = 1)
    }
}

data class SceneTab(val label: String, val icon: ImageVector)

/**
 * 浮动胶囊标签栏（`tab-bars.md`：只导航不执行动作；不隐藏、不禁用；单词标签；填充符号）。
 * 预设四个：场景 / 实时 / 资料库 / 设置。iOS 26+ 由 UITabBarController 原生承载，这里是 Android / iOS 18 的自绘。
 */
@Composable
fun SceneTabBar(
    tabs: List<SceneTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = SceneTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = SceneSpacing.page, end = SceneSpacing.page, bottom = 8.dp)
            .glass(CircleShape)
            .height(SceneSize.tabBar)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, tab ->
            val selected = i == selectedIndex
            val interaction = remember { MutableInteractionSource() }
            val fg = if (selected) c.tint else c.label
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .then(if (selected) Modifier.background(if (c.isDark) c.label.copy(alpha = 0.14f) else c.systemBackground.copy(alpha = 0.8f)) else Modifier)
                    .semantics { this.selected = selected }
                    .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                SceneIcon(tab.icon, contentDescription = null, size = 26.dp, tint = fg)
                SceneText(tab.label, style = SceneTheme.type.caption2.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
            }
        }
    }
}

/**
 * 底部 dock：会话页主控件所在的玻璃胶囊，固定在拇指区（`designing-for-ios.md`）。
 * 里面放 1 个 prominent 主按钮 + 若干圆形次要按钮；黄标 / 破坏性按钮用对应语气。
 */
@Composable
fun SceneDock(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 88.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = SceneSpacing.page, end = SceneSpacing.page, bottom = 8.dp)
            .glass(CircleShape)
            .height(height)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
