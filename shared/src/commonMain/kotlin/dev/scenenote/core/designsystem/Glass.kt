package dev.scenenote.core.designsystem

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// ---------- Liquid Glass（07 篇 §7.14）：玻璃只出现在功能层，内容层绝不用 ----------
// 实现：内容层用 haze 标记为取样源（hazeSource），每个玻璃条用 hazeEffect 取样并模糊（26 dp）+ 叠 58% 白 / 72% 黑填充 + 2% 噪点，
// 上面再画 0.5 dp 描边与顶部高光线。平台差异：iOS / 桌面走 Skia RenderEffect，Android 12+ 走 RenderNode + RenderEffect；
// Android < 12 没有 RenderEffect（HazeDefaults.blurEnabled() = false），不挂 haze，直接画高填充（sheet 则不透明）。
// Reduce Transparency → 不走 haze，不透明回退；Increase Contrast → 加强描边与填充。

/** 内容层的取样源：由 [GlassScaffold] 创建、[glassBackdropSource] 挂到内容层、[glass] 读。一个页面一个。 */
@Stable
class GlassBackdrop internal constructor(internal val hazeState: HazeState)

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val state = rememberHazeState()
    return remember(state) { GlassBackdrop(state) }
}

/** 为 null 表示此处拿不到内容层（不在 [GlassScaffold] 里，或自己就在内容层中）：玻璃走高填充回退。 */
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** 挂在内容层根上：把这个节点及其子树标记为玻璃的取样源。 */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier = this.hazeSource(backdrop.hazeState)

enum class GlassStyle {
    /** 标准：浅色白玻璃 / 深色黑玻璃，用于 Tab 栏、导航按钮、dock。 */
    Regular,
    /** 视频画面之上的浮动控件：清透变体 + 约 35% 压暗。 */
    Clear,
}

/** 玻璃视觉参数，随无障碍偏好变化。[saturation] 仅作记录：haze 1.7 无饱和度参数，由填充 tint 近似。[blurEnabled] 为 false 时没有模糊可叠，只画填充。 */
data class GlassSpec(val fill: Color, val stroke: Color, val highlight: Color, val blurRadius: Dp, val saturation: Float, val blurEnabled: Boolean)

/** 平台能不能做背景模糊：iOS / 桌面恒可以，Android 需要 API 31+ 的 RenderEffect。 */
fun platformSupportsBackdropBlur(): Boolean = HazeDefaults.blurEnabled()

@Composable
fun rememberGlassSpec(style: GlassStyle = GlassStyle.Regular, backdropAvailable: Boolean = true): GlassSpec {
    val colors = SceneTheme.colors
    val a11y = SceneTheme.a11y
    return remember(colors, a11y, style, backdropAvailable) {
        val blurEnabled = backdropAvailable && !a11y.reduceTransparency && platformSupportsBackdropBlur()
        val baseFill = when (style) {
            GlassStyle.Regular -> colors.glassFill
            GlassStyle.Clear -> Color(0x47141416)
        }
        val fill = when {
            a11y.reduceTransparency -> when (style) { GlassStyle.Regular -> colors.secondarySystemBackground.copy(alpha = 0.98f); GlassStyle.Clear -> Color(0xEB141416) }
            !blurEnabled -> baseFill.boostedForFallback()
            a11y.increaseContrast -> baseFill.copy(alpha = (baseFill.alpha + 0.15f).coerceAtMost(1f))
            else -> baseFill
        }
        val stroke = if (a11y.increaseContrast) colors.glassStroke.copy(alpha = (colors.glassStroke.alpha * 2.5f).coerceAtMost(1f)) else colors.glassStroke
        val highlight = if (style == GlassStyle.Clear) Color(0x59FFFFFF) else colors.glassHighlight
        GlassSpec(fill = fill, stroke = stroke, highlight = highlight, blurRadius = 26.dp, saturation = 1.6f, blurEnabled = blurEnabled)
    }
}

/** 没有模糊可叠时的高填充：透明度 +34%，封顶 96%，视觉上仍是"材质"而不是纯色块。 */
private fun Color.boostedForFallback(): Color = copy(alpha = (alpha + 0.34f).coerceAtMost(0.96f))

/**
 * 把这个节点画成玻璃：背景模糊取样自 [backdrop]（默认取 [LocalGlassBackdrop]），叠填充 + 描边 + 高光 + 投影。
 * 不传 backdrop、系统开了「降低透明度」或平台不支持模糊（Android < 12）时不挂 haze，退化为高填充半透明面。
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    style: GlassStyle = GlassStyle.Regular,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
    elevation: Dp = 12.dp,
    fillOverride: Color? = null,
): Modifier {
    val spec = rememberGlassSpec(style, backdropAvailable = backdrop != null)
    val fill = fillOverride ?: spec.fill
    val shadowColor = if (SceneTheme.colors.isDark) Color(0xB3000000) else Color(0x33000000)
    val base = this
        .shadow(elevation = elevation, shape = shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)
        .clip(shape)
    val material = if (spec.blurEnabled && backdrop != null) {
        val hazeStyle = remember(fill, spec.blurRadius) {
            HazeStyle(
                backgroundColor = fill.copy(alpha = 1f),
                tints = listOf(HazeTint(fill)),
                blurRadius = spec.blurRadius,
                noiseFactor = 0.02f,
                fallbackTint = HazeTint(fill.boostedForFallback()),
            )
        }
        base.hazeEffect(backdrop.hazeState, style = hazeStyle)
    } else {
        base.drawBehind { drawRect(fill) }
    }
    return material
        // 顶部 0.5 dp 高光线：模拟玻璃边缘折射
        .drawBehind { drawLine(spec.highlight, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1f) }
        .border(0.5.dp, spec.stroke, shape)
}
