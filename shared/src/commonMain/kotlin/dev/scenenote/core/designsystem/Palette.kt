package dev.scenenote.core.designsystem

import kotlin.math.pow

/**
 * 语义色的原始 ARGB 值（纯 Kotlin，无 Compose 依赖），是 [SceneColors] 的唯一来源，
 * 也是 `TokensContrastTest` 的输入——07 篇 §7.14 要求"contrast 以 token 实际色值计算并写入 design-system 单测"。
 * 一色一义：不要在页面里直接写十六进制。
 */
object Palette {
    // ---- 浅色 ----
    const val LightLabel: Long = 0xFF1C1C1E
    const val LightSecondaryLabel: Long = 0xFF5F5F66      // 白底 5.9:1
    const val LightTertiaryLabel: Long = 0xFF8A8A8E       // 只用于 ≥ 18 pt 或非文字
    const val LightSystemBackground: Long = 0xFFFFFFFF
    const val LightSecondarySystemBackground: Long = 0xFFF2F2F7
    const val LightGroupedBackground: Long = 0xFFF2F2F7
    const val LightSecondaryGroupedBackground: Long = 0xFFFFFFFF
    const val LightSeparator: Long = 0xFFD8D8DC
    const val LightFill: Long = 0x24787880                // rgba(120,120,128,0.14)
    const val LightTint: Long = 0xFF0E7C86                // 青绿"耳机"色；白字 4.95:1
    const val LightOnTint: Long = 0xFFFFFFFF
    const val LightTintSoft: Long = 0xFFE3F3F4
    const val LightOnTintSoft: Long = 0xFF0A5F67
    const val LightWarningSoft: Long = 0xFFFFF4D6         // 黄标（外放降级）
    const val LightOnWarningSoft: Long = 0xFF8A4B00
    const val LightDestructiveSoft: Long = 0xFFFBE5E3
    const val LightOnDestructiveSoft: Long = 0xFFA32820
    const val LightDestructive: Long = 0xFFC8322B
    const val LightRecording: Long = 0xFFE0342C
    const val LightGlassFill: Long = 0x94FFFFFF           // 玻璃填充 58%
    const val LightGlassStroke: Long = 0x12000000
    const val LightGlassHighlight: Long = 0xF2FFFFFF

    // ---- 深色 ----
    const val DarkLabel: Long = 0xFFF2F2F7
    const val DarkSecondaryLabel: Long = 0xFFA1A1A6       // #1C1C1E 底 7.2:1
    const val DarkTertiaryLabel: Long = 0xFF6E6E73
    const val DarkSystemBackground: Long = 0xFF000000
    const val DarkSecondarySystemBackground: Long = 0xFF1C1C1E
    const val DarkGroupedBackground: Long = 0xFF000000
    const val DarkSecondaryGroupedBackground: Long = 0xFF1C1C1E
    const val DarkSeparator: Long = 0xFF3A3A3C
    const val DarkFill: Long = 0x52787880                 // rgba(120,120,128,0.32)
    const val DarkTint: Long = 0xFF4FC3CC                 // 黑底 10:1
    const val DarkOnTint: Long = 0xFF00363B
    const val DarkTintSoft: Long = 0xFF0B3F44
    const val DarkOnTintSoft: Long = 0xFF7FD6DD
    const val DarkWarningSoft: Long = 0xFF3A2A12
    const val DarkOnWarningSoft: Long = 0xFFE0A455
    const val DarkDestructiveSoft: Long = 0xFF4A1A17
    const val DarkOnDestructiveSoft: Long = 0xFFFF8A80
    const val DarkDestructive: Long = 0xFFFF6B63
    const val DarkRecording: Long = 0xFFFF453A
    const val DarkGlassFill: Long = 0xB81E1E20            // 72%：叠在纯白内容上 label 仍 ≥ 4.5:1（见单测）
    const val DarkGlassStroke: Long = 0x14FFFFFF
    const val DarkGlassHighlight: Long = 0x38FFFFFF

    /** WCAG 相对亮度（sRGB）。 */
    fun relativeLuminance(argb: Long): Double {
        fun ch(v: Long): Double {
            val c = (v and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(argb shr 16) + 0.7152 * ch(argb shr 8) + 0.0722 * ch(argb)
    }

    /** WCAG 对比度；两色都视为不透明。 */
    fun contrast(fg: Long, bg: Long): Double {
        val l1 = relativeLuminance(fg); val l2 = relativeLuminance(bg)
        val hi = maxOf(l1, l2); val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }
}
