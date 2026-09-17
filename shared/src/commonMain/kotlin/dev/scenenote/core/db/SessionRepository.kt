package dev.scenenote.core.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.scenenote.core.model.EngineInfo
import dev.scenenote.core.model.Segment
import dev.scenenote.core.model.Source
import dev.scenenote.db.SceneNoteDb
import dev.scenenote.db.Session
import dev.scenenote.live.LiveLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** 会话种类（session.kind）。 */
enum class SessionKind { RECORD, LIVE, SCREEN }

/** 资料库列表行（session 表 + 派生字段）。 */
data class SessionRow(
    val id: String, val kind: SessionKind, val sceneId: String, val modeId: String?, val startedAt: Long, val endedAt: Long?,
    val defaultLang: String, val myLang: String?, val otherLang: String?, val title: String?, val summary: String?, val audioPath: String?,
) {
    val durationMs: Long get() = (endedAt ?: startedAt) - startedAt
}

/** 一条落库的对话行（utterance 表）。 */
@Serializable data class StoredUtterance(
    val id: String, val speaker: String, val lang: String, val raw: String, val translation: String?, val targetLang: String?,
    val polished: String?, val captureTsMs: Long,
)

data class StoredNote(val kind: String, val backend: String, val style: String, val json: String, val markdown: String, val createdAt: Long)
data class Bookmark(val atMs: Long, val label: String)
data class GlossaryCandidate(val term: String, val translation: String, val lang: String, val status: String)

/** 会话 / 段落 / 对话行 / 产物的落库（SQLDelight）。所有写操作在 Default 调度器。 */
class SessionRepository(private val db: SceneNoteDb) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun create(id: String, kind: SessionKind, sceneId: String, modeId: String?, defaultLang: String, myLang: String?, otherLang: String?, engine: EngineInfo, startedAt: Long = now()) = withContext(Dispatchers.Default) {
        db.sessionsQueries.insertSession(id, kind.name, sceneId, modeId, startedAt, null, defaultLang, myLang, otherLang, engine.provider, engine.model, engine.version, null, null, null)
    }

    suspend fun end(id: String, title: String?, summary: String?, audioPath: String? = null, endedAt: Long = now()) = withContext(Dispatchers.Default) {
        db.sessionsQueries.endSession(endedAt, summary, title, id)
        if (audioPath != null) db.sessionsQueries.updateAudioPath(audioPath, id)   // 不用 REPLACE：外键级联会删掉段落
    }

    suspend fun addSegment(sessionId: String, seg: Segment) = withContext(Dispatchers.Default) {
        db.segmentsQueries.insertSegment(seg.id, sessionId, seg.startMs, seg.endMs, seg.lang, seg.rawText, seg.text, seg.confidence?.toDouble(), seg.speaker, seg.isFinal, seg.source.name, seg.revision.toLong(), seg.engine.provider, seg.engine.model, seg.engine.version, seg.pendingCloud)
    }

    /** 实时会话结束时把快路径的行落库（对方音频不落盘，只存文字）。 */
    suspend fun addUtterances(sessionId: String, lines: List<LiveLine>) = withContext(Dispatchers.Default) {
        db.transaction {
            for (l in lines) {
                val fast = l.translation?.let { json.encodeToString(mapOf("text" to it, "targetLang" to l.tgtLang, "engine" to (l.mtEngine ?: ""))) }
                db.utterancesQueries.insertUtterance(l.id, sessionId, l.speaker.name, l.srcLang, l.text, fast, null, "{}", l.tts.name, "LOCAL_MIC", l.startMs, "[]")
            }
        }
    }

    suspend fun addBookmark(sessionId: String, atMs: Long, label: String = "") = withContext(Dispatchers.Default) { db.notesQueries.insertBookmark(sessionId, atMs, label) }
    suspend fun bookmarks(sessionId: String): List<Bookmark> = withContext(Dispatchers.Default) { db.notesQueries.selectBookmarks(sessionId).executeAsList().map { Bookmark(it.at_ms, it.label) } }

    suspend fun saveNote(sessionId: String, kind: String, backend: String, style: String, jsonText: String, markdown: String) = withContext(Dispatchers.Default) {
        db.notesQueries.upsertNote(sessionId, kind, backend, style, jsonText, markdown, now())
    }
    suspend fun note(sessionId: String, kind: String): StoredNote? = withContext(Dispatchers.Default) {
        db.notesQueries.selectNote(sessionId, kind).executeAsOneOrNull()?.let { StoredNote(it.kind, it.backend, it.style, it.json, it.markdown, it.created_at) }
    }

    /** 只写新词：已确认 / 忽略的不被重跑覆盖。 */
    suspend fun saveCandidates(sessionId: String, items: List<GlossaryCandidate>) = withContext(Dispatchers.Default) {
        val existing = db.notesQueries.selectCandidates(sessionId).executeAsList().associateBy { it.term }
        db.transaction { items.forEach { if (existing[it.term] == null) db.notesQueries.upsertCandidate(sessionId, it.term, it.translation, it.lang, it.status) } }
    }
    suspend fun candidates(sessionId: String): List<GlossaryCandidate> = withContext(Dispatchers.Default) {
        db.notesQueries.selectCandidates(sessionId).executeAsList().map { GlossaryCandidate(it.term, it.translation, it.lang, it.status) }
    }
    suspend fun setCandidateStatus(sessionId: String, term: String, status: String) = withContext(Dispatchers.Default) { db.notesQueries.setCandidateStatus(status, sessionId, term) }
    fun pendingCandidateCount(): Flow<Long> = db.notesQueries.countPendingCandidates().asFlow().mapToList(Dispatchers.Default).let { f -> kotlinx.coroutines.flow.flow { f.collect { emit(it.firstOrNull() ?: 0L) } } }

    fun recent(limit: Long = 200): Flow<List<SessionRow>> = db.sessionsQueries.selectRecent(limit).asFlow().mapToList(Dispatchers.Default).let { f ->
        kotlinx.coroutines.flow.flow { f.collect { emit(it.map(::row)) } }
    }
    suspend fun byId(id: String): SessionRow? = withContext(Dispatchers.Default) { db.sessionsQueries.selectById(id).executeAsOneOrNull()?.let(::row) }
    suspend fun segments(sessionId: String): List<Segment> = withContext(Dispatchers.Default) {
        db.segmentsQueries.selectBySession(sessionId).executeAsList().map { s ->
            Segment(s.id, s.start_ms, s.end_ms, s.lang, s.raw_text, s.text, s.confidence?.toFloat(), s.speaker, emptyList(), s.is_final, Source.valueOf(s.source), s.revision.toInt(), EngineInfo(s.engine_provider, s.engine_model, s.engine_version), s.pending_cloud)
        }
    }
    suspend fun utterances(sessionId: String): List<StoredUtterance> = withContext(Dispatchers.Default) {
        db.utterancesQueries.selectBySession(sessionId).executeAsList().map { u ->
            val fast = u.fast_translation_json?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            StoredUtterance(u.id, u.speaker, u.lang, u.raw, fast?.get("text"), fast?.get("targetLang"), u.polished, u.capture_ts_ms)
        }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.Default) {
        db.transaction {   // 显式删子表（驱动未必开启外键级联）
            db.notesQueries.deleteSegmentsOf(id); db.notesQueries.deleteUtterancesOf(id); db.notesQueries.deleteNotesOf(id)
            db.notesQueries.deleteBookmarksOf(id); db.notesQueries.deleteCandidatesOf(id); db.notesQueries.deleteSession(id)
        }
    }
    suspend fun search(q: String): List<String> = withContext(Dispatchers.Default) {
        if (q.isBlank()) emptyList() else db.notesQueries.searchSessionIds(q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")).executeAsList()
    }

    // ---- 润色修订（S4：每一版都存，可回滚）----
    suspend fun addRevision(segmentId: String, styleVer: String, backend: String, text: String, userEdited: Boolean = false): Long = withContext(Dispatchers.Default) {
        val next = (db.polishRevisionsQueries.latestRev(segmentId).executeAsOneOrNull()?.MAX ?: 0L) + 1
        db.polishRevisionsQueries.insertRevision(segmentId, next, styleVer, backend, text, userEdited, now())
        next
    }
    suspend fun revisions(segmentId: String): List<Pair<Long, String>> = withContext(Dispatchers.Default) {
        db.polishRevisionsQueries.selectRevisions(segmentId).executeAsList().map { it.rev to it.text }
    }

    private fun row(s: Session) = SessionRow(s.id, SessionKind.valueOf(s.kind), s.scene_id, s.mode_id, s.started_at, s.ended_at, s.default_lang, s.my_lang, s.other_lang, s.title, s.summary, s.my_audio_path)
    private fun now() = Clock.System.now().toEpochMilliseconds()
}
