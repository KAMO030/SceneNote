package dev.scenenote.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- 07 篇 §7.14：core/design-system Tokens ----------
// 语义色一色一义；字体刻度 = Apple 文本样式；间距 4 / 8 / 16 / 20；圆角 10 / 14 / 20 / 28。
// 不引入 compose.material3；SF Pro / SF Symbols 只在 Apple 平台由系统提供，Android 用系统默认字体套同一刻度。

/** 语义色。名字对齐 UIKit 语义色，便于 iOS 原生宿主（UITabBarController 等）与 Compose 内容层配色一致。 */
@Immutable
data class SceneColors(
    val isDark: Boolean,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val systemBackground: Color,
    val secondarySystemBackground: Color,
    val groupedBackground: Color,
    val secondaryGroupedBackground: Color,
    val separator: Color,
    /** 灰底按钮 / 分段控件轨道 / 未选中开关：半透明，叠在任何底上都成立。 */
    val fill: Color,
    val tint: Color,
    val onTint: Color,
    val tintSoft: Color,
    val onTintSoft: Color,
    /** 黄标：外放类降级动作专用，不作其他用途。 */
    val warningSoft: Color,
    val onWarningSoft: Color,
    val destructiveSoft: Color,
    val onDestructiveSoft: Color,
    val destructive: Color,
    val recording: Color,
    /** 玻璃三件套：填充（含透明度）、0.5 dp 描边、顶部高光线。 */
    val glassFill: Color,
    val glassStroke: Color,
    val glassHighlight: Color,
) {
    companion object {
        val Light = SceneColors(
            isDark = false,
            label = Color(Palette.LightLabel), secondaryLabel = Color(Palette.LightSecondaryLabel), tertiaryLabel = Color(Palette.LightTertiaryLabel),
            systemBackground = Color(Palette.LightSystemBackground), secondarySystemBackground = Color(Palette.LightSecondarySystemBackground),
            groupedBackground = Color(Palette.LightGroupedBackground), secondaryGroupedBackground = Color(Palette.LightSecondaryGroupedBackground),
            separator = Color(Palette.LightSeparator), fill = Color(Palette.LightFill),
            tint = Color(Palette.LightTint), onTint = Color(Palette.LightOnTint), tintSoft = Color(Palette.LightTintSoft), onTintSoft = Color(Palette.LightOnTintSoft),
            warningSoft = Color(Palette.LightWarningSoft), onWarningSoft = Color(Palette.LightOnWarningSoft),
            destructiveSoft = Color(Palette.LightDestructiveSoft), onDestructiveSoft = Color(Palette.LightOnDestructiveSoft),
            destructive = Color(Palette.LightDestructive), recording = Color(Palette.LightRecording),
            glassFill = Color(Palette.LightGlassFill), glassStroke = Color(Palette.LightGlassStroke), glassHighlight = Color(Palette.LightGlassHighlight),
        )
        val Dark = SceneColors(
            isDark = true,
            label = Color(Palette.DarkLabel), secondaryLabel = Color(Palette.DarkSecondaryLabel), tertiaryLabel = Color(Palette.DarkTertiaryLabel),
            systemBackground = Color(Palette.DarkSystemBackground), secondarySystemBackground = Color(Palette.DarkSecondarySystemBackground),
            groupedBackground = Color(Palette.DarkGroupedBackground), secondaryGroupedBackground = Color(Palette.DarkSecondaryGroupedBackground),
            separator = Color(Palette.DarkSeparator), fill = Color(Palette.DarkFill),
            tint = Color(Palette.DarkTint), onTint = Color(Palette.DarkOnTint), tintSoft = Color(Palette.DarkTintSoft), onTintSoft = Color(Palette.DarkOnTintSoft),
            warningSoft = Color(Palette.DarkWarningSoft), onWarningSoft = Color(Palette.DarkOnWarningSoft),
            destructiveSoft = Color(Palette.DarkDestructiveSoft), onDestructiveSoft = Color(Palette.DarkOnDestructiveSoft),
            destructive = Color(Palette.DarkDestructive), recording = Color(Palette.DarkRecording),
            glassFill = Color(Palette.DarkGlassFill), glassStroke = Color(Palette.DarkGlassStroke), glassHighlight = Color(Palette.DarkGlassHighlight),
        )
    }
}

/**
 * Apple 文本样式刻度（`typography.md › Specifications`）：正文 17 pt、最小 11 pt。
 * 用 sp 以支持 Dynamic Type / fontScale；行高按 Apple 默认值。
 */
@Immutable
data class SceneTypography(
    val largeTitle: TextStyle,
    val title1: TextStyle,
    val title2: TextStyle,
    val title3: TextStyle,
    val headline: TextStyle,
    val body: TextStyle,
    val callout: TextStyle,
    val subheadline: TextStyle,
    val footnote: TextStyle,
    val caption1: TextStyle,
    val caption2: TextStyle,
    /** 对方半屏 / 速译卡的大字：≥ 28 pt，粗体，紧字距。 */
    val facingScreen: TextStyle,
) {
    companion object {
        fun default(family: FontFamily = FontFamily.Default): SceneTypography = SceneTypography(
            largeTitle = TextStyle(fontFamily = family, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
            title1 = TextStyle(fontFamily = family, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
            title2 = TextStyle(fontFamily = family, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
            title3 = TextStyle(fontFamily = family, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
            headline = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            body = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
            callout = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
            subheadline = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
            footnote = TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
            caption1 = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
            caption2 = TextStyle(fontFamily = family, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal),
            facingScreen = TextStyle(fontFamily = family, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        )
    }
}

/** 间距刻度 4 / 8 / 16 / 20（外加页边距 20 与列表行内距 16）。 */
object SceneSpacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 16.dp
    val l: Dp = 20.dp
    /** 页面左右边距（inset grouped 列表与卡片）。 */
    val page: Dp = 20.dp
    /** 列表行水平内距。 */
    val row: Dp = 16.dp
}

/** 圆角刻度 10 / 14 / 20 / 28；胶囊用 [SceneRadius.capsule]。 */
object SceneRadius {
    val s: Dp = 10.dp
    val m: Dp = 14.dp
    val l: Dp = 20.dp
    val xl: Dp = 28.dp
    val capsule: Dp = 999.dp
}

/** 触控目标：iOS 44 pt、Android 48 dp（两端取 48 就同时满足）。 */
object SceneSize {
    val touchTarget: Dp = 48.dp
    val buttonHeight: Dp = 50.dp
    val rowMinHeight: Dp = 46.dp
    val navBar: Dp = 44.dp
    val tabBar: Dp = 66.dp
    val glassButton: Dp = 44.dp
}

/** 动效：Reduce Motion 时 [SceneMotion.forReduced] 把时长归零、去掉形变。 */
@Immutable
data class SceneMotion(val fastMs: Int = 150, val normalMs: Int = 250, val slowMs: Int = 400, val reduced: Boolean = false) {
    companion object {
        val Default = SceneMotion()
        fun forReduced() = SceneMotion(fastMs = 0, normalMs = 0, slowMs = 0, reduced = true)
    }
}
