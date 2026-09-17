package dev.scenenote.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * PCM16 16 kHz 单声道 → AAC-LC .m4a（MediaCodec 同步模式 + MediaMuxer）。
 * write() 在采集线程调用，必须快：只做入队 + 出队，不做阻塞等待（超时 0）。
 */
class AndroidAacFileWriter(private val path: String, private val sampleRate: Int = 16_000, bitrate: Int = 32_000) : PcmFileWriter {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxerStarted = false
    private val info = MediaCodec.BufferInfo()
    private var presentationUs = 0L
    private var closed = false
    private val pending = ArrayDeque<ByteArray>()

    override fun write(frame: ShortArray) {
        if (closed) return
        val bytes = ByteArray(frame.size * 2)
        for (i in frame.indices) { val v = frame[i].toInt(); bytes[2 * i] = (v and 0xFF).toByte(); bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte() }
        pending.addLast(bytes)
        feed(timeoutUs = 0)
        drain(timeoutUs = 0)
    }

    private fun feed(timeoutUs: Long) {
        while (pending.isNotEmpty()) {
            val idx = codec.dequeueInputBuffer(timeoutUs)
            if (idx < 0) return
            val chunk = pending.removeFirst()
            val buf: ByteBuffer = codec.getInputBuffer(idx) ?: return
            buf.clear(); buf.put(chunk)
            codec.queueInputBuffer(idx, 0, chunk.size, presentationUs, 0)
            presentationUs += chunk.size / 2 * 1_000_000L / sampleRate
        }
    }

    private fun drain(timeoutUs: Long): Boolean {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx)
                    if (out != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        out.position(info.offset); out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                    }
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(idx, false)
                    if (eos) return true
                }
            }
        }
    }

    override fun close(): String {
        if (closed) return path
        closed = true
        runCatching {
            // 把剩余帧喂完，再发 EOS，等编码器排空。
            var guard = 0
            while (pending.isNotEmpty() && guard++ < 200) { feed(timeoutUs = 10_000); drain(timeoutUs = 0) }
            val idx = codec.dequeueInputBuffer(50_000)
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            var eos = false; guard = 0
            while (!eos && guard++ < 100) eos = drain(timeoutUs = 10_000)
        }
        runCatching { codec.stop() }; runCatching { codec.release() }
        runCatching { if (muxerStarted) muxer.stop() }; runCatching { muxer.release() }
        return path
    }
}
