package dev.scenenote.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import dev.scenenote.shared.resources.*
import dev.scenenote.core.i18n.stringResource

// ---------- 功能层：导航栏 / 标签栏 / 工具栏 dock（全部玻璃，内容从下面滚过）----------

/**
 * 透明导航栏 + 圆形玻璃按钮（iOS 26 样式）：左返回、中标题胶囊、右动作。
 * 只放当前视图的动作；Done / 主要动作用 prominent，其余单色（`toolbars.md`）。
 * 标题居中，但绝不压到两侧按钮：两侧先量，标题只能用中间剩下的宽度——居中放不下就往空的一侧挪，再放不下就截断加省略号。
 */
@Composable
fun SceneNavBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = stringResource(Res.string.common_back),
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = SceneSpacing.m)
            .height(SceneSize.navBar),
        content = {
            Row(Modifier.layoutId("leading"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) SceneIconButton(SceneIcons.ChevronLeft, contentDescription = backContentDescription, onClick = onBack, glass = true)
                if (leading != null) leading()
            }
            if (title != null) {
                Box(
                    Modifier
                        .layoutId("title")
                        .glass(CircleShape, elevation = 6.dp)
                        .height(36.dp)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    SceneText(title, style = SceneTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = SceneTheme.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) {
                Row(Modifier.layoutId("trailing"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
            }
        },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leadingP = measurables.firstOrNull { it.layoutId == "leading" }?.measure(loose)
        val trailingP = measurables.firstOrNull { it.layoutId == "trailing" }?.measure(loose)
        val width = constraints.maxWidth
        val lw = leadingP?.width ?: 0
        val tw = trailingP?.width ?: 0
        // 标题可用区：两侧按钮之外各留一个间隙
        val freeStart = if (lw > 0) lw + gap else 0
        val freeEnd = width - (if (tw > 0) tw + gap else 0)
        val free = (freeEnd - freeStart).coerceAtLeast(0)
        val titleP = measurables.firstOrNull { it.layoutId == "title" }?.takeIf { free > 0 }?.measure(loose.copy(maxWidth = free))
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else maxOf(leadingP?.height ?: 0, trailingP?.height ?: 0, titleP?.height ?: 0)
        layout(width, height) {
            leadingP?.placeRelative(0, (height - leadingP.height) / 2)
            trailingP?.placeRelative(width - tw, (height - trailingP.height) / 2)
            if (titleP != null) {
                val centered = (width - titleP.width) / 2
                val x = centered.coerceIn(freeStart, (freeEnd - titleP.width).coerceAtLeast(freeStart))
                titleP.placeRelative(x, (height - titleP.height) / 2)
            }
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
 * 切换动效：选中底是一枚胶囊，弹簧滑到新位置；图标 / 文字颜色渐变；新选中的图标轻弹一下。系统「减少动态效果」时全部瞬切。
 */
@Composable
fun SceneTabBar(
    tabs: List<SceneTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = SceneTheme.colors
    val motion = SceneTheme.motion
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 2.dp.toPx() }
    // 选中底的位置：以 tab 序号为单位做弹簧插值，画在所有 tab 后面。记住起点，路上按进度让胶囊先胀大再缩回。
    val indicator = remember { Animatable(selectedIndex.toFloat()) }
    var from by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        from = indicator.value   // 连点时从半路接着走
        if (motion.reduced) indicator.snapTo(selectedIndex.toFloat())
        else indicator.animateTo(selectedIndex.toFloat(), spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
    }
    val pill = if (c.isDark) c.label.copy(alpha = 0.14f) else c.systemBackground.copy(alpha = 0.8f)
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = SceneSpacing.page, end = SceneSpacing.page, bottom = 8.dp)
            .glass(CircleShape)
            .height(SceneSize.tabBar)
            .padding(6.dp)
            .drawBehind {
                if (tabs.isEmpty()) return@drawBehind
                val slot = (size.width - gapPx * (tabs.size - 1)) / tabs.size
                val pos = indicator.value
                // 行程进度 0 → 1：一起步就胀起来、路上保持、落位缩回（sin 开 0.6 次方让"胀大"阶段更长，不只在半路一闪）；到位或减少动态效果时为 0
                val total = selectedIndex - from
                val progress = if (abs(total) < 0.001f || motion.reduced) 1f else ((pos - from) / total).coerceIn(0f, 1f)
                // sin(π) 在浮点里是 −8.7e-8，负数开分数次方是 NaN → 胶囊整个不画；先夹到 0
                val grow = sin(PI.toFloat() * progress).coerceIn(0f, 1f).pow(0.5f)
                val w = slot * (1f + 0.50f * grow)
                val h = size.height * (1f + 0.3f * grow)   // 内区 54 dp → 最高 65.3 dp，几乎撑满 66 dp 的玻璃胶囊；再高会被外层 clip 切平
                val cx = pos * (slot + gapPx) + slot / 2f
                drawRoundRect(pill, topLeft = Offset(cx - w / 2f, (size.height - h) / 2f), size = Size(w, h), cornerRadius = CornerRadius(h / 2f))
            },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { i, tab ->
            val selected = i == selectedIndex
            val interaction = remember { MutableInteractionSource() }
            val fg by animateColorAsState(if (selected) c.tint else c.label, if (motion.reduced) snap() else tween(motion.normalMs), label = "tabColor")
            // 新选中时图标弹一下（1 → 1.12 → 1）
            val bounce = remember { Animatable(1f) }
            LaunchedEffect(selected) {
                if (selected && !motion.reduced) {
                    bounce.animateTo(1.12f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                    bounce.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
                } else bounce.snapTo(1f)
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .semantics { this.selected = selected }
                    .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                SceneIcon(tab.icon, contentDescription = null, size = 26.dp, tint = fg, modifier = Modifier.scale(bounce.value))
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
