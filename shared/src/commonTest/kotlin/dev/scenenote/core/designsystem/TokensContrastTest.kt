package dev.scenenote.core.designsystem

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 07 篇 §7.14 验收："contrast 以 token 实际色值计算并写入 design-system 单测"。
 * ≤ 17 pt 文本 4.5:1；≥ 18 pt 或粗体 3:1；tertiaryLabel 只准用于 ≥ 18 pt / 非文字，所以只卡 3:1。
 */
class TokensContrastTest {
    private fun assertContrast(name: String, fg: Long, bg: Long, min: Double) {
        val r = Palette.contrast(fg, bg)
        assertTrue(r >= min, "$name 对比度 ${(r * 100).roundToInt() / 100.0} < $min")
    }

    @Test fun lightBodyText() {
        assertContrast("label/systemBackground", Palette.LightLabel, Palette.LightSystemBackground, 4.5)
        assertContrast("label/groupedBackground", Palette.LightLabel, Palette.LightGroupedBackground, 4.5)
        assertContrast("secondaryLabel/systemBackground", Palette.LightSecondaryLabel, Palette.LightSystemBackground, 4.5)
        assertContrast("secondaryLabel/groupedBackground", Palette.LightSecondaryLabel, Palette.LightGroupedBackground, 4.5)
        assertContrast("tertiaryLabel/systemBackground (≥18pt)", Palette.LightTertiaryLabel, Palette.LightSystemBackground, 3.0)
    }

    @Test fun lightTintAndSoftSurfaces() {
        assertContrast("onTint/tint", Palette.LightOnTint, Palette.LightTint, 4.5)
        assertContrast("tint/systemBackground (plain button)", Palette.LightTint, Palette.LightSystemBackground, 4.5)
        assertContrast("onTintSoft/tintSoft", Palette.LightOnTintSoft, Palette.LightTintSoft, 4.5)
        assertContrast("onWarningSoft/warningSoft", Palette.LightOnWarningSoft, Palette.LightWarningSoft, 4.5)
        assertContrast("onDestructiveSoft/destructiveSoft", Palette.LightOnDestructiveSoft, Palette.LightDestructiveSoft, 4.5)
        assertContrast("destructive/systemBackground", Palette.LightDestructive, Palette.LightSystemBackground, 4.5)
    }

    @Test fun darkBodyText() {
        assertContrast("label/systemBackground", Palette.DarkLabel, Palette.DarkSystemBackground, 4.5)
        assertContrast("label/secondarySystemBackground", Palette.DarkLabel, Palette.DarkSecondarySystemBackground, 4.5)
        assertContrast("secondaryLabel/secondarySystemBackground", Palette.DarkSecondaryLabel, Palette.DarkSecondarySystemBackground, 4.5)
        assertContrast("tertiaryLabel/secondarySystemBackground (≥18pt)", Palette.DarkTertiaryLabel, Palette.DarkSecondarySystemBackground, 3.0)
    }

    @Test fun darkTintAndSoftSurfaces() {
        assertContrast("onTint/tint", Palette.DarkOnTint, Palette.DarkTint, 4.5)
        assertContrast("tint/systemBackground", Palette.DarkTint, Palette.DarkSystemBackground, 4.5)
        assertContrast("onTintSoft/tintSoft", Palette.DarkOnTintSoft, Palette.DarkTintSoft, 4.5)
        assertContrast("onWarningSoft/warningSoft", Palette.DarkOnWarningSoft, Palette.DarkWarningSoft, 4.5)
        assertContrast("onDestructiveSoft/destructiveSoft", Palette.DarkOnDestructiveSoft, Palette.DarkDestructiveSoft, 4.5)
        assertContrast("destructive/secondarySystemBackground", Palette.DarkDestructive, Palette.DarkSecondarySystemBackground, 4.5)
    }

    /** 玻璃上的单色标签：按"玻璃填充完全叠在最亮 / 最暗底上"的最坏情况估算。 */
    @Test fun labelsOnGlassWorstCase() {
        // 浅色玻璃（58% 白）叠在浅灰分组底上 ≈ 更亮的灰；label 深色对比只会更高，取纯白底作为最坏情况已在 lightBodyText 覆盖。
        // 深色玻璃（55% #1E1E20）叠在纯白内容上 ≈ 中灰：label #F2F2F7 需 ≥ 4.5。
        val darkOverWhite = blend(Palette.DarkGlassFill, 0xFFFFFFFF)
        assertContrast("darkLabel/darkGlass over white", Palette.DarkLabel, darkOverWhite, 4.5)
        // 浅色玻璃（58% 白）叠在纯黑内容（M3 / 播放器）上：label #1C1C1E 需 ≥ 4.5。
        val lightOverBlack = blend(Palette.LightGlassFill, 0xFF000000)
        assertContrast("lightLabel/lightGlass over black", Palette.LightLabel, lightOverBlack, 4.5)
    }

    private fun blend(fgArgb: Long, bgArgb: Long): Long {
        val a = ((fgArgb shr 24) and 0xFF) / 255.0
        fun ch(shift: Int): Long {
            val f = (fgArgb shr shift) and 0xFF; val b = (bgArgb shr shift) and 0xFF
            return (f * a + b * (1 - a)).toLong().coerceIn(0, 255)
        }
        return (0xFFL shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
