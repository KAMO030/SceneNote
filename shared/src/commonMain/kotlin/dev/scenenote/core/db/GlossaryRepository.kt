package dev.scenenote.core.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.scenenote.db.SceneNoteDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** 术语表条目：term → 各语言译名。bucket = 场景词袋（travel / work / general…）。 */
data class GlossaryTerm(val term: String, val bucket: String, val translations: Map<String, String>, val hits: Long, val updatedAt: Long)
data class Correction(val wrong: String, val right: String, val bucket: String, val count: Long)

/**
 * 术语表 + 纠错映射（I7 数据层回流）：实时翻译按场景词袋注入 terms（qwen-mt translation_options.terms），
 * S0 前置替换纠错映射；新词候选「确认」进这里。
 */
class GlossaryRepository(private val db: SceneNoteDb) {
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): Flow<List<GlossaryTerm>> = db.glossaryQueries.selectAllGlossary().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map(::term) }
    suspend fun byBucket(bucket: String): List<GlossaryTerm> = withContext(Dispatchers.Default) { db.glossaryQueries.selectGlossary(bucket).executeAsList().map(::term) }

    /** 某语言对可注入的术语（最多 [limit] 条，按命中数）：源语言词 → 目标语言译名。 */
    suspend fun termsFor(bucket: String, tgtLang: String, limit: Int = 40): Map<String, String> = withContext(Dispatchers.Default) {
        val rows = db.glossaryQueries.selectGlossary(bucket).executeAsList() + if (bucket != "general") db.glossaryQueries.selectGlossary("general").executeAsList() else emptyList()
        rows.mapNotNull { r -> term(r).translations[tgtLang]?.takeIf { it.isNotBlank() }?.let { r.term to it } }.distinctBy { it.first }.take(limit).toMap()
    }

    suspend fun upsert(term: String, bucket: String, translations: Map<String, String>, reading: String? = null) = withContext(Dispatchers.Default) {
        val existing = db.glossaryQueries.selectGlossary(bucket).executeAsList().firstOrNull { it.term == term }
        val merged = (existing?.let { runCatching { json.decodeFromString<Map<String, String>>(it.translation_json) }.getOrNull() }.orEmpty()) + translations.filterValues { it.isNotBlank() }
        db.glossaryQueries.upsertGlossary(term, bucket, reading ?: existing?.reading, json.encodeToString(merged), Clock.System.now().toEpochMilliseconds())
    }
    suspend fun delete(term: String, bucket: String) = withContext(Dispatchers.Default) { db.glossaryQueries.deleteGlossary(term, bucket) }
    suspend fun bumpHits(terms: Collection<String>, bucket: String) = withContext(Dispatchers.Default) { db.transaction { terms.forEach { db.glossaryQueries.bumpGlossaryHit(it, bucket) } } }

    suspend fun corrections(bucket: String): List<Correction> = withContext(Dispatchers.Default) {
        (db.glossaryQueries.selectCorrections(bucket).executeAsList() + db.glossaryQueries.selectCorrections("general").executeAsList()).map { Correction(it.wrong, it.right, it.bucket, it.count) }
    }
    suspend fun addCorrection(wrong: String, right: String, bucket: String) = withContext(Dispatchers.Default) { db.glossaryQueries.upsertCorrection(wrong, right, bucket) }
    suspend fun deleteCorrection(wrong: String, bucket: String) = withContext(Dispatchers.Default) { db.glossaryQueries.deleteCorrection(wrong, bucket) }

    private fun term(r: dev.scenenote.db.Glossary_entry) = GlossaryTerm(r.term, r.bucket, runCatching { json.decodeFromString<Map<String, String>>(r.translation_json) }.getOrDefault(emptyMap()), r.hits, r.updated_at)
}
