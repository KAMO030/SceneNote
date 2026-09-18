package dev.scenenote.core.platform

import platform.Foundation.NSUserDefaults
import platform.posix.exit

/**
 * iOS 界面语言覆盖：写 AppleLanguages（系统只在下次启动时读它），自己的 key 记住是不是我们设的。
 * 清掉 AppleLanguages 即回到系统语言列表。
 */
class IosAppLocale : AppLocale {
    private val defaults get() = NSUserDefaults.standardUserDefaults

    override val appliesImmediately: Boolean = false

    override val override: String? get() = defaults.stringForKey(KEY)

    override fun set(tag: String?) {
        if (tag == null) { defaults.removeObjectForKey(KEY); defaults.removeObjectForKey(APPLE_LANGUAGES) }
        else { defaults.setObject(tag, forKey = KEY); defaults.setObject(listOf(tag), forKey = APPLE_LANGUAGES) }
        defaults.synchronize()
    }

    override fun exitApp() { exit(0) }

    private companion object { const val KEY = "ui_language"; const val APPLE_LANGUAGES = "AppleLanguages" }
}
