package dev.scenenote.asr

actual object SherpaRuntime {
    /** AAR 未导出版本函数；以依赖坐标为准。 */
    actual val version: String get() = "1.13.8"
    actual val onnxruntimeVersion: String get() = "bundled"
}
