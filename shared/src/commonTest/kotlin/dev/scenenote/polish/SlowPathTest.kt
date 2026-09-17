package dev.scenenote.polish

import dev.scenenote.core.model.EngineInfo
import dev.scenenote.core.model.Segment
import dev.scenenote.core.model.Source
import dev.scenenote.core.model.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun seg(id: String, start: Long, end: Long, text: String) = Segment(id, start, end, "zh-CN", text, text, isFinal = true, source = Source.LOCAL, revision = 0, engine = EngineInfo("t", "m", "1"))

class RedactorTest {
    @Test fun redactsAndRestores() {
        val r = Redactor()
        val src = "联系 13812345678 或 a.b@example.com，卡号 6222021234567890123"
        val red = r.redact(src)
        assertFalse(red.text.contains("13812345678")); assertFalse(red.text.contains("example.com"))
        assertEquals(src, r.restore(red.text, red.map))
    }
}

class FactCheckTest {
    @Test fun normalizedNumbersMatch() {
        assertEquals("12:30", FactCheck.normalize("十二点半"))
        assertTrue(FactCheck.check("十二点半开会，三个人", "12:30 开会，3 个人", Style.NEUTRAL).passed)
    }
    @Test fun missingNumberFails() {
        val v = FactCheck.check("预算 500 万，下周 3 号交", "预算下周交", Style.BUSINESS)
        assertFalse(v.passed); assertTrue(v.missingNumbers.isNotEmpty())
    }
    @Test fun negationChangeFails() {
        assertFalse(FactCheck.check("这个方案不能通过", "这个方案能通过", Style.NEUTRAL).passed)
        assertTrue(FactCheck.check("不是，这个方案不能通过", "这个方案不能通过", Style.NEUTRAL).passed)   // 句首口癖不算
    }
    @Test fun editRatioThresholdByStyle() {
        val v = FactCheck.check("我们明天去看看那个新的办公室", "明日前往新办公室考察", Style.CASUAL)
        assertTrue(v.overThreshold)
    }
}

class RulesMinutesTest {
    @Test fun groupsByPauseAndWindow() {
        val segs = listOf(seg("a", 0, 3000, "今天讨论预算"), seg("b", 4000, 6000, "大概 500 万"), seg("c", 20_000, 22_000, "下周三号之前交方案"), seg("d", 200_000, 203_000, "另外要记得订会议室"))
        val m = RulesMinutes.minutes(segs, "会议")
        assertEquals(3, m.timeline.size)
        assertTrue(m.timeline[0].highlights.contains("500 万") || m.timeline[0].highlights.any { it.startsWith("500") })
        assertTrue(m.todos.any { it.text.contains("记得") })
        assertEquals("rules", m.backend)
    }
    @Test fun markdownHasSections() {
        val md = Markdown.minutes(MeetingMinutes("周会", topics = listOf("预算"), todos = listOf(TodoItem("交方案", "小王", "周三"))), dev.scenenote.core.db.SessionRow("s", dev.scenenote.core.db.SessionKind.RECORD, "meeting", null, 0, 1000, "zh-CN", null, null, "周会", null, null))
        assertTrue(md.contains("# 周会")); assertTrue(md.contains("- [ ] 交方案 @小王（周三）"))
    }
}

class SubtitlesTest {
    @Test fun srtAndVtt() {
        val cues = listOf(Cue(1, 0, 1500, "Hello", "你好"), Cue(2, 61_000, 62_250, "Bye"))
        val srt = Subtitles.srt(cues, translated = true)
        assertTrue(srt.startsWith("1\n00:00:00,000 --> 00:00:01,500\n你好\nHello"))
        val vtt = Subtitles.vtt(cues, translated = false)
        assertTrue(vtt.startsWith("WEBVTT")); assertTrue(vtt.contains("00:01:01.000 --> 00:01:02.250\nBye"))
    }
}
