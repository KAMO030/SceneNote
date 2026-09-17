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
    cancelText: String = "取消",
    doneText: String = "完成",
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

/** sheet 的平板材质：更高填充、更强模糊、不画对角高光。 */
@Composable
fun Modifier.glassFlat(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val c = SceneTheme.colors
    val a11y = SceneTheme.a11y
    val fill = if (a11y.reduceTransparency) c.secondarySystemBackground else c.secondarySystemBackground.copy(alpha = 0.86f)
    return this.glass(shape, elevation = 16.dp, fillOverride = fill)
}
