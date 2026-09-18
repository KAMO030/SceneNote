package dev.scenenote.nmt

import dev.scenenote.core.Diag
import dev.scenenote.core.model.Lang
import dev.scenenote.core.platform.MemoryTier
import dev.scenenote.models.ModelPack
import dev.scenenote.models.ModelStore
import dev.scenenote.translate.MtFailed
import dev.scenenote.translate.MtRequest
import dev.scenenote.translate.MtResult
import dev.scenenote.translate.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.time.TimeSource

/**
 * 端侧翻译（core/nmt，08 篇 §3 / 13 篇 I3.5）：opus-mt ONNX int8 + 自实现 SentencePiece，ORT 贪心解码带 KV cache。
 * 零 Key 时的默认离线档，也是云端 1.5 s 超时后的降级目标（[dev.scenenote.translate.FastTranslator]）。
 * 一次只跑一句（[mutex]）：手机上 NMT 吃满 CPU，并发只会互相拖慢并撞上会话卸载。
 * 常驻上限按内存分级（[maxLoaded]），超出按 LRU 卸载；每方向 int8 常驻约 130 MB。
 */
class OnnxNmtTranslator(
    private val store: ModelStore,
    private val numThreads: Int = 2,
    private val maxLoaded: Int = when (MemoryTier.current) { MemoryTier.HIGH -> 3; else -> 2 },
    /** 非高内存机型不预打包权重（每方向省 ≈ 100 MB）。 */
    private val lowMemory: Boolean = MemoryTier.current != MemoryTier.HIGH,
) : Translator {
    override val id = "nmt"
    override val cloud = false

    private val mutex = Mutex()
    private val loaded = LinkedHashMap<String, LoadedModel>()   // 访问顺序 = 手动维护的 LRU
    private val _resident = MutableStateFlow<List<String>>(emptyList())
    /** 当前常驻的包 id（设置页 / 自检展示）。 */
    val resident: StateFlow<List<String>> = _resident.asStateFlow()

    override fun supports(src: String, tgt: String): Boolean = NmtRoutes.resolve(src, tgt, store::isInstalled) != null

    /** 预热：把某方向需要的模型装进内存（会话开始时调，首句不用等 0.5–1 s 加载）。 */
    suspend fun warm(src: String, tgt: String) {
        val route = NmtRoutes.resolve(src, tgt, store::isInstalled) ?: return
        mutex.withLock { withContext(Dispatchers.Default) { route.legs.forEach { model(it.pack) } } }
    }

    /** 释放全部常驻模型（会话结束、内存告警）。 */
    suspend fun unloadAll() { mutex.withLock { loaded.values.forEach { it.close() }; loaded.clear(); _resident.value = emptyList() } }

    override suspend fun translate(req: MtRequest): MtResult {
        val route = NmtRoutes.resolve(req.src, req.tgt, store::isInstalled) ?: throw MtFailed("local language pair not installed: ${req.src}→${req.tgt}", retryable = false)
        val t0 = TimeSource.Monotonic.markNow()
        if (route is NmtRoute.Identity) return MtResult(req.text, req.src, req.tgt, id, "identity", 0)
        var inTok = 0; var outTok = 0
        val text = mutex.withLock {
            withContext(Dispatchers.Default) {
                var cur = req.text
                for ((i, leg) in route.legs.withIndex()) {
                    val m = model(leg.pack)
                    val tgtLang = if (i == route.legs.lastIndex) req.tgt else Lang.EN
                    val r = m.translate(cur, leg.langToken, tgtLang)
                    inTok += r.inputTokens; outTok += r.outputTokens
                    cur = r.text
                }
                cur
            }
        }
        val ms = t0.elapsedNow().inWholeMilliseconds
        Diag.log("nmt", "${route.label} ${req.src}→${req.tgt} in=$inTok out=$outTok ${ms}ms")
        return MtResult(text, req.src, req.tgt, id, route.label, ms, inTok, outTok, estCost = 0.0)
    }

    /** 取（或装载）一个方向的模型；调用方持有 [mutex]。 */
    private fun model(pack: ModelPack): LoadedModel {
        loaded.remove(pack.id)?.let { loaded[pack.id] = it; return it }
        if (!store.isInstalled(pack)) throw MtFailed("pack not installed: ${pack.id}", retryable = false)
        while (loaded.size >= maxLoaded) { val oldest = loaded.keys.first(); loaded.remove(oldest)?.close(); Diag.log("nmt", "evict $oldest") }
        val t0 = TimeSource.Monotonic.markNow()
        val m = LoadedModel.load(pack, store, numThreads, lowMemory)
        loaded[pack.id] = m
        _resident.value = loaded.keys.toList()
        Diag.log("nmt", "loaded ${pack.id} in ${t0.elapsedNow().inWholeMilliseconds}ms (ort ${Ort.version})")
        return m
    }
}

/** 一个方向的 encoder + decoder 会话与分词器。 */
internal class LoadedModel private constructor(
    val pack: ModelPack,
    private val tokenizer: MarianTokenizer,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
) : AutoCloseable {
    private val pastNames: List<String> = decoder.inputNames.filter { it.startsWith("past_key_values.") }
    private val presentNames: List<String> = decoder.outputNames.filter { it.startsWith("present.") }
    private val decoderOutputs: List<String> = listOf("logits") + presentNames

    class Out(val text: String, val inputTokens: Int, val outputTokens: Int)

    /**
     * 长文本按句切开逐句翻（opus-mt 位置编码 512，实时句子远不到；成稿段落会）。
     * 换行原样保留、逐行翻：字幕按批送来（一行一句），译文行数要和原文对得上。
     */
    suspend fun translate(text: String, langToken: String?, tgtLang: String): Out {
        val lines = text.trim().lines()
        var inTok = 0; var outTok = 0
        val out = lines.map { line ->
            val sb = StringBuilder()
            for (p in splitSentences(line.trim())) {
                val ids = tokenizer.encode(p, langToken)
                inTok += ids.size
                val o = decodeGreedy(ids)
                outTok += o.size
                val s = tokenizer.decode(o)
                if (sb.isNotEmpty() && !isCjk(tgtLang)) sb.append(' ')
                sb.append(s)
            }
            if (isCjk(tgtLang)) tidyCjk(sb.toString()) else sb.toString()
        }
        return Out(out.joinToString("\n"), inTok, outTok)
    }

    /** 贪心解码：第一步走无 cache 分支，之后把 present.* 回灌为 past_key_values.*（encoder 侧只用第一步的）。 */
    private suspend fun decodeGreedy(inputIds: IntArray): IntArray {
        val n = inputIds.size
        val ids64 = LongArray(n) { inputIds[it].toLong() }
        val shape = longArrayOf(1, n.toLong())
        val inputIdsT = Ort.tensor(ids64, shape)
        val maskT = Ort.tensor(LongArray(n) { 1L }, shape)
        var hidden: OrtValue? = null
        val past = HashMap<String, OrtValue>(pastNames.size)
        val result = ArrayList<Int>(n * 2)
        try {
            hidden = encoder.run(mapOf("input_ids" to inputIdsT, "attention_mask" to maskT), listOf("last_hidden_state")).getValue("last_hidden_state")
            val dModel = hidden.shape.last().toInt()
            val headDim = dModel / HEADS
            for (name in pastNames) past[name] = Ort.tensor(FloatArray(0), longArrayOf(1, HEADS.toLong(), 0, headDim.toLong()))
            var cur = tokenizer.padId   // decoder_start_token_id == pad
            var useCache = false
            val maxNew = minOf(MAX_NEW_TOKENS, n * 3 + 10)
            val eos = tokenizer.eosId; val pad = tokenizer.padId
            while (result.size < maxNew) {
                currentCoroutineContext().ensureActive()
                val curT = Ort.tensor(longArrayOf(cur.toLong()), longArrayOf(1, 1))
                val flagT = Ort.tensor(booleanArrayOf(useCache), longArrayOf(1))
                val feeds = HashMap<String, OrtValue>(pastNames.size + 4)
                feeds["input_ids"] = curT; feeds["encoder_hidden_states"] = hidden; feeds["encoder_attention_mask"] = maskT; feeds["use_cache_branch"] = flagT
                feeds.putAll(past)
                val out = try { decoder.run(feeds, decoderOutputs) } finally { curT.close(); flagT.close() }
                val logitsT = out.getValue("logits")
                val logits = logitsT.floats()
                logitsT.close()
                val vocab = logitsT.shape.last().toInt()
                val off = logits.size - vocab   // 只看最后一个位置
                var best = -1; var bestScore = Float.NEGATIVE_INFINITY
                for (v in 0 until vocab) {
                    if (v == pad) continue   // bad_words_ids = [[pad]]
                    val s = logits[off + v]
                    if (s > bestScore) { bestScore = s; best = v }
                }
                for (name in presentNames) {
                    val v = out.getValue(name)
                    val key = "past_key_values." + name.removePrefix("present.")
                    if (useCache && name.contains(".encoder.")) { v.close(); continue }   // cache 分支不重算 encoder K/V，沿用第一步的
                    past.remove(key)?.close(); past[key] = v
                }
                useCache = true
                if (best == eos || best < 0) break
                result.add(best)
                if (looping(result)) { repeat(LOOP_WINDOW) { result.removeAt(result.lastIndex) }; break }
                cur = best
            }
        } finally {
            inputIdsT.close(); maskT.close(); hidden?.close(); past.values.forEach { it.close() }
        }
        return result.toIntArray()
    }

    /** 贪心偶尔陷入复读（"the the the"）：最近 [LOOP_WINDOW] 个 token 与前一窗完全相同就截断。 */
    private fun looping(ids: List<Int>): Boolean {
        val w = LOOP_WINDOW
        if (ids.size < w * 2) return false
        val n = ids.size
        for (k in 0 until w) if (ids[n - 1 - k] != ids[n - 1 - w - k]) return false
        return true
    }

    override fun close() { encoder.close(); decoder.close() }

    companion object {
        /** opus-mt（transformer-base）：8 头、d_model 512；装载时用 encoder 输出维度反推 head_dim。 */
        private const val HEADS = 8
        private const val MAX_NEW_TOKENS = 256
        private const val LOOP_WINDOW = 6
        private val fs = FileSystem.SYSTEM

        fun load(pack: ModelPack, store: ModelStore, numThreads: Int, lowMemory: Boolean): LoadedModel {
            val src = SentencePiece.parse(fs.read(store.path(pack, "source.spm").toPath()) { readByteArray() })
            val tgt = SentencePiece.parse(fs.read(store.path(pack, "target.spm").toPath()) { readByteArray() })
            val vocab = MarianTokenizer.parseVocab(fs.read(store.path(pack, "vocab.json").toPath()) { readUtf8() })
            val tok = MarianTokenizer(src, tgt, vocab)
            val enc = Ort.session(store.path(pack, "onnx/encoder_model_quantized"), numThreads, lowMemory)
            val decOriginal = store.path(pack, "onnx/decoder_model_merged_quantized")
            val decPath = NmtModelPatch.ensure(decOriginal)   // 首次装载时改写 lm_head（见 NmtModelPatch）
            val dec = try {
                try { Ort.session(decPath, numThreads, lowMemory) }
                catch (t: Throwable) { if (decPath == decOriginal) throw t; dev.scenenote.core.Diag.log("nmt", "patched decoder rejected (${t.message}), falling back"); NmtModelPatch.discard(decPath); Ort.session(decOriginal, numThreads, lowMemory) }
            } catch (t: Throwable) { enc.close(); throw t }
            if ("use_cache_branch" !in dec.inputNames) { enc.close(); dec.close(); error("${pack.id}: decoder is not the merged (KV cache) export") }
            return LoadedModel(pack, tok, enc, dec)
        }

        private fun isCjk(lang: String) = Lang.family(lang) == "zh" || lang == Lang.JA

        /** 中 / 日译文：模型吐的半角逗号、句间空格按书面习惯整理（TTS 与屏幕都更自然）。 */
        internal fun tidyCjk(s: String): String {
            val sb = StringBuilder(s.length)
            for (i in s.indices) {
                val c = s[i]
                val prev = sb.lastOrNull()
                val next = s.getOrNull(i + 1)
                when {
                    c == ' ' && prev != null && isCjkChar(prev) && next != null && isCjkChar(next) -> sb.append('，')
                    c == ',' && prev != null && isCjkChar(prev) -> sb.append('，')
                    c == '?' && prev != null && isCjkChar(prev) -> sb.append('？')
                    c == '!' && prev != null && isCjkChar(prev) -> sb.append('！')
                    c == ':' && prev != null && isCjkChar(prev) -> sb.append('：')
                    c == ';' && prev != null && isCjkChar(prev) -> sb.append('；')
                    c == '.' && prev != null && isCjkChar(prev) && (next == null || next == ' ') -> sb.append('。')
                    else -> sb.append(c)
                }
            }
            return sb.toString().replace("，，", "，").replace("， ", "，")
        }

        private fun isCjkChar(c: Char): Boolean = c in '一'..'鿿' || c in '㐀'..'䶿' || c in '぀'..'ヿ' || c in '！'..'～' || c in '　'..'〿'

        /** 按句末标点切句；不足 [MIN_SPLIT_CHARS] 字的短文本原样一句。 */
        internal fun splitSentences(text: String): List<String> {
            if (text.isEmpty()) return emptyList()
            if (text.length < MIN_SPLIT_CHARS) return listOf(text)
            val out = ArrayList<String>()
            var start = 0
            for (i in text.indices) {
                val c = text[i]
                val endsHere = c == '。' || c == '！' || c == '？' || ((c == '.' || c == '!' || c == '?') && (i + 1 >= text.length || text[i + 1] == ' ' || text[i + 1] == '\n'))
                if (endsHere || c == '\n') {
                    val seg = text.substring(start, i + 1).trim()
                    if (seg.isNotEmpty()) out.add(seg)
                    start = i + 1
                }
            }
            val tail = text.substring(start).trim()
            if (tail.isNotEmpty()) out.add(tail)
            return out
        }
        private const val MIN_SPLIT_CHARS = 120
    }
}
