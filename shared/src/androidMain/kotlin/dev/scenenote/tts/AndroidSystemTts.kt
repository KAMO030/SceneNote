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
        val ok = withTimeoutOrNull(15_000) { done.await() } ?: false
        if (!ok) { file.delete(); throw TtsFailed("system TTS synthesis failed or timed out") }
        val pcm = withContext(Dispatchers.IO) { runCatching { WavIo.readPcm16k(file.absolutePath) }.also { file.delete() }.getOrElse { throw TtsFailed("failed to read system TTS output: ${it.message}", it) } }
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
