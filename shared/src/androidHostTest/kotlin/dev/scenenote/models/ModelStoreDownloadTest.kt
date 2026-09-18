package dev.scenenote.models

import com.russhwolf.settings.MapSettings
import com.sun.net.httpserver.HttpServer
import dev.scenenote.core.egress.ConsentRegistry
import dev.scenenote.core.egress.Destination
import dev.scenenote.core.egress.EgressGate
import dev.scenenote.core.egress.Ledger
import dev.scenenote.core.model.LedgerEntry
import dev.scenenote.core.egress.SpendGate
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.core.settings.AppSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 本机起一个慢速 HTTP 服务当镜像，验证整包进度与同包去重（不走外网）。 */
class ModelStoreDownloadTest {
    private val blobs = linkedMapOf(
        "encoder.onnx" to ByteArray(1_500_000) { (it * 7).toByte() },
        "sub/data.bin" to ByteArray(900_000) { (it * 3).toByte() },
        "tokens.txt" to ByteArray(4_000) { 'a'.code.toByte() },
    )
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private lateinit var server: HttpServer
    private lateinit var root: File

    @BeforeTest fun setUp() {
        root = Files.createTempDirectory("modelstore").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val name = ex.requestURI.path.removePrefix("/")
            val body = blobs[name]
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            hits.getOrPut(name) { AtomicInteger() }.incrementAndGet()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { out -> body.asList().chunked(64 * 1024).forEach { out.write(it.toByteArray()); out.flush(); Thread.sleep(15) } }
        }
        server.start()
    }

    @AfterTest fun tearDown() { server.stop(0); root.deleteRecursively() }

    private fun pack() = ModelPack(
        id = "test-pack", kind = ModelKind.VAD, name = "test", langs = listOf("*"),
        files = blobs.map { (n, b) -> ModelFile(n, b.size.toLong()) },
        mirrors = listOf("http://127.0.0.1:${server.address.port}"), license = "", version = "",
    )

    private fun store(): ModelStore {
        val settings = AppSettings(MapSettings())
        val ledger = NoLedger()
        return ModelStore(FilePaths(root.path), EgressGate(settings, ConsentRegistry(), SpendGate(settings, ledger), ledger, EgressGate.defaultHttpClient()))
    }

    @Test fun progressCoversWholePackAndNeverJumpsBack() = runBlocking {
        val store = store(); val pack = pack()
        val seen = CopyOnWriteArrayList<ModelState>()
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { store.states.collect { m -> m[pack.id]?.let { if (seen.lastOrNull() != it) seen += it } } }
        val result = withTimeout(60_000) { store.install(pack).toList() }
        watcher.cancel()
        assertIs<ModelState.Installed>(result.single())
        val progress = seen.filterIsInstance<ModelState.Downloading>()
        assertTrue(progress.size >= 3, "expected several progress updates, got $progress")
        progress.forEach { assertEquals(pack.totalBytes, it.bytesTotal) }
        progress.zipWithNext().forEach { (a, b) -> assertTrue(b.bytesDone >= a.bytesDone, "progress went back: $a → $b") }
        assertTrue(progress.any { it.fileIndex == 2 && it.bytesDone > blobs.getValue("encoder.onnx").size }, "second file's progress should include the first file")
        assertTrue(store.isInstalled(pack))
    }

    @Test fun samePackIsDownloadedOnlyOnce() = runBlocking {
        val store = store(); val pack = pack()
        val first = async(Dispatchers.Default) { store.install(pack).toList() }
        withTimeout(10_000) { store.states.first { it[pack.id] is ModelState.Downloading } }
        val second = withTimeout(10_000) { store.install(pack).toList() }   // 同包在下：直接结束，不发状态
        assertTrue(second.isEmpty(), "duplicate install should be a no-op, got $second")
        assertIs<ModelState.Installed>(withTimeout(60_000) { first.await() }.single())
        blobs.keys.forEach { assertEquals(1, hits[it]?.get(), "$it fetched ${hits[it]?.get()} times") }
    }

    @Test fun onlyMissingFilesAreFetchedAndCountedFromWhatIsOnDisk() = runBlocking {
        val pack = pack()
        File(root, "models/${pack.id}").mkdirs()
        File(root, "models/${pack.id}/encoder.onnx").writeBytes(blobs.getValue("encoder.onnx"))
        val store = store()
        val seen = CopyOnWriteArrayList<ModelState>()
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { store.states.collect { m -> m[pack.id]?.let { seen += it } } }
        assertIs<ModelState.Installed>(withTimeout(60_000) { store.install(pack).toList() }.single())
        watcher.cancel()
        assertEquals(null, hits["encoder.onnx"])
        val progress = seen.filterIsInstance<ModelState.Downloading>()
        assertTrue(progress.isNotEmpty())
        progress.forEach { assertTrue(it.bytesDone >= blobs.getValue("encoder.onnx").size, "should start from what's on disk, not 0: $it") }
    }

    @Test fun orphanPackDirsAreRemovedButCatalogPacksKept() {
        File(root, "models/nmt-en-mul/onnx").mkdirs()
        File(root, "models/nmt-en-mul/onnx/encoder_model_quantized.onnx").writeBytes(ByteArray(10))
        File(root, "models/${ModelCatalog.vadSilero.id}").mkdirs()
        File(root, "models/${ModelCatalog.vadSilero.id}/silero_vad.onnx").writeBytes(ByteArray(10))
        store().pruneOrphans()
        assertTrue(!File(root, "models/nmt-en-mul").exists(), "orphan pack dir should be gone")
        assertTrue(File(root, "models/${ModelCatalog.vadSilero.id}/silero_vad.onnx").exists(), "catalog pack must be kept")
    }
}

private class FilePaths(override val filesDir: String) : AppPaths {
    override val cacheDir: String get() = "$filesDir/cache"
    override fun ensureDir(path: String) { File(path).mkdirs() }
    override fun exists(path: String): Boolean = File(path).exists()
    override fun sizeBytes(path: String): Long = File(path).length()
    override fun listFiles(dir: String): List<String> = File(dir).listFiles()?.map { it.absolutePath }?.sorted().orEmpty()   // 与平台实现一致：完整路径
    override fun delete(path: String): Boolean = File(path).delete()
    override fun readText(path: String): String? = File(path).takeIf { it.exists() }?.readText()
    override fun appendText(path: String, text: String) { File(path).appendText(text) }
}

private class NoLedger : Ledger {
    override suspend fun record(entry: LedgerEntry) {}
    override fun since(ts: Long): Flow<List<LedgerEntry>> = flowOf(emptyList())
    override suspend fun monthlyEstCost(): Double = 0.0
    override suspend fun bytesSince(ts: Long, destination: Destination): Long = 0
}
