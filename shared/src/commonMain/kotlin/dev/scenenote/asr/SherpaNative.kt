package dev.scenenote.asr

// ---------- sherpa-onnx 原生句柄抽象（Android：AAR Kotlin API；iOS：C API cinterop） ----------

data class OnlineTransducerSpec(
    val encoder: String, val decoder: String, val joiner: String, val tokens: String,
    val numThreads: Int = 2, val decodingMethod: String = "greedy_search", val maxActivePaths: Int = 4,
    /** 端点规则（秒）：rule1 = 任意静音、rule2 = 有语音后的静音、rule3 = 最长句长（02 篇 §2.1：rule2 0.5–0.7 s）。 */
    val rule1TrailingSilence: Float = 2.4f, val rule2TrailingSilence: Float = 0.6f, val rule3MaxUtterance: Float = 20f,
    val hotwordsFile: String = "", val hotwordsScore: Float = 1.5f, val provider: String = "cpu",
)

data class SenseVoiceSpec(
    val model: String, val tokens: String,
    /** "auto" / "zh" / "en" / "yue" / "ja" / "ko"。 */
    val language: String = "auto", val useItn: Boolean = true, val numThreads: Int = 2, val provider: String = "cpu",
)

/** 非流式 Paraformer（四川话包）。 */
data class ParaformerSpec(val model: String, val tokens: String, val numThreads: Int = 2, val provider: String = "cpu")

data class VadSpec(
    val model: String, val threshold: Float = 0.5f, val minSilenceSec: Float = 0.25f, val minSpeechSec: Float = 0.25f,
    val windowSize: Int = 512, val maxSpeechSec: Float = 20f, val bufferSec: Float = 30f, val numThreads: Int = 1,
)

data class SpeakerSpec(val model: String, val numThreads: Int = 1, val provider: String = "cpu")

/** 端侧 TTS 模型种类（sherpa-onnx OfflineTts）。 */
enum class TtsKind { VITS, MATCHA, KOKORO }
data class TtsSpec(
    val kind: TtsKind, val model: String, val tokens: String,
    val lexicon: String = "", val vocoder: String = "", val voices: String = "", val dataDir: String = "",
    val ruleFsts: String = "", val lang: String = "",
    val numThreads: Int = 2, val lengthScale: Float = 1f, val provider: String = "cpu",
)

/** 端侧 TTS：generate 阻塞直到整句合成完；onChunk 每个内部块（约 1 句 / 若干百毫秒）回调一次，返回 false 中止。 */
interface NativeTts {
    val sampleRate: Int
    val numSpeakers: Int
    fun generate(text: String, sid: Int, speed: Float, onChunk: (FloatArray) -> Boolean): FloatArray
    fun close()
}

data class NativeOnlineResult(val text: String, val tokens: List<String>, val timestamps: FloatArray)
data class NativeOfflineResult(val text: String, val lang: String, val emotion: String, val event: String, val tokens: List<String>, val timestamps: FloatArray)
class VadSegment(val startSample: Int, val samples: FloatArray)

interface NativeOnlineStream : AutoCloseable {
    fun accept(samples: FloatArray, sampleRate: Int = 16_000)
    fun inputFinished()
}

interface NativeOnlineRecognizer : AutoCloseable {
    fun createStream(): NativeOnlineStream
    fun isReady(stream: NativeOnlineStream): Boolean
    fun decode(stream: NativeOnlineStream)
    fun result(stream: NativeOnlineStream): NativeOnlineResult
    fun isEndpoint(stream: NativeOnlineStream): Boolean
    fun reset(stream: NativeOnlineStream)
}

interface NativeOfflineRecognizer : AutoCloseable {
    fun transcribe(samples: FloatArray, sampleRate: Int = 16_000): NativeOfflineResult
}

interface NativeVad : AutoCloseable {
    fun accept(samples: FloatArray)
    fun isSpeechDetected(): Boolean
    /** 取出并清空已完成的语音段。 */
    fun popSegments(): List<VadSegment>
    fun flush()
    fun reset()
}

interface NativeSpeakerExtractor : AutoCloseable {
    val dim: Int
    fun embed(samples: FloatArray, sampleRate: Int = 16_000): FloatArray
}

/** 平台工厂；模型文件路径必须是沙盒内的绝对路径（不用 assets）。创建失败抛 IllegalStateException。 */
expect object SherpaNative {
    fun online(spec: OnlineTransducerSpec): NativeOnlineRecognizer
    fun senseVoice(spec: SenseVoiceSpec): NativeOfflineRecognizer
    fun paraformer(spec: ParaformerSpec): NativeOfflineRecognizer
    fun vad(spec: VadSpec): NativeVad
    fun speaker(spec: SpeakerSpec): NativeSpeakerExtractor
    fun tts(spec: TtsSpec): NativeTts
}

/** PCM16 → Float32 [-1, 1]。 */
fun ShortArray.toFloatPcm(): FloatArray = FloatArray(size) { i -> this[i] / 32768f }

/** 余弦相似度（声纹判向用）。 */
fun cosine(a: FloatArray, b: FloatArray): Float {
    var dot = 0f; var na = 0f; var nb = 0f
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    val d = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
    return if (d == 0f) 0f else dot / d
}
