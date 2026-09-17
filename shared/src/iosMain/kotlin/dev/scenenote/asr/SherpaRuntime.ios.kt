package dev.scenenote.asr

import dev.scenenote.sherpa.native.SherpaOnnxGetOnnxruntimeVersionStr
import dev.scenenote.sherpa.native.SherpaOnnxGetVersionStr
import kotlinx.cinterop.toKString

actual object SherpaRuntime {
    actual val version: String get() = SherpaOnnxGetVersionStr()?.toKString() ?: "?"
    actual val onnxruntimeVersion: String get() = SherpaOnnxGetOnnxruntimeVersionStr()?.toKString() ?: "?"
}
