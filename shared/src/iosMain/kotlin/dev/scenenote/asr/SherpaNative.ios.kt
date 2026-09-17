package dev.scenenote.asr

import dev.scenenote.sherpa.native.SherpaOnnxAcceptWaveformOffline
import dev.scenenote.sherpa.native.SherpaOnnxCreateOfflineRecognizer
import dev.scenenote.sherpa.native.SherpaOnnxCreateOfflineStream
import dev.scenenote.sherpa.native.SherpaOnnxCreateOnlineRecognizer
import dev.scenenote.sherpa.native.SherpaOnnxCreateOnlineStream
import dev.scenenote.sherpa.native.SherpaOnnxCreateSpeakerEmbeddingExtractor
import dev.scenenote.sherpa.native.SherpaOnnxCreateVoiceActivityDetector
import dev.scenenote.sherpa.native.SherpaOnnxDecodeOfflineStream
import dev.scenenote.sherpa.native.SherpaOnnxDecodeOnlineStream
import dev.scenenote.sherpa.native.SherpaOnnxDestroyOfflineRecognizer
import dev.scenenote.sherpa.native.SherpaOnnxDestroyOfflineRecognizerResult
import dev.scenenote.sherpa.native.SherpaOnnxDestroyOfflineStream
import dev.scenenote.sherpa.native.SherpaOnnxDestroyOnlineRecognizer
import dev.scenenote.sherpa.native.SherpaOnnxDestroyOnlineRecognizerResult
import dev.scenenote.sherpa.native.SherpaOnnxDestroyOnlineStream
import dev.scenenote.sherpa.native.SherpaOnnxDestroySpeakerEmbeddingExtractor
import dev.scenenote.sherpa.native.SherpaOnnxDestroySpeechSegment
import dev.scenenote.sherpa.native.SherpaOnnxDestroyVoiceActivityDetector
import dev.scenenote.sherpa.native.SherpaOnnxGetOfflineStreamResult
import dev.scenenote.sherpa.native.SherpaOnnxGetOnlineStreamResult
import dev.scenenote.sherpa.native.SherpaOnnxIsOnlineStreamReady
import dev.scenenote.sherpa.native.SherpaOnnxOfflineRecognizerConfig
import cnames.structs.SherpaOnnxOnlineRecognizer
import dev.scenenote.sherpa.native.SherpaOnnxOnlineRecognizerConfig
import cnames.structs.SherpaOnnxOnlineStream
import dev.scenenote.sherpa.native.SherpaOnnxOnlineStreamAcceptWaveform
import dev.scenenote.sherpa.native.SherpaOnnxOnlineStreamInputFinished
import dev.scenenote.sherpa.native.SherpaOnnxOnlineStreamIsEndpoint
import dev.scenenote.sherpa.native.SherpaOnnxOnlineStreamReset
import dev.scenenote.sherpa.native.SherpaOnnxSpeakerEmbeddingExtractorComputeEmbedding
import dev.scenenote.sherpa.native.SherpaOnnxSpeakerEmbeddingExtractorConfig
import dev.scenenote.sherpa.native.SherpaOnnxSpeakerEmbeddingExtractorCreateStream
import dev.scenenote.sherpa.native.SherpaOnnxSpeakerEmbeddingExtractorDestroyEmbedding
import dev.scenenote.sherpa.native.SherpaOnnxSpeakerEmbeddingExtractorDim
import dev.scenenote.sherpa.native.SherpaOnnxSpeakerEmbeddingExtractorIsReady
import dev.scenenote.sherpa.native.SherpaOnnxVadModelConfig
import dev.scenenote.sherpa.native.SherpaOnnxVoiceActivityDetectorAcceptWaveform
import dev.scenenote.sherpa.native.SherpaOnnxVoiceActivityDetectorDetected
import dev.scenenote.sherpa.native.SherpaOnnxVoiceActivityDetectorEmpty
import dev.scenenote.sherpa.native.SherpaOnnxVoiceActivityDetectorFlush
import dev.scenenote.sherpa.native.SherpaOnnxVoiceActivityDetectorFront
import dev.scenenote.sherpa.native.SherpaOnnxVoiceActivityDetectorPop
import dev.scenenote.sherpa.native.SherpaOnnxVoiceActivityDetectorReset
import dev.scenenote.sherpa.native.SherpaOnnxCreateOfflineTts
import dev.scenenote.sherpa.native.SherpaOnnxDestroyOfflineTts
import dev.scenenote.sherpa.native.SherpaOnnxDestroyOfflineTtsGeneratedAudio
import dev.scenenote.sherpa.native.SherpaOnnxOfflineTtsConfig
import dev.scenenote.sherpa.native.SherpaOnnxOfflineTtsGenerateWithCallbackWithArg
import dev.scenenote.sherpa.native.SherpaOnnxOfflineTtsNumSpeakers
import dev.scenenote.sherpa.native.SherpaOnnxOfflineTtsSampleRate
import cnames.structs.SherpaOnnxOfflineTts
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import platform.posix.memset

/** iOS：sherpa-onnx C API（cinterop，静态库嵌入 klib）。配置结构体在 memScoped 内清零后逐字段赋值。 */
actual object SherpaNative {
    private fun MemScope.zeroed(size: Long, p: CPointer<*>) { memset(p, 0, size.toULong()) }

    actual fun online(spec: OnlineTransducerSpec): NativeOnlineRecognizer {
        val rec: CPointer<SherpaOnnxOnlineRecognizer> = memScoped {
            val c = alloc<SherpaOnnxOnlineRecognizerConfig>()
            zeroed(kotlinx.cinterop.sizeOf<SherpaOnnxOnlineRecognizerConfig>(), c.ptr)
            c.feat_config.sample_rate = 16_000; c.feat_config.feature_dim = 80
            c.model_config.transducer.encoder = spec.encoder.cstr.ptr
            c.model_config.transducer.decoder = spec.decoder.cstr.ptr
            c.model_config.transducer.joiner = spec.joiner.cstr.ptr
            c.model_config.tokens = spec.tokens.cstr.ptr
            c.model_config.num_threads = spec.numThreads
            c.model_config.provider = spec.provider.cstr.ptr
            c.model_config.model_type = "zipformer".cstr.ptr
            c.model_config.modeling_unit = "".cstr.ptr
            c.model_config.bpe_vocab = "".cstr.ptr
            c.decoding_method = spec.decodingMethod.cstr.ptr
            c.max_active_paths = spec.maxActivePaths
            c.enable_endpoint = 1
            c.rule1_min_trailing_silence = spec.rule1TrailingSilence
            c.rule2_min_trailing_silence = spec.rule2TrailingSilence
            c.rule3_min_utterance_length = spec.rule3MaxUtterance
            c.hotwords_file = spec.hotwordsFile.cstr.ptr
            c.hotwords_score = spec.hotwordsScore
            c.rule_fsts = "".cstr.ptr; c.rule_fars = "".cstr.ptr
            SherpaOnnxCreateOnlineRecognizer(c.ptr)
        } ?: throw IllegalStateException("流式识别器创建失败（检查模型路径）")
        return object : NativeOnlineRecognizer {
            override fun createStream(): NativeOnlineStream = IosOnlineStream(SherpaOnnxCreateOnlineStream(rec) ?: error("创建流失败"))
            override fun isReady(stream: NativeOnlineStream) = SherpaOnnxIsOnlineStreamReady(rec, (stream as IosOnlineStream).s) != 0
            override fun decode(stream: NativeOnlineStream) = SherpaOnnxDecodeOnlineStream(rec, (stream as IosOnlineStream).s)
            override fun result(stream: NativeOnlineStream): NativeOnlineResult {
                val r = SherpaOnnxGetOnlineStreamResult(rec, (stream as IosOnlineStream).s) ?: return NativeOnlineResult("", emptyList(), FloatArray(0))
                try {
                    val res = r.pointed
                    val n = res.count
                    val tokens = (0 until n).map { i -> res.tokens_arr?.get(i)?.toKString() ?: "" }
                    val ts = FloatArray(n) { i -> res.timestamps?.get(i) ?: 0f }
                    return NativeOnlineResult(res.text?.toKString() ?: "", tokens, ts)
                } finally { SherpaOnnxDestroyOnlineRecognizerResult(r) }
            }
            override fun isEndpoint(stream: NativeOnlineStream) = SherpaOnnxOnlineStreamIsEndpoint(rec, (stream as IosOnlineStream).s) != 0
            override fun reset(stream: NativeOnlineStream) = SherpaOnnxOnlineStreamReset(rec, (stream as IosOnlineStream).s)
            override fun close() = SherpaOnnxDestroyOnlineRecognizer(rec)
        }
    }

    private class IosOnlineStream(val s: CPointer<SherpaOnnxOnlineStream>) : NativeOnlineStream {
        override fun accept(samples: FloatArray, sampleRate: Int) {
            samples.usePinned { p -> SherpaOnnxOnlineStreamAcceptWaveform(s, sampleRate, p.addressOf(0), samples.size) }
        }
        override fun inputFinished() = SherpaOnnxOnlineStreamInputFinished(s)
        override fun close() = SherpaOnnxDestroyOnlineStream(s)
    }

    actual fun senseVoice(spec: SenseVoiceSpec): NativeOfflineRecognizer {
        val rec = memScoped {
            val c = alloc<SherpaOnnxOfflineRecognizerConfig>()
            zeroed(kotlinx.cinterop.sizeOf<SherpaOnnxOfflineRecognizerConfig>(), c.ptr)
            c.feat_config.sample_rate = 16_000; c.feat_config.feature_dim = 80
            c.model_config.sense_voice.model = spec.model.cstr.ptr
            c.model_config.sense_voice.language = spec.language.cstr.ptr
            c.model_config.sense_voice.use_itn = if (spec.useItn) 1 else 0
            c.model_config.tokens = spec.tokens.cstr.ptr
            c.model_config.num_threads = spec.numThreads
            c.model_config.provider = spec.provider.cstr.ptr
            c.model_config.model_type = "sense_voice".cstr.ptr
            c.model_config.modeling_unit = "".cstr.ptr; c.model_config.bpe_vocab = "".cstr.ptr; c.model_config.telespeech_ctc = "".cstr.ptr
            c.decoding_method = "greedy_search".cstr.ptr
            c.max_active_paths = 4
            c.hotwords_file = "".cstr.ptr; c.rule_fsts = "".cstr.ptr; c.rule_fars = "".cstr.ptr
            SherpaOnnxCreateOfflineRecognizer(c.ptr)
        } ?: throw IllegalStateException("SenseVoice 创建失败（检查模型路径）")
        return IosOffline(rec)
    }

    actual fun paraformer(spec: ParaformerSpec): NativeOfflineRecognizer {
        val rec = memScoped {
            val c = alloc<SherpaOnnxOfflineRecognizerConfig>()
            zeroed(kotlinx.cinterop.sizeOf<SherpaOnnxOfflineRecognizerConfig>(), c.ptr)
            c.feat_config.sample_rate = 16_000; c.feat_config.feature_dim = 80
            c.model_config.paraformer.model = spec.model.cstr.ptr
            c.model_config.tokens = spec.tokens.cstr.ptr
            c.model_config.num_threads = spec.numThreads
            c.model_config.provider = spec.provider.cstr.ptr
            c.model_config.model_type = "paraformer".cstr.ptr
            c.model_config.modeling_unit = "".cstr.ptr; c.model_config.bpe_vocab = "".cstr.ptr; c.model_config.telespeech_ctc = "".cstr.ptr
            c.decoding_method = "greedy_search".cstr.ptr
            c.max_active_paths = 4
            c.hotwords_file = "".cstr.ptr; c.rule_fsts = "".cstr.ptr; c.rule_fars = "".cstr.ptr
            SherpaOnnxCreateOfflineRecognizer(c.ptr)
        } ?: throw IllegalStateException("Paraformer 创建失败（检查模型路径）")
        return IosOffline(rec)
    }

    private class IosOffline(private val rec: CPointer<cnames.structs.SherpaOnnxOfflineRecognizer>) : NativeOfflineRecognizer {
        override fun transcribe(samples: FloatArray, sampleRate: Int): NativeOfflineResult {
            val s = SherpaOnnxCreateOfflineStream(rec) ?: error("创建离线流失败")
            try {
                samples.usePinned { p -> SherpaOnnxAcceptWaveformOffline(s, sampleRate, p.addressOf(0), samples.size) }
                SherpaOnnxDecodeOfflineStream(rec, s)
                val r = SherpaOnnxGetOfflineStreamResult(s) ?: return NativeOfflineResult("", "", "", "", emptyList(), FloatArray(0))
                try {
                    val res = r.pointed
                    val n = res.count
                    val tokens = (0 until n).map { i -> res.tokens_arr?.get(i)?.toKString() ?: "" }
                    val ts = FloatArray(n) { i -> res.timestamps?.get(i) ?: 0f }
                    return NativeOfflineResult(res.text?.toKString() ?: "", res.lang?.toKString() ?: "", res.emotion?.toKString() ?: "", res.event?.toKString() ?: "", tokens, ts)
                } finally { SherpaOnnxDestroyOfflineRecognizerResult(r) }
            } finally { SherpaOnnxDestroyOfflineStream(s) }
        }
        override fun close() = SherpaOnnxDestroyOfflineRecognizer(rec)
    }

    actual fun vad(spec: VadSpec): NativeVad {
        val vad = memScoped {
            val c = alloc<SherpaOnnxVadModelConfig>()
            zeroed(kotlinx.cinterop.sizeOf<SherpaOnnxVadModelConfig>(), c.ptr)
            c.silero_vad.model = spec.model.cstr.ptr
            c.silero_vad.threshold = spec.threshold
            c.silero_vad.min_silence_duration = spec.minSilenceSec
            c.silero_vad.min_speech_duration = spec.minSpeechSec
            c.silero_vad.window_size = spec.windowSize
            c.silero_vad.max_speech_duration = spec.maxSpeechSec
            c.sample_rate = 16_000; c.num_threads = spec.numThreads; c.provider = "cpu".cstr.ptr; c.debug = 0
            SherpaOnnxCreateVoiceActivityDetector(c.ptr, spec.bufferSec)
        } ?: throw IllegalStateException("VAD 创建失败（检查模型路径）")
        return object : NativeVad {
            override fun accept(samples: FloatArray) { samples.usePinned { p -> SherpaOnnxVoiceActivityDetectorAcceptWaveform(vad, p.addressOf(0), samples.size) } }
            override fun isSpeechDetected() = SherpaOnnxVoiceActivityDetectorDetected(vad) != 0
            override fun popSegments(): List<VadSegment> {
                val out = ArrayList<VadSegment>()
                while (SherpaOnnxVoiceActivityDetectorEmpty(vad) == 0) {
                    val seg = SherpaOnnxVoiceActivityDetectorFront(vad) ?: break
                    val n = seg.pointed.n
                    val samples = FloatArray(n) { i -> seg.pointed.samples?.get(i) ?: 0f }
                    out += VadSegment(seg.pointed.start, samples)
                    SherpaOnnxDestroySpeechSegment(seg)
                    SherpaOnnxVoiceActivityDetectorPop(vad)
                }
                return out
            }
            override fun flush() = SherpaOnnxVoiceActivityDetectorFlush(vad)
            override fun reset() = SherpaOnnxVoiceActivityDetectorReset(vad)
            override fun close() = SherpaOnnxDestroyVoiceActivityDetector(vad)
        }
    }

    actual fun speaker(spec: SpeakerSpec): NativeSpeakerExtractor {
        val ex = memScoped {
            val c = alloc<SherpaOnnxSpeakerEmbeddingExtractorConfig>()
            zeroed(kotlinx.cinterop.sizeOf<SherpaOnnxSpeakerEmbeddingExtractorConfig>(), c.ptr)
            c.model = spec.model.cstr.ptr; c.num_threads = spec.numThreads; c.debug = 0; c.provider = spec.provider.cstr.ptr
            SherpaOnnxCreateSpeakerEmbeddingExtractor(c.ptr)
        } ?: throw IllegalStateException("声纹模型创建失败（检查模型路径）")
        return object : NativeSpeakerExtractor {
            override val dim: Int = SherpaOnnxSpeakerEmbeddingExtractorDim(ex)
            override fun embed(samples: FloatArray, sampleRate: Int): FloatArray {
                val s = SherpaOnnxSpeakerEmbeddingExtractorCreateStream(ex) ?: error("创建声纹流失败")
                try {
                    samples.usePinned { p -> SherpaOnnxOnlineStreamAcceptWaveform(s, sampleRate, p.addressOf(0), samples.size) }
                    SherpaOnnxOnlineStreamInputFinished(s)
                    check(SherpaOnnxSpeakerEmbeddingExtractorIsReady(ex, s) != 0) { "声纹：语音太短" }
                    val e = SherpaOnnxSpeakerEmbeddingExtractorComputeEmbedding(ex, s) ?: error("声纹计算失败")
                    try { return FloatArray(dim) { i -> e[i] } } finally { SherpaOnnxSpeakerEmbeddingExtractorDestroyEmbedding(e) }
                } finally { SherpaOnnxDestroyOnlineStream(s) }
            }
            override fun close() = SherpaOnnxDestroySpeakerEmbeddingExtractor(ex)
        }
    }
    /** 回调经 StableRef 把 Kotlin lambda 递给 C；返回 0 中止合成。 */
    private class TtsCallbackHolder(val onChunk: (FloatArray) -> Boolean, val all: ArrayList<FloatArray>)

    actual fun tts(spec: TtsSpec): NativeTts {
        val handle: CPointer<SherpaOnnxOfflineTts> = memScoped {
            val c = alloc<SherpaOnnxOfflineTtsConfig>()
            zeroed(kotlinx.cinterop.sizeOf<SherpaOnnxOfflineTtsConfig>(), c.ptr)
            c.model.num_threads = spec.numThreads
            c.model.provider = spec.provider.cstr.ptr
            c.model.debug = 0
            when (spec.kind) {
                TtsKind.VITS -> {
                    c.model.vits.model = spec.model.cstr.ptr; c.model.vits.lexicon = spec.lexicon.cstr.ptr; c.model.vits.tokens = spec.tokens.cstr.ptr
                    c.model.vits.data_dir = spec.dataDir.cstr.ptr; c.model.vits.dict_dir = "".cstr.ptr
                    c.model.vits.noise_scale = 0.667f; c.model.vits.noise_scale_w = 0.8f; c.model.vits.length_scale = spec.lengthScale
                }
                TtsKind.MATCHA -> {
                    c.model.matcha.acoustic_model = spec.model.cstr.ptr; c.model.matcha.vocoder = spec.vocoder.cstr.ptr
                    c.model.matcha.lexicon = spec.lexicon.cstr.ptr; c.model.matcha.tokens = spec.tokens.cstr.ptr
                    c.model.matcha.data_dir = spec.dataDir.cstr.ptr; c.model.matcha.dict_dir = "".cstr.ptr
                    c.model.matcha.noise_scale = 0.667f; c.model.matcha.length_scale = spec.lengthScale
                }
                TtsKind.KOKORO -> {
                    c.model.kokoro.model = spec.model.cstr.ptr; c.model.kokoro.voices = spec.voices.cstr.ptr; c.model.kokoro.tokens = spec.tokens.cstr.ptr
                    c.model.kokoro.data_dir = spec.dataDir.cstr.ptr; c.model.kokoro.dict_dir = "".cstr.ptr; c.model.kokoro.lexicon = spec.lexicon.cstr.ptr
                    c.model.kokoro.lang = spec.lang.cstr.ptr; c.model.kokoro.length_scale = spec.lengthScale
                }
            }
            c.rule_fsts = spec.ruleFsts.cstr.ptr
            c.rule_fars = "".cstr.ptr
            c.max_num_sentences = 1
            c.silence_scale = 0.2f
            SherpaOnnxCreateOfflineTts(c.ptr) ?: error("TTS 创建失败（模型文件缺失或损坏）")
        }
        return object : NativeTts {
            override val sampleRate: Int = SherpaOnnxOfflineTtsSampleRate(handle)
            override val numSpeakers: Int = SherpaOnnxOfflineTtsNumSpeakers(handle)
            override fun generate(text: String, sid: Int, speed: Float, onChunk: (FloatArray) -> Boolean): FloatArray {
                val holder = TtsCallbackHolder(onChunk, ArrayList())
                val ref = StableRef.create(holder)
                try {
                    val cb = staticCFunction { samples: CPointer<FloatVar>?, n: Int, arg: COpaquePointer? ->
                        val h = arg!!.asStableRef<TtsCallbackHolder>().get()
                        val chunk = FloatArray(n) { i -> samples!![i] }
                        h.all += chunk
                        if (h.onChunk(chunk)) 1 else 0
                    }
                    val audio = SherpaOnnxOfflineTtsGenerateWithCallbackWithArg(handle, text, sid, speed, cb, ref.asCPointer())
                    val out = if (audio != null) {
                        val a = audio.pointed
                        FloatArray(a.n) { i -> a.samples!![i] }.also { SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio) }
                    } else FloatArray(0)
                    return out
                } finally { ref.dispose() }
            }
            override fun close() = SherpaOnnxDestroyOfflineTts(handle)
        }
    }

}
