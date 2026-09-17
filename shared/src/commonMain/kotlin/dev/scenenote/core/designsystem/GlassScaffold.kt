package dev.scenenote.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 两层模型的页面骨架：内容层在下（被录制供玻璃取样），功能层（导航按钮 / Tab 栏 / dock）浮在上面。
 * 内容自己负责给顶部 / 底部留出空间（见 [SceneSize.navBar] / [SceneSize.tabBar]），因为 iOS 26 的内容本来就要从玻璃下面滚过去。
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    background: Color = SceneTheme.colors.groupedBackground,
    topBar: @Composable BoxScope.() -> Unit = {},
    bottomBar: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(modifier.fillMaxSize().background(background)) {
            Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) { content() }
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) { topBar() }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { bottomBar() }
        }
    }
}
