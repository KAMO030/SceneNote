package dev.scenenote.nmt

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 真实 decoder 上的图改写：体积 = 原文件 + 转置权重 + 少量节点；改写后再改一次找不到模式（幂等）。装载与译文一致性由 OnnxNmtTranslatorTest 覆盖。 */
class NmtModelPatchTest {
    @Test fun patchAddsTransposedWeightAndIsIdempotent() {
        val root = testEnv("SCENENOTE_NMT_STORE") ?: return
        val fs = FileSystem.SYSTEM
        val original = "$root/models/nmt-zh-en/onnx/decoder_model_merged_quantized.onnx"
        val input = fs.read(original.toPath()) { readByteArray() }
        val out = NmtModelPatch.patch(input) ?: error("pattern not found")
        val bytes = out.toByteArray()
        assertEquals(out.size, bytes.size.toLong())
        val extra = bytes.size - input.size
        assertTrue(extra in 33_280_512..33_290_000, "extra bytes $extra")   // 65001 × 512 + 张量头 + 新节点 − 删掉的三个节点
        assertNull(NmtModelPatch.patch(bytes))
        val scratch = testEnv("SCENENOTE_NMT_SCRATCH")
        if (scratch != null) fs.write("$scratch/decoder_patched_kotlin.onnx".toPath()) { write(bytes) }
    }
}
