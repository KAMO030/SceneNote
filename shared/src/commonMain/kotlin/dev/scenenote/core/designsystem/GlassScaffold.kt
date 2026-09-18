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
 * 两层模型的页面骨架：内容层在下（haze 取样源，含页面底色），功能层（导航按钮 / Tab 栏 / dock）浮在上面取样它。
 * 内容自己负责给顶部 / 底部留出空间（见 [SceneSize.navBar] / [SceneSize.tabBar]），因为 iOS 26 的内容本来就要从玻璃下面滚过去。
 * [backdrop] 默认自建；画在骨架之上的覆盖层（sheet）要取样同一内容层时，宿主自己 [rememberGlassBackdrop] 传进来并提供给覆盖层。
 */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    background: Color = SceneTheme.colors.groupedBackground,
    backdrop: GlassBackdrop = rememberGlassBackdrop(),
    topBar: @Composable BoxScope.() -> Unit = {},
    bottomBar: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Box(modifier.fillMaxSize().background(background)) {
            // 底色画在取样源里面，玻璃模糊到的才是"页面"而不是透明
            Box(Modifier.fillMaxSize().glassBackdropSource(backdrop).background(background)) { content() }
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) { topBar() }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { bottomBar() }
        }
    }
}
