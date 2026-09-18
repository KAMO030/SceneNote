package dev.scenenote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.scenenote.core.designsystem.Accents
import dev.scenenote.core.designsystem.SceneTheme
import dev.scenenote.core.settings.AppSettings
import org.koin.compose.koinInject

/**
 * 应用主题入口：只包一层 [SceneTheme]（core/design-system，Apple HIG，07 篇 §7.14），
 * 由它提供语义色 / 字体刻度 / 玻璃 / 无障碍偏好。主题色从设置读（设置 → 外观 → 主题色），改了立即全 App 生效。
 */
@Composable
fun SceneNoteTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val settings = koinInject<AppSettings>()
    val accentId by settings.accent.collectAsState()
    SceneTheme(dark = dark, accent = Accents.byId(accentId), content = content)
}
