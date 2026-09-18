package dev.scenenote.nmt

import dev.scenenote.core.Diag
import okio.BufferedSink
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use
import kotlin.time.TimeSource

/**
 * 下载后一次性修补 Marian 系 decoder 的 ONNX int8 导出（optimum + quantize_dynamic）：
 * 输出层 lm_head 与词嵌入共享 [V × d] 权重，导出时被留成 `Transpose(uint8) → DequantizeLinear → MatMul(float)`
 * （FuguMT 的无 cache 分支甚至直接 `Transpose(float 权重) → MatMul`），每解码一步都要把整张表反量化成 float（opus-mt 133 MB）再做 float 矩阵乘——
 * 桌面实测 decoder 每句 110 ms、vivo 每方向常驻 ≈ 470 MB。这里改写成模型其它层用的动态量化模式：
 * `DynamicQuantizeLinear(x) → MatMulInteger(x_q, Wᵀ_q) → Cast → Mul(scale)`，转置后的 uint8 权重作为初始化张量存一份（文件 +V×d 字节）。
 * 桌面实测同一句 113 → 10 ms，输出逐 token 一致。
 *
 * 实现是流式的 protobuf 改写：先在顶层图（以及 If 节点的子图）里按模式找到那几个节点与权重名，其余字节原样切片拷贝；不依赖 onnx 库。
 * 输出写到原文件旁的 `*.lmhead.onnx`，装载失败会删掉退回原文件。
 */
object NmtModelPatch {
    const val SUFFIX = ".lmhead.onnx"
    private val fs = FileSystem.SYSTEM

    /** 返回应装载的 decoder 路径：补丁文件已存在 → 用它；能生成 → 生成后用它；模式对不上 / 出错 → 原文件。 */
    fun ensure(original: String): String {
        val patched = original.removeSuffix(".onnx") + SUFFIX
        if (fs.exists(patched.toPath())) return patched
        val t0 = TimeSource.Monotonic.markNow()
        return try {
            val input = fs.read(original.toPath()) { readByteArray() }
            val out = patch(input) ?: run { Diag.log("nmt", "patch: lm_head pattern not found in $original, using as is"); return original }
            val tmp = "$patched.part"
            fs.sink(tmp.toPath()).buffer().use { sink -> out.writeTo(sink) }
            fs.atomicMove(tmp.toPath(), patched.toPath())
            Diag.log("nmt", "patched decoder → $patched (${out.size / 1_000_000} MB) in ${t0.elapsedNow().inWholeMilliseconds}ms")
            patched
        } catch (t: Throwable) {
            Diag.log("nmt", "patch failed for $original: ${t.message}")
            runCatching { fs.delete("$patched.part".toPath(), mustExist = false) }
            original
        }
    }

    /** 装载补丁文件失败时调用：删掉它，下次退回原文件。 */
    fun discard(patchedPath: String) { if (patchedPath.endsWith(SUFFIX)) runCatching { fs.delete(patchedPath.toPath(), mustExist = false) } }

    // ---------- 改写 ----------

    private class TensorRef(val name: String, val dtype: Int, val dims: List<Long>, val rawOff: Int, val rawLen: Int)
    /** 找到的模式：uint8 权重 + 它的 scale / zero_point + （可选）同形的 float 权重。 */
    private class Target(val wq: TensorRef, val scale: String, val zp: String, val wf: String?) {
        val wtName = wq.name + "_T"
        fun isWeightInput(name: String) = name == wq.name || name == wf
    }
    private class Node(val opType: String, val inputs: List<String>, val outputs: List<String>)

    /** 返回 null = 没找到可改写的模式（不是这种导出，或已改过）。 */
    internal fun patch(model: ByteArray): Chunks? {
        val r = ProtoReader(model, 0, model.size)
        var gStart = -1; var gEnd = -1
        while (r.hasMore()) {
            val tag = r.varint().toInt()
            if ((tag ushr 3) == 7 && (tag and 7) == 2) { val len = r.varint().toInt(); gStart = r.pos; gEnd = r.pos + len; r.pos += len } else r.skip(tag and 7)
        }
        if (gStart < 0) return null
        val inits = readInitializers(model, gStart, gEnd)
        val target = discover(model, gStart, gEnd, inits) ?: return null
        val out = Chunks()
        var patched = false
        val top = ProtoReader(model, 0, model.size)
        while (top.hasMore()) {
            val start = top.pos
            val tag = top.varint().toInt(); val field = tag ushr 3; val wire = tag and 7
            if (field == 7 && wire == 2) {
                val len = top.varint().toInt(); val s = top.pos; top.pos += len
                val g = rewriteGraph(model, s, s + len, target, topLevel = true) { patched = true }
                out.tag(7, 2); out.varint(g.size); out.add(g)
            } else { top.skip(wire); out.slice(model, start, top.pos - start) }
        }
        return if (patched) out else null
    }

    /** 顶层 GraphProto 的 initializer（field 5）：名字、类型、形状、raw_data 位置。 */
    private fun readInitializers(buf: ByteArray, start: Int, end: Int): Map<String, TensorRef> {
        val out = HashMap<String, TensorRef>()
        val r = ProtoReader(buf, start, end)
        while (r.hasMore()) {
            val tag = r.varint().toInt()
            if ((tag ushr 3) == 5 && (tag and 7) == 2) {
                val len = r.varint().toInt(); val tStart = r.pos; r.pos += len
                val t = ProtoReader(buf, tStart, tStart + len)
                var name = ""; val dims = ArrayList<Long>(); var dtype = 0; var rawOff = -1; var rawLen = 0
                while (t.hasMore()) {
                    val tt = t.varint().toInt()
                    when (tt ushr 3) {
                        1 -> if (tt and 7 == 0) dims.add(t.varint()) else { val l = t.varint().toInt(); val e = t.pos + l; while (t.pos < e) dims.add(t.varint()) }   // 可能 packed
                        2 -> dtype = t.varint().toInt()
                        8 -> name = t.string()
                        9 -> { rawLen = t.varint().toInt(); rawOff = t.pos; t.pos += rawLen }
                        else -> t.skip(tt and 7)
                    }
                }
                out[name] = TensorRef(name, dtype, dims, rawOff, rawLen)
            } else r.skip(tag and 7)
        }
        return out
    }

    /** 在图（含 If 子图）里找 `DequantizeLinear(Transpose(uint8 二维初始化张量))`，取权重名与 scale / zero_point；再看有没有同形的 float 权重也被 Transpose。 */
    private fun discover(buf: ByteArray, start: Int, end: Int, inits: Map<String, TensorRef>): Target? {
        val transposeOf = HashMap<String, String>()   // Transpose 输出 → 输入名
        var found: Target? = null
        fun scan(s: Int, e: Int) {
            val r = ProtoReader(buf, s, e)
            while (r.hasMore()) {
                val tag = r.varint().toInt()
                if ((tag ushr 3) == 1 && (tag and 7) == 2) {
                    val len = r.varint().toInt(); val ns = r.pos; r.pos += len
                    val n = parseNode(buf, ns, ns + len)
                    when (n.opType) {
                        "Transpose" -> if (n.inputs.firstOrNull() in inits && n.outputs.isNotEmpty()) transposeOf[n.outputs[0]] = n.inputs[0]
                        "DequantizeLinear" -> {
                            val w = transposeOf[n.inputs.firstOrNull()]?.let { inits[it] }
                            if (found == null && w != null && w.dtype == 2 && w.dims.size == 2 && w.rawOff >= 0 && w.rawLen == (w.dims[0] * w.dims[1]).toInt() && n.inputs.size >= 3) {
                                val fname = w.name.removeSuffix("_quantized")
                                found = Target(w, n.inputs[1], n.inputs[2], inits[fname]?.takeIf { it.dtype == 1 && it.dims == w.dims }?.name)
                            }
                        }
                        "If" -> forEachSubgraph(buf, ns, ns + len) { gs, ge -> scan(gs, ge) }
                    }
                } else r.skip(tag and 7)
            }
        }
        scan(start, end)
        return found
    }

    private inline fun forEachSubgraph(buf: ByteArray, start: Int, end: Int, block: (Int, Int) -> Unit) {
        val r = ProtoReader(buf, start, end)
        while (r.hasMore()) {
            val tag = r.varint().toInt()
            if ((tag ushr 3) == 5 && (tag and 7) == 2) {
                val len = r.varint().toInt(); val a = ProtoReader(buf, r.pos, r.pos + len); r.pos += len
                while (a.hasMore()) {
                    val t = a.varint().toInt()
                    if ((t ushr 3) == 6 && (t and 7) == 2) { val gl = a.varint().toInt(); block(a.pos, a.pos + gl); a.pos += gl } else a.skip(t and 7)
                }
            } else r.skip(tag and 7)
        }
    }

    private fun rewriteGraph(buf: ByteArray, start: Int, end: Int, target: Target, topLevel: Boolean, onPatched: () -> Unit): Chunks {
        // 本图里要删的 Transpose / DequantizeLinear 输出名，与要替换的 MatMul 的第二输入名
        val transposeOuts = HashSet<String>(); val deqOuts = HashSet<String>(); var graphName = ""
        run {
            val scan = ProtoReader(buf, start, end)
            while (scan.hasMore()) {
                val tag = scan.varint().toInt()
                when {
                    (tag ushr 3) == 1 && (tag and 7) == 2 -> {
                        val len = scan.varint().toInt(); val n = parseNode(buf, scan.pos, scan.pos + len); scan.pos += len
                        if (n.opType == "Transpose" && n.inputs.firstOrNull()?.let(target::isWeightInput) == true) n.outputs.firstOrNull()?.let(transposeOuts::add)
                    }
                    (tag ushr 3) == 2 && (tag and 7) == 2 -> graphName = scan.string()
                    else -> scan.skip(tag and 7)
                }
            }
            val scan2 = ProtoReader(buf, start, end)
            while (scan2.hasMore()) {
                val tag = scan2.varint().toInt()
                if ((tag ushr 3) == 1 && (tag and 7) == 2) {
                    val len = scan2.varint().toInt(); val n = parseNode(buf, scan2.pos, scan2.pos + len); scan2.pos += len
                    if (n.opType == "DequantizeLinear" && n.inputs.firstOrNull() in transposeOuts) n.outputs.firstOrNull()?.let(deqOuts::add)
                } else scan2.skip(tag and 7)
            }
        }
        val prefix = "lm_head_q" + (if (graphName.isNotEmpty()) "/$graphName" else "")
        val out = Chunks()
        val r = ProtoReader(buf, start, end)
        while (r.hasMore()) {
            val fStart = r.pos
            val tag = r.varint().toInt(); val field = tag ushr 3; val wire = tag and 7
            if (field == 1 && wire == 2) {   // node
                val len = r.varint().toInt(); val nStart = r.pos; r.pos += len
                val n = parseNode(buf, nStart, nStart + len)
                when {
                    n.opType == "If" -> {
                        val rewritten = rewriteIfNode(buf, nStart, nStart + len, target, onPatched)
                        out.tag(1, 2); out.varint(rewritten.size); out.add(rewritten)
                    }
                    n.opType == "Transpose" && n.outputs.firstOrNull() in transposeOuts -> Unit   // 删
                    n.opType == "DequantizeLinear" && n.outputs.firstOrNull() in deqOuts -> Unit   // 删
                    n.opType == "MatMul" && n.inputs.size == 2 && (n.inputs[1] in deqOuts || n.inputs[1] in transposeOuts) -> {
                        val x = n.inputs[0]; val y = n.outputs[0]
                        out.node("DynamicQuantizeLinear", listOf(x), listOf("$prefix/x_q", "$prefix/x_scale", "$prefix/x_zp"), "$prefix/DynamicQuantizeLinear")
                        out.node("MatMulInteger", listOf("$prefix/x_q", target.wtName, "$prefix/x_zp", target.zp), listOf("$prefix/y_i32"), "$prefix/MatMulInteger")
                        out.node("Cast", listOf("$prefix/y_i32"), listOf("$prefix/y_f"), "$prefix/Cast", castTo = 1)
                        out.node("Mul", listOf("$prefix/x_scale", target.scale), listOf("$prefix/scale"), "$prefix/ScaleMul")
                        out.node("Mul", listOf("$prefix/y_f", "$prefix/scale"), listOf(y), "$prefix/MatMul")
                        onPatched()
                    }
                    else -> out.slice(buf, fStart, r.pos - fStart)
                }
            } else {
                r.skip(wire)
                out.slice(buf, fStart, r.pos - fStart)
            }
        }
        if (topLevel) {   // 追加转置后的权重 [cols × rows]
            val w = target.wq
            val rows = w.dims[0].toInt(); val cols = w.dims[1].toInt()
            val t = ByteArray(rows * cols)
            var src = w.rawOff
            for (i in 0 until rows) { var dst = i; for (j in 0 until cols) { t[dst] = buf[src++]; dst += rows } }
            val tensor = Chunks()
            tensor.tag(1, 0); tensor.varint(cols.toLong()); tensor.tag(1, 0); tensor.varint(rows.toLong())
            tensor.tag(2, 0); tensor.varint(2)   // UINT8
            tensor.bytesField(8, target.wtName.encodeToByteArray())
            tensor.bytesField(9, t)
            out.tag(5, 2); out.varint(tensor.size); out.add(tensor)
        }
        return out
    }

    /** If 节点：attribute（5）里 type=GRAPH 的 g（6）逐个改写，其余字段原样。 */
    private fun rewriteIfNode(buf: ByteArray, start: Int, end: Int, target: Target, onPatched: () -> Unit): Chunks {
        val out = Chunks()
        val r = ProtoReader(buf, start, end)
        while (r.hasMore()) {
            val fStart = r.pos
            val tag = r.varint().toInt(); val field = tag ushr 3; val wire = tag and 7
            if (field == 5 && wire == 2) {
                val len = r.varint().toInt(); val aStart = r.pos; r.pos += len
                val attr = Chunks()
                val a = ProtoReader(buf, aStart, aStart + len)
                while (a.hasMore()) {
                    val s = a.pos
                    val t = a.varint().toInt()
                    if ((t ushr 3) == 6 && (t and 7) == 2) {
                        val gl = a.varint().toInt(); val gs = a.pos; a.pos += gl
                        val g = rewriteGraph(buf, gs, gs + gl, target, topLevel = false, onPatched = onPatched)
                        attr.tag(6, 2); attr.varint(g.size); attr.add(g)
                    } else { a.skip(t and 7); attr.slice(buf, s, a.pos - s) }
                }
                out.tag(5, 2); out.varint(attr.size); out.add(attr)
            } else { r.skip(wire); out.slice(buf, fStart, r.pos - fStart) }
        }
        return out
    }

    private fun parseNode(buf: ByteArray, start: Int, end: Int): Node {
        val r = ProtoReader(buf, start, end)
        var op = ""; val ins = ArrayList<String>(4); val outs = ArrayList<String>(2)
        while (r.hasMore()) {
            val t = r.varint().toInt()
            when (t ushr 3) {
                1 -> ins.add(r.string())
                2 -> outs.add(r.string())
                4 -> op = r.string()
                else -> r.skip(t and 7)
            }
        }
        return Node(op, ins, outs)
    }

    /** 由原缓冲区切片 + 新字节拼成的输出；写出时不再拷贝上百 MB 的原始张量。 */
    internal class Chunks {
        private val parts = ArrayList<Any>()   // ByteArray | IntArray(off, len) 引用 base
        private var base: ByteArray? = null
        var size = 0L; private set
        private val small = ArrayList<Byte>(64)

        private fun flushSmall() { if (small.isNotEmpty()) { parts.add(small.toByteArray()); small.clear() } }
        fun slice(buf: ByteArray, off: Int, len: Int) { if (len == 0) return; flushSmall(); base = buf; parts.add(intArrayOf(off, len)); size += len }
        fun add(c: Chunks) { flushSmall(); c.flushSmall(); parts.addAll(c.parts); if (c.base != null) base = c.base; size += c.size }
        fun addBytes(b: ByteArray) { flushSmall(); parts.add(b); size += b.size }
        fun byte(b: Int) { small.add(b.toByte()); size++ }
        fun varint(v: Long) { var x = v; while (true) { val b = (x and 0x7F).toInt(); x = x ushr 7; if (x == 0L) { byte(b); return } else byte(b or 0x80) } }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun bytesField(field: Int, data: ByteArray) { tag(field, 2); varint(data.size.toLong()); addBytes(data) }
        fun node(op: String, inputs: List<String>, outputs: List<String>, name: String, castTo: Int? = null) {
            val n = Chunks()
            inputs.forEach { n.bytesField(1, it.encodeToByteArray()) }
            outputs.forEach { n.bytesField(2, it.encodeToByteArray()) }
            n.bytesField(3, name.encodeToByteArray())
            n.bytesField(4, op.encodeToByteArray())
            if (castTo != null) {   // AttributeProto { name="to", type=INT(2), i=castTo }
                val a = Chunks(); a.bytesField(1, "to".encodeToByteArray()); a.tag(20, 0); a.varint(2); a.tag(3, 0); a.varint(castTo.toLong())
                n.tag(5, 2); n.varint(a.size); n.add(a)
            }
            tag(1, 2); varint(n.size); add(n)
        }
        fun writeTo(sink: BufferedSink) {
            flushSmall()
            for (p in parts) when (p) {
                is ByteArray -> sink.write(p)
                is IntArray -> sink.write(base!!, p[0], p[1])
            }
        }
        fun toByteArray(): ByteArray { flushSmall(); val out = ByteArray(size.toInt()); var o = 0; for (p in parts) when (p) { is ByteArray -> { p.copyInto(out, o); o += p.size }; is IntArray -> { base!!.copyInto(out, o, p[0], p[0] + p[1]); o += p[1] } }; return out }
    }
}
