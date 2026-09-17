package dev.scenenote.core.platform

import platform.UIKit.UIDevice

actual object PlatformInfo {
    actual val os: Os = Os.IOS
    actual val osVersion: Int = UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 18
    actual val osName: String = "iOS ${UIDevice.currentDevice.systemVersion}"
}
