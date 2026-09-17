package dev.scenenote.core.platform

/** 页面按平台增删内容用（Android 不显示 Apple 翻译 / 快捷指令；iOS 不显示磁贴 / ML Kit）。 */
enum class Os { ANDROID, IOS }

expect object PlatformInfo {
    val os: Os
    /** 主版本号：Android API level / iOS 大版本。 */
    val osVersion: Int
    /** 用户可见的系统名（"Android 11" / "iOS 18"）。 */
    val osName: String
}

val PlatformInfo.isAndroid: Boolean get() = os == Os.ANDROID
val PlatformInfo.isIos: Boolean get() = os == Os.IOS
