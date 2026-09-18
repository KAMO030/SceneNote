package dev.scenenote.ui.brand

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

/**
 * 记记酱本人。形状来自 [JijiArt]，和两端桌面图标、docs/品牌/ 下的 SVG 同一份 path。
 *
 * 全程一个 [Canvas] 画完，只用 save / restore 级的变换：iOS 上自绘里再开图层会踩 Skiko 的坑
 * （见 docs/附录B 待验证项），所以这里不用 graphicsLayer，透明度直接进 paint。
 */
@Composable
fun JijiLogo(
    modifier: Modifier = Modifier,
    /** 0 睁眼、1 闭眼。 */
    blink: Float = 0f,
    /** 猫耳外摆角度，两只对称。 */
    earTilt: Float = 0f,
    /** 呆毛摆动角度。 */
    ahogeSwing: Float = 0f,
    scale: Float = 1f,
    alpha: Float = 1f,
) {
    Canvas(modifier) {
        if (alpha <= 0f || scale <= 0f) return@Canvas
        val k = size.minDimension / JijiArt.VIEWPORT * scale
        val dx = (size.width - JijiArt.VIEWPORT * k) / 2f
        val dy = (size.height - JijiArt.VIEWPORT * k) / 2f
        val lid = 1f - 0.94f * blink.coerceIn(0f, 1f)
        withTransform({
            translate(dx, dy)
            scale(k, k, pivot = Offset.Zero)
        }) {
            READY.forEach { s ->
                when (s.part) {
                    "earL" -> withTransform({ rotate(-earTilt, EAR_L) }) { paint(s, alpha) }
                    "earR" -> withTransform({ rotate(earTilt, EAR_R) }) { paint(s, alpha) }
                    "eyeL", "lashL" -> withTransform({ scale(1f, lid, EYE_L) }) { paint(s, alpha) }
                    "eyeR", "lashR" -> withTransform({ scale(1f, lid, EYE_R) }) { paint(s, alpha) }
                    "ahoge" -> withTransform({ rotate(ahogeSwing, AHOGE_ROOT) }) { paint(s, alpha) }
                    else -> paint(s, alpha)
                }
            }
        }
    }
}

// 动效支点，都在 512 坐标系里：耳根、两只眼心、呆毛根
private val EAR_L = Offset(176f, 204f)
private val EAR_R = Offset(336f, 204f)
private val EYE_L = Offset(195f, 320f)
private val EYE_R = Offset(317f, 320f)
private val AHOGE_ROOT = Offset(262f, 80f)

/** 解析好的一条形状：path 与 brush 都在 512 坐标系里，进程内解析一次。 */
private class Ready(
    val part: String,
    val path: Path,
    val fill: Brush?,
    val stroke: Brush?,
    val strokeWidth: Float,
    val alpha: Float,
)

private fun brushOf(paint: JijiPaint): Brush = when (paint) {
    is JijiPaint.Solid -> SolidColor(Color(paint.argb))
    is JijiPaint.Grad -> {
        val g = JijiArt.gradients.getValue(paint.id)
        Brush.linearGradient(
            colorStops = g.stops.toTypedArray(),
            start = Offset(g.x1, g.y1),
            end = Offset(g.x2, g.y2),
        )
    }
}

private val READY: List<Ready> by lazy {
    JijiArt.shapes.map { s ->
        Ready(
            part = s.part,
            path = PathParser().parsePathString(s.pathData).toPath(),
            fill = s.fill?.let(::brushOf),
            stroke = s.stroke?.let(::brushOf),
            strokeWidth = s.strokeWidth,
            alpha = s.alpha,
        )
    }
}

private fun DrawScope.paint(s: Ready, alpha: Float) {
    val a = s.alpha * alpha
    s.fill?.let { drawPath(s.path, it, alpha = a) }
    s.stroke?.let {
        drawPath(s.path, it, alpha = a, style = Stroke(s.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
