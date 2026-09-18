package dev.scenenote.nmt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession.SessionOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Android：ai.onnxruntime Java API（onnxruntime-android 1.28.2 AAR；libonnxruntime.so 与 sherpa-onnx AAR 内置的是同一份）。 */
actual class OrtValue internal constructor(internal val tensor: OnnxTensor) : AutoCloseable {
    actual val shape: LongArray get() = tensor.info.shape
    actual fun floats(): FloatArray { val fb = tensor.floatBuffer; val out = FloatArray(fb.remaining()); fb.get(out); return out }
    actual override fun close() = tensor.close()
}

actual class OrtSession internal constructor(private val session: ai.onnxruntime.OrtSession) : AutoCloseable {
    actual val inputNames: List<String> = session.inputNames.toList()
    actual val outputNames: List<String> = session.outputNames.toList()
    /** 不 close 返回的 Result（它会连带关掉所有输出张量）；每个输出交给调用方单独 close。 */
    actual fun run(inputs: Map<String, OrtValue>, outputs: List<String>): Map<String, OrtValue> {
        val result = session.run(inputs.mapValues { it.value.tensor }, outputs.toSet())
        return outputs.associateWith { name -> OrtValue(result.get(name).get() as OnnxTensor) }
    }
    actual override fun close() = session.close()
}

actual object Ort {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    actual val version: String get() = env.version

    actual fun session(modelPath: String, numThreads: Int, lowMemory: Boolean): OrtSession {
        val opts = SessionOptions().apply {
            setIntraOpNumThreads(numThreads)
            setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)
            if (lowMemory) { addConfigEntry("session.disable_prepacking", "1"); setMemoryPatternOptimization(false) }
        }
        return OrtSession(env.createSession(modelPath, opts))
    }

    /** 零元素张量（KV cache 首步）：Java 侧要求 buffer 有地址，给 1 个元素容量、limit 0。 */
    private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(maxOf(bytes, 8)).order(ByteOrder.nativeOrder())

    actual fun tensor(data: FloatArray, shape: LongArray): OrtValue {
        val fb: FloatBuffer = direct(data.size * 4).asFloatBuffer(); fb.put(data); fb.flip()
        return OrtValue(OnnxTensor.createTensor(env, fb, shape))
    }
    actual fun tensor(data: LongArray, shape: LongArray): OrtValue {
        val lb: LongBuffer = direct(data.size * 8).asLongBuffer(); lb.put(data); lb.flip()
        return OrtValue(OnnxTensor.createTensor(env, lb, shape))
    }
    /** 只用到一维 bool（use_cache_branch: [1]）；Java API 按数组形状建张量。 */
    actual fun tensor(data: BooleanArray, shape: LongArray): OrtValue {
        require(shape.size == 1 && shape[0] == data.size.toLong()) { "only 1-D bool tensors are supported" }
        return OrtValue(OnnxTensor.createTensor(env, data))
    }
}
