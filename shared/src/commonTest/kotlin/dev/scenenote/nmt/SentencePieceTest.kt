package dev.scenenote.nmt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 手写一个最小 ModelProto（proto2 二进制）：不带 charsmap，只验切分与空白规则。 */
private object ProtoWriter {
    private fun varint(v: Long, out: MutableList<Byte>) { var x = v; while (true) { val b = (x and 0x7F).toInt(); x = x ushr 7; if (x == 0L) { out.add(b.toByte()); return } else out.add((b or 0x80).toByte()) } }
    private fun bytes(field: Int, data: ByteArray, out: MutableList<Byte>) { varint(((field shl 3) or 2).toLong(), out); varint(data.size.toLong(), out); data.forEach { out.add(it) } }
    private fun piece(text: String, score: Float, type: Int): ByteArray {
        val o = mutableListOf<Byte>()
        bytes(1, text.encodeToByteArray(), o)
        varint(((2 shl 3) or 5).toLong(), o); val bits = score.toRawBits(); for (k in 0 until 4) o.add(((bits ushr (8 * k)) and 0xFF).toByte())
        varint(((3 shl 3) or 0).toLong(), o); varint(type.toLong(), o)
        return o.toByteArray()
    }
    fun model(pieces: List<Triple<String, Float, Int>>): ByteArray {
        val o = mutableListOf<Byte>()
        pieces.forEach { (p, s, t) -> bytes(1, piece(p, s, t), o) }
        // normalizer_spec：name 只填个字段，其余用默认（dummy prefix / 折叠 / 转义都开）
        val ns = mutableListOf<Byte>(); bytes(1, "identity".encodeToByteArray(), ns)
        bytes(3, ns.toByteArray(), o)
        return o.toByteArray()
    }
}

class SentencePieceTest {
    private val sp = SentencePiece.parse(ProtoWriter.model(listOf(
        Triple("<unk>", 0f, 2), Triple("<s>", 0f, 3), Triple("</s>", 0f, 3),
        Triple("▁he", -1f, 1), Triple("llo", -2f, 1), Triple("▁hello", -1.5f, 1),
        Triple("▁w", -1f, 1), Triple("or", -1f, 1), Triple("ld", -1f, 1), Triple("▁world", -2f, 1),
        Triple("▁", -5f, 1), Triple("h", -6f, 1), Triple("e", -6f, 1), Triple("l", -6f, 1), Triple("o", -6f, 1),
    )))

    @Test fun viterbiPicksBestScoringSegmentation() {
        assertEquals(listOf("▁hello", "▁world"), sp.encodeAsPieces("hello world"))
    }

    @Test fun whitespaceRulesFollowSentencePiece() {
        assertEquals(listOf("▁hello", "▁world"), sp.encodeAsPieces("  hello   world  "))
        assertEquals(emptyList(), sp.encodeAsPieces("   "))
        assertEquals(emptyList(), sp.encodeAsPieces(""))
    }

    @Test fun unknownCharactersSurfaceAsTheirOwnPiece() {
        // "x" 没有任何 piece 覆盖 → 原字面输出（对应 <unk>）；代理对整体作为一个 unk
        assertEquals(listOf("▁hello", "▁", "x"), sp.encodeAsPieces("hello x"))
        assertEquals(listOf("▁", "😀", "▁hello"), sp.encodeAsPieces("😀 hello"))
    }

    @Test fun decodeRestoresSpacesAndDropsDummyPrefix() {
        assertEquals("hello world", sp.decodePieces(listOf("▁hello", "▁world")))
        assertEquals("hello ⁇ world", sp.decodePieces(listOf("▁hello", "▁", "<unk>", "▁world")).replace("  ", " "))
    }

    @Test fun cleanUpMatchesTransformers() {
        assertEquals("Hello, world. I'm here!", MarianTokenizer.cleanUp("Hello , world . I 'm here !"))
    }

    /** 真实 opus-mt 模型：与 Python sentencepiece 的输出逐条对齐（SCENENOTE_NMT_MODELS 未设则跳过）。 */
    @Test fun realModelsMatchPythonReference() {
        val root = testEnv("SCENENOTE_NMT_MODELS") ?: return
        val fs = FileSystem.SYSTEM
        val cases = Json.parseToJsonElement(fs.read("$root/tokenizer-cases.json".toPath()) { readUtf8() }).jsonObject
        var checked = 0
        for ((repo, arr) in cases) {
            val dir = "$root/$repo"
            if (!fs.exists("$dir/source.spm".toPath())) continue
            val src = SentencePiece.parse(fs.read("$dir/source.spm".toPath()) { readByteArray() })
            val tgt = SentencePiece.parse(fs.read("$dir/target.spm".toPath()) { readByteArray() })
            val tok = MarianTokenizer(src, tgt, MarianTokenizer.parseVocab(fs.read("$dir/vocab.json".toPath()) { readUtf8() }))
            for (c in arr.jsonArray) {
                val o = c.jsonObject
                val text = o.getValue("text").jsonPrimitive.content
                val pieces = o.getValue("pieces").jsonArray.map { it.jsonPrimitive.content }
                val ids = o.getValue("ids").jsonArray.map { it.jsonPrimitive.int }
                assertEquals(pieces, src.encodeAsPieces(text), "$repo pieces for '$text'")
                assertEquals(ids, tok.encode(text).toList(), "$repo ids for '$text'")
                checked++
            }
        }
        assertTrue(checked > 0, "no cases checked")
        println("SentencePiece: $checked cases matched python reference")
    }
}
