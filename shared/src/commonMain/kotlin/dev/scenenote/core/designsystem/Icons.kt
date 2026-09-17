package dev.scenenote.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 两端语义同名的图标集（07 篇 §7.14：iOS 用 SF Symbols、Android 用权重接近的开源符号，待验证 47）。
 * 这里是 Compose 自绘的最小集合（1.8 px 圆头描边，24 pt 视口），iOS 26 原生宿主的标签栏另用 SF Symbols。
 */
object SceneIcons {
    private fun stroke(name: String, width: Float = 1.8f, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null, pathBuilder = block)
            .build()

    private fun fill(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(fill = SolidColor(Color.Black), pathBuilder = block)
            .build()

    private fun PathBuilder.rect(x: Float, y: Float, w: Float, h: Float) { moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x, y + h); close() }
    private fun PathBuilder.line(x1: Float, y1: Float, x2: Float, y2: Float) { moveTo(x1, y1); lineTo(x2, y2) }
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    /** Tab · 场景（卡片组）。 */
    val Scenes: ImageVector by lazy { stroke("scenes") { rect(3f, 4f, 18f, 7f); rect(3f, 14f, 8f, 6f); rect(14f, 14f, 7f, 6f) } }
    /** Tab · 实时（波形）。 */
    val Waveform: ImageVector by lazy { stroke("waveform") { line(4f, 10f, 4f, 14f); line(8f, 6f, 8f, 18f); line(12f, 3f, 12f, 21f); line(16f, 7f, 16f, 17f); line(20f, 10f, 20f, 14f) } }
    /** Tab · 资料库（书脊）。 */
    val Library: ImageVector by lazy { stroke("library") { rect(4f, 4f, 4f, 16f); rect(10f, 4f, 4f, 16f); moveTo(16f, 5f); lineTo(20f, 6f); lineTo(17f, 20f); lineTo(13f, 19f); close() } }
    /** Tab · 设置（滑杆）。 */
    val Sliders: ImageVector by lazy { stroke("sliders") { line(4f, 7f, 10f, 7f); line(18f, 7f, 20f, 7f); line(4f, 17f, 8f, 17f); line(12f, 17f, 20f, 17f); circle(16f, 7f, 2f); circle(10f, 17f, 2f) } }
    val ChevronLeft: ImageVector by lazy { stroke("chevron.left", 2.2f) { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) } }
    val ChevronRight: ImageVector by lazy { stroke("chevron.right", 2.2f) { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) } }
    val Mic: ImageVector by lazy { stroke("mic") { rect(9f, 3f, 6f, 11f); moveTo(5f, 11f); arcTo(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 19f, y1 = 11f); line(12f, 18f, 12f, 21f) } }
    val Check: ImageVector by lazy { stroke("check", 2.4f) { moveTo(5f, 12f); lineTo(9f, 16f); lineTo(19f, 6f) } }
    val Close: ImageVector by lazy { stroke("xmark", 2.2f) { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) } }
    val Share: ImageVector by lazy { stroke("square.and.arrow.up", 2f) { line(12f, 3f, 12f, 15f); moveTo(8f, 7f); lineTo(12f, 3f); lineTo(16f, 7f); moveTo(5f, 12f); lineTo(5f, 19f); lineTo(19f, 19f); lineTo(19f, 12f) } }
    val Lock: ImageVector by lazy { stroke("lock") { rect(5f, 11f, 14f, 10f); moveTo(8f, 11f); lineTo(8f, 8f); arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 16f, y1 = 8f); lineTo(16f, 11f) } }
    val Headphones: ImageVector by lazy { stroke("headphones") { moveTo(4f, 14f); lineTo(4f, 12f); arcTo(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 12f); lineTo(20f, 14f); rect(4f, 14f, 4f, 6f); rect(16f, 14f, 4f, 6f) } }
    val Rotate: ImageVector by lazy { stroke("arrow.clockwise") { moveTo(20f, 12f); arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 17.7f, y1 = 6.3f); moveTo(20f, 4f); lineTo(20f, 9f); lineTo(15f, 9f) } }
    val Speaker: ImageVector by lazy { stroke("speaker.wave.1") { moveTo(4f, 9f); lineTo(4f, 15f); lineTo(8f, 15f); lineTo(13f, 19f); lineTo(13f, 5f); lineTo(8f, 9f); close(); moveTo(16f, 9f); arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 16f, y1 = 15f) } }
    val Ellipsis: ImageVector by lazy { fill("ellipsis") { circle(6f, 12f, 2f); circle(12f, 12f, 2f); circle(18f, 12f, 2f) } }
    val Key: ImageVector by lazy { stroke("key") { circle(8f, 14f, 4f); line(11f, 11f, 20f, 2f); line(16f, 6f, 18f, 8f); line(13f, 9f, 15f, 11f) } }
    val Bookmark: ImageVector by lazy { stroke("bookmark") { moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(12f, 17f); lineTo(6f, 21f); close() } }
    val Search: ImageVector by lazy { stroke("magnifyingglass", 2f) { circle(11f, 11f, 7f); line(20f, 20f, 16f, 16f) } }
    val Swap: ImageVector by lazy { stroke("arrow.up.arrow.down") { line(7f, 4f, 7f, 17f); moveTo(4f, 7f); lineTo(7f, 4f); lineTo(10f, 7f); line(17f, 20f, 17f, 7f); moveTo(14f, 17f); lineTo(17f, 20f); lineTo(20f, 17f) } }
    val Doc: ImageVector by lazy { stroke("doc") { moveTo(7f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(7f, 21f); close(); moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f) } }
    val Sparkle: ImageVector by lazy { stroke("sparkle", 2f) { moveTo(12f, 3f); lineTo(13.8f, 8.2f); lineTo(19f, 10f); lineTo(13.8f, 11.8f); lineTo(12f, 17f); lineTo(10.2f, 11.8f); lineTo(5f, 10f); lineTo(10.2f, 8.2f); close() } }
    val Play: ImageVector by lazy { fill("play.fill") { moveTo(8f, 5f); lineTo(8f, 19f); lineTo(19f, 12f); close() } }
    val Pause: ImageVector by lazy { fill("pause.fill") { rect(7f, 5f, 3.5f, 14f); rect(13.5f, 5f, 3.5f, 14f) } }
    val Stop: ImageVector by lazy { fill("stop.fill") { rect(6f, 6f, 12f, 12f) } }
}

/** 单色图标：默认跟随 [LocalContentColor]；随 Dynamic Type 缩放由调用方通过 `size` 决定。 */
@Composable
fun SceneIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = Color.Unspecified,
) {
    val color = if (tint.isSpecified) tint else LocalContentColor.current
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(color),
    )
}
