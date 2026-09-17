package dev.scenenote.asr

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Android：sherpa-onnx AAR 的 Kotlin API（com.k2fsa.sherpa.onnx），模型从沙盒文件路径加载。 */
actual object SherpaNative {
    actual fun online(spec: OnlineTransducerSpec): NativeOnlineRecognizer {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = spec.encoder, decoder = spec.decoder, joiner = spec.joiner),
                tokens = spec.tokens, numThreads = spec.numThreads, provider = spec.provider, modelType = "zipformer",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, spec.rule1TrailingSilence, 0f),
                rule2 = EndpointRule(true, spec.rule2TrailingSilence, 0f),
                rule3 = EndpointRule(false, 0f, spec.rule3MaxUtterance),
            ),
            enableEndpoint = true, decodingMethod = spec.decodingMethod, maxActivePaths = spec.maxActivePaths,
            hotwordsFile = spec.hotwordsFile, hotwordsScore = spec.hotwordsScore,
        )
        val rec = runCatching { OnlineRecognizer(config = config) }.getOrElse { throw IllegalStateException("流式识别器创建失败：${it.message}", it) }
        return object : NativeOnlineRecognizer {
            override fun createStream(): NativeOnlineStream = AndroidOnlineStream(rec.createStream())
            override fun isReady(stream: NativeOnlineStream) = rec.isReady((stream as AndroidOnlineStream).s)
            override fun decode(stream: NativeOnlineStream) = rec.decode((stream as AndroidOnlineStream).s)
            override fun result(stream: NativeOnlineStream): NativeOnlineResult {
                val r = rec.getResult((stream as AndroidOnlineStream).s)
                return NativeOnlineResult(r.text, r.tokens.toList(), r.timestamps)
            }
            override fun isEndpoint(stream: NativeOnlineStream) = rec.isEndpoint((stream as AndroidOnlineStream).s)
            override fun reset(stream: NativeOnlineStream) = rec.reset((stream as AndroidOnlineStream).s)
            override fun close() = rec.release()
        }
    }

    private class AndroidOnlineStream(val s: OnlineStream) : NativeOnlineStream {
        override fun accept(samples: FloatArray, sampleRate: Int) = s.acceptWaveform(samples, sampleRate)
        override fun inputFinished() = s.inputFinished()
        override fun close() = s.release()
    }

    actual fun senseVoice(spec: SenseVoiceSpec): NativeOfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = spec.model, language = spec.language, useInverseTextNormalization = spec.useItn),
                tokens = spec.tokens, numThreads = spec.numThreads, provider = spec.provider, modelType = "sense_voice",
            ),
            decodingMethod = "greedy_search",
        )
        val rec = runCatching { OfflineRecognizer(config = config) }.getOrElse { throw IllegalStateException("SenseVoice 创建失败：${it.message}", it) }
        return AndroidOffline(rec)
    }

    actual fun paraformer(spec: ParaformerSpec): NativeOfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = spec.model),
                tokens = spec.tokens, numThreads = spec.numThreads, provider = spec.provider, modelType = "paraformer",
            ),
            decodingMethod = "greedy_search",
        )
        val rec = runCatching { OfflineRecognizer(config = config) }.getOrElse { throw IllegalStateException("Paraformer 创建失败：${it.message}", it) }
        return AndroidOffline(rec)
    }

    private class AndroidOffline(private val rec: OfflineRecognizer) : NativeOfflineRecognizer {
        override fun transcribe(samples: FloatArray, sampleRate: Int): NativeOfflineResult {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                rec.decode(stream)
                val r = rec.getResult(stream)
                return NativeOfflineResult(r.text, r.lang, r.emotion, r.event, r.tokens.toList(), r.timestamps)
            } finally { stream.release() }
        }
        override fun close() = rec.release()
    }

    actual fun vad(spec: VadSpec): NativeVad {
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = spec.model, threshold = spec.threshold, minSilenceDuration = spec.minSilenceSec,
                minSpeechDuration = spec.minSpeechSec, windowSize = spec.windowSize, maxSpeechDuration = spec.maxSpeechSec),
            sampleRate = 16_000, numThreads = spec.numThreads,
        )
        val vad = runCatching { Vad(config = config) }.getOrElse { throw IllegalStateException("VAD 创建失败：${it.message}", it) }
        return object : NativeVad {
            override fun accept(samples: FloatArray) = vad.acceptWaveform(samples)
            override fun isSpeechDetected() = vad.isSpeechDetected()
            override fun popSegments(): List<VadSegment> {
                val out = ArrayList<VadSegment>()
                while (!vad.empty()) { val f = vad.front(); out += VadSegment(f.start, f.samples); vad.pop() }
                return out
            }
            override fun flush() = vad.flush()
            override fun reset() = vad.reset()
            override fun close() = vad.release()
        }
    }

    actual fun speaker(spec: SpeakerSpec): NativeSpeakerExtractor {
        val ex = runCatching { SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = spec.model, numThreads = spec.numThreads, provider = spec.provider)) }
            .getOrElse { throw IllegalStateException("声纹模型创建失败：${it.message}", it) }
        return object : NativeSpeakerExtractor {
            override val dim: Int = ex.dim()
            override fun embed(samples: FloatArray, sampleRate: Int): FloatArray {
                val s = ex.createStream()
                try {
                    s.acceptWaveform(samples, sampleRate); s.inputFinished()
                    check(ex.isReady(s)) { "声纹：语音太短" }
                    return ex.compute(s)
                } finally { s.release() }
            }
            override fun close() = ex.release()
        }
    }
    actual fun tts(spec: TtsSpec): NativeTts {
        val model = OfflineTtsModelConfig(numThreads = spec.numThreads, provider = spec.provider, debug = false).apply {
            when (spec.kind) {
                TtsKind.VITS -> vits = OfflineTtsVitsModelConfig(model = spec.model, lexicon = spec.lexicon, tokens = spec.tokens, dataDir = spec.dataDir, lengthScale = spec.lengthScale)
                TtsKind.MATCHA -> matcha = OfflineTtsMatchaModelConfig(acousticModel = spec.model, vocoder = spec.vocoder, lexicon = spec.lexicon, tokens = spec.tokens, dataDir = spec.dataDir, noiseScale = 0.667f, lengthScale = spec.lengthScale)   // 与 iOS C API 缺省一致（AAR 默认 1.0）
                TtsKind.KOKORO -> kokoro = OfflineTtsKokoroModelConfig(model = spec.model, voices = spec.voices, tokens = spec.tokens, dataDir = spec.dataDir, lexicon = spec.lexicon, lang = spec.lang, lengthScale = spec.lengthScale)
            }
        }
        val config = OfflineTtsConfig(model = model, ruleFsts = spec.ruleFsts, maxNumSentences = 1)
        val tts = runCatching { OfflineTts(assetManager = null, config = config) }.getOrElse { throw IllegalStateException("TTS 创建失败：${it.message}", it) }
        return object : NativeTts {
            override val sampleRate: Int = tts.sampleRate()
            override val numSpeakers: Int = tts.numSpeakers()
            override fun generate(text: String, sid: Int, speed: Float, onChunk: (FloatArray) -> Boolean): FloatArray {
                // JNI 按 invoke([F)Ljava/lang/Integer; 查方法：必须是显式类（indy lambda 只有擦除签名，会 NoSuchMethodError 崩溃）
                val cb = object : Function1<FloatArray, Int> { override fun invoke(chunk: FloatArray): Int = if (onChunk(chunk)) 1 else 0 }
                return tts.generateWithCallback(text, sid, speed, cb).samples
            }
            override fun close() = tts.release()
        }
    }

}
