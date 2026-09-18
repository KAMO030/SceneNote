package dev.scenenote.core.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.StringResource

/**
 * 界面语言版本号：应用内切换语言后 [bump]，所有经 [stringResource] 取过文案的组合作用域都会重组，
 * 重组时 CMP 重新读 Locale.current（Android 已由 AppLocale 改成新语言），于是不用重建 Activity 就整页换语言。
 */
object UiLocale {
    var version by mutableIntStateOf(0)
        private set
    fun bump() { version++ }
}

/** 项目内统一用这个而不是 org.jetbrains.compose.resources.stringResource：多订阅一次 [UiLocale.version]。 */
@Composable
fun stringResource(resource: StringResource): String {
    UiLocale.version
    return org.jetbrains.compose.resources.stringResource(resource)
}

@Composable
fun stringResource(resource: StringResource, vararg formatArgs: Any): String {
    UiLocale.version
    return org.jetbrains.compose.resources.stringResource(resource, *formatArgs)
}
