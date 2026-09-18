package dev.scenenote.screen

import dev.scenenote.core.model.EngineInfo
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.Segment
import dev.scenenote.core.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleLangTest {
    private fun seg(text: String, lang: String, revision: Int) =
        Segment(id = "s", startMs = 0, endMs = 1000, lang = lang, rawText = text, text = text, isFinal = true, source = Source.LOCAL, revision = revision, engine = EngineInfo("sherpa-onnx", "m", "1"))

    @Test fun finalizerLanguageWins() {
        // 设定对方语言是日语，SenseVoice 定稿说这句是中文 / 英文：按识别结果，不按设定
        assertEquals(Lang.ZH_CN, cueLang(seg("今天天气不错", Lang.ZH_CN, 1), Lang.JA))
        assertEquals(Lang.EN, cueLang(seg("Nice weather today", Lang.EN, 1), Lang.JA))
        assertEquals(Lang.JA, cueLang(seg("東京大学", Lang.JA, 1), Lang.EN))   // 纯汉字的日语也信定稿
    }

    @Test fun draftFallsBackToScript() {
        // 草稿（rev0）的 lang 是会话设定：按文字脚本改判
        assertEquals(Lang.ZH_CN, cueLang(seg("今天天气不错", Lang.JA, 0), Lang.JA))
        assertEquals(Lang.EN, cueLang(seg("Nice weather today", Lang.JA, 0), Lang.JA))
        // 同家族（方言 / 粤语脚本分不出）沿用设定
        assertEquals(Lang.ZH_SICHUAN, cueLang(seg("今天天气巴适", Lang.ZH_SICHUAN, 0), Lang.ZH_SICHUAN))
        assertEquals(Lang.YUE_HK, cueLang(seg("今日天氣好好", Lang.YUE_HK, 0), Lang.YUE_HK))
        // 定稿没给出语种（自动）也按脚本
        assertEquals(Lang.EN, cueLang(seg("hello there", Lang.AUTO, 1), Lang.AUTO))
    }

    @Test fun onlyForeignSentencesAreTranslated() {
        assertFalse(needsMt(Lang.ZH_CN, Lang.ZH_CN))
        assertFalse(needsMt(Lang.ZH_SICHUAN, Lang.ZH_CN))   // 同一套书面语
        assertFalse(needsMt(Lang.WUU, Lang.ZH_CN))
        assertTrue(needsMt(Lang.YUE_HK, Lang.ZH_CN))        // 书面粤语 ≠ 普通话
        assertTrue(needsMt(Lang.ZH_CN, Lang.YUE_HK))
        assertTrue(needsMt(Lang.EN, Lang.ZH_CN))
        assertTrue(needsMt(Lang.JA, Lang.ZH_CN))
    }

    @Test fun batchesGroupSameLanguageUpToEight() {
        val langs = listOf(Lang.EN, Lang.ZH_CN, Lang.EN, Lang.JA, Lang.JA, Lang.EN) + List(10) { Lang.EN }
        val todo = langs.indices.filter { needsMt(langs[it], Lang.ZH_CN) }   // 下标 1 是中文，不翻
        val batches = batchesByLang(todo, langs)
        assertEquals(listOf(listOf(0, 2), listOf(3, 4), listOf(5, 6, 7, 8, 9, 10, 11, 12), listOf(13, 14, 15)), batches)
        batches.forEach { b -> assertEquals(1, b.map { langs[it] }.distinct().size) }
    }
}
