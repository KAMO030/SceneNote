package dev.scenenote.tts

import kotlin.math.roundToInt

data class TtsRequest(val text: String, val lang: String, val utteranceId: String, val rate: Float = 1f)

/** 合成统计：首块延迟用于 06 篇的"句尾 → 首音"验收。 */
data class TtsStats(val engineId: String, val sampleRate: Int, val samples: Int, val firstChunkMs: Long, val totalMs: Long)

class TtsFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * TTS 引擎：只产 PCM，不直接出声（05 篇 §7.3 "TTS 输出路径唯一"）。
 * onChunk 收到的是 16 kHz 单声道 PCM16（引擎内部完成重采样）；返回 false 表示取消。
 */
interface TtsEngine {
    val id: String
    fun supports(lang: String): Boolean
    suspend fun synthesize(req: TtsRequest, onChunk: suspend (ShortArray) -> Boolean): TtsStats
    fun close() {}
}

/** 线性重采样到 16 kHz（TTS 模型多为 22.05 / 24 / 44.1 kHz；AudioSink 固定 16 kHz）。 */
object Resample {
    fun toPcm16(src: FloatArray, from: Int, to: Int = 16_000): ShortArray {
        if (src.isEmpty()) return ShortArray(0)
        if (from == to) return ShortArray(src.size) { clamp(src[it]) }
        val n = (src.size.toLong() * to / from).toInt()
        return ShortArray(n) { i ->
            val x = i.toDouble() * from / to
            val i0 = x.toInt().coerceIn(0, src.size - 1); val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val f = (x - i0).toFloat()
            clamp(src[i0] * (1 - f) + src[i1] * f)
        }
    }
    fun toPcm16(src: ShortArray, from: Int, to: Int = 16_000): ShortArray {
        if (from == to || src.isEmpty()) return src
        val n = (src.size.toLong() * to / from).toInt()
        return ShortArray(n) { i ->
            val x = i.toDouble() * from / to
            val i0 = x.toInt().coerceIn(0, src.size - 1); val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val f = x - i0
            (src[i0] * (1 - f) + src[i1] * f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
    private fun clamp(v: Float): Short = (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
}
