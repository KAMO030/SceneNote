package dev.scenenote.core.platform

import android.os.Build

actual object PlatformInfo {
    actual val os: Os = Os.ANDROID
    actual val osVersion: Int = Build.VERSION.SDK_INT
    actual val osName: String = "Android ${Build.VERSION.RELEASE}"
}
