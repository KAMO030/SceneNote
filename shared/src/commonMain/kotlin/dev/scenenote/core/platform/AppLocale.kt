package dev.scenenote.core.platform

/** 设置里可选的界面语言（BCP-47 语言码，对应 composeResources 的 values-*）。 */
val UiLanguages: List<String> = listOf("zh", "en", "ja", "ko")

/** 语言的本族名：不随界面语言翻译，用户在任何语言下都能认出自己的。 */
fun uiLanguageEndonym(tag: String): String = when (tag) { "zh" -> "中文"; "en" -> "English"; "ja" -> "日本語"; "ko" -> "한국어"; else -> tag }

/**
 * 界面语言覆盖（设置 → 语言 → 界面语言）。null = 跟随系统。
 * Android 用系统的按应用语言（13+ LocaleManager；更早的版本进程内改默认 locale 并重建 Activity），立即生效；
 * iOS 只能写 AppleLanguages，下次启动生效（Compose 的 Locale.current 从 NSLocale.preferredLanguages 取，进程内不可覆盖）。
 */
interface AppLocale {
    val override: String?
    val appliesImmediately: Boolean
    fun set(tag: String?)
    /** iOS：用户选择「现在退出」以便重开生效；Android 不需要。 */
    fun exitApp() {}
}
