package dev.scenenote.ui.brand

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.i18n.stringResource
import dev.scenenote.shared.resources.Res
import dev.scenenote.shared.resources.app_name
import kotlin.math.PI
import kotlin.math.sin

/**
 * 开屏：底色接着系统开屏（Android 12+ 的 windowSplashScreen / iOS 的 UILaunchScreen 都用同一支粉），
 * 渐变铺开 → 记记酱弹进来 → 猫耳抖一下、眨个眼 → 字出来 → 整体让位给首页。
 *
 * Reduce Motion 打开时只剩淡入淡出，时长也压到三分之一（07 篇 §7.14 无障碍状态）。
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val reduced = SceneTheme.motion.reduced
    val dark = SceneTheme.colors.isDark
    val appName = stringResource(Res.string.app_name)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = if (reduced) 600 else 1600, easing = LinearEasing))
        SplashOnce.played = true
        onFinished()
    }
    val p = progress.value

    val sky = JijiArt.sky.getValue(if (dark) "skyDark" else "skyLight")
    val base = sky.stops[1].second                           // 与两端系统开屏的纯色底同一支
    val ink = if (dark) Color(0xFFFFF0F7) else Color(0xFF3B2B48)
    val out = 1f - seg(p, 0.90f, 1f)                         // 收尾淡出

    val skyIn = if (reduced) seg(p, 0f, 0.5f) else seg(p, 0f, 0.20f)
    val enter = if (reduced) seg(p, 0f, 0.6f) else easeOutBack(seg(p, 0.04f, 0.40f))
    val charAlpha = (if (reduced) seg(p, 0f, 0.6f) else seg(p, 0.04f, 0.26f)) * out
    val earTilt = if (reduced) 0f else wobble(seg(p, 0.34f, 0.56f), cycles = 1.5f) * 8f
    val ahoge = if (reduced) 0f else wobble(seg(p, 0.26f, 0.78f), cycles = 2f) * 10f
    val blink = if (reduced) 0f else sin(seg(p, 0.50f, 0.66f) * PI.toFloat())
    val textIn = if (reduced) seg(p, 0.2f, 0.8f) else easeOutCubic(seg(p, 0.36f, 0.64f))
    val wave = if (reduced) 0f else seg(p, 0.34f, 0.94f)

    Box(
        Modifier
            .fillMaxSize()
            .background(base)
            // 盖着的时候把手势全吃掉：底下的首页已经组合好了，不拦就会被误触
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
            .semantics { contentDescription = appName },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.linearGradient(
                    colorStops = sky.stops.toTypedArray(),
                    start = Offset(size.width * 0.23f, 0f),
                    end = Offset(size.width * 0.77f, size.height),
                ),
                alpha = skyIn * out,
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = if (dark) 0.30f else 0.55f), Color.White.copy(alpha = 0f)),
                    center = Offset(size.width / 2f, size.height * 0.36f),
                    radius = size.minDimension * 0.75f,
                ),
                alpha = skyIn * out,
            )
            if (!reduced) drawSparkles(seg(p, 0.24f, 1f), out, dark)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // 声波：呼应"戴上耳机就能听"，从记记酱身上一圈圈推出去
                if (wave > 0f) {
                    Canvas(Modifier.size(380.dp)) { drawWaves(wave, out) }
                }
                JijiLogo(
                    modifier = Modifier
                        .size(210.dp)
                        .offset(y = ((1f - enter) * 42f).dp),
                    blink = blink,
                    earTilt = earTilt,
                    ahogeSwing = ahoge,
                    scale = 0.78f + 0.22f * enter,
                    alpha = charAlpha,
                )
            }
            Spacer(Modifier.height(24.dp))
            StickerText(
                text = appName,
                ink = ink,
                alpha = textIn * out,
                lift = (1f - textIn) * 14f,
                sizeSp = 40f,
                spacingSp = if (appName.length <= 4) 6f else 1f,
            )
            if (appName != LATIN) {
                Spacer(Modifier.height(6.dp))
                StickerText(
                    text = LATIN,
                    ink = ink.copy(alpha = 0.72f),
                    alpha = easeOutCubic(seg(p, 0.46f, 0.72f)) * out * (if (reduced) textIn else 1f),
                    lift = (1f - textIn) * 8f,
                    sizeSp = 17f,
                    spacingSp = 3f,
                    outline = false,
                )
            }
        }
    }
}

private const val LATIN = "SceneNote"

/** 进程内只播一次：深浅色切换之类的重建不该再看一遍开屏。 */
object SplashOnce {
    var played: Boolean = false
}

/** 贴纸字：先描一圈白边再压实心，和 logo 的白描边是同一套做法。 */
@Composable
private fun StickerText(
    text: String,
    ink: Color,
    alpha: Float,
    lift: Float,
    sizeSp: Float,
    spacingSp: Float,
    outline: Boolean = true,
) {
    val style = SceneTheme.type.largeTitle.copy(
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.2f).sp,
        letterSpacing = spacingSp.sp,
        textAlign = TextAlign.Center,
    )
    Box(Modifier.offset(y = lift.dp), contentAlignment = Alignment.Center) {
        if (outline) {
            SceneText(
                text = text,
                style = style.copy(drawStyle = Stroke(width = 9f, join = StrokeJoin.Round, cap = StrokeCap.Round)),
                color = Color.White.copy(alpha = alpha * 0.92f),
            )
        }
        SceneText(text = text, style = style, color = ink.copy(alpha = ink.alpha * alpha))
    }
}

// ---- 时间线小工具 ----

/** 把整条进度里的一段映射成自己的 0→1。 */
private fun seg(p: Float, from: Float, to: Float): Float = ((p - from) / (to - from)).coerceIn(0f, 1f)

/** 越摆越小的正弦，用来抖猫耳和呆毛。 */
private fun wobble(t: Float, cycles: Float): Float =
    if (t <= 0f || t >= 1f) 0f else sin(t * cycles * 2f * PI.toFloat()) * (1f - t)

private fun easeOutCubic(t: Float): Float {
    val u = 1f - t
    return 1f - u * u * u
}

/** 冲过头再回弹一点，弹入用。 */
private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val u = t - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

// ---- 背景零件 ----

private fun DrawScope.drawWaves(t: Float, out: Float) {
    repeat(3) { i ->
        val local = (t * 1.6f - i * 0.22f).coerceIn(0f, 1f)
        if (local <= 0f || local >= 1f) return@repeat
        val r = size.minDimension * (0.26f + 0.24f * local)
        drawCircle(
            color = Color.White,
            radius = r,
            style = Stroke(width = (4.5f - 3f * local).coerceAtLeast(1f)),
            alpha = (1f - local) * 0.62f * out,
        )
    }
}

private fun DrawScope.drawSparkles(t: Float, out: Float, dark: Boolean) {
    if (t <= 0f) return
    val color = if (dark) Color(0xFFFFF3FA) else Color.White
    // 相对位置 + 相对半径 + 闪烁相位，避开中间的角色
    val spots = listOf(
        Triple(0.13f, 0.20f, 0.055f) to 0.0f,
        Triple(0.86f, 0.26f, 0.038f) to 0.35f,
        Triple(0.18f, 0.74f, 0.030f) to 0.62f,
        Triple(0.84f, 0.70f, 0.046f) to 0.18f,
        Triple(0.50f, 0.10f, 0.026f) to 0.80f,
    )
    spots.forEach { (spot, phase) ->
        val (fx, fy, fr) = spot
        val twinkle = 0.55f + 0.45f * sin((t * 2f + phase) * 2f * PI.toFloat())
        val r = size.minDimension * fr * (0.7f + 0.3f * twinkle) * t
        if (r <= 0.5f) return@forEach
        drawPath(
            sparklePath(Offset(size.width * fx, size.height * fy), r),
            color = color,
            alpha = (0.35f + 0.4f * twinkle) * t * out,
        )
    }
}

/** 四角星。 */
private fun sparklePath(c: Offset, r: Float): Path {
    val k = r * 0.28f
    return Path().apply {
        moveTo(c.x, c.y - r)
        cubicTo(c.x + k, c.y - k, c.x + k, c.y - k, c.x + r, c.y)
        cubicTo(c.x + k, c.y + k, c.x + k, c.y + k, c.x, c.y + r)
        cubicTo(c.x - k, c.y + k, c.x - k, c.y + k, c.x - r, c.y)
        cubicTo(c.x - k, c.y - k, c.x - k, c.y - k, c.x, c.y - r)
        close()
    }
}
