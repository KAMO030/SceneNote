package dev.scenenote.core.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

val LocalSceneColors = staticCompositionLocalOf { SceneColors.Light }
val LocalSceneTypography = staticCompositionLocalOf { SceneTypography.default() }
val LocalSceneMotion = staticCompositionLocalOf { SceneMotion.Default }

/** 当前"前景色"（相当于 UIKit 的 label 色在容器内的继承），按钮 / 玻璃条会覆盖它。 */
val LocalContentColor = compositionLocalOf { Color(Palette.LightLabel) }

/** 当前默认文本样式（默认 body）。 */
val LocalTextStyle = compositionLocalOf { SceneTypography.default().body }

/**
 * 场记设计系统入口（07 篇 §7.14）：Apple HIG 而非 Material 3。
 * - 不提供 App 内外观开关：`dark` 默认跟随系统（`dark-mode.md › Best practices`）。
 * - 无障碍偏好从系统读取，玻璃 / 动效据此回退。
 * - 与 `compose.material3` 无关，可与旧的 MaterialTheme 同时存在（迁移期）。
 */
@Composable
fun SceneTheme(
    dark: Boolean = isSystemInDarkTheme(),
    typography: SceneTypography = SceneTypography.default(),
    accessibility: AccessibilityPrefs = rememberSystemAccessibilityPrefs(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) SceneColors.Dark else SceneColors.Light
    val motion = remember(accessibility.reduceMotion) { if (accessibility.reduceMotion) SceneMotion.forReduced() else SceneMotion.Default }
    val indication = remember(colors, accessibility.increaseContrast) { PressHighlightIndication.forColors(colors, accessibility.increaseContrast) }
    CompositionLocalProvider(
        LocalSceneColors provides colors,
        LocalSceneTypography provides typography,
        LocalSceneMotion provides motion,
        LocalAccessibilityPrefs provides accessibility,
        LocalContentColor provides colors.label,
        LocalTextStyle provides typography.body,
        LocalIndication provides indication,
        content = content,
    )
}

/** 便捷访问：`SceneTheme.colors.tint`、`SceneTheme.type.headline`。 */
object SceneTheme {
    val colors: SceneColors @Composable @ReadOnlyComposable get() = LocalSceneColors.current
    val type: SceneTypography @Composable @ReadOnlyComposable get() = LocalSceneTypography.current
    val motion: SceneMotion @Composable @ReadOnlyComposable get() = LocalSceneMotion.current
    val a11y: AccessibilityPrefs @Composable @ReadOnlyComposable get() = LocalAccessibilityPrefs.current
}

/** 在子树里换前景色（例如着色按钮内的白字）。 */
@Composable
fun ProvideContentColor(color: Color, textStyle: TextStyle? = null, content: @Composable () -> Unit) {
    if (textStyle == null) {
        CompositionLocalProvider(LocalContentColor provides color, content = content)
    } else {
        CompositionLocalProvider(LocalContentColor provides color, LocalTextStyle provides textStyle, content = content)
    }
}
