package dev.scenenote.live

import dev.scenenote.asr.AsrEvent
import dev.scenenote.audio.AudioSink
import dev.scenenote.bench.LatencyProbe
import dev.scenenote.core.model.EngineInfo
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.ModeSpecs
import dev.scenenote.core.model.PlaybackStatus
import dev.scenenote.core.model.Segment
import dev.scenenote.core.model.Source
import dev.scenenote.core.model.Speaker
import dev.scenenote.core.platform.ThermalLevel
import dev.scenenote.translate.FastTranslator
import dev.scenenote.translate.MtRequest
import dev.scenenote.translate.MtResult
import dev.scenenote.translate.Translator
import dev.scenenote.tts.TtsEngine
import dev.scenenote.tts.TtsPreference
import dev.scenenote.tts.TtsRequest
import dev.scenenote.tts.TtsRouter
import dev.scenenote.tts.TtsStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class EchoTranslator : Translator {
    override val id = "echo"; override val cloud = true
    override fun supports(src: String, tgt: String) = true
    override suspend fun translate(req: MtRequest) = MtResult("[${req.tgt}] ${req.text}", req.src, req.tgt, id, "m", 5)
}
private class SlowTts : TtsEngine {
    override val id = "slow"
    override fun supports(lang: String) = true
    override suspend fun synthesize(req: TtsRequest, onChunk: suspend (ShortArray) -> Boolean): TtsStats {
        repeat(40) { delay(50); if (!onChunk(ShortArray(800))) return TtsStats(id, 16_000, 0, 0, 0) }
        return TtsStats(id, 16_000, 32_000, 50, 2_000)
    }
}
private class VolSink : AudioSink {
    override var volumeDb: Float = 0f
    val volumes = mutableListOf<Float>()
    var stops = 0
    override suspend fun play(chunk: ShortArray, channelMask: Int) { volumes += volumeDb; delay(10) }
    override fun stop() { stops++ }
    override fun flush() {}
    override fun prime() {}
    override fun release() {}
}

private fun seg(id: String, text: String, lang: String = Lang.EN) = Segment(id, 0, 1000, lang, text, text, isFinal = true, source = Source.LOCAL, revision = 0, engine = EngineInfo("t", "m", "1"))

class FastPathTest {
    private fun build(scope: kotlinx.coroutines.CoroutineScope): Triple<FastPath, VolSink, PlaybackQueue> {
        val sink = VolSink()
        val queue = PlaybackQueue(sink, scope)
        val fp = FastPath(FastTranslator({ EchoTranslator() }, { null }), TtsRouter({ SlowTts() }, { null }), queue, sink, LatencyProbe(), scope)
        return Triple(fp, sink, queue)
    }

    @Test fun listenModeTranslatesAndSpeaks() = runTest {
        val (fp, sink, _) = build(backgroundScope)
        fp.configure(ModeSpecs.byId("M0"), Lang.ZH_CN, Lang.EN, voiceOut = true, ttsPref = TtsPreference.AUTO, sessionId = "s")
        fp.onAsr(AsrEvent.Final(seg("u1", "hello there")))
        advanceTimeBy(500); runCurrent()
        val line = fp.lines.value.single()
        assertEquals(Speaker.OTHER, line.speaker); assertEquals("[zh-CN] hello there", line.translation)
        assertEquals(PlaybackStatus.PLAYING, line.tts)
        assertTrue(sink.volumes.isNotEmpty())
    }

    @Test fun otherInterruptionDucksThenFlushes() = runTest {
        val (fp, sink, queue) = build(backgroundScope)
        fp.configure(ModeSpecs.byId("M0"), Lang.ZH_CN, Lang.EN, voiceOut = true, ttsPref = TtsPreference.AUTO, sessionId = "s")
        fp.onAsr(AsrEvent.Final(seg("u1", "a long sentence to play")))
        advanceTimeBy(400); runCurrent()
        assertEquals("u1", queue.playing.value)
        fp.onAsr(AsrEvent.SpeechStart(0))
        advanceTimeBy(350); runCurrent()
        assertEquals(-12f, sink.volumeDb)            // > 300 ms：压低
        advanceTimeBy(1_300); runCurrent()
        assertTrue(sink.stops >= 1)                    // > 1.5 s：丢弃
        assertEquals(0f, sink.volumeDb)
        assertEquals(PlaybackStatus.SHOWN_ON_SCREEN, fp.lines.value.single().tts)
    }

    @Test fun shortInterruptionRestoresAfter500ms() = runTest {
        val (fp, sink, _) = build(backgroundScope)
        fp.configure(ModeSpecs.byId("M0"), Lang.ZH_CN, Lang.EN, voiceOut = true, ttsPref = TtsPreference.AUTO, sessionId = "s")
        fp.onAsr(AsrEvent.Final(seg("u1", "a long sentence to play")))
        advanceTimeBy(400); runCurrent()
        fp.onAsr(AsrEvent.SpeechStart(0)); advanceTimeBy(600); runCurrent()
        assertEquals(-12f, sink.volumeDb)
        fp.onAsr(AsrEvent.SpeechEnd(600)); advanceTimeBy(200); runCurrent()
        assertEquals(-12f, sink.volumeDb)               // 结束后 500 ms 内仍压低
        advanceTimeBy(400); runCurrent()
        assertEquals(0f, sink.volumeDb)
    }

    @Test fun conversationDirectionAndMeStopsPlayback() = runTest {
        val (fp, sink, queue) = build(backgroundScope)
        fp.configure(ModeSpecs.byId("M1"), Lang.ZH_CN, Lang.EN, voiceOut = true, ttsPref = TtsPreference.AUTO, sessionId = "s")
        fp.onAsr(AsrEvent.Final(seg("u1", "where is the train station please")))
        advanceTimeBy(400); runCurrent()
        assertEquals(Speaker.OTHER, fp.lines.value[0].speaker); assertEquals("u1", queue.playing.value)
        fp.onAsr(AsrEvent.Final(seg("u2", "火车站往前走右转就到了", Lang.ZH_CN)))
        advanceTimeBy(100); runCurrent()
        val me = fp.lines.value[1]
        assertEquals(Speaker.ME, me.speaker); assertEquals(Lang.EN, me.tgtLang)
        assertTrue(sink.stops >= 1)                              // 我开口：立即停播对方译文
        advanceTimeBy(300); runCurrent()
        assertEquals(PlaybackStatus.SHOWN_ON_SCREEN, fp.lines.value[1].tts)   // 我的译文只上屏，不朗读
    }

    @Test fun thermalCriticalSilencesTts() = runTest {
        val (fp, _, _) = build(backgroundScope)
        fp.configure(ModeSpecs.byId("M0"), Lang.ZH_CN, Lang.EN, voiceOut = true, ttsPref = TtsPreference.AUTO, sessionId = "s")
        fp.setThermal(ThermalLevel.CRITICAL, lowBattery = false)
        fp.onAsr(AsrEvent.Final(seg("u1", "hello there")))
        advanceTimeBy(300); runCurrent()
        assertEquals(PlaybackStatus.SHOWN_ON_SCREEN, fp.lines.value.single().tts)
    }

    @Test fun flipRetranslatesReversed() = runTest {
        val (fp, _, _) = build(backgroundScope)
        fp.configure(ModeSpecs.byId("M3"), Lang.ZH_CN, Lang.EN, voiceOut = false, ttsPref = TtsPreference.OFF, sessionId = "s")
        fp.onAsr(AsrEvent.Final(seg("u1", "ok")))
        advanceTimeBy(100); runCurrent()
        val before = fp.lines.value.single()
        fp.flip("u1"); advanceTimeBy(100); runCurrent()
        val after = fp.lines.value.single()
        assertTrue(before.speaker != after.speaker)
        assertEquals(before.srcLang, after.tgtLang)
    }

    @Test fun restoreIsCancelledByNewSpeechStart() = runTest {
        val (fp, sink, _) = build(backgroundScope)
        fp.configure(ModeSpecs.byId("M0"), Lang.ZH_CN, Lang.EN, voiceOut = true, ttsPref = TtsPreference.AUTO, sessionId = "s")
        fp.onAsr(AsrEvent.Final(seg("u1", "a long sentence to play")))
        advanceTimeBy(400); runCurrent()
        fp.onAsr(AsrEvent.SpeechStart(0)); advanceTimeBy(400); runCurrent()
        fp.onAsr(AsrEvent.SpeechEnd(400)); advanceTimeBy(100); runCurrent()
        fp.onAsr(AsrEvent.SpeechStart(500)); advanceTimeBy(500); runCurrent()   // 旧的 500 ms 恢复任务应已被取消
        assertEquals(-12f, sink.volumeDb)
    }

    @Test fun playbackStartedWhileSpeakingGetsDucked() = runTest {
        val (fp, sink, _) = build(backgroundScope)
        fp.configure(ModeSpecs.byId("M0"), Lang.ZH_CN, Lang.EN, voiceOut = true, ttsPref = TtsPreference.AUTO, sessionId = "s")
        fp.onAsr(AsrEvent.SpeechStart(0)); runCurrent()                    // 队列还空：不计时
        fp.onAsr(AsrEvent.Final(seg("u1", "a long sentence to play")))    // 译文开播时对方仍在说
        advanceTimeBy(400); runCurrent()
        advanceTimeBy(350); runCurrent()
        assertEquals(-12f, sink.volumeDb)
    }

    @Test fun leakedTtsTextIsDropped() = runTest {
        val (fp, _, _) = build(backgroundScope)
        fp.configure(ModeSpecs.byId("M0"), Lang.ZH_CN, Lang.EN, voiceOut = true, ttsPref = TtsPreference.AUTO, sessionId = "s")
        fp.onAsr(AsrEvent.Final(seg("u1", "good morning everyone")))
        advanceTimeBy(300); runCurrent()
        // 麦克风把刚播的译文「[zh-CN] good morning everyone」拾了回来
        fp.onAsr(AsrEvent.Final(seg("u2", "zh-CN good morning everyone", Lang.ZH_CN)))
        runCurrent()
        assertEquals(1, fp.lines.value.size)
    }

    @Test fun differentScriptKeepsSourceLangUnderDebounce() = runTest {
        val (fp, _, _) = build(backgroundScope)
        fp.configure(ModeSpecs.byId("M3"), Lang.ZH_CN, Lang.EN, voiceOut = false, ttsPref = TtsPreference.OFF, sessionId = "s")
        fp.onAsr(AsrEvent.Final(seg("u1", "where is the station please")))
        fp.onAsr(AsrEvent.Final(seg("u2", "好的", Lang.ZH_CN)))          // 短句：说话人标签防抖沿用 OTHER，但源语言仍按脚本 = 中文
        advanceTimeBy(100); runCurrent()
        val l = fp.lines.value[1]
        assertEquals(Lang.ZH_CN, l.srcLang); assertEquals(Lang.EN, l.tgtLang)
        assertTrue(l.dirTentative)
    }
}
