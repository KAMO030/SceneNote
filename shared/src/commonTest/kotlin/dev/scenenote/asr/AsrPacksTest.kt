package dev.scenenote.asr

import dev.scenenote.core.model.Lang
import dev.scenenote.core.platform.MemoryTier
import dev.scenenote.models.ModelCatalog
import dev.scenenote.models.ModelPack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「听懂这门语言要下哪些包」与「按语言选定稿引擎」——日 / 韩只有 SenseVoice 认得，这两处都不能漏。 */
class AsrPacksTest {
    private fun installed(vararg packs: ModelPack): (ModelPack) -> Boolean = { it in packs }

    @Test fun baseCoversChineseAndEnglishOnly() {
        assertEquals(ModelCatalog.asrBase, ModelCatalog.asrPacksFor(Lang.ZH_CN))
        assertEquals(ModelCatalog.asrBase, ModelCatalog.asrPacksFor(Lang.EN))
        assertEquals(null, ModelCatalog.asrExtraFor(Lang.EN))
    }

    @Test fun japaneseAndKoreanNeedSenseVoice() {
        assertEquals(ModelCatalog.senseVoice, ModelCatalog.asrExtraFor(Lang.JA))
        assertEquals(ModelCatalog.senseVoice, ModelCatalog.asrExtraFor(Lang.KO))
        assertEquals(ModelCatalog.senseVoice, ModelCatalog.asrExtraFor(Lang.YUE_HK))
        assertEquals(ModelCatalog.paraformerSichuan, ModelCatalog.asrExtraFor(Lang.ZH_SICHUAN))
        assertTrue(ModelCatalog.senseVoice in ModelCatalog.asrPacksFor(Lang.JA))
    }

    /** 用户下了日译英 / 英译日两个翻译包却还被提示：缺的是识别侧的 SenseVoice，翻译包不顶用。 */
    @Test fun translationPacksDoNotSatisfyRecognition() {
        val withNmtOnly = installed(ModelCatalog.nmtJaEn, ModelCatalog.nmtEnJa, ModelCatalog.vadSilero, ModelCatalog.zipformerZhEn)
        assertEquals(listOf(ModelCatalog.senseVoice), ModelCatalog.asrPacksMissing(listOf(Lang.JA, Lang.EN), withNmtOnly))
        val complete = installed(ModelCatalog.vadSilero, ModelCatalog.zipformerZhEn, ModelCatalog.senseVoice)
        assertEquals(emptyList(), ModelCatalog.asrPacksMissing(listOf(Lang.JA, Lang.EN), complete))
    }

    @Test fun missingPacksAreListedOncePerLanguageSet() {
        val none: (ModelPack) -> Boolean = { false }
        assertEquals(
            listOf(ModelCatalog.vadSilero, ModelCatalog.zipformerZhEn, ModelCatalog.senseVoice),
            ModelCatalog.asrPacksMissing(listOf(Lang.JA, Lang.EN, Lang.KO), none),
        )
    }

    @Test fun onlyChineseAndEnglishHaveStreamingDraft() {
        assertTrue(Lang.hasStreamingDraft(Lang.ZH_CN))
        assertTrue(Lang.hasStreamingDraft(Lang.EN))
        assertTrue(Lang.hasStreamingDraft(Lang.YUE_HK))
        assertFalse(Lang.hasStreamingDraft(Lang.JA))
        assertFalse(Lang.hasStreamingDraft(Lang.KO))
    }

    /** 低内存机型省掉定稿是为了给 TTS 让内存，但日 / 韩省掉它就一个字都出不来。 */
    @Test fun lowMemoryStillLoadsFinalizerForJapanese() {
        assertEquals(Finalizer.NONE, LoadPlan.forLang(Lang.ZH_CN, tier = MemoryTier.LOW).finalizer)
        assertEquals(Finalizer.SENSE_VOICE, LoadPlan.forLang(Lang.JA, tier = MemoryTier.LOW).finalizer)
        assertEquals(Finalizer.SENSE_VOICE, LoadPlan.forLang(Lang.KO, tier = MemoryTier.LOW).finalizer)
        assertEquals(Finalizer.SENSE_VOICE, LoadPlan.forLang(Lang.YUE_HK, tier = MemoryTier.LOW).finalizer)
        assertEquals(Finalizer.SICHUAN, LoadPlan.forLang(Lang.ZH_SICHUAN, tier = MemoryTier.LOW).finalizer)
    }

    /** SenseVoice 的语种标签要能映射回 BCP-47，否则日 / 韩定稿回来会被当成会话语言。 */
    @Test fun senseVoiceLangTagsMapToBcp47() {
        assertEquals(Lang.JA, SherpaAsrEngine.mapLang("ja", Lang.EN))
        assertEquals(Lang.KO, SherpaAsrEngine.mapLang("<|ko|>", Lang.EN))
        assertEquals(Lang.YUE_HK, SherpaAsrEngine.mapLang("yue", Lang.EN))
        assertEquals(Lang.EN, SherpaAsrEngine.mapLang("unknown", Lang.EN))
    }
}
