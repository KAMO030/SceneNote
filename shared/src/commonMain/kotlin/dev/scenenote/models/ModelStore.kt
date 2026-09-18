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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.HashingSink
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use
import kotlin.time.TimeSource

sealed interface ModelState {
    data object NotInstalled : ModelState
    /** [bytesDone] / [bytesTotal] 按整包算（已在本机的文件也计入），不是当前这一个文件。 */
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
    /** 正在下载的包 id：同一个包只跑一路（连点「下载」、自动下载撞上手动下载时，后来的直接结束，免得两路写同一个 `.part`）。 */
    private val active = MutableStateFlow<Set<String>>(emptySet())

    fun dir(pack: ModelPack): String = paths.join(paths.filesDir, "models", pack.id)
    fun path(pack: ModelPack, file: ModelFile): String = paths.join(dir(pack), file.name)
    fun path(pack: ModelPack, prefix: String): String = path(pack, pack.file(prefix))
    fun join(pack: ModelPack, sub: String): String = paths.join(dir(pack), sub)

    private fun present(pack: ModelPack, f: ModelFile): Boolean = paths.exists(path(pack, f)) && (f.bytes == 0L || paths.sizeBytes(path(pack, f)) == f.bytes)
    fun isInstalled(pack: ModelPack): Boolean = pack.files.all { present(pack, it) }

    /** 按磁盘重算各包状态；正在下载的包保持 Downloading（多个包并行下载时各自的进度互不覆盖）。 */
    fun refresh() {
        val disk = ModelCatalog.all.associate { p -> p.id to (if (isInstalled(p)) ModelState.Installed(p.files.sumOf { paths.sizeBytes(path(p, it)) }) else null) }
        _states.update { cur -> disk.mapValues { (id, s) -> s ?: (cur[id] as? ModelState.Downloading ?: ModelState.NotInstalled) } }
    }

    /**
     * 清单里已经没有的旧包目录（改版换掉的模型，如 nmt-en-mul）：语音包页上看不到也删不掉，白占一两百 MB，进语音包页时清掉。
     * 只动 `models/` 下目录名不在清单里的；正在下载的不碰。
     */
    fun pruneOrphans() {
        val root = paths.join(paths.filesDir, "models")
        if (!paths.exists(root)) return
        val keep = ModelCatalog.all.map { it.id }.toSet() + active.value
        paths.listFiles(root).map { it.substringAfterLast('/') }.filter { it.isNotEmpty() && it !in keep }
            .forEach { runCatching { fs.deleteRecursively(paths.join(root, it).toPath()) } }
    }

    /** 连同派生文件一起删（NMT 包下载后本机改写的 `*.lmhead.onnx`、未完成的 `.part`），目录整个清掉。 */
    fun delete(pack: ModelPack) { pack.files.forEach { paths.delete(path(pack, it)) }; runCatching { fs.deleteRecursively(dir(pack).toPath()) }; paths.delete(dir(pack)); refresh() }

    /**
     * 下载缺失文件；进度经 states 与返回的 Flow 同时发出。
     * 进度按整包字节算（逐文件算的话多文件包每个文件都从 0 跳到 100%，TTS 包有上百个小文件，进度条来回抽），最多每 [PROGRESS_MS] 发一次。
     * 同一个包已在下载时直接结束（不发任何状态）。
     * 取消（收集方离开）不算失败：不再试下一个镜像，把 Downloading 清回真实状态后原样抛出。
     */
    fun install(pack: ModelPack): Flow<ModelState> = flow {
        if (!claim(pack.id)) return@flow
        try {
            paths.ensureDir(dir(pack))
            val missing = pack.files.filterNot { present(pack, it) }
            val total = pack.totalBytes
            var base = total - missing.sumOf { it.bytes }   // 已在本机的文件
            // 连上镜像之前就切到下载中：按钮立刻消失，不会被再点一次
            set(pack.id, ModelState.Downloading(missing.firstOrNull()?.name.orEmpty(), 1, missing.size, base, total))
            val clock = TimeSource.Monotonic.markNow()
            var lastMs = -PROGRESS_MS
            missing.forEachIndexed { idx, f ->
                var lastError: Throwable? = null
                for (url in f.urls ?: pack.mirrors.map { "$it/${f.name}" }) {
                    try {
                        downloadOne(pack, f, url) { fileDone ->
                            val now = clock.elapsedNow().inWholeMilliseconds
                            if (now - lastMs >= PROGRESS_MS) { lastMs = now; set(pack.id, ModelState.Downloading(f.name, idx + 1, missing.size, base + fileDone, total)) }
                        }
                        lastError = null; break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) { lastError = t }
                }
                lastError?.let { throw it }
                base += f.bytes
            }
            settle(pack)
            emit(_states.value[pack.id] ?: ModelState.NotInstalled)
        } catch (e: CancellationException) {
            settle(pack)   // 已下完的文件保留，据此判成 Installed / NotInstalled
            throw e
        } catch (t: Throwable) {
            val s = ModelState.Failed(t.message ?: t.toString()); set(pack.id, s); emit(s)
        } finally {
            active.update { it - pack.id }
        }
    }.flowOn(Dispatchers.Default)

    private fun claim(id: String): Boolean {
        while (true) {
            val cur = active.value
            if (id in cur) return false
            if (active.compareAndSet(cur, cur + id)) return true
        }
    }

    /** 并行下载的几个包都在改同一张表：一律原子更新，别互相覆盖。 */
    private fun set(id: String, s: ModelState) { _states.update { it + (id to s) } }

    /** 下载结束（完成 / 取消）后按磁盘落定这一个包的状态。 */
    private fun settle(pack: ModelPack) = set(pack.id, if (isInstalled(pack)) ModelState.Installed(pack.files.sumOf { paths.sizeBytes(path(pack, it)) }) else ModelState.NotInstalled)

    /** [progress]：本文件已写入的字节数。 */
    private suspend fun downloadOne(pack: ModelPack, f: ModelFile, url: String, progress: (Long) -> Unit) {
        val host = Url(url).host
        val tmp = (path(pack, f) + ".part").toPath()
        val final = path(pack, f).toPath()
        if ('/' in f.name) paths.ensureDir(path(pack, f).substringBeforeLast('/'))   // 包内子目录（espeak-ng-data/…）
        egress.perform(EgressRequest(providerId = "model_mirror", host = host, kind = EgressKind.MODEL_ASSET, transport = "http")) { http ->
            var total = 0L
            http.prepareGet(url).execute { resp ->
                if (resp.status.value !in 200..299) error("HTTP ${resp.status.value} $url")
                val channel: ByteReadChannel = resp.bodyAsChannel()
                withContext(Dispatchers.Default) {
                    val hashing = HashingSink.sha256(fs.sink(tmp))
                    hashing.buffer().use { sink ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = channel.readAvailable(buf, 0, buf.size)
                            if (n <= 0) break
                            sink.write(buf, 0, n); total += n
                            progress(total)
                        }
                        sink.flush()
                    }
                    val digest = hashing.hash.hex()
                    if (f.sha256 != null && !digest.equals(f.sha256, ignoreCase = true)) { fs.delete(tmp); error("${f.name}: sha256 mismatch") }
                    if (f.bytes > 0 && total != f.bytes) { fs.delete(tmp); error("${f.name}: size mismatch $total ≠ ${f.bytes}") }
                    if (total == 0L) { fs.delete(tmp); error("${f.name}: empty download") }
                    fs.atomicMove(tmp, final)
                }
            }
            Egressed(Unit, total)
        }
    }

    private companion object { const val PROGRESS_MS = 100L }
}
