package dev.scenenote.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.scenenote.shared.resources.*
import dev.scenenote.core.i18n.stringResource

/**
 * Sheet（`sheets.md › Best practices`）：设置类短任务专用；一次一个、可拖拽有 grabber、Done 必配 Cancel、可下滑关闭。
 * 复杂或持续的流程（实时会话、播放器）用全屏视图，不用 sheet。
 * 背景是材质（glass-flat），后面的父页面被压暗；iOS 26+ 由 UISheetPresentationController 原生承载拿真 detent。
 */
@Composable
fun BoxScope.SceneSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String = stringResource(Res.string.common_cancel),
    doneText: String = stringResource(Res.string.common_done),
    topInset: Dp = 96.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = SceneTheme.colors
    val motion = SceneTheme.motion
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 96.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    val dimInteraction = remember { MutableInteractionSource() }

    AnimatedVisibility(visible = visible, enter = fadeIn(tween(motion.normalMs)), exit = fadeOut(tween(motion.normalMs))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
                .clickable(interactionSource = dimInteraction, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(tween(motion.normalMs)) { it },
        exit = slideOutVertically(tween(motion.normalMs)) { it },
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = topInset)
                .glassFlat(RoundedCornerShape(topStart = SceneRadius.xl, topEnd = SceneRadius.xl))
                .fillMaxSize(),
        ) {
            // grabber：拖动区
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .pointerInput(onDismiss) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (dragged > dismissThresholdPx) onDismiss(); dragged = 0f },
                            onDragCancel = { dragged = 0f },
                        ) { _, dy -> dragged += dy }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 36.dp, height = 5.dp).clip(CircleShape).background(c.label.copy(alpha = 0.3f)))
            }
            Box(Modifier.fillMaxWidth().height(SceneSize.navBar).padding(horizontal = SceneSpacing.m)) {
                val cancelInteraction = remember { MutableInteractionSource() }
                val doneInteraction = remember { MutableInteractionSource() }
                SceneText(
                    cancelText,
                    Modifier.align(Alignment.CenterStart).clickable(interactionSource = cancelInteraction, indication = null, role = Role.Button, onClick = onDismiss).padding(4.dp),
                    style = SceneTheme.type.body, color = c.tint,
                )
                SceneText(title, Modifier.align(Alignment.Center), style = SceneTheme.type.headline, color = c.label, maxLines = 1)
                SceneText(
                    doneText,
                    Modifier.align(Alignment.CenterEnd).clickable(interactionSource = doneInteraction, indication = null, role = Role.Button, onClick = onDone).padding(4.dp),
                    style = SceneTheme.type.headline, color = c.tint,
                )
            }
            content()
        }
    }
}

/**
 * sheet 的平板材质：比 Tab 栏（58%）更高的填充、不画对角高光。
 * 填充色与页面底色同为 secondarySystemBackground，透出的那部分只有下面的白色行、彩色图标和文字能看出来，所以 alpha 不能太高——
 * 86% 时模糊后的差异只剩几个灰阶，肉眼等于不透明；72% 能看到柔和的形状又不影响 sheet 上的文字。
 * 只有真能模糊（拿得到内容层、平台支持、没开「降低透明度」）才半透明；否则下面的页面会直接穿出来，改为不透明。
 */
@Composable
fun Modifier.glassFlat(shape: androidx.compose.ui.graphics.Shape, backdrop: GlassBackdrop? = LocalGlassBackdrop.current): Modifier {
    val c = SceneTheme.colors
    val spec = rememberGlassSpec(backdropAvailable = backdrop != null)
    val fill = if (spec.blurEnabled) c.secondarySystemBackground.copy(alpha = 0.72f) else c.secondarySystemBackground
    return this.glass(shape, backdrop = backdrop, elevation = 16.dp, fillOverride = fill)
}
