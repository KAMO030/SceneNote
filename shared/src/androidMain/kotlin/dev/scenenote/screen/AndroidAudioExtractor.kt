package dev.scenenote.screen

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.scenenote.core.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android 抽音频（S4）：MediaExtractor 选第一条音轨 → MediaCodec 解成 PCM → 多声道平均成单声道 → 线性重采样到 16 kHz
 * → 写 PCM16 WAV（44 字节头）到 `cacheDir/extract/`。全程在 IO 线程；协程取消时释放解码器并删掉半成品。
 *
 * - 进度 = 当前输出帧 presentationTime / 时长（时长优先取轨道 KEY_DURATION，没有再问 MediaMetadataRetriever）。
 * - 解码器输出可能是 PCM16 / PCM Float（KEY_PCM_ENCODING），两种都转成 float 再混 / 重采样；采样率 / 声道数以
 *   INFO_OUTPUT_FORMAT_CHANGED 后的输出格式为准（AAC HE-v2 等的轨道声明值不一定等于实际输出）。
 * - 输入既可以是文件路径也可以是 content:// URI（选择器已复制到 cacheDir，这里只是兜底）。
 */
class AndroidAudioExtractor(context: Context) : AudioExtractor {
    private val ctx = context.applicationContext

    override suspend fun extractPcm16k(videoPath: String, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        val outDir = File(ctx.cacheDir, "extract").apply { mkdirs() }
        val out = File(outDir, "${stableName(videoPath)}-16k.wav")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var writer: WavWriter? = null
        try {
            setSource(extractor, videoPath)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("这个视频没有声音")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("这个视频没有声音")
            extractor.selectTrack(track)
            val durationUs = (if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L)
                .takeIf { it > 0 } ?: (durationMs(videoPath) * 1000).takeIf { it > 0 } ?: -1L

            val dec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrElse { error("不支持这种声音格式") }
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            var sampleRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = format.getIntegerOr(KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            var resampler = LinearResampler(sampleRate, TARGET_RATE)
            val wav = WavWriter(out, TARGET_RATE); writer = wav
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastProgress = -1f
            var idleRounds = 0
            var mono = FloatArray(4096)
            var pcm16 = ShortArray(4096)
            onProgress(0f)

            while (!outputDone) {
                ensureActive()
                if (idleRounds > 600) error("这个视频的声音解不出来")   // 输入已送完却 6 s 没有任何输出：解码器卡死
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx) ?: continue
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                idleRounds = if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) idleRounds + 1 else 0
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        sampleRate = f.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = f.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = f.getIntegerOr(KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        resampler = LinearResampler(sampleRate, TARGET_RATE)
                        Diag.log("extract", "format sr=$sampleRate ch=$channels enc=$pcmEncoding mime=$mime")
                    }
                    outIdx >= 0 -> {
                        val buf = dec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val frames = frameCount(info.size, channels, pcmEncoding)
                            if (mono.size < frames) mono = FloatArray(frames)
                            toMono(buf, channels, pcmEncoding, frames, mono)
                            val need = resampler.outputCapacity(frames)
                            if (pcm16.size < need) pcm16 = ShortArray(need)
                            val produced = resampler.process(mono, frames, pcm16)
                            wav.write(pcm16, produced)
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (durationUs > 0) {
                            val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                            if (p - lastProgress >= 0.005f) { lastProgress = p; onProgress(p) }
                        }
                    }
                }
            }
            wav.close(); writer = null
            onProgress(1f)
            Diag.log("extract", "done ${out.name} bytes=${out.length()} dur=${durationUs / 1000} ms")
            out.absolutePath
        } catch (t: Throwable) {
            runCatching { writer?.close() }
            out.delete()
            if (t is MediaCodec.CodecException || t is java.io.IOException) {
                Diag.log("extract", "failed: ${t.message}")
                throw IllegalStateException("这个视频的声音解不出来", t)
            }
            throw t
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    override suspend fun durationMs(videoPath: String): Long = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            if (videoPath.startsWith("content://")) r.setDataSource(ctx, Uri.parse(videoPath)) else r.setDataSource(videoPath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
        } catch (_: Throwable) {
            -1L
        } finally {
            runCatching { r.release() }
        }
    }

    private fun setSource(extractor: MediaExtractor, path: String) {
        if (path.startsWith("content://")) extractor.setDataSource(ctx, Uri.parse(path), null) else extractor.setDataSource(path)
    }

    /** 同一输入总是落到同一个输出名（路径 hash + 文件大小），重复抽取直接覆盖，不堆积。 */
    private fun stableName(path: String): String {
        val f = File(path)
        val h = (path.hashCode().toLong() and 0xffffffffL).toString(16)
        return if (f.exists()) "$h-${f.length()}" else h
    }

    private fun MediaFormat.getIntegerOr(key: String, default: Int): Int = if (containsKey(key)) getInteger(key) else default

    private fun frameCount(bytes: Int, channels: Int, encoding: Int): Int {
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else if (encoding == AudioFormat.ENCODING_PCM_8BIT) 1 else 2
        return bytes / (bytesPerSample * channels.coerceAtLeast(1))
    }

    /** 各声道取平均 → [-1, 1] 的 float。 */
    private fun toMono(buf: ByteBuffer, channels: Int, encoding: Int, frames: Int, out: FloatArray) {
        val ch = channels.coerceAtLeast(1)
        val inv = 1f / ch
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until ch) s += fb.get(i * ch + c)
                    out[i] = s * inv
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until ch) s += ((buf.get(i * ch + c).toInt() and 0xff) - 128) / 128f
                    out[i] = s * inv
                }
            }
            else -> {
                val sb = buf.asShortBuffer()
                for (i in 0 until frames) {
                    var s = 0f
                    for (c in 0 until ch) s += sb.get(i * ch + c) / 32768f
                    out[i] = s * inv
                }
            }
        }
    }

    companion object {
        const val TARGET_RATE = 16_000
        private const val KEY_PCM_ENCODING = "pcm-encoding"   // MediaFormat.KEY_PCM_ENCODING（API 24+）
    }
}

/**
 * 线性插值重采样（任意采样率 → 16 kHz）。跨块保持相位：记住上一块的最后一个样本与小数位置。
 * 语音识别用不着高阶滤波；44.1k → 16k 的混叠对 zipformer / SenseVoice 影响可忽略。
 */
internal class LinearResampler(private val srcRate: Int, private val dstRate: Int) {
    private val step = srcRate.toDouble() / dstRate
    private var t = 0.0          // 当前输出点相对 prev 的位置（0 ≤ t < 1，单位：输入样本）
    private var prev = 0f
    private var hasPrev = false

    fun outputCapacity(inputFrames: Int): Int = (inputFrames / step).toInt() + 4

    /** 返回写入 out 的样本数。 */
    fun process(input: FloatArray, n: Int, out: ShortArray): Int {
        if (n <= 0) return 0
        if (srcRate == dstRate) {
            for (i in 0 until n) out[i] = clip(input[i])
            return n
        }
        var produced = 0
        var i = 0
        if (!hasPrev) { prev = input[0]; hasPrev = true; i = 1; t = 0.0 }
        // 区间 [prev, input[i]]：输出 prev + (cur - prev) * t，然后 t += step；t ≥ 1 时向前推进一个输入样本
        while (i < n) {
            val cur = input[i]
            while (t < 1.0) {
                if (produced >= out.size) return produced
                out[produced++] = clip(prev + (cur - prev) * t.toFloat())
                t += step
            }
            t -= 1.0
            prev = cur
            i++
        }
        return produced
    }

    private fun clip(v: Float): Short {
        val s = (v * 32767f).toInt()
        return (if (s > 32767) 32767 else if (s < -32768) -32768 else s).toShort()
    }
}

/** PCM16 单声道 WAV：先占 44 字节头，close 时回填 RIFF / data 长度。 */
internal class WavWriter(private val file: File, private val sampleRate: Int) {
    private val stream = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var dataBytes = 0L
    private var scratch = ByteArray(8192)

    init { stream.write(ByteArray(44)) }

    fun write(samples: ShortArray, n: Int) {
        if (n <= 0) return
        if (scratch.size < n * 2) scratch = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = samples[i].toInt()
            scratch[i * 2] = (v and 0xff).toByte()
            scratch[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        stream.write(scratch, 0, n * 2)
        dataBytes += n * 2
    }

    fun close() {
        stream.flush(); stream.close()
        RandomAccessFile(file, "rw").use { raf ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt())
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            header.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(dataBytes.toInt())
            raf.seek(0); raf.write(header.array())
        }
    }
}

