package dev.scenenote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import dev.scenenote.core.designsystem.SceneTheme

/**
 * 应用主题入口：只包一层 [SceneTheme]（core/design-system，Apple HIG，07 篇 §7.14），
 * 由它提供语义色 / 字体刻度 / 玻璃 / 无障碍偏好。页面全部迁到设计系统后，迁移期的 Material 内层主题已删除。
 */
@Composable
fun SceneNoteTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    SceneTheme(dark = dark, content = content)
}
