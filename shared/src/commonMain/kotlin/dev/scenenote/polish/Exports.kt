package dev.scenenote.polish

import dev.scenenote.core.db.SessionRepository
import dev.scenenote.core.db.SessionRow
import dev.scenenote.core.db.StoredUtterance
import dev.scenenote.core.model.Segment
import dev.scenenote.core.platform.AppPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath

/** `.voxnote`：一个会话的全部文字产物（不含音频、不含 Key），JSON，可被另一台装了场记的手机导入（v1.1）。 */
@Serializable data class VoxNote(
    val version: Int = 1, val id: String, val kind: String, val sceneId: String, val startedAt: Long, val endedAt: Long?,
    val title: String?, val myLang: String?, val otherLang: String?,
    val segments: List<VoxSegment> = emptyList(), val utterances: List<StoredUtterance> = emptyList(),
    val notes: Map<String, String> = emptyMap(),   // kind → json
)
@Serializable data class VoxSegment(val id: String, val startMs: Long, val endMs: Long, val lang: String, val text: String, val speaker: String? = null)

/** 字幕 cue（S4）。 */
data class Cue(val index: Int, val startMs: Long, val endMs: Long, val text: String, val translation: String? = null)

class Exports(private val repo: SessionRepository, private val paths: AppPaths) {
    private val json = Json { encodeDefaults = true }
    private val fs = FileSystem.SYSTEM

    val exportDir: String get() = paths.join(paths.filesDir, "export")

    suspend fun voxnote(session: SessionRow): String {
        val segs = repo.segments(session.id).map { VoxSegment(it.id, it.startMs, it.endMs, it.lang, it.text, it.speaker) }
        val utts = repo.utterances(session.id)
        val notes = listOf("minutes", "card", "subtitle").mapNotNull { k -> repo.note(session.id, k)?.let { k to it.json } }.toMap()
        val v = VoxNote(id = session.id, kind = session.kind.name, sceneId = session.sceneId, startedAt = session.startedAt, endedAt = session.endedAt, title = session.title, myLang = session.myLang, otherLang = session.otherLang, segments = segs, utterances = utts, notes = notes)
        return write("${safe(session.title ?: session.id)}.voxnote", json.encodeToString(v))
    }

    suspend fun markdown(session: SessionRow, md: String): String = write("${safe(session.title ?: session.id)}.md", md)
    suspend fun srt(session: SessionRow, cues: List<Cue>, translated: Boolean): String = write("${safe(session.title ?: session.id)}.srt", Subtitles.srt(cues, translated))
    suspend fun vtt(session: SessionRow, cues: List<Cue>, translated: Boolean): String = write("${safe(session.title ?: session.id)}.vtt", Subtitles.vtt(cues, translated))
    suspend fun bytes(name: String, data: ByteArray): String = withContext(Dispatchers.Default) {
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        paths.ensureDir(exportDir); val p = paths.join(exportDir, safe(base) + (if (ext.isNotEmpty()) ".$ext" else "")); fs.write(p.toPath()) { write(data) }; p
    }

    private suspend fun write(name: String, text: String): String = withContext(Dispatchers.Default) { paths.ensureDir(exportDir); val p = paths.join(exportDir, name); fs.write(p.toPath()) { writeUtf8(text) }; p }
    private fun safe(s: String) = s.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(40).ifBlank { "note" }
}

object Subtitles {
    fun srt(cues: List<Cue>, translated: Boolean): String = cues.joinToString("\n") { c ->
        "${c.index}\n${ts(c.startMs, ',')} --> ${ts(c.endMs, ',')}\n${line(c, translated)}\n"
    }
    fun vtt(cues: List<Cue>, translated: Boolean): String = "WEBVTT\n\n" + cues.joinToString("\n") { c ->
        "${ts(c.startMs, '.')} --> ${ts(c.endMs, '.')}\n${line(c, translated)}\n"
    }
    private fun line(c: Cue, translated: Boolean) = if (translated && c.translation != null) "${c.translation}\n${c.text}" else c.text
    private fun ts(ms: Long, sep: Char): String {
        val h = ms / 3_600_000; val m = ms / 60_000 % 60; val s = ms / 1000 % 60; val f = ms % 1000
        return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}$sep${f.toString().padStart(3, '0')}"
    }
    fun fromSegments(segments: List<Segment>, translations: Map<String, String> = emptyMap()): List<Cue> =
        segments.mapIndexed { i, s -> Cue(i + 1, s.startMs, s.endMs, s.text, translations[s.id]) }
}
