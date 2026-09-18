package dev.scenenote.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.scenenote.bench.WavIo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.time.TimeSource

/**
 * Android 系统 TTS（有引擎才可用）：synthesizeToFile → WAV → 16 kHz PCM 分块。
 * onAudioAvailable 在国产引擎上支持度不明（附录 B 17），MVP 统一走文件兜底。
 */
class AndroidSystemTts private constructor(private val context: Context, private val tts: TextToSpeech) : TtsEngine {
    override val id = "android-system-tts"

    private fun localeFor(lang: String): Locale = when (lang) {
        "en" -> Locale.US; "zh-CN", "zh-CN-sichuan" -> Locale.SIMPLIFIED_CHINESE; "yue-HK" -> Locale("yue", "HK"); "ja" -> Locale.JAPAN; "ko" -> Locale.KOREA
        else -> Locale.forLanguageTag(lang)
    }

    override fun supports(lang: String): Boolean = runCatching { tts.isLanguageAvailable(localeFor(lang)) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)

    override suspend fun synthesize(req: TtsRequest, onChunk: suspend (ShortArray) -> Boolean): TtsStats {
        val t0 = TimeSource.Monotonic.markNow()
        val file = File(context.cacheDir, "tts-${req.utteranceId}.wav")
        val done = CompletableDeferred<Boolean>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == req.utteranceId) done.complete(true) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { if (utteranceId == req.utteranceId) done.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == req.utteranceId) done.complete(false) }
        })
        tts.language = localeFor(req.lang)
        tts.setSpeechRate(req.rate)
        val r = tts.synthesizeToFile(req.text, null, file, req.utteranceId)
        if (r != TextToSpeech.SUCCESS) throw TtsFailed("system TTS refused synthesis ($r)")
        val pcm = try {
            // 实时会话里一句十几二十个字，正常引擎 1 s 内就写完文件；按长度放宽，上限留给慢路径的长文本。
            // 不能像原来那样一律等 15 s：合成是串行的，一句卡住后面整条播放队列都跟着停。
            val timeoutMs = (2_000L + req.text.length * 60L).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
            if (withTimeoutOrNull(timeoutMs) { done.await() } != true) throw TtsFailed("system TTS timed out after ${timeoutMs}ms")
            withContext(Dispatchers.IO) { runCatching { WavIo.readPcm16k(file.absolutePath) }.getOrElse { throw TtsFailed("failed to read system TTS output: ${it.message}", it) } }
        } finally {
            // 超时，或被播放队列的首块看门狗取消：让引擎停手，别把半个文件留在 cacheDir
            if (!done.isCompleted) runCatching { tts.stop() }
            runCatching { file.delete() }
        }
        val firstMs = t0.elapsedNow().inWholeMilliseconds
        var off = 0; var sent = 0
        while (off < pcm.size) {
            val n = minOf(3200, pcm.size - off)
            if (!onChunk(pcm.copyOfRange(off, off + n))) break
            off += n; sent += n
        }
        return TtsStats(id, 16_000, sent, firstMs, t0.elapsedNow().inWholeMilliseconds)
    }

    override fun close() { tts.shutdown() }

    companion object {
        private const val MIN_TIMEOUT_MS = 3_000L
        private const val MAX_TIMEOUT_MS = 15_000L

        /** 没有任何引擎（vivo V2054A：tts_default_synth = null）→ null；有引擎则初始化并等待 onInit。 */
        suspend fun create(context: Context): AndroidSystemTts? {
            val probe = TextToSpeech(context) {}
            val engines = runCatching { probe.engines }.getOrDefault(emptyList())
            probe.shutdown()
            if (engines.isEmpty()) return null
            val init = CompletableDeferred<Int>()
            val tts = TextToSpeech(context) { status -> init.complete(status) }
            val status = withTimeoutOrNull(5_000) { init.await() } ?: TextToSpeech.ERROR
            if (status != TextToSpeech.SUCCESS) { tts.shutdown(); return null }
            return AndroidSystemTts(context, tts)
        }
    }
}

/** 首次访问时同步探测引擎是否存在（PackageManager 查询很快）；有引擎则异步初始化后可用。 */
class AndroidSystemTtsProvider(private val context: Context) : SystemTtsProvider {
    @Volatile private var engine: TtsEngine? = null
    private val ready = CompletableDeferred<Unit>()
    override fun get(): TtsEngine? = engine
    override suspend fun awaitReady() { ready.await() }

    suspend fun init() {
        if (ready.isCompleted) return
        try { engine = runCatching { AndroidSystemTts.create(context) }.getOrNull() } finally { ready.complete(Unit) }
    }
}
