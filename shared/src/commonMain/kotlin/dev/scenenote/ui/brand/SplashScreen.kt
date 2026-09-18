package dev.scenenote.ui.brand

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.scenenote.core.designsystem.SceneSpacing
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.designsystem.SceneText
import dev.scenenote.core.i18n.stringResource
import dev.scenenote.shared.resources.Res
import dev.scenenote.shared.resources.app_name
import kotlin.math.PI
import kotlin.math.sin

/**
 * 开屏。视觉跟 App 里的页面是同一套令牌，不另起一套"品牌皮肤"：
 *  - 底色 = 页面底色 `groupedBackground`，和两端系统开屏的纯色（Android `brand_splash` / iOS `LaunchBackground`）同一支，
 *    也和底下已经组合好的首页同色——收尾时内容先淡、底色再淡，露出来的就是首页，没有换底的那一下；
 *  - 记记酱站在一块 `tintSoft` 圆盘上，声波环用 `tint`：和实时页"我在听"的脉冲点 / 呼吸灯是同一种说法，主题色换了这里跟着换；
 *  - 字用首页大标题那一档 `largeTitle` + `label`，拉丁名 `subheadline` + `secondaryLabel`，不描边、不拉字距。
 *
 * 时间线：记记酱弹进来 → 猫耳抖一下、呆毛摆、眨个眼 → 声波推出去 → 字出来 → 整层让位给首页。
 * Reduce Motion 打开时只剩淡入淡出，时长也压到三分之一（07 篇 §7.14 无障碍状态）。
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val reduced = SceneTheme.motion.reduced
    val c = SceneTheme.colors
    val type = SceneTheme.type
    val appName = stringResource(Res.string.app_name)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = if (reduced) 600 else 1600, easing = LinearEasing))
        SplashOnce.played = true
        onFinished()
    }
    val p = progress.value

    // 收尾分两拍：记记酱和字先淡，底色后淡——首页从底下透出来的时候，上面已经没有东西和它叠着了
    val out = 1f - seg(p, 0.84f, 0.94f)
    val bgOut = 1f - seg(p, 0.90f, 1f)
    val enter = if (reduced) 1f else easeOutBack(seg(p, 0.04f, 0.40f))
    val charAlpha = (if (reduced) seg(p, 0f, 0.6f) else seg(p, 0.04f, 0.26f)) * out
    val earTilt = if (reduced) 0f else wobble(seg(p, 0.34f, 0.56f), cycles = 1.5f) * 8f
    val ahoge = if (reduced) 0f else wobble(seg(p, 0.26f, 0.78f), cycles = 2f) * 10f
    val blink = if (reduced) 0f else sin(seg(p, 0.50f, 0.66f) * PI.toFloat())
    val textIn = if (reduced) seg(p, 0.2f, 0.8f) else easeOutCubic(seg(p, 0.36f, 0.64f))
    val subIn = if (reduced) textIn else easeOutCubic(seg(p, 0.46f, 0.72f))
    val wave = if (reduced) 0f else seg(p, 0.34f, 0.94f)
    val scale = 0.78f + 0.22f * enter

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(c.groupedBackground, alpha = bgOut) }
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
        // 整组略高于正中：视觉重心在头像上，正中会显得往下坠
        Column(Modifier.offset(y = (-28).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.offset(y = ((1f - enter) * 36f).dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(STAGE)) {
                    // 圆盘的圆心对着脸，不是对着画框正中（头像的 bbox 上面多出一对耳朵）
                    val center = Offset(size.width / 2f, size.height / 2f + FACE_DROP.toPx() * scale)
                    val disc = DISC_RADIUS.toPx() * scale
                    if (wave > 0f) drawWaves(center, disc, wave, c.tint, out)
                    drawCircle(c.tintSoft, radius = disc, center = center, alpha = charAlpha)
                }
                JijiLogo(
                    modifier = Modifier.size(JIJI_SIZE),
                    blink = blink,
                    earTilt = earTilt,
                    ahogeSwing = ahoge,
                    scale = scale,
                    alpha = charAlpha,
                )
            }
            Spacer(Modifier.height(SceneSpacing.s))
            SceneText(
                appName,
                Modifier.offset(y = ((1f - textIn) * 12f).dp),
                style = type.largeTitle,
                color = c.label.copy(alpha = textIn * out),
                maxLines = 1,
            )
            if (appName != LATIN) {
                Spacer(Modifier.height(2.dp))
                SceneText(
                    LATIN,
                    Modifier.offset(y = ((1f - subIn) * 8f).dp),
                    style = type.subheadline,
                    color = c.secondaryLabel.copy(alpha = subIn * out),
                    maxLines = 1,
                )
            }
        }
    }
}

private const val LATIN = "SceneNote"

/** 声波环能铺到的范围；圆盘半径；头像画框；脸心比画框正中低多少（512 视口里脸心在 y≈290）。 */
private val STAGE = 280.dp
private val DISC_RADIUS = 96.dp
private val JIJI_SIZE = 200.dp
private val FACE_DROP = 13.dp

/** 进程内只播一次：深浅色切换之类的重建不该再看一遍开屏。 */
object SplashOnce {
    var played: Boolean = false
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

// ---- 声波 ----

/** 从圆盘边上一圈圈推出去的细环：呼应"戴上耳机就能听"，颜色、透明度和实时页的脉冲光晕同一档。 */
private fun DrawScope.drawWaves(center: Offset, discRadius: Float, t: Float, tint: Color, out: Float) {
    val reach = size.minDimension / 2f - discRadius
    repeat(3) { i ->
        val local = (t * 1.6f - i * 0.22f).coerceIn(0f, 1f)
        if (local <= 0f || local >= 1f) return@repeat
        drawCircle(
            color = tint,
            radius = discRadius + reach * (0.12f + 0.80f * local),
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
            alpha = (1f - local) * 0.36f * out,
        )
    }
}
