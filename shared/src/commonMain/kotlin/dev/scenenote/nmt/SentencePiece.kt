package dev.scenenote.nmt

/**
 * SentencePiece unigram 模型（opus-mt 的 source.spm / target.spm）的纯 Kotlin 推理实现，两端一致、不带 native 依赖。
 * 覆盖：ModelProto 解析（只取 pieces + normalizer_spec）、nmt_nfkc 预编译字符映射（Darts 双数组 trie）、
 * 空白规则（dummy prefix / 折叠 / ▁ 转义）、Viterbi 最优切分、pieces → 文本。
 * 与官方 C++ 的差异只在浮点并列时的取舍；opus-mt 的 6 个模型都是 unigram + nmt_nfkc，没有 byte_fallback / user_defined。
 */
class SentencePiece private constructor(
    private val pieces: Array<String>,
    private val scores: FloatArray,
    private val types: IntArray,
    private val normalizer: CharsMap?,
    private val addDummyPrefix: Boolean,
    private val removeExtraWhitespaces: Boolean,
    private val escapeWhitespaces: Boolean,
) {
    /** 只有 NORMAL / USER_DEFINED 类型进 trie（控制符与 <unk> 不能被文本命中）。 */
    private val pieceToId = HashMap<String, Int>(pieces.size * 2)
    private val maxPieceLen: Int
    private val unkScore: Float
    val unkId: Int
    val size: Int get() = pieces.size

    init {
        var maxLen = 1
        var minScore = Float.MAX_VALUE
        var unk = 0
        for (i in pieces.indices) {
            when (types[i]) {
                TYPE_NORMAL, TYPE_USER_DEFINED -> { pieceToId[pieces[i]] = i; if (pieces[i].length > maxLen) maxLen = pieces[i].length; if (scores[i] < minScore) minScore = scores[i] }
                TYPE_UNKNOWN -> unk = i
            }
        }
        maxPieceLen = maxLen
        unkScore = (if (minScore == Float.MAX_VALUE) 0f else minScore) - UNK_PENALTY
        unkId = unk
    }

    fun piece(id: Int): String = pieces[id]

    /** 文本 → pieces（含 ▁ 前缀）；与 `sp.encode(text, out_type=str)` 对齐。找不到的字以原字面输出（对应 <unk>），相邻的合成一个。 */
    fun encodeAsPieces(text: String): List<String> {
        val s = normalize(text)
        val n = s.length
        if (n == 0) return emptyList()
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val back = IntArray(n + 1) { -1 }
        best[0] = 0f
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            val charLen = if (s[i].isHighSurrogate() && i + 1 < n && s[i + 1].isLowSurrogate()) 2 else 1
            var hasSingle = false
            var len = 1
            while (len <= maxPieceLen && i + len <= n) {
                // 不在代理对中间切
                if (s[i + len - 1].isHighSurrogate() && i + len < n) { len++; continue }
                val id = pieceToId[s.substring(i, i + len)]
                if (id != null) {
                    val sc = best[i] + scores[id]
                    if (sc > best[i + len]) { best[i + len] = sc; back[i + len] = i }
                    if (len == charLen) hasSingle = true
                }
                len++
            }
            if (!hasSingle) {
                val sc = best[i] + unkScore
                if (sc > best[i + charLen]) { best[i + charLen] = sc; back[i + charLen] = i }
            }
        }
        val out = ArrayList<String>()
        var pos = n
        var prevUnk = false
        while (pos > 0) {
            val from = back[pos]
            val piece = s.substring(from, pos)
            val unk = piece !in pieceToId
            // 官方实现把相邻的未知字合成一个 <unk>（"のテキスト" 整体一个），不然一个生僻词会变成一串 <unk>
            if (unk && prevUnk) out[out.lastIndex] = piece + out[out.lastIndex] else out.add(piece)
            prevUnk = unk
            pos = from
        }
        out.reverse()
        return out
    }

    /** pieces → 文本：▁ 还原为空格、去掉句首 dummy prefix；控制符（<s> </s>）丢弃、<unk> 用 " ⁇ " 占位（与官方 unk_surface 一致）。 */
    fun decodePieces(pieces: List<String>): String {
        val sb = StringBuilder()
        for (p in pieces) {
            val id = pieceToId[p]
            when {
                id != null -> sb.append(p)
                p == "<unk>" -> sb.append(" ⁇ ")
                p == "<s>" || p == "</s>" || p == "<pad>" -> Unit
                else -> sb.append(p)   // 词表里有、spm 里没有的 piece（Marian 联合词表）原样输出
            }
        }
        var text = sb.toString().replace(SPACE, " ")
        if (addDummyPrefix && text.startsWith(" ")) text = text.substring(1)
        return text
    }

    /** 官方 Normalizer::Normalize：跳头部空白 → 加 ▁ 前缀 → 逐前缀查 charsmap → 折叠连续空白 → 去尾部空白。 */
    internal fun normalize(text: String): String {
        val inp = text.encodeToByteArray()
        val n = inp.size
        var pos = 0
        val rep = Prefix()
        if (removeExtraWhitespaces) {
            while (pos < n) {
                normalizePrefix(inp, pos, rep)
                if (!(rep.len == 1 && rep.byteAt(0) == ' '.code.toByte())) break
                pos += rep.consumed
            }
        }
        if (pos >= n) return ""
        val out = ByteBuilder(n * 3 + 3)
        if (addDummyPrefix) out.appendSpace()
        var prevSpace = removeExtraWhitespaces
        while (pos < n) {
            normalizePrefix(inp, pos, rep)
            var s = 0
            if (removeExtraWhitespaces) while (prevSpace && s < rep.len && rep.byteAt(s) == ' '.code.toByte()) s++
            if (s < rep.len) {
                for (k in s until rep.len) {
                    val b = rep.byteAt(k)
                    if (escapeWhitespaces && b == ' '.code.toByte()) out.appendSpace() else out.append(b)
                }
                prevSpace = rep.byteAt(rep.len - 1) == ' '.code.toByte()
            }
            pos += rep.consumed
            if (!removeExtraWhitespaces) prevSpace = false
        }
        if (removeExtraWhitespaces) {
            if (escapeWhitespaces) while (out.endsWithSpace()) out.dropSpace() else while (out.endsWithByte(' '.code.toByte())) out.drop(1)
        }
        return out.toStringUtf8()
    }

    /** 一次前缀归一化：charsmap 里最长命中 → 替换串；否则原样拷一个 UTF-8 字符（非法字节 → U+FFFD）。 */
    private fun normalizePrefix(inp: ByteArray, pos: Int, out: Prefix) {
        val cm = normalizer
        if (cm != null) {
            var longestLen = 0; var longestVal = 0
            val trie = cm.trie
            var nodePos = 0
            var unit = trie[0]
            nodePos = nodePos xor offset(unit)
            var i = pos
            while (i < inp.size) {
                val c = inp[i].toInt() and 0xFF
                nodePos = nodePos xor c
                unit = trie[nodePos]
                if (label(unit) != c) break
                nodePos = nodePos xor offset(unit)
                if (hasLeaf(unit)) {
                    val len = i - pos + 1
                    if (longestLen == 0 || len > longestLen) { longestLen = len; longestVal = value(trie[nodePos]) }
                }
                i++
            }
            if (longestLen > 0) {
                var end = longestVal
                val norm = cm.normalized
                while (end < norm.size && norm[end] != 0.toByte()) end++
                out.set(norm, longestVal, end - longestVal, longestLen)
                return
            }
        }
        val len = utf8Len(inp, pos)
        if (len == 0) out.set(REPLACEMENT, 0, REPLACEMENT.size, 1) else out.set(inp, pos, len, len)
    }

    private class Prefix {
        var src: ByteArray = ByteArray(0); var off = 0; var len = 0; var consumed = 0
        fun set(src: ByteArray, off: Int, len: Int, consumed: Int) { this.src = src; this.off = off; this.len = len; this.consumed = consumed }
        fun byteAt(i: Int): Byte = src[off + i]
    }

    private class ByteBuilder(cap: Int) {
        private var buf = ByteArray(cap); var size = 0; private set
        fun append(b: Byte) { if (size == buf.size) buf = buf.copyOf(buf.size * 2 + 8); buf[size++] = b }
        fun appendSpace() { append(0xE2.toByte()); append(0x96.toByte()); append(0x81.toByte()) }
        fun endsWithSpace(): Boolean = size >= 3 && buf[size - 3] == 0xE2.toByte() && buf[size - 2] == 0x96.toByte() && buf[size - 1] == 0x81.toByte()
        fun endsWithByte(b: Byte): Boolean = size >= 1 && buf[size - 1] == b
        fun dropSpace() { size -= 3 }
        fun drop(n: Int) { size -= n }
        fun toStringUtf8(): String = buf.decodeToString(0, size)
    }

    /** 预编译字符映射：Darts 双数组（uint32[]）+ 以 \0 分隔的替换串池。 */
    internal class CharsMap(val trie: IntArray, val normalized: ByteArray)

    companion object {
        /** U+2581 LOWER ONE EIGHTH BLOCK，SentencePiece 的空格符。 */
        const val SPACE = "▁"
        private const val UNK_PENALTY = 10f
        private const val TYPE_NORMAL = 1; private const val TYPE_UNKNOWN = 2; private const val TYPE_USER_DEFINED = 4
        private val REPLACEMENT = byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())

        // Darts::DoubleArrayUnit
        private fun hasLeaf(u: Int) = ((u ushr 8) and 1) == 1
        private fun value(u: Int) = u and 0x7FFFFFFF
        private fun label(u: Int) = u and (Int.MIN_VALUE or 0xFF)
        private fun offset(u: Int) = (u ushr 10) shl ((u and (1 shl 9)) ushr 6)

        /** UTF-8 首字节决定长度并校验续字节；非法返回 0。 */
        private fun utf8Len(b: ByteArray, pos: Int): Int {
            val c = b[pos].toInt() and 0xFF
            val len = when {
                c < 0x80 -> 1
                c in 0xC2..0xDF -> 2
                c in 0xE0..0xEF -> 3
                c in 0xF0..0xF4 -> 4
                else -> return 0
            }
            if (pos + len > b.size) return 0
            for (k in 1 until len) if ((b[pos + k].toInt() and 0xC0) != 0x80) return 0
            return len
        }

        /** 解析 sentencepiece_model.proto 的 ModelProto（proto2 二进制）。 */
        fun parse(bytes: ByteArray): SentencePiece {
            val pieces = ArrayList<String>(32_000); val scores = ArrayList<Float>(32_000); val types = ArrayList<Int>(32_000)
            var charsMap: CharsMap? = null
            var addDummyPrefix = true; var removeExtra = true; var escapeWs = true
            val r = ProtoReader(bytes, 0, bytes.size)
            while (r.hasMore()) {
                val tag = r.varint().toInt(); val field = tag ushr 3; val wire = tag and 7
                when {
                    field == 1 && wire == 2 -> {   // repeated SentencePiece pieces
                        val sub = r.subMessage()
                        var piece = ""; var score = 0f; var type = TYPE_NORMAL
                        while (sub.hasMore()) {
                            val t = sub.varint().toInt()
                            when (t ushr 3) {
                                1 -> piece = sub.string()
                                2 -> score = Float.fromBits(sub.fixed32())
                                3 -> type = sub.varint().toInt()
                                else -> sub.skip(t and 7)
                            }
                        }
                        pieces.add(piece); scores.add(score); types.add(type)
                    }
                    field == 3 && wire == 2 -> {   // NormalizerSpec
                        val sub = r.subMessage()
                        while (sub.hasMore()) {
                            val t = sub.varint().toInt()
                            when (t ushr 3) {
                                2 -> charsMap = decodeCharsMap(sub.bytes())
                                3 -> addDummyPrefix = sub.varint() != 0L
                                4 -> removeExtra = sub.varint() != 0L
                                5 -> escapeWs = sub.varint() != 0L
                                else -> sub.skip(t and 7)
                            }
                        }
                    }
                    else -> r.skip(wire)
                }
            }
            return SentencePiece(pieces.toTypedArray(), scores.toFloatArray(), types.toIntArray(), charsMap, addDummyPrefix, removeExtra, escapeWs)
        }

        private fun decodeCharsMap(blob: ByteArray): CharsMap? {
            if (blob.size < 4) return null
            val trieBytes = (blob[0].toInt() and 0xFF) or ((blob[1].toInt() and 0xFF) shl 8) or ((blob[2].toInt() and 0xFF) shl 16) or ((blob[3].toInt() and 0xFF) shl 24)
            val trie = IntArray(trieBytes / 4)
            var p = 4
            for (i in trie.indices) {
                trie[i] = (blob[p].toInt() and 0xFF) or ((blob[p + 1].toInt() and 0xFF) shl 8) or ((blob[p + 2].toInt() and 0xFF) shl 16) or ((blob[p + 3].toInt() and 0xFF) shl 24)
                p += 4
            }
            return CharsMap(trie, blob.copyOfRange(p, blob.size))
        }
    }
}

/** 最小 protobuf 读取器（varint / fixed32 / length-delimited）。 */
internal class ProtoReader(private val buf: ByteArray, var pos: Int, private val end: Int) {
    fun hasMore() = pos < end
    fun varint(): Long {
        var shift = 0; var result = 0L
        while (true) {
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
    fun fixed32(): Int { val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24); pos += 4; return v }
    fun bytes(): ByteArray { val len = varint().toInt(); val out = buf.copyOfRange(pos, pos + len); pos += len; return out }
    fun string(): String { val len = varint().toInt(); val s = buf.decodeToString(pos, pos + len); pos += len; return s }
    fun subMessage(): ProtoReader { val len = varint().toInt(); val r = ProtoReader(buf, pos, pos + len); pos += len; return r }
    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> { val len = varint().toInt(); pos += len }
            5 -> pos += 4
            else -> error("unsupported protobuf wire type $wire")
        }
    }
}
