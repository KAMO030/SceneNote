package dev.scenenote.polish

import dev.scenenote.core.db.GlossaryCandidate
import dev.scenenote.core.db.SessionKind
import dev.scenenote.core.db.SessionRepository
import dev.scenenote.core.db.SessionRow
import dev.scenenote.core.db.StoredUtterance
import dev.scenenote.core.model.Segment
import dev.scenenote.core.model.Style
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ---------- 产物结构 ----------

@Serializable data class TodoItem(val text: String, val owner: String? = null, val due: String? = null)
@Serializable data class MeetingMinutes(
    val title: String = "", val topics: List<String> = emptyList(), val conclusions: List<String> = emptyList(),
    val todos: List<TodoItem> = emptyList(), val commitments: List<String> = emptyList(),
    /** 零 Key 规则版：时间轴要点（mm:ss 起点 + 一句）。 */
    val timeline: List<TimelinePoint> = emptyList(),
    val flags: List<String> = emptyList(), val factCheckPassed: Boolean = true, val backend: String = "rules",
)
@Serializable data class TimelinePoint(val atMs: Long, val text: String, val highlights: List<String> = emptyList())
@Serializable data class NewWord(val term: String, val translation: String = "", val lang: String = "")
@Serializable data class ConversationCard(
    val title: String = "", val keyPoints: List<String> = emptyList(), val newWords: List<NewWord> = emptyList(),
    val flags: List<String> = emptyList(), val backend: String = "rules",
)

// ---------- S0：脱敏（发送前打占位符，回填时还原）----------

class Redactor {
    private val patterns = listOf(
        "PHONE" to Regex("""(?<!\d)1[3-9]\d{9}(?!\d)"""),
        "ID" to Regex("""(?<!\d)\d{17}[\dXx](?!\d)"""),
        "EMAIL" to Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
        "CARD" to Regex("""(?<!\d)\d{16,19}(?!\d)"""),
    )
    data class Result(val text: String, val map: Map<String, String>)
    fun redact(text: String): Result {
        var out = text; val map = mutableMapOf<String, String>(); var n = 0
        for ((tag, re) in patterns) out = re.replace(out) { m -> val key = "«$tag${++n}»"; map[key] = m.value; key }
        return Result(out, map)
    }
    /** 模型常把 «PHONE1» 改写成 <PHONE1> / PHONE1 / «PHONE 1»：按 tag + 序号容错回填。 */
    private val placeholder = Regex("""[«<\[]?\s*(PHONE|ID|EMAIL|CARD)\s*(\d+)\s*[»>\]]?""")
    fun restore(text: String, map: Map<String, String>): String = placeholder.replace(text) { m -> map["«${m.groupValues[1]}${m.groupValues[2]}»"] ?: m.value }
}

// ---------- S3：事实守恒（02 篇 §2.4 可执行定义）----------

object FactCheck {
    private val number = Regex("""\d+(?:[.:/-]\d+)*%?""")
    private val negation = Regex("""(?!不过|不错|不同|不少|不仅|不如|未来|别人|别的|没关系|没事)(不|没|未|别|无法|不能)(?=[一-鿿])""")
    private val zhDigits = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)

    /** 中文数字归一（十二点半 → 12:30 只做整数与"点半"）；不完整但双方同一规则即可。 */
    fun normalize(text: String): String {
        var t = text.replace(Regex("""(?<![一-鿿])([一二两三四五六七八九十]{1,3})点半""")) { "${zh(it.groupValues[1])}:30" }
        t = t.replace(Regex("""(?<![一-鿿])([一二两三四五六七八九十]{1,3})点(?=[分到开前后钟整\s，。]|$)""")) { "${zh(it.groupValues[1])}:00" }
        t = t.replace(Regex("""(?<![\d:])[一二两三四五六七八九十百千]{1,4}(?=[个月号日年块元人次])""")) { zh(it.value).toString() }
        return t
    }
    private fun zh(s: String): Int {
        var total = 0; var cur = 0
        for (c in s) when (c) {
            '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
            '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
            '千' -> { total += (if (cur == 0) 1 else cur) * 1000; cur = 0 }
            else -> cur = zhDigits[c] ?: cur
        }
        return total + cur
    }
    fun numbers(text: String): Set<String> = number.findAll(normalize(text)).map { it.value }.toSet()
    fun negations(text: String): Int = negation.findAll(text.replace(Regex("^不是，|不不不|不好意思"), "")).count()

    data class Verdict(val passed: Boolean, val missingNumbers: Set<String>, val negationChanged: Boolean, val editRatio: Float, val overThreshold: Boolean)
    /** 润色模式：润色后不能丢数字、不能改否定；摘要模式（纪要 / 卡片）：只要求不凭空出现数字。 */
    fun check(source: String, polished: String, style: Style, summary: Boolean = false): Verdict {
        val src = numbers(source); val dst = numbers(polished)
        val missing = if (summary) dst - src else src - dst
        val neg = !summary && negations(source) != negations(polished)
        val ratio = if (summary) 0f else editRatio(source, polished)
        val threshold = when (style) { Style.NEUTRAL, Style.CASUAL -> 0.30f; Style.FORMAL, Style.BUSINESS -> 0.45f; else -> 0.60f }
        return Verdict(passed = missing.isEmpty() && !neg, missingNumbers = missing, negationChanged = neg, editRatio = ratio, overThreshold = ratio > threshold)
    }
    /** 字符二元组 Dice 距离作改动幅度近似（够用，避免 O(n²) 编辑距离）。 */
    private fun editRatio(a: String, b: String): Float {
        fun grams(s: String) = s.filter { !it.isWhitespace() }.windowed(2, 1, partialWindows = false).groupingBy { it }.eachCount()
        val ga = grams(a); val gb = grams(b)
        if (ga.isEmpty() && gb.isEmpty()) return 0f
        val inter = ga.entries.sumOf { (k, v) -> minOf(v, gb[k] ?: 0) }
        return 1f - 2f * inter / (ga.values.sum() + gb.values.sum()).coerceAtLeast(1)
    }
}

// ---------- 规则层（零 Key 永远可用）----------

object RulesMinutes {
    private val todoCue = Regex("(需要|要在|要把|记得|负责|安排|下周|明天|之前|截止|deadline|todo|follow up|need to)", RegexOption.IGNORE_CASE)
    private val highlight = Regex("""\d+(?:[.:/-]\d+)*%?[个月号日年块元万亿人次]?|[一二两三四五六七八九十]+[点月号日年块万]""")

    /** 时间轴要点：按停顿 ≥ 8 s 或 3 分钟窗口分组，每组首句作要点；数字 / 日期高亮；待办候选句。 */
    fun minutes(segments: List<Segment>, title: String, bookmarks: List<Long> = emptyList()): MeetingMinutes {
        val groups = mutableListOf<MutableList<Segment>>()
        for (s in segments) {
            val g = groups.lastOrNull()
            val marked = bookmarks.any { it in (g?.last()?.endMs ?: -1)..s.startMs }   // 「标记要点」处强制切组
            if (g == null || marked || s.startMs - g.last().endMs >= 8_000 || s.startMs - g.first().startMs >= 180_000) groups += mutableListOf(s) else g += s
        }
        val timeline = groups.map { g ->
            val text = g.joinToString("") { it.text }
            val marked = bookmarks.any { it in g.first().startMs..g.last().endMs }
            TimelinePoint(g.first().startMs, (if (marked) "★ " else "") + text.take(60), highlight.findAll(g.joinToString(" ") { it.text }).map { it.value }.distinct().take(6).toList())
        }
        val todos = segments.filter { todoCue.containsMatchIn(it.text) }.map { TodoItem(it.text) }.take(12)
        return MeetingMinutes(title = title, timeline = timeline, todos = todos, backend = "rules")
    }

    fun card(utterances: List<StoredUtterance>, title: String): ConversationCard {
        val points = utterances.filter { it.raw.length >= 6 }.take(5).map { (it.translation ?: it.raw).take(50) }
        return ConversationCard(title = title, keyPoints = points, backend = "rules")
    }
}

// ---------- 模板（S2 提示词）----------

object Templates {
    fun minutesSystem(style: Style) = """
你是会议纪要助手。只根据给定的转写整理，不添加转写中没有的事实；数字、日期、金额、人名一律照抄；不确定的写进 flags。
风格：${styleWord(style)}。输出 JSON：{"title":"...","topics":["..."],"conclusions":["..."],"todos":[{"text":"...","owner":"人名，没有就 null","due":"时间，没有就 null"}],"commitments":["谁承诺了什么"],"flags":["模型不确定的地方"]}
转写里以 [要点] 开头的行是用户当场标记的重点，优先写进结论。
""".trimIndent()
    fun cardSystem(myLang: String, otherLang: String) = """
你是面对面对话的记录助手。根据双语对话（我说 $myLang，对方说 $otherLang）输出 JSON：
{"title":"一句话概括这次对话","keyPoints":["最多 5 条要点，用我的语言"],"newWords":[{"term":"对方语言里我可能不熟的词","translation":"我的语言","lang":"$otherLang"}],"flags":[]}
只用对话里出现的内容，不编造。
""".trimIndent()
    private fun styleWord(s: Style) = when (s) { Style.BUSINESS -> "商务、正式全称、条目化"; Style.FORMAL -> "正式"; Style.ACADEMIC -> "学术、保留术语"; Style.CASUAL -> "口语、简短"; else -> "中性、简洁" }
}

// ---------- 慢路径编排 ----------

/** 慢路径结果：产物 + 用了哪一层 + 事实守恒结论。 */
data class SlowPathOutcome(val backend: String, val markdown: String, val json: String, val flags: List<String>, val factCheckPassed: Boolean)

/**
 * 慢路径（02 篇 §2.4 S0–S4）：会话结束后整段一次性执行；有 Key 走云端 LLM（脱敏 → 模板 → 事实守恒），无 Key / 出错降到规则层。
 * 产物写入 session_note，永不改动已显示的原文；重跑追加新版本（换风格 / 换后端）。
 */
class SlowPath(private val repo: SessionRepository, private val llm: BailianLlm?) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true; isLenient = true; coerceInputValues = true }
    private val redactor = Redactor()

    suspend fun minutes(session: SessionRow, style: Style, force: Boolean = false): SlowPathOutcome {
        if (!force) repo.note(session.id, "minutes")?.takeIf { it.style == style.name }?.let { return SlowPathOutcome(it.backend, it.markdown, it.json, emptyList(), true) }
        val segments = repo.segments(session.id)
        val bookmarks = repo.bookmarks(session.id).map { it.atMs }
        val title = session.title ?: defaultTitle(session)
        val plain = segments.joinToString("\n") { it.text }   // 校验源：不带时间戳
        val prompt = segments.joinToString("\n") { s -> (if (bookmarks.any { b -> b in s.startMs..s.endMs }) "[要点] " else "") + "[${mmss(s.startMs)}] ${s.text}" }
        var minutes: MeetingMinutes
        val flags = mutableListOf<String>()
        if (llm != null && llm.available() && segments.isNotEmpty()) {
            try {
                val red = redactor.redact(prompt)
                val r = llm.complete(Templates.minutesSystem(style), red.text, session.id)
                val parsed = parse<MeetingMinutes>(r.text)
                val restored = restore(parsed, red.map)
                val verdict = FactCheck.check(plain, restored.markdownBody(), style, summary = true)
                if (!verdict.passed) flags += "有数字对不上原文，请核对"
                val backend = "cloud:bailian:${r.model}"
                minutes = restored.copy(title = restored.title.ifBlank { title }, flags = restored.flags + flags, factCheckPassed = verdict.passed, backend = backend)
                if (!verdict.passed || minutes.topics.isEmpty() && minutes.conclusions.isEmpty()) minutes = minutes.copy(timeline = RulesMinutes.minutes(segments, title, bookmarks).timeline)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                flags += "云端整理没成功，这次用本机整理"
                minutes = RulesMinutes.minutes(segments, title, bookmarks).copy(flags = flags)
            }
        } else minutes = RulesMinutes.minutes(segments, title, bookmarks)
        val md = Markdown.minutes(minutes, session)
        val js = json.encodeToString(minutes)
        if (segments.isNotEmpty()) repo.saveNote(session.id, "minutes", minutes.backend, style.name, js, md)   // 空内容不缓存
        return SlowPathOutcome(minutes.backend, md, js, minutes.flags, minutes.factCheckPassed)
    }

    suspend fun card(session: SessionRow, force: Boolean = false): SlowPathOutcome {
        if (!force) repo.note(session.id, "card")?.let { return SlowPathOutcome(it.backend, it.markdown, it.json, emptyList(), true) }
        val utts = repo.utterances(session.id)
        val title = session.title ?: defaultTitle(session)
        var card: ConversationCard
        if (llm != null && llm.available() && utts.isNotEmpty()) {
            try {
                val src = utts.joinToString("\n") { "${if (it.speaker == "ME") "我" else "对方"}：${it.raw}${it.translation?.let { t -> "（译：$t）" } ?: ""}" }
                val red = redactor.redact(src)
                val r = llm.complete(Templates.cardSystem(session.myLang ?: "zh-CN", session.otherLang ?: "en"), red.text, session.id)
                val parsed = parse<ConversationCard>(r.text)
                card = parsed.copy(title = redactor.restore(parsed.title.ifBlank { title }, red.map), keyPoints = parsed.keyPoints.map { redactor.restore(it, red.map) },
                    newWords = parsed.newWords.map { it.copy(term = redactor.restore(it.term, red.map), translation = redactor.restore(it.translation, red.map)) }, backend = "cloud:bailian:${r.model}")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { card = RulesMinutes.card(utts, title).copy(flags = listOf("云端整理没成功，这次用本机整理")) }
        } else card = RulesMinutes.card(utts, title)
        repo.saveCandidates(session.id, card.newWords.filter { it.term.isNotBlank() }.map { GlossaryCandidate(it.term, it.translation, it.lang, "pending") })
        val md = Markdown.card(card, utts, session)
        val js = json.encodeToString(card)
        if (utts.isNotEmpty()) repo.saveNote(session.id, "card", card.backend, "", js, md)
        return SlowPathOutcome(card.backend, md, js, card.flags, true)
    }

    private inline fun <reified T> parse(text: String): T {
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{'); val end = cleaned.lastIndexOf('}')
        return json.decodeFromString(if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned)
    }
    private fun restore(m: MeetingMinutes, map: Map<String, String>) = m.copy(
        title = redactor.restore(m.title, map), topics = m.topics.map { redactor.restore(it, map) }, conclusions = m.conclusions.map { redactor.restore(it, map) },
        todos = m.todos.map { it.copy(text = redactor.restore(it.text, map)) }, commitments = m.commitments.map { redactor.restore(it, map) },
    )
    private fun MeetingMinutes.markdownBody() = (topics + conclusions + todos.map { it.text } + commitments).joinToString("\n")
    private fun defaultTitle(s: SessionRow) = when (s.kind) { SessionKind.RECORD -> "会议"; SessionKind.LIVE -> "对话"; SessionKind.SCREEN -> "字幕" }

    companion object {
        fun mmss(ms: Long): String { val s = ms / 1000; return "${(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}" }
    }
}

/** 产物 Markdown（分享 / 导出用）。 */
object Markdown {
    fun minutes(m: MeetingMinutes, s: SessionRow): String = buildString {
        appendLine("# ${m.title}"); appendLine()
        if (m.topics.isNotEmpty()) { appendLine("## 议题"); m.topics.forEach { appendLine("- $it") }; appendLine() }
        if (m.conclusions.isNotEmpty()) { appendLine("## 结论"); m.conclusions.forEach { appendLine("- $it") }; appendLine() }
        if (m.todos.isNotEmpty()) { appendLine("## 待办"); m.todos.forEach { appendLine("- [ ] ${it.text}${it.owner?.let { o -> " @$o" } ?: ""}${it.due?.let { d -> "（$d）" } ?: ""}") }; appendLine() }
        if (m.commitments.isNotEmpty()) { appendLine("## 承诺"); m.commitments.forEach { appendLine("- $it") }; appendLine() }
        if (m.timeline.isNotEmpty()) { appendLine("## 时间轴要点"); m.timeline.forEach { appendLine("- ${SlowPath.mmss(it.atMs)} ${it.text}${if (it.highlights.isNotEmpty()) "  ·  " + it.highlights.joinToString(" ") else ""}") }; appendLine() }
        if (m.flags.isNotEmpty()) { appendLine("> " + m.flags.joinToString("；")); appendLine() }
        appendLine("---"); appendLine("场记 · ${if (m.backend == "rules") "本机整理" else "云端成稿"} · 录音不出手机")
    }
    fun card(c: ConversationCard, utts: List<StoredUtterance>, s: SessionRow): String = buildString {
        appendLine("# ${c.title}"); appendLine()
        if (c.keyPoints.isNotEmpty()) { appendLine("## 要点"); c.keyPoints.forEach { appendLine("- $it") }; appendLine() }
        appendLine("## 对话"); utts.forEach { appendLine("- ${if (it.speaker == "ME") "我" else "对方"}：${it.raw}${it.translation?.let { t -> "\n  > $t" } ?: ""}") }; appendLine()
        if (c.newWords.isNotEmpty()) { appendLine("## 新词"); c.newWords.forEach { appendLine("- ${it.term} → ${it.translation}") }; appendLine() }
        appendLine("---"); appendLine("场记 · 对方的声音没有保存")
    }
    fun transcript(segments: List<Segment>): String = segments.joinToString("\n") { "[${SlowPath.mmss(it.startMs)}] ${it.text}" }
}
