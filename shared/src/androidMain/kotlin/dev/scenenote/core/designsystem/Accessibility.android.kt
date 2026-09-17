package dev.scenenote.core.designsystem

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberSystemAccessibilityPrefs(): AccessibilityPrefs {
    val context = LocalContext.current
    return remember(context) {
        val resolver = context.contentResolver
        val animScale = runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        val highContrast = runCatching { Settings.Secure.getInt(resolver, "high_text_contrast_enabled", 0) == 1 }.getOrDefault(false)
        AccessibilityPrefs(
            reduceTransparency = false,                 // Android 没有对应系统开关；由"移除动画"或低端机策略另行决定
            reduceMotion = animScale == 0f,             // 系统「移除动画」
            increaseContrast = highContrast,            // 「高对比度文字」
        )
    }
}
