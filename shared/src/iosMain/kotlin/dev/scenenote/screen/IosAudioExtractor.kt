@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package dev.scenenote.screen

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderAudioMixOutput
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetTrack
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleaved
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.duration
import platform.AVFoundation.tracksWithMediaType
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMBlockBufferCopyDataBytes
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fseek
import platform.posix.fwrite
import kotlin.coroutines.coroutineContext

/**
 * iOS 抽音频（S4 快路径前置）：AVURLAsset → AVAssetReader + AVAssetReaderAudioMixOutput（多音轨自动混成一路）
 * → 线性 PCM 16 kHz 单声道 Int16 → 逐个 CMSampleBuffer 从 CMBlockBuffer 拷字节 → 写 .wav 到 NSTemporaryDirectory。
 * - 进度 = 当前 sample 的 presentation time / asset.duration（读完置 1）。
 * - 协程取消：检查 ensureActive，取消时 cancelReading 并删掉半成品。
 * - HLS（.m3u8 / .movpkg）AVAssetReader 不可读 → 直接抛"暂不支持流媒体链接"（模块规格 §3.2）。
 * - 抽取在 Dispatchers.Default 上跑；AVAssetReader 本身可在后台线程使用。
 */
class IosAudioExtractor : AudioExtractor {

    override suspend fun extractPcm16k(videoPath: String, onProgress: (Float) -> Unit): String = withContext(Dispatchers.Default) {
        if (videoPath.endsWith(".m3u8", ignoreCase = true) || videoPath.endsWith(".movpkg", ignoreCase = true)) {
            error("暂不支持流媒体链接：可改用已保存的视频文件。")
        }
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(videoPath), options = null)
        val totalSec = asset.duration.useContents { if (timescale == 0) 0.0 else value.toDouble() / timescale }
        val tracks = asset.tracksWithMediaType(AVMediaTypeAudio).filterIsInstance<AVAssetTrack>()
        if (tracks.isEmpty()) error("这个视频没有声音轨道。")

        val reader = memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            AVAssetReader.assetReaderWithAsset(asset, error = err.ptr)
                ?: error("视频无法读取${err.value?.localizedDescription?.let { "：$it" } ?: "。"}")
        }
        val output = AVAssetReaderAudioMixOutput(audioTracks = tracks, audioSettings = pcmSettings()).apply {
            alwaysCopiesSampleData = false
        }
        if (!reader.canAddOutput(output)) error("这个视频的声音格式暂不支持。")
        reader.addOutput(output)
        if (!reader.startReading()) error("视频无法读取：${reader.error?.localizedDescription ?: "未知原因"}")

        val outPath = NSTemporaryDirectory().trimEnd('/') + "/scenenote-pcm16k-" + NSUUID().UUIDString + ".wav"
        val file = fopen(outPath, "wb") ?: run { reader.cancelReading(); error("无法写入临时文件。") }
        var dataBytes = 0L
        var scratch = ByteArray(64 * 1024)
        try {
            try {
                // 先占位 44 字节头，读完回填长度
                wavHeader(0).usePinned { fwrite(it.addressOf(0), 1u, WAV_HEADER_SIZE.toULong(), file) }
                onProgress(0f)
                while (true) {
                    coroutineContext.ensureActive()
                    val sample = output.copyNextSampleBuffer() ?: break
                    try {
                        val block = CMSampleBufferGetDataBuffer(sample)
                        if (block != null) {
                            val len = CMBlockBufferGetDataLength(block).toInt()
                            if (len > 0) {
                                if (scratch.size < len) scratch = ByteArray(len)
                                val status = scratch.usePinned { CMBlockBufferCopyDataBytes(block, 0u, len.toULong(), it.addressOf(0)) }
                                if (status != 0) error("读取声音数据失败（$status）。")
                                scratch.usePinned { fwrite(it.addressOf(0), 1u, len.toULong(), file) }
                                dataBytes += len
                            }
                        }
                        if (totalSec > 0) {
                            val pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
                            onProgress((pts / totalSec).toFloat().coerceIn(0f, 0.999f))
                        }
                    } finally {
                        CFRelease(sample)
                    }
                }
                if (reader.status == AVAssetReaderStatusFailed) error("视频无法读取：${reader.error?.localizedDescription ?: "未知原因"}")
                // 回填 RIFF / data 长度
                fseek(file, 0, SEEK_SET)
                wavHeader(dataBytes.toInt()).usePinned { fwrite(it.addressOf(0), 1u, WAV_HEADER_SIZE.toULong(), file) }
            } finally {
                fclose(file)
            }
        } catch (t: Throwable) {
            reader.cancelReading()
            NSFileManager.defaultManager.removeItemAtPath(outPath, error = null)
            throw t
        }
        onProgress(1f)
        outPath
    }

    override suspend fun durationMs(videoPath: String): Long = withContext(Dispatchers.Default) {
        runCatching {
            val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(videoPath), options = null)
            asset.duration.useContents { if (timescale == 0) -1L else (value * 1000L) / timescale }
        }.getOrDefault(-1L)
    }

    /** 线性 PCM 16 kHz / 单声道 / 16 bit 有符号整数 / 小端 / 交织。 */
    private fun pcmSettings(): Map<Any?, Any?> = mapOf(
        AVFormatIDKey to kAudioFormatLinearPCM.toInt(),
        AVSampleRateKey to SAMPLE_RATE,
        AVNumberOfChannelsKey to 1,
        AVLinearPCMBitDepthKey to 16,
        AVLinearPCMIsFloatKey to false,
        AVLinearPCMIsBigEndianKey to false,
        AVLinearPCMIsNonInterleaved to false,
    )

    /** 44 字节 PCM WAV 头（RIFF / fmt / data），小端。 */
    private fun wavHeader(dataLen: Int): ByteArray {
        val h = ByteArray(WAV_HEADER_SIZE)
        fun str(off: Int, s: String) { s.forEachIndexed { i, c -> h[off + i] = c.code.toByte() } }
        fun i32(off: Int, v: Int) { h[off] = v.toByte(); h[off + 1] = (v shr 8).toByte(); h[off + 2] = (v shr 16).toByte(); h[off + 3] = (v shr 24).toByte() }
        fun i16(off: Int, v: Int) { h[off] = v.toByte(); h[off + 1] = (v shr 8).toByte() }
        val channels = 1; val bits = 16
        val blockAlign = channels * bits / 8
        str(0, "RIFF"); i32(4, 36 + dataLen); str(8, "WAVE")
        str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, channels); i32(24, SAMPLE_RATE); i32(28, SAMPLE_RATE * blockAlign); i16(32, blockAlign); i16(34, bits)
        str(36, "data"); i32(40, dataLen)
        return h
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WAV_HEADER_SIZE = 44
    }
}
