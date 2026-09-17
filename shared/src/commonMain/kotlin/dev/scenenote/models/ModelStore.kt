package dev.scenenote.models

import dev.scenenote.core.egress.EgressGate
import dev.scenenote.core.egress.EgressKind
import dev.scenenote.core.egress.EgressRequest
import dev.scenenote.core.egress.Egressed
import dev.scenenote.core.platform.AppPaths
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.HashingSink
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val file: String, val fileIndex: Int, val fileCount: Int, val bytesDone: Long, val bytesTotal: Long) : ModelState
    data class Installed(val bytes: Long) : ModelState
    data class Failed(val reason: String) : ModelState
}

/**
 * 模型仓库：`files/models/<packId>/<file>`。下载经 Egress（kind=model_asset，按镜像逐个尝试），临时文件写完并校验后改名。
 * 校验：有 sha256 用 sha256；否则用 bytes（清单里为 0 表示未知，只检查非空）。
 */
class ModelStore(private val paths: AppPaths, private val egress: EgressGate) {
    private val fs = FileSystem.SYSTEM
    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val states: StateFlow<Map<String, ModelState>> = _states

    fun dir(pack: ModelPack): String = paths.join(paths.filesDir, "models", pack.id)
    fun path(pack: ModelPack, file: ModelFile): String = paths.join(dir(pack), file.name)
    fun path(pack: ModelPack, prefix: String): String = path(pack, pack.file(prefix))
    fun join(pack: ModelPack, sub: String): String = paths.join(dir(pack), sub)

    fun isInstalled(pack: ModelPack): Boolean = pack.files.all { f -> paths.exists(path(pack, f)) && (f.bytes == 0L || paths.sizeBytes(path(pack, f)) == f.bytes) }

    fun refresh() {
        _states.value = ModelCatalog.all.associate { p ->
            p.id to (if (isInstalled(p)) ModelState.Installed(p.files.sumOf { paths.sizeBytes(path(p, it)) }) else (_states.value[p.id] as? ModelState.Downloading ?: ModelState.NotInstalled))
        }
    }

    fun delete(pack: ModelPack) { pack.files.forEach { paths.delete(path(pack, it)) }; paths.delete(dir(pack)); refresh() }

    /**
     * 下载缺失文件；进度经 states 与返回的 Flow 同时发出。
     * 取消（收集方离开）不算失败：不再试下一个镜像，把 Downloading 清回真实状态后原样抛出。
     */
    fun install(pack: ModelPack): Flow<ModelState> = flow {
        paths.ensureDir(dir(pack))
        val missing = pack.files.filterNot { f -> paths.exists(path(pack, f)) && (f.bytes == 0L || paths.sizeBytes(path(pack, f)) == f.bytes) }
        try {
            missing.forEachIndexed { idx, f ->
                var lastError: Throwable? = null
                for (url in f.urls ?: pack.mirrors.map { "$it/${f.name}" }) {
                    try {
                        downloadOne(pack, f, url) { done, total -> val s = ModelState.Downloading(f.name, idx + 1, missing.size, done, total); set(pack.id, s) }
                        lastError = null; break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) { lastError = t }
                }
                lastError?.let { throw it }
            }
            refresh()
            emit(_states.value[pack.id] ?: ModelState.NotInstalled)
        } catch (e: CancellationException) {
            set(pack.id, ModelState.NotInstalled); refresh()   // 已下完的文件保留，refresh 会据此判成 Installed / NotInstalled
            throw e
        } catch (t: Throwable) {
            val s = ModelState.Failed(t.message ?: t.toString()); set(pack.id, s); emit(s)
        }
    }.flowOn(Dispatchers.Default)

    private fun set(id: String, s: ModelState) { _states.value = _states.value + (id to s) }

    private suspend fun downloadOne(pack: ModelPack, f: ModelFile, url: String, progress: (Long, Long) -> Unit) {
        val host = Url(url).host
        val tmp = (path(pack, f) + ".part").toPath()
        val final = path(pack, f).toPath()
        if ('/' in f.name) paths.ensureDir(path(pack, f).substringBeforeLast('/'))   // 包内子目录（espeak-ng-data/…）
        egress.perform(EgressRequest(providerId = "model_mirror", host = host, kind = EgressKind.MODEL_ASSET, transport = "http")) { http ->
            var total = 0L
            http.prepareGet(url).execute { resp ->
                if (resp.status.value !in 200..299) error("HTTP ${resp.status.value} $url")
                val expected = resp.headers["Content-Length"]?.toLongOrNull() ?: f.bytes
                val channel: ByteReadChannel = resp.bodyAsChannel()
                withContext(Dispatchers.Default) {
                    val hashing = HashingSink.sha256(fs.sink(tmp))
                    hashing.buffer().use { sink ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = channel.readAvailable(buf, 0, buf.size)
                            if (n <= 0) break
                            sink.write(buf, 0, n); total += n
                            progress(total, expected)
                        }
                        sink.flush()
                    }
                    val digest = hashing.hash.hex()
                    if (f.sha256 != null && !digest.equals(f.sha256, ignoreCase = true)) { fs.delete(tmp); error("${f.name} 校验失败（sha256 不匹配）") }
                    if (f.bytes > 0 && total != f.bytes) { fs.delete(tmp); error("${f.name} 大小不符：$total ≠ ${f.bytes}") }
                    if (total == 0L) { fs.delete(tmp); error("${f.name} 下载为空") }
                    fs.atomicMove(tmp, final)
                }
            }
            Egressed(Unit, total)
        }
    }
}
