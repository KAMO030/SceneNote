package dev.scenenote.audio

import dev.scenenote.bench.WavIo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * 验收用"假麦克风"：按实时节奏（20 ms/帧）回放一个 16 kHz WAV，末尾补 1 s 静音让 VAD 收句。
 * 深链：scenenote://scene/<id>?autostart=1&feed=<bench 目录内文件名或绝对路径>
 */
class FileAudioSource(private val path: String, private val trailingSilenceMs: Int = 1500) : AudioSource {
    private val _route = MutableStateFlow(AudioRoute.BuiltIn)
    override val route: StateFlow<AudioRoute> = _route
    private var stopped = false
    private var config = CaptureConfig()

    override val frames: Flow<ShortArray> = flow {
        val pcm = WavIo.readPcm16k(path)
        val n = config.frameSamples
        var off = 0
        while (!stopped && off < pcm.size) {
            val end = minOf(off + n, pcm.size)
            val frame = ShortArray(n).also { pcm.copyInto(it, 0, off, end) }
            emit(frame); off += n
            delay(config.frameMs.toLong())
        }
        repeat(trailingSilenceMs / config.frameMs) { if (stopped) return@flow; emit(ShortArray(n)); delay(config.frameMs.toLong()) }
    }

    override suspend fun start(config: CaptureConfig) { this.config = config; stopped = false }
    override fun stop() { stopped = true }
}
