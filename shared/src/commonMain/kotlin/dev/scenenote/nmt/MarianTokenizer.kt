package dev.scenenote.nmt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Marian 分词（transformers `MarianTokenizer` 的推理子集）：源 / 目标各一个 spm 切 pieces，id 走联合词表 vocab.json；
 * 句尾补 </s>（id 0），目标语言标记（`>>cmn_Hans<<`）作为独立 token 放句首。
 */
class MarianTokenizer(
    val source: SentencePiece,
    val target: SentencePiece,
    private val vocab: Map<String, Int>,
) {
    private val idToPiece: Array<String?> = arrayOfNulls<String>((vocab.values.maxOrNull() ?: 0) + 1).also { arr -> vocab.forEach { (p, id) -> arr[id] = p } }
    val eosId: Int = vocab["</s>"] ?: 0
    val unkId: Int = vocab["<unk>"] ?: 1
    val padId: Int = vocab["<pad>"] ?: (idToPiece.size - 1)
    val vocabSize: Int get() = idToPiece.size

    fun langTokenId(token: String?): Int? = token?.let { vocab[it] }

    /** 文本 → ids（[langToken] + pieces + </s>）。 */
    fun encode(text: String, langToken: String? = null): IntArray {
        val pieces = source.encodeAsPieces(text)
        val out = IntArray(pieces.size + 1 + (if (langToken != null) 1 else 0))
        var i = 0
        if (langToken != null) out[i++] = vocab[langToken] ?: error("language token $langToken not in vocab")
        for (p in pieces) out[i++] = vocab[p] ?: unkId
        out[i] = eosId
        return out
    }

    /** ids → 文本：跳过 pad / </s>，目标 spm 拼回，再做 transformers 的 clean_up_tokenization。 */
    fun decode(ids: IntArray): String {
        val pieces = ArrayList<String>(ids.size)
        for (id in ids) {
            if (id == eosId || id == padId) continue
            val p = idToPiece.getOrNull(id) ?: continue
            pieces.add(p)
        }
        return cleanUp(target.decodePieces(pieces).trim())
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseVocab(text: String): Map<String, Int> = json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.int }

        /** transformers `clean_up_tokenization`。 */
        fun cleanUp(s: String): String = s
            .replace(" .", ".").replace(" ?", "?").replace(" !", "!").replace(" ,", ",")
            .replace(" ' ", "'").replace(" n't", "n't").replace(" 'm", "'m").replace(" 's", "'s").replace(" 've", "'ve").replace(" 're", "'re")
    }
}
