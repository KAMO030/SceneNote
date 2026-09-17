package dev.scenenote.core

/** 管线诊断日志：println → Android 的 I/System.out、iOS 的 stdout；`adb logcat -s System.out` 过滤 `SN/`。 */
object Diag {
    fun log(tag: String, msg: String) = println("SN/$tag $msg")
}
