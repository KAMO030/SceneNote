package dev.scenenote.live

import dev.scenenote.asr.NativeSpeakerExtractor
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.Speaker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 假声纹：样本首个值 > 0 视为「我」的声音（embedding 指向 +x），否则指向 −x。 */
private class FakeExtractor : NativeSpeakerExtractor {
    override val dim = 2
    override fun embed(samples: FloatArray, sampleRate: Int): FloatArray = if (samples.firstOrNull() ?: 0f > 0f) floatArrayOf(1f, 0f) else floatArrayOf(-1f, 0f)
    override fun close() {}
}

class DirectionStageTest {
    @Test fun scriptDecidesZhEn() {
        val d = DirectionStage(Lang.ZH_CN, Lang.EN)
        assertEquals(Speaker.ME, d.decideByText("今天天气怎么样啊").speaker)
        assertEquals(Speaker.OTHER, d.decideByText("It is sunny today").speaker)
    }

    @Test fun autoOtherLangGuessesByScriptAndFamily() {
        val d = DirectionStage(Lang.ZH_CN, Lang.AUTO)
        assertEquals(Speaker.ME, d.decideByText("今天天气怎么样啊").speaker)
        assertEquals(Speaker.OTHER, d.decideByText("It is sunny today").speaker)
        assertEquals(Speaker.OTHER, d.decideByText("こんにちは、よろしくお願いします").speaker)
        val o = d.overrideBy(Speaker.OTHER, 0.9f, "lid")
        assertNotNull(o); assertEquals("lid", o.basis)
        d.fixed = Speaker.OTHER
        assertNull(d.overrideBy(Speaker.ME, 0.9f, "lid"))   // 固定方向不改
    }

    @Test fun debounceHoldsOneWeakFlip() {
        val d = DirectionStage(Lang.ZH_CN, Lang.EN)
        assertEquals(Speaker.OTHER, d.decideByText("Where is the station please").speaker)
        // 短句（< 6 个字母）证据弱：第一次相反判定先沿用旧方向
        val weak = d.decideByText("好的")
        assertEquals(Speaker.OTHER, weak.speaker); assertTrue(weak.tentative)
        // 连续第二句同向才翻转
        val second = d.decideByText("没问题")
        assertEquals(Speaker.ME, second.speaker); assertFalse(second.tentative)
    }

    @Test fun strongEvidenceFlipsImmediately() {
        val d = DirectionStage(Lang.ZH_CN, Lang.EN)
        d.decideByText("Where is the station please")
        val r = d.decideByText("我想去火车站请问怎么走")
        assertEquals(Speaker.ME, r.speaker); assertFalse(r.tentative)
    }

    @Test fun voiceRefinesSameScript() {
        val d = DirectionStage(Lang.ZH_CN, Lang.ZH_SICHUAN, FakeExtractor())
        assertFalse(d.enrollMe(floatArrayOf(1f, 0f, 0f)))   // 第一句只作候选
        assertFalse(d.enrolled)
        assertTrue(d.enrollMe(floatArrayOf(1f, 0f, 0f)))    // 两句一致才落定
        assertTrue(d.enrolled)
        val byText = d.decideByText("要得")            // 同脚本：文本无法判
        assertEquals("same-script", byText.basis)
        assertEquals(Speaker.OTHER, byText.speaker)   // 无声纹证据时默认对方
        val refined = d.refineByVoice(floatArrayOf(1f, 0f), byText)   // 声纹说是我
        assertNotNull(refined); assertEquals(Speaker.ME, refined.speaker); assertEquals("voice", refined.basis)
        val same = d.refineByVoice(floatArrayOf(-1f, 0f), DirectionDecision(Speaker.OTHER, 0.3f, false, "x"))
        assertNull(same)   // 与当前一致不改
    }

    @Test fun userFlipRaisesVoiceWeightAndSuggestsFixedAfterThree() {
        val d = DirectionStage(Lang.ZH_CN, Lang.EN)
        assertFalse(d.userFlipped(Speaker.ME)); assertFalse(d.userFlipped(Speaker.OTHER))
        assertTrue(d.userFlipped(Speaker.ME))
        assertTrue(d.voiceWeight > 1f)
        d.fixed = Speaker.OTHER
        assertEquals("fixed", d.decideByText("我说的话").basis)
    }
}
