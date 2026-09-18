package dev.scenenote.core.platform

import dev.scenenote.core.i18n.UiLocale
import platform.Foundation.NSUserDefaults
import platform.posix.exit

/**
 * iOS 界面语言覆盖：写 AppleLanguages，自己的 key 记住是不是我们设的。清掉 AppleLanguages 即回到系统语言列表。
 *
 * 写完立刻生效，不用重开：Compose 的 `Locale.current` 在 iOS 上每次读都走 `NSLocale.preferredLanguages`
 * （= AppleLanguages，我们刚写的那份），而 CMP 的 stringResource 把 ResourceEnvironment `remember` 在
 * `Locale.current` 上——所以只差一次重组。[UiLocale.bump] 就是那次重组（与 Android 同一套机制）。
 *
 * 只有 Bundle 里的 .lproj（权限说明、快捷指令短语、App 显示名）还是系统按启动时的语言解析的，那些要下次启动才换。
 */
class IosAppLocale : AppLocale {
    private val defaults get() = NSUserDefaults.standardUserDefaults

    override val appliesImmediately: Boolean = true

    override val override: String? get() = defaults.stringForKey(KEY)

    override fun set(tag: String?) {
        if (tag == null) { defaults.removeObjectForKey(KEY); defaults.removeObjectForKey(APPLE_LANGUAGES) }
        else { defaults.setObject(tag, forKey = KEY); defaults.setObject(listOf(tag), forKey = APPLE_LANGUAGES) }
        defaults.synchronize()
        UiLocale.bump()
    }

    override fun exitApp() { exit(0) }

    private companion object { const val KEY = "ui_language"; const val APPLE_LANGUAGES = "AppleLanguages" }
}
