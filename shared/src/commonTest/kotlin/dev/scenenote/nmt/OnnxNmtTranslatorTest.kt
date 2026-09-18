package dev.scenenote.nmt

import com.russhwolf.settings.MapSettings
import dev.scenenote.core.egress.ConsentRegistry
import dev.scenenote.core.egress.Destination
import dev.scenenote.core.egress.EgressGate
import dev.scenenote.core.egress.Ledger
import dev.scenenote.core.egress.SpendGate
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.LedgerEntry
import dev.scenenote.core.platform.AppPaths
import dev.scenenote.core.settings.AppSettings
import dev.scenenote.models.ModelCatalog
import dev.scenenote.models.ModelStore
import dev.scenenote.translate.MtRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** 指向一个现成目录的 AppPaths（models/<pack>/… 已就位，不下载）。 */
private class DirPaths(override val filesDir: String) : AppPaths {
    private val fs = FileSystem.SYSTEM
    override val cacheDir: String get() = "$filesDir/cache"
    override fun ensureDir(path: String) { fs.createDirectories(path.toPath()) }
    override fun exists(path: String): Boolean = fs.exists(path.toPath())
    override fun sizeBytes(path: String): Long = runCatching { fs.metadataOrNull(fs.canonicalize(path.toPath()))?.size }.getOrNull() ?: 0L   // 测试目录里是符号链接
    override fun listFiles(dir: String): List<String> = fs.list(dir.toPath()).map { it.toString() }
    override fun delete(path: String): Boolean { fs.delete(path.toPath(), mustExist = false); return true }
    override fun readText(path: String): String? = if (exists(path)) fs.read(path.toPath()) { readUtf8() } else null
    override fun appendText(path: String, text: String) { fs.appendingSink(path.toPath()).buffer().use { it.writeUtf8(text) } }
}

private class NoLedger : Ledger {
    override suspend fun record(entry: LedgerEntry) {}
    override fun since(ts: Long): Flow<List<LedgerEntry>> = flowOf(emptyList())
    override suspend fun monthlyEstCost(): Double = 0.0
    override suspend fun bytesSince(ts: Long, destination: Destination): Long = 0
}

/**
 * 端到端：真实 opus-mt 模型 + 本平台的 ORT 绑定（JVM 用桌面版 ORT，iOS 模拟器用 cinterop），贪心解码结果与 Python onnxruntime 逐 token 对齐。
 * 需要 SCENENOTE_NMT_STORE 指向含 models/nmt-zh-en、models/nmt-en-zh 的目录，SCENENOTE_NMT_MODELS 指向含 nmt-cases.json 的目录；没设则跳过。
 */
class OnnxNmtTranslatorTest {
    private fun store(): ModelStore? {
        val root = testEnv("SCENENOTE_NMT_STORE") ?: return null
        val settings = AppSettings(MapSettings())
        val ledger = NoLedger()
        return ModelStore(DirPaths(root), EgressGate(settings, ConsentRegistry(), SpendGate(settings, ledger), ledger, EgressGate.defaultHttpClient()))
    }

    @Test fun greedyDecodingMatchesPythonReference() = runTest(timeout = kotlin.time.Duration.parse("10m")) {
        val store = store() ?: return@runTest
        val casesDir = testEnv("SCENENOTE_NMT_MODELS") ?: return@runTest
        assertTrue(store.isInstalled(ModelCatalog.nmtZhEn), "nmt-zh-en not installed under test store")
        assertTrue(store.isInstalled(ModelCatalog.nmtEnZh), "nmt-en-zh not installed under test store")
        val nmt = OnnxNmtTranslator(store, numThreads = 2, maxLoaded = 2, lowMemory = false)   // 参考译文用 Python 默认会话（预打包开）生成；关预打包的 int8 核在近似平局处会选另一个词
        assertTrue(nmt.supports(Lang.ZH_CN, Lang.EN)); assertTrue(nmt.supports(Lang.EN, Lang.YUE_HK)); assertFalse(nmt.supports(Lang.EN, Lang.KO))
        val cases = Json.parseToJsonElement(FileSystem.SYSTEM.read("$casesDir/nmt-cases.json".toPath()) { readUtf8() }).jsonObject
        var n = 0
        val mismatches = mutableListOf<String>()
        for ((repo, arr) in cases) for (c in arr.jsonArray) {
            val o = c.jsonObject
            val text = o.getValue("text").jsonPrimitive.content
            val lang = o["lang"]?.jsonPrimitive?.content?.takeIf { it != "null" }
            val expected = o.getValue("out").jsonPrimitive.content
            val (src, tgt) = when (repo) {
                "opus-mt-zh-en" -> Lang.ZH_CN to Lang.EN
                else -> Lang.EN to (if (lang == ">>yue_Hant<<") Lang.YUE_HK else Lang.ZH_CN)
            }
            val t0 = TimeSource.Monotonic.markNow()
            val r = nmt.translate(MtRequest(text, src, tgt))
            println("nmt ${r.model} '$text' -> '${r.text}' (${t0.elapsedNow().inWholeMilliseconds} ms, ${r.inputTokens}→${r.outputTokens} tok)")
            val want = if (tgt == Lang.EN) expected else LoadedModel.tidyCjk(expected)
            // int8 卷积核在不同 ORT 构建（桌面 x86 / arm64、iOS 静态库）上偶有 1 个 token 的取舍差异（"we" / "you"），不算错；大面积不同才是 bug
            if (want != r.text) { mismatches += "$repo '$text': want '$want' got '${r.text}'"; assertTrue(similar(want, r.text), "$repo '$text': want '$want' got '${r.text}'") }
            n++
        }
        assertTrue(n > 0)
        println("nmt: ${n - mismatches.size}/$n exact; near-misses: $mismatches")
        assertTrue(mismatches.size * 4 <= n, "too many near-misses: $mismatches")
        assertEquals(listOf(ModelCatalog.nmtZhEn.id, ModelCatalog.nmtEnZh.id).sorted(), nmt.resident.value.sorted())
        nmt.unloadAll()
        assertTrue(nmt.resident.value.isEmpty())
    }

    /**
     * 其余语言对（日 / 韩 / 粤 / 英 → 日）与经英语中转的两步路线：装了哪些包就测哪些，直译与 Python 参考对齐，中转只查目标文字系统。
     * 参考文件 `nmt-cases-other.json`：[{src, tgt, text, out}]。
     */
    @Test fun otherLanguagePairsAndPivots() = runTest(timeout = kotlin.time.Duration.parse("15m")) {
        val store = store() ?: return@runTest
        val casesDir = testEnv("SCENENOTE_NMT_MODELS") ?: return@runTest
        val file = "$casesDir/nmt-cases-other.json".toPath()
        if (!FileSystem.SYSTEM.exists(file)) return@runTest
        val nmt = OnnxNmtTranslator(store, numThreads = 2, maxLoaded = 3, lowMemory = false)
        var direct = 0; var pivot = 0
        for (c in Json.parseToJsonElement(FileSystem.SYSTEM.read(file) { readUtf8() }).jsonArray) {
            val o = c.jsonObject
            val src = o.getValue("src").jsonPrimitive.content; val tgt = o.getValue("tgt").jsonPrimitive.content
            val text = o.getValue("text").jsonPrimitive.content; val expected = o["out"]?.jsonPrimitive?.content
            if (!nmt.supports(src, tgt)) { println("nmt skip $src→$tgt (packs not installed)"); continue }
            val r = nmt.translate(MtRequest(text, src, tgt))
            println("nmt ${r.model} $src→$tgt '$text' -> '${r.text}' (${r.latencyMs} ms)")
            assertTrue(r.text.isNotBlank(), "$src→$tgt empty")
            assertTrue(scriptOk(r.text, tgt), "$src→$tgt output script mismatch: '${r.text}'")
            if (expected != null) { direct++; val want = if (tgt == Lang.EN) expected else LoadedModel.tidyCjk(expected); assertTrue(want == r.text || similar(want, r.text), "$src→$tgt want '$want' got '${r.text}'") }
            else pivot++
        }
        println("nmt other pairs: $direct direct, $pivot pivot")
        nmt.unloadAll()
    }

    /** 字幕按批（一行一句）送来：译文行数要和原文一致，直译与经英语中转都一样。 */
    @Test fun multiLineInputKeepsOneLinePerSentence() = runTest(timeout = kotlin.time.Duration.parse("10m")) {
        val store = store() ?: return@runTest
        val nmt = OnnxNmtTranslator(store, numThreads = 2, maxLoaded = 3, lowMemory = false)
        val cases = listOf(
            Triple(Lang.ZH_CN, Lang.EN, listOf("今天天气很好。", "我们下午去公园散步吧", "你好")),
            Triple(Lang.EN, Lang.ZH_CN, listOf("The weather is nice today.", "Let's take a walk in the park this afternoon.", "Hello")),
            Triple(Lang.JA, Lang.ZH_CN, listOf("今日はいい天気ですね。", "午後は公園を散歩しましょう。")),
        )
        for ((src, tgt, lines) in cases) {
            if (!nmt.supports(src, tgt)) { println("nmt skip multiline $src→$tgt (packs not installed)"); continue }
            val r = nmt.translate(MtRequest(lines.joinToString("\n"), src, tgt))
            println("nmt multiline ${r.model} $src→$tgt -> ${r.text.lines()}")
            val out = r.text.lines()
            assertEquals(lines.size, out.size, "$src→$tgt line count: '${r.text}'")
            out.forEach { assertTrue(it.isNotBlank() && scriptOk(it, tgt), "$src→$tgt bad line '$it'") }
            // 与逐句单独翻的结果一致（批量只是省调用次数，不改译文）
            lines.forEachIndexed { i, l -> assertEquals(nmt.translate(MtRequest(l, src, tgt)).text, out[i]) }
        }
        nmt.unloadAll()
    }

    private fun scriptOk(s: String, lang: String): Boolean = when (lang) {
        Lang.EN -> s.any { it in 'a'..'z' || it in 'A'..'Z' }
        Lang.JA -> s.any { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' }
        else -> s.any { it in '\u4E00'..'\u9FFF' }
    }

    /** 词级 Jaccard ≥ 0.5（中文按字）：不同 ORT 构建的 int8 核在近似平局处会换一个说法（"Why don't you get" / "Wouldn't it be"），但主干词要一致。 */
    private fun similar(a: String, b: String): Boolean {
        fun toks(s: String) = if (s.any { it in '\u3040'..'\u9FFF' }) s.filter { !it.isWhitespace() }.map { it.toString() }.toSet() else s.lowercase().split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }.toSet()
        val x = toks(a); val y = toks(b)
        return (x intersect y).size.toDouble() / (x union y).size >= 0.5
    }

    @Test fun lruEvictsTheOldestDirection() = runTest(timeout = kotlin.time.Duration.parse("5m")) {
        val store = store() ?: return@runTest
        val nmt = OnnxNmtTranslator(store, numThreads = 2, maxLoaded = 1)
        // 只装一份的 LRU：中 → 英之后再英 → 中会把前者卸掉
        nmt.translate(MtRequest("你好", Lang.ZH_CN, Lang.EN))
        nmt.translate(MtRequest("Hello", Lang.EN, Lang.ZH_CN))
        assertEquals(listOf(ModelCatalog.nmtEnZh.id), nmt.resident.value)
        nmt.unloadAll()
    }
}
