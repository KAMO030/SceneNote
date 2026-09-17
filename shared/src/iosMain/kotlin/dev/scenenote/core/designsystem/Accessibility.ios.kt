package dev.scenenote.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityDarkerSystemColorsEnabled
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityIsReduceTransparencyEnabled

@Composable
actual fun rememberSystemAccessibilityPrefs(): AccessibilityPrefs = remember {
    AccessibilityPrefs(
        reduceTransparency = UIAccessibilityIsReduceTransparencyEnabled(),
        reduceMotion = UIAccessibilityIsReduceMotionEnabled(),
        increaseContrast = UIAccessibilityDarkerSystemColorsEnabled(),
    )
}

/**
 * iOS 上暂不做 Compose 自绘背景模糊：CMP 1.12.0 的 Skiko 在"同一 GraphicsLayer 既直接绘制、又被另一层嵌套绘制"时
 * 于 RenderNode::drawShadow 处崩溃（iPhone 17 模拟器 / iOS 26 实测，2026-09-17，见 docs/验收记录/design-system.md）。
 * 玻璃退化为高填充半透明面；iOS 26+ 的标签栏 / 导航栏 / sheet 按 07 篇 §7.14 由原生宿主承载拿真 Liquid Glass（待验证 48）。
 */
actual fun platformSupportsBackdropBlur(): Boolean = false
