package dev.scenenote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 07 篇 §7.14 UI 设计语言的最小实现：冷绿灰中性色 + 青绿主色（"耳机"色）+ 琥珀色只用于黄标降级。 */
object SceneNoteColors {
    val Accent = Color(0xFF0B7A6E)
    val AccentDark = Color(0xFF49C4B1)
    val Warn = Color(0xFFA8641A)
    val WarnDark = Color(0xFFE0A455)
}

private val Light: ColorScheme = lightColorScheme(
    primary = SceneNoteColors.Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F1EE), onPrimaryContainer = Color(0xFF075C53),
    secondary = Color(0xFF5F6B66), onSecondary = Color.White,
    tertiary = SceneNoteColors.Warn, tertiaryContainer = Color(0xFFF7EBD9), onTertiaryContainer = Color(0xFF5C3608),
    background = Color(0xFFF5F7F5), onBackground = Color(0xFF1A211E),
    surface = Color.White, onSurface = Color(0xFF1A211E),
    surfaceVariant = Color(0xFFEEF1EF), onSurfaceVariant = Color(0xFF5F6B66),
    outline = Color(0xFFD8DEDA), error = Color(0xFFB3261E),
)
private val Dark: ColorScheme = darkColorScheme(
    primary = SceneNoteColors.AccentDark, onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF16302B), onPrimaryContainer = Color(0xFF7FDACB),
    secondary = Color(0xFF97A39D), onSecondary = Color(0xFF1A211E),
    tertiary = SceneNoteColors.WarnDark, tertiaryContainer = Color(0xFF3A2A12), onTertiaryContainer = Color(0xFFF7EBD9),
    background = Color(0xFF0F1412), onBackground = Color(0xFFE5EBE7),
    surface = Color(0xFF161C19), onSurface = Color(0xFFE5EBE7),
    surfaceVariant = Color(0xFF1C2320), onSurfaceVariant = Color(0xFF97A39D),
    outline = Color(0xFF2A332E), error = Color(0xFFF2B8B5),
)

@Composable
fun SceneNoteTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
