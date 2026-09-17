package dev.scenenote.tts

import dev.scenenote.asr.NativeTts
import dev.scenenote.asr.SherpaNative
import dev.scenenote.asr.TtsSpec
import dev.scenenote.models.ModelCatalog
import dev.scenenote.models.ModelPack
import dev.scenenote.models.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * 端侧 TTS（sherpa-onnx OfflineTts）：按目标语言选已安装的语音包，首次合成时装载；换语言时卸载重装（只驻留一个 TTS，05 篇内存分级）。
 * 合成在 Default 调度器阻塞跑，块经 Channel 送回给 onChunk（Native 回调线程不能挂起）。
 */
class SherpaTts(private val store: ModelStore, private val numThreads: Int = 4) : TtsEngine {
    override val id = "sherpa-tts"
    private var native: NativeTts? = null
    private var loadedPack: ModelPack? = null
    private val mutex = Mutex()
    /** 最近一次装载耗时（验收记录用）。 */
    var lastLoadMs: Long = -1; private set

    fun packFor(lang: String): ModelPack? = ModelCatalog.ttsPacks.firstOrNull { p -> lang in p.langs && store.isInstalled(p) }
        ?: ModelCatalog.ttsPacks.firstOrNull { p -> p.langs.any { it.substringBefore('-') == lang.substringBefore('-') } && store.isInstalled(p) }

    override fun supports(lang: String): Boolean = packFor(lang) != null

    private suspend fun ensureLoaded(pack: ModelPack): NativeTts = mutex.withLock {
        native?.takeIf { loadedPack?.id == pack.id }?.let { return it }
        native?.close(); native = null; loadedPack = null
        val t = TimeSource.Monotonic.markNow()
        val n = withContext(Dispatchers.Default) { SherpaNative.tts(ModelCatalog.ttsSpec(pack, store, numThreads)) }
        lastLoadMs = t.elapsedNow().inWholeMilliseconds
        native = n; loadedPack = pack
        n
    }

    /** 预热：会话开始前先把目标语言的语音包装进内存。 */
    suspend fun preload(lang: String): Boolean { val p = packFor(lang) ?: return false; ensureLoaded(p); return true }

    override suspend fun synthesize(req: TtsRequest, onChunk: suspend (ShortArray) -> Boolean): TtsStats {
        val pack = packFor(req.lang) ?: throw TtsFailed("没有 ${req.lang} 的端侧语音包")
        val n = ensureLoaded(pack)
        val t0 = TimeSource.Monotonic.markNow()
        var firstMs = -1L; var samples = 0
        val chunks = Channel<ShortArray?>(Channel.UNLIMITED)
        val sr = n.sampleRate
        val speed = req.rate.coerceIn(0.5f, 2f)
        coroutineScope {
            var cancelled = false
            val producer = launch(Dispatchers.Default) {
                try {
                    // 按标点切成短句逐段合成：首音只等第一小句（matcha 前端不按中文逗号切句，vivo 实测整句 3.5 s 才回首块）
                    for (clause in Clauses.split(req.text)) {
                        if (cancelled) break
                        n.generate(clause, 0, speed) { f ->
                            if (cancelled) return@generate false
                            chunks.trySend(Resample.toPcm16(f, sr)); true
                        }
                    }
                } catch (t: Throwable) { chunks.trySend(null); throw TtsFailed("合成失败：${t.message}", t) }
                finally { chunks.trySend(null) }
            }
            for (c in chunks) {
                if (c == null) break
                if (firstMs < 0) firstMs = t0.elapsedNow().inWholeMilliseconds
                samples += c.size
                if (!onChunk(c)) { cancelled = true; break }
            }
            if (cancelled) producer.cancel()
            producer.join()
        }
        return TtsStats(id, 16_000, samples, firstMs.coerceAtLeast(0), t0.elapsedNow().inWholeMilliseconds)
    }

    /** 释放模型（会话结束 / 内存告警）；不与 synthesize 并发调用。 */
    fun unload() { native?.close(); native = null; loadedPack = null }
    override fun close() = unload()
}

/** 短句切分：在句读处断开，太短的片段并入下一段（避免"你好，"单独一段太碎）。 */
object Clauses {
    private val breakers = setOf('，', '。', '！', '？', '；', '：', ',', '.', '!', '?', ';', ':', '、', '\n')
    private fun hasCjk(s: CharSequence) = s.any { it in '\u4E00'..'\u9FFF' || it in '\u3040'..'\u30FF' || it in '\uAC00'..'\uD7AF' }
    /** 中文按 ≥ 4 字断，拉丁文按 ≥ 12 字符断（"Hello," 单独一段太碎）；超过 maxChars 强制断。 */
    fun split(text: String, maxChars: Int = 40): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun minChars() = if (hasCjk(cur)) 4 else 12
        for (c in text) {
            cur.append(c)
            val isBreak = c in breakers
            if ((isBreak && cur.trim().length >= minChars()) || cur.length >= maxChars) { out += cur.toString().trim(); cur.clear() }
        }
        if (cur.isNotBlank()) { if (out.isNotEmpty() && cur.trim().length < minChars()) out[out.size - 1] = out.last() + cur.toString().trim() else out += cur.toString().trim() }
        return out.filter { it.any { ch -> ch.isLetterOrDigit() } }.ifEmpty { listOf(text) }
    }
}
