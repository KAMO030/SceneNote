package dev.scenenote.tts

import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import platform.AVFAudio.AVAudioBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import kotlin.time.TimeSource

/**
 * iOS 系统 TTS 取 PCM（05 篇 §7.3）：AVSpeechSynthesizer.writeUtterance:toBufferCallback: 逐块回调 AVAudioPCMBuffer，
 * 空 buffer（frameLength == 0）表示结束；不经系统直接出声，重采样到 16 kHz 后交给 AudioSink。
 */
class IosSystemTts : TtsEngine {
    override val id = "ios-system-tts"
    private val synth = AVSpeechSynthesizer()

    private fun voiceFor(lang: String): AVSpeechSynthesisVoice? {
        val bcp = when (lang) { "en" -> "en-US"; "zh-CN-sichuan" -> "zh-CN"; else -> lang }
        return AVSpeechSynthesisVoice.voiceWithLanguage(bcp) ?: AVSpeechSynthesisVoice.voiceWithLanguage(bcp.substringBefore('-'))
    }

    override fun supports(lang: String): Boolean = voiceFor(lang) != null

    override suspend fun synthesize(req: TtsRequest, onChunk: suspend (ShortArray) -> Boolean): TtsStats {
        val voice = voiceFor(req.lang) ?: throw TtsFailed("no system voice for ${req.lang}")
        val utt = AVSpeechUtterance(string = req.text).apply {
            this.voice = voice
            rate = (AVSpeechUtteranceDefaultSpeechRate * req.rate).coerceIn(0.1f, 0.7f)
        }
        val chunks = Channel<ShortArray?>(Channel.UNLIMITED)
        val t0 = TimeSource.Monotonic.markNow()
        var firstMs = -1L; var samples = 0; var cancelled = false
        withContext(Dispatchers.Main) {
            synth.writeUtterance(utt) { buffer: AVAudioBuffer? ->
                val pcm = buffer as? AVAudioPCMBuffer
                val n = pcm?.frameLength?.toInt() ?: 0
                if (pcm == null || n == 0) { chunks.trySend(null); return@writeUtterance }
                val fmt = pcm.format
                val sr = fmt.sampleRate.toInt()
                val out: ShortArray = when (fmt.commonFormat) {
                    AVAudioPCMFormatFloat32 -> {
                        val ch = pcm.floatChannelData!![0]!!
                        Resample.toPcm16(FloatArray(n) { ch[it] }, sr)
                    }
                    AVAudioPCMFormatInt16 -> {
                        val ch = pcm.int16ChannelData!![0]!!
                        Resample.toPcm16(ShortArray(n) { ch[it] }, sr)
                    }
                    else -> {
                        // Int32 / 其他：按 Float32 处理失败时回退为静音，避免崩溃
                        val ch = pcm.floatChannelData?.get(0)
                        if (ch != null) Resample.toPcm16(FloatArray(n) { ch[it] }, sr) else ShortArray(0)
                    }
                }
                if (out.isNotEmpty()) chunks.trySend(out)
            }
        }
        for (c in chunks) {
            if (c == null) break
            if (firstMs < 0) firstMs = t0.elapsedNow().inWholeMilliseconds
            samples += c.size
            if (!onChunk(c)) { cancelled = true; break }
        }
        if (cancelled) withContext(Dispatchers.Main) { synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate) }
        return TtsStats(id, 16_000, samples, firstMs.coerceAtLeast(0), t0.elapsedNow().inWholeMilliseconds)
    }
}

class IosSystemTtsProvider : SystemTtsProvider {
    private val engine by lazy { IosSystemTts() }
    override fun get(): TtsEngine? = engine
}
