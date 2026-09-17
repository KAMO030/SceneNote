package dev.scenenote.core.designsystem

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ---------- Liquid Glass（07 篇 §7.14）：玻璃只出现在功能层，内容层绝不用 ----------
// 实现：内容层先录进一个 GraphicsLayer；每个玻璃条再把这个 layer 平移到自己坐标系、套 BlurEffect + 饱和度矩阵重画一遍，
// 上面盖 60–80% 填充、0.5 dp 描边、顶部高光线。iOS 26+ 的标签栏 / 导航栏 / sheet 由原生宿主承载拿真 Liquid Glass，
// 这里的自绘只服务 Android 与 iOS 18（待验证 46 / 48）。Reduce Transparency → 不透明回退；Increase Contrast → 加强描边与填充。

/** 内容层的录制结果：由 [glassBackdropSource] 写、[glass] 读。一个页面一个（[GlassScaffold] 会自动创建）。 */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var originInRoot by mutableStateOf(Offset.Zero)
    /** 内容每次重绘 +1，玻璃条订阅它以便在滚动时跟着重绘。 */
    internal var tick by mutableIntStateOf(0)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** 挂在内容层根上：把内容录进 layer 再原样画出，玻璃条据此取样。 */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.originInRoot = it.positionInRoot() }
    .drawWithContent {
        val layer = backdrop.layer
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
        // 不订阅自己写的状态，否则会自激重绘
        Snapshot.withoutReadObservation { backdrop.tick = backdrop.tick + 1 }
    }

enum class GlassStyle {
    /** 标准：浅色白玻璃 / 深色黑玻璃，用于 Tab 栏、导航按钮、dock。 */
    Regular,
    /** 视频画面之上的浮动控件：清透变体 + 约 35% 压暗。 */
    Clear,
}

/** 玻璃视觉参数，随无障碍偏好变化。 */
data class GlassSpec(val fill: Color, val stroke: Color, val highlight: Color, val blurRadius: Dp, val saturation: Float, val blurEnabled: Boolean)

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
            !blurEnabled -> baseFill.copy(alpha = (baseFill.alpha + 0.34f).coerceAtMost(0.96f))
            a11y.increaseContrast -> baseFill.copy(alpha = (baseFill.alpha + 0.15f).coerceAtMost(1f))
            else -> baseFill
        }
        val stroke = if (a11y.increaseContrast) colors.glassStroke.copy(alpha = (colors.glassStroke.alpha * 2.5f).coerceAtMost(1f)) else colors.glassStroke
        val highlight = if (style == GlassStyle.Clear) Color(0x59FFFFFF) else colors.glassHighlight
        GlassSpec(fill = fill, stroke = stroke, highlight = highlight, blurRadius = 24.dp, saturation = 1.6f, blurEnabled = blurEnabled)
    }
}

/**
 * 把这个节点画成玻璃：背景模糊取样自 [backdrop]（默认取 [LocalGlassBackdrop]），叠填充 + 描边 + 高光 + 投影。
 * 不传 backdrop（或平台不支持）时退化为高填充半透明面，视觉上仍是"材质"而不是纯色块。
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
    val blurLayer = if (spec.blurEnabled) rememberGraphicsLayer() else null
    val density = LocalDensity.current
    val blurPx = remember(spec.blurRadius, density) { with(density) { spec.blurRadius.toPx() } }
    val effect = remember(blurPx) { BlurEffect(blurPx, blurPx, TileMode.Clamp) }
    val saturate = remember(spec.saturation) { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(spec.saturation) }) }
    var positionInRoot by remember { mutableStateOf(Offset.Zero) }
    val shadowColor = if (SceneTheme.colors.isDark) Color(0xB3000000) else Color(0x33000000)
    return this
        .onGloballyPositioned { positionInRoot = it.positionInRoot() }
        .shadow(elevation = elevation, shape = shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)
        .clip(shape)
        .drawBehind {
            if (blurLayer != null && backdrop != null) {
                @Suppress("UNUSED_VARIABLE") val subscribe = backdrop.tick
                blurLayer.renderEffect = effect
                blurLayer.colorFilter = saturate
                blurLayer.record(size = IntSize(size.width.roundToInt(), size.height.roundToInt())) {
                    val dx = backdrop.originInRoot.x - positionInRoot.x
                    val dy = backdrop.originInRoot.y - positionInRoot.y
                    translate(dx, dy) { drawLayer(backdrop.layer) }
                }
                drawLayer(blurLayer)
            }
            drawRect(fillOverride ?: spec.fill)
            // 顶部 0.5 dp 高光线：模拟玻璃边缘折射
            drawLine(spec.highlight, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1f)
        }
        .border(0.5.dp, spec.stroke, shape)
}
