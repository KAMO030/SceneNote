package dev.scenenote.asr

import kotlin.test.Test
import kotlin.test.assertTrue

class SherpaRuntimeTest {
    @Test fun runtimeLinks() {
        // iOS：真正调用静态库里的 C 函数，验证 cinterop + 链接；Android：常量。
        assertTrue(SherpaRuntime.version.isNotBlank())
        assertTrue(SherpaRuntime.onnxruntimeVersion.isNotBlank())
    }
}
