package dev.scenenote.nmt

import dev.scenenote.ort.native.OrtAllocator
import cnames.structs.OrtEnv
import cnames.structs.OrtStatus
import cnames.structs.OrtTensorTypeAndShapeInfo
import cnames.structs.OrtSession as COrtSession
import cnames.structs.OrtSessionOptions
import cnames.structs.OrtValue as COrtValue
import dev.scenenote.ort.native.ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL
import dev.scenenote.ort.native.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT
import dev.scenenote.ort.native.ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64
import dev.scenenote.ort.native.ONNXTensorElementDataType
import dev.scenenote.ort.native.ORT_API_VERSION
import dev.scenenote.ort.native.ORT_ENABLE_ALL
import dev.scenenote.ort.native.ORT_LOGGING_LEVEL_WARNING
import dev.scenenote.ort.native.OrtApi
import dev.scenenote.ort.native.OrtGetApiBase
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.memcpy
import platform.posix.size_tVar

/** iOS：onnxruntime C API（函数指针表 OrtApi），静态库随 sherpa cinterop 一起链进 Shared.framework。 */
private object OrtC {
    val api: OrtApi = (OrtGetApiBase()!!.pointed.GetApi!!(ORT_API_VERSION.convert()) ?: error("onnxruntime C API v$ORT_API_VERSION unavailable")).pointed
    val version: String = OrtGetApiBase()!!.pointed.GetVersionString!!()!!.toKString()
    val env: CPointer<OrtEnv> = memScoped {
        val out = alloc<CPointerVar<OrtEnv>>()
        check(api.CreateEnv!!(ORT_LOGGING_LEVEL_WARNING, "scenenote".cstr.ptr, out.ptr))
        out.value!!
    }
    val allocator: CPointer<OrtAllocator> = memScoped {
        val out = alloc<CPointerVar<OrtAllocator>>()
        check(api.GetAllocatorWithDefaultOptions!!(out.ptr))
        out.value!!
    }

    fun check(status: CPointer<OrtStatus>?) {
        if (status == null) return
        val msg = api.GetErrorMessage!!(status)?.toKString() ?: "unknown"
        api.ReleaseStatus!!(status)
        throw IllegalStateException("onnxruntime: $msg")
    }

    /** ORT 分配、拷入数据；零元素张量（KV cache 首步）只建不拷。 */
    fun create(shape: LongArray, type: ONNXTensorElementDataType, byteSize: Int, fill: (COpaquePointer) -> Unit): OrtValue = memScoped {
        val dims = allocArray<LongVar>(shape.size)
        shape.forEachIndexed { i, d -> dims[i] = d }
        val out = alloc<CPointerVar<COrtValue>>()
        check(api.CreateTensorAsOrtValue!!(allocator, dims, shape.size.convert(), type, out.ptr))
        val v = out.value!!
        if (byteSize > 0) {
            val data = alloc<COpaquePointerVar>()
            try { check(api.GetTensorMutableData!!(v, data.ptr)) } catch (t: Throwable) { api.ReleaseValue!!(v); throw t }
            fill(data.value!!)
        }
        OrtValue(v)
    }
}

actual class OrtValue internal constructor(internal var ptr: CPointer<COrtValue>?) : AutoCloseable {
    actual val shape: LongArray by lazy {
        memScoped {
            val p = ptr ?: error("OrtValue closed")
            val info = alloc<CPointerVar<OrtTensorTypeAndShapeInfo>>()
            OrtC.check(OrtC.api.GetTensorTypeAndShape!!(p, info.ptr))
            try {
                val n = alloc<size_tVar>()
                OrtC.check(OrtC.api.GetDimensionsCount!!(info.value, n.ptr))
                val count = n.value.toInt()
                val dims = allocArray<LongVar>(count)
                OrtC.check(OrtC.api.GetDimensions!!(info.value, dims, count.convert()))
                LongArray(count) { dims[it] }
            } finally { OrtC.api.ReleaseTensorTypeAndShapeInfo!!(info.value) }
        }
    }

    actual fun floats(): FloatArray = memScoped {
        val p = ptr ?: error("OrtValue closed")
        val n = shape.fold(1L) { a, b -> a * b }.toInt()
        if (n == 0) return FloatArray(0)
        val data = alloc<COpaquePointerVar>()
        OrtC.check(OrtC.api.GetTensorMutableData!!(p, data.ptr))
        val out = FloatArray(n)
        out.usePinned { memcpy(it.addressOf(0), data.value, (n * 4).convert()) }
        out
    }

    actual override fun close() { ptr?.let { OrtC.api.ReleaseValue!!(it) }; ptr = null }
}

actual class OrtSession internal constructor(private var ptr: CPointer<COrtSession>?) : AutoCloseable {
    actual val inputNames: List<String> = names(input = true)
    actual val outputNames: List<String> = names(input = false)

    private fun names(input: Boolean): List<String> = memScoped {
        val p = ptr ?: error("OrtSession closed")
        val cnt = alloc<size_tVar>()
        OrtC.check(if (input) OrtC.api.SessionGetInputCount!!(p, cnt.ptr) else OrtC.api.SessionGetOutputCount!!(p, cnt.ptr))
        List(cnt.value.toInt()) { i ->
            val out = alloc<CPointerVar<ByteVar>>()
            OrtC.check(if (input) OrtC.api.SessionGetInputName!!(p, i.convert(), OrtC.allocator, out.ptr) else OrtC.api.SessionGetOutputName!!(p, i.convert(), OrtC.allocator, out.ptr))
            val s = out.value!!.toKString()
            OrtC.check(OrtC.api.AllocatorFree!!(OrtC.allocator, out.value))
            s
        }
    }

    actual fun run(inputs: Map<String, OrtValue>, outputs: List<String>): Map<String, OrtValue> = memScoped {
        val p = ptr ?: error("OrtSession closed")
        val nIn = inputs.size; val nOut = outputs.size
        val inNames = allocArray<CPointerVar<ByteVar>>(nIn)
        val inVals = allocArray<CPointerVar<COrtValue>>(nIn)
        inputs.entries.forEachIndexed { i, (k, v) -> inNames[i] = k.cstr.ptr; inVals[i] = v.ptr ?: error("input $k closed") }
        val outNames = allocArray<CPointerVar<ByteVar>>(nOut)
        outputs.forEachIndexed { i, n -> outNames[i] = n.cstr.ptr }
        val outVals = allocArray<CPointerVar<COrtValue>>(nOut)   // 置零 = 让 ORT 分配输出
        OrtC.check(OrtC.api.Run!!(p, null, inNames, inVals, nIn.convert(), outNames, nOut.convert(), outVals))
        buildMap(nOut) { outputs.forEachIndexed { i, n -> put(n, OrtValue(outVals[i] ?: error("output $n missing"))) } }
    }

    actual override fun close() { ptr?.let { OrtC.api.ReleaseSession!!(it) }; ptr = null }
}

actual object Ort {
    actual val version: String get() = OrtC.version

    actual fun session(modelPath: String, numThreads: Int, lowMemory: Boolean): OrtSession = memScoped {
        val opts = alloc<CPointerVar<OrtSessionOptions>>()
        OrtC.check(OrtC.api.CreateSessionOptions!!(opts.ptr))
        try {
            OrtC.check(OrtC.api.SetIntraOpNumThreads!!(opts.value, numThreads))
            OrtC.check(OrtC.api.SetSessionGraphOptimizationLevel!!(opts.value, ORT_ENABLE_ALL))
            if (lowMemory) {
                OrtC.check(OrtC.api.AddSessionConfigEntry!!(opts.value, "session.disable_prepacking".cstr.ptr, "1".cstr.ptr))
                OrtC.check(OrtC.api.DisableMemPattern!!(opts.value))
            }
            val out = alloc<CPointerVar<COrtSession>>()
            OrtC.check(OrtC.api.CreateSession!!(OrtC.env, modelPath.cstr.ptr, opts.value, out.ptr))
            OrtSession(out.value!!)
        } finally { OrtC.api.ReleaseSessionOptions!!(opts.value) }
    }

    actual fun tensor(data: FloatArray, shape: LongArray): OrtValue = OrtC.create(shape, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, data.size * 4) { dst ->
        data.usePinned { memcpy(dst, it.addressOf(0), (data.size * 4).convert()) }
    }
    actual fun tensor(data: LongArray, shape: LongArray): OrtValue = OrtC.create(shape, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, data.size * 8) { dst ->
        data.usePinned { memcpy(dst, it.addressOf(0), (data.size * 8).convert()) }
    }
    actual fun tensor(data: BooleanArray, shape: LongArray): OrtValue = OrtC.create(shape, ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL, data.size) { dst ->
        val bytes = dst.reinterpret<ByteVar>()
        data.forEachIndexed { i, b -> bytes[i] = if (b) 1 else 0 }
    }
}
