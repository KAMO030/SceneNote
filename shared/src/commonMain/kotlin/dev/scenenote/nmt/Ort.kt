package dev.scenenote.nmt

/**
 * onnxruntime 的最小跨平台门面（core/nmt 只需要：建会话、喂 int64 / float / bool 张量、跑一次、读回 float）。
 * Android 走 ai.onnxruntime Java API（onnxruntime-android AAR），iOS 走 C API cinterop；两边都是 sherpa-onnx 用的同一份 ORT 1.28.2。
 * 张量句柄持有原生内存，用完必须 [OrtValue.close]；KV cache 逐步在解码循环里传递，不经 Kotlin 数组拷贝。
 */
expect class OrtValue : AutoCloseable {
    val shape: LongArray
    /** 拷出全部元素（只对 float 张量有意义）。 */
    fun floats(): FloatArray
    override fun close()
}

expect class OrtSession : AutoCloseable {
    val inputNames: List<String>
    val outputNames: List<String>
    /** 跑一次；返回的每个 [OrtValue] 由调用方负责 close。 */
    fun run(inputs: Map<String, OrtValue>, outputs: List<String>): Map<String, OrtValue>
    override fun close()
}

expect object Ort {
    val version: String
    /**
     * [lowMemory]：不预打包权重（MatMulInteger 的 B 矩阵不再复制一份内核布局，每方向省 ≈ 100 MB，解码慢 ≈ 1.5×）、
     * 不记内存模式（KV cache 每步形状都变，模式记录只会让 arena 越长越大）。
     */
    fun session(modelPath: String, numThreads: Int, lowMemory: Boolean = false): OrtSession
    fun tensor(data: FloatArray, shape: LongArray): OrtValue
    fun tensor(data: LongArray, shape: LongArray): OrtValue
    fun tensor(data: BooleanArray, shape: LongArray): OrtValue
}
