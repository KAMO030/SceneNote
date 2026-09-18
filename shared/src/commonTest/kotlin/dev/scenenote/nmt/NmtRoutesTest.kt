package dev.scenenote.nmt

import dev.scenenote.core.model.Lang
import dev.scenenote.models.ModelCatalog
import dev.scenenote.models.ModelPack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NmtRoutesTest {
    private fun installed(vararg packs: ModelPack): (ModelPack) -> Boolean = { it in packs }

    @Test fun directPairsUseTheDedicatedPack() {
        val r = NmtRoutes.resolve(Lang.ZH_CN, Lang.EN, installed(ModelCatalog.nmtZhEn, ModelCatalog.nmtEnZh))
        assertIs<NmtRoute.Direct>(r); assertEquals(ModelCatalog.nmtZhEn, r.leg.pack); assertNull(r.leg.langToken)
        val e = NmtRoutes.resolve(Lang.EN, Lang.ZH_CN, installed(ModelCatalog.nmtZhEn, ModelCatalog.nmtEnZh))
        assertIs<NmtRoute.Direct>(e); assertEquals(">>cmn_Hans<<", e.leg.langToken)
        val y = NmtRoutes.resolve(Lang.EN, Lang.YUE_HK, installed(ModelCatalog.nmtEnZh))
        assertIs<NmtRoute.Direct>(y); assertEquals(">>yue_Hant<<", y.leg.langToken)
    }

    @Test fun dialectsShareTheMandarinPack() {
        assertIs<NmtRoute.Direct>(NmtRoutes.resolve(Lang.ZH_SICHUAN, Lang.EN, installed(ModelCatalog.nmtZhEn)))
        assertIs<NmtRoute.Identity>(NmtRoutes.resolve(Lang.ZH_SICHUAN, Lang.ZH_CN, installed()))
    }

    @Test fun cantoneseToEnglishUsesTheChinesePack() {
        val b = NmtRoutes.resolve(Lang.YUE_HK, Lang.EN, installed(ModelCatalog.nmtZhEn))
        assertIs<NmtRoute.Direct>(b); assertEquals(ModelCatalog.nmtZhEn, b.leg.pack)
        val j = NmtRoutes.resolve(Lang.EN, Lang.JA, installed(ModelCatalog.nmtEnJa))
        assertIs<NmtRoute.Direct>(j); assertEquals(ModelCatalog.nmtEnJa, j.leg.pack); assertNull(j.leg.langToken)   // FuguMT 不需要目标语言标记
    }

    @Test fun pivotsThroughEnglishWhenNoDirectModel() {
        val r = NmtRoutes.resolve(Lang.JA, Lang.ZH_CN, installed(ModelCatalog.nmtJaEn, ModelCatalog.nmtEnZh))
        assertIs<NmtRoute.Pivot>(r); assertEquals(ModelCatalog.nmtJaEn, r.first.pack); assertEquals(ModelCatalog.nmtEnZh, r.second.pack)
        assertEquals("ja-en→en-zh:cmn_Hans", r.label)
        val z = NmtRoutes.resolve(Lang.ZH_CN, Lang.JA, installed(ModelCatalog.nmtZhEn, ModelCatalog.nmtEnJa))
        assertIs<NmtRoute.Pivot>(z); assertEquals(ModelCatalog.nmtEnJa, z.second.pack); assertEquals("zh-en→en-ja", z.label)
        assertNull(NmtRoutes.resolve(Lang.JA, Lang.ZH_CN, installed(ModelCatalog.nmtJaEn)))   // 缺第二段
    }

    @Test fun englishToKoreanHasNoLocalModel() {
        assertNull(NmtRoutes.resolve(Lang.EN, Lang.KO) { true })
        assertNull(NmtRoutes.resolve(Lang.ZH_CN, Lang.KO) { true })
        assertTrue(NmtRoutes.packsFor(Lang.EN, Lang.KO) { false }.isEmpty())
    }

    @Test fun packsForListsWhatToDownload() {
        assertEquals(listOf(ModelCatalog.nmtZhEn), NmtRoutes.packsFor(Lang.ZH_CN, Lang.EN) { false })
        assertEquals(listOf(ModelCatalog.nmtZhEn, ModelCatalog.nmtEnZh), NmtRoutes.packsForPair(Lang.ZH_CN, Lang.EN) { false })
        assertEquals(listOf(ModelCatalog.nmtEnZh), NmtRoutes.packsForPair(Lang.ZH_CN, Lang.EN, installed(ModelCatalog.nmtZhEn)))
        assertEquals(emptyList(), NmtRoutes.packsForPair(Lang.ZH_CN, Lang.EN, installed(ModelCatalog.nmtZhEn, ModelCatalog.nmtEnZh)))
        assertEquals(listOf(ModelCatalog.nmtJaEn, ModelCatalog.nmtEnZh), NmtRoutes.packsFor(Lang.JA, Lang.ZH_CN) { false })
    }

    @Test fun cjkTidyNormalisesPunctuation() {
        assertEquals("你好，很高兴见到你。", LoadedModel.tidyCjk("你好,很高兴见到你."))
        assertEquals("今天天气不错，我们去公园散步吧", LoadedModel.tidyCjk("今天天气不错 我们去公园散步吧"))
        assertEquals("明天下午3点开会，请不要迟到", LoadedModel.tidyCjk("明天下午3点开会 请不要迟到"))
        assertEquals("Wang Xiaoming 是工程师", LoadedModel.tidyCjk("Wang Xiaoming 是工程师"))
    }

    @Test fun sentenceSplitOnlyForLongText() {
        assertEquals(listOf("短句。第二句。"), LoadedModel.splitSentences("短句。第二句。"))
        val long = "第一句话说得比较长一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点一点。第二句话也很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长！最后一句"
        assertEquals(3, LoadedModel.splitSentences(long).size)
        val en = "This is the first sentence which goes on and on and on and on and on and on and on and on. Second one here! And a third? Yes."
        assertEquals(listOf("This is the first sentence which goes on and on and on and on and on and on and on and on.", "Second one here!", "And a third?", "Yes."), LoadedModel.splitSentences(en))
    }
}
