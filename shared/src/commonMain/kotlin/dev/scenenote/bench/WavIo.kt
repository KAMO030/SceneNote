package dev.scenenote.bench

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

/** 最小 WAV 读取：PCM16，任意声道取第一声道，非 16 kHz 做线性重采样（只给基准 / 样本用，不做生产解码）。 */
object WavIo {
    fun readPcm16k(path: String): ShortArray {
        val bytes = FileSystem.SYSTEM.read(path.toPath()) { readByteArray() }
        require(bytes.size > 44 && bytes.decodeToString(0, 4) == "RIFF" && bytes.decodeToString(8, 12) == "WAVE") { "不是 WAV 文件：$path" }
        var pos = 12
        var channels = 1; var sampleRate = 16_000; var bits = 16
        var data: ShortArray? = null
        while (pos + 8 <= bytes.size) {
            val id = bytes.decodeToString(pos, pos + 4)
            val size = le32(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> { channels = le16(bytes, body + 2); sampleRate = le32(bytes, body + 4); bits = le16(bytes, body + 14) }
                "data" -> {
                    require(bits == 16) { "只支持 16-bit PCM（当前 $bits-bit）" }
                    val n = minOf(size, bytes.size - body) / 2
                    val all = ShortArray(n) { i -> ((bytes[body + 2 * i].toInt() and 0xFF) or (bytes[body + 2 * i + 1].toInt() shl 8)).toShort() }
                    data = if (channels <= 1) all else ShortArray(n / channels) { i -> all[i * channels] }
                }
            }
            pos = body + size + (size and 1)
        }
        val pcm = data ?: error("WAV 无 data 块")
        return if (sampleRate == 16_000) pcm else resample(pcm, sampleRate, 16_000)
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)

    private fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        val n = (src.size.toLong() * to / from).toInt()
        return ShortArray(n) { i ->
            val x = i.toDouble() * from / to
            val i0 = x.toInt().coerceIn(0, src.size - 1); val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val f = x - i0
            (src[i0] * (1 - f) + src[i1] * f).toInt().toShort()
        }
    }
}
