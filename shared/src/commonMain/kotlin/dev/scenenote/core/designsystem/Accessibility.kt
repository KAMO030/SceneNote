package dev.scenenote.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 系统无障碍偏好（07 篇 §7.14 Liquid Glass › 无障碍状态）：
 * Reduce Transparency → 玻璃回退为不透明；Increase Contrast → 加强填充与描边；Reduce Motion → 去掉形变 / 折射动画。
 */
@Immutable
data class AccessibilityPrefs(
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false,
    val increaseContrast: Boolean = false,
) {
    companion object { val None = AccessibilityPrefs() }
}

val LocalAccessibilityPrefs = staticCompositionLocalOf { AccessibilityPrefs.None }

/** 读取系统无障碍偏好；iOS 走 UIAccessibility，Android 走 Settings（无"降低透明度"，恒为 false）。 */
@Composable
expect fun rememberSystemAccessibilityPrefs(): AccessibilityPrefs

/** 平台能否做背景模糊（Android 需 API 31 RenderEffect；iOS Skia 总是可以）。不能时玻璃只画高填充。 */
expect fun platformSupportsBackdropBlur(): Boolean
