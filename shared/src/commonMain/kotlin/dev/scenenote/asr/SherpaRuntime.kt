package dev.scenenote.asr

/** sherpa-onnx 运行时信息（用于自检与基准记录）。 */
expect object SherpaRuntime {
    val version: String
    val onnxruntimeVersion: String
}
