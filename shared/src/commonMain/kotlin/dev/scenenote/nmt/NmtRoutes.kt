package dev.scenenote.nmt

import dev.scenenote.core.model.Lang
import dev.scenenote.models.ModelCatalog
import dev.scenenote.models.ModelPack

/** 一条可翻的方向：用哪个包、句首要不要目标语言标记（opus-mt 多目标模型靠 `>>xxx<<` 选目标）。 */
data class NmtLeg(val pack: ModelPack, val langToken: String?) {
    val label: String get() = pack.id.removePrefix("nmt-") + (langToken?.let { ":${it.trim('>', '<')}" } ?: "")
}

/** 解析出的路线：直译一步，或经英语中转两步（日 ↔ 中、韩 → 中 / 日 都没有直连模型）。 */
sealed interface NmtRoute {
    val legs: List<NmtLeg>
    val label: String get() = legs.joinToString("→") { it.label }
    data class Direct(val leg: NmtLeg) : NmtRoute { override val legs get() = listOf(leg) }
    data class Pivot(val first: NmtLeg, val second: NmtLeg) : NmtRoute { override val legs get() = listOf(first, second) }
    /** 同一书写系统（普通话 ↔ 川渝 / 吴 / 闽）：不翻，原文即译文。 */
    data object Identity : NmtRoute { override val legs get() = emptyList<NmtLeg>() }
}

/**
 * 语言对 → 候选包（顺序即优先级；第一项是推荐下载的）。源按语言家族比较（方言与普通话同脚本），目标按具体标签。
 * 粤语 → 英走中英包（书面粤语实测比 opus-mt-mul-en 好得多）；日 ↔ 英是 FuguMT，不需要目标语言标记。
 * 英 → 韩暂无可用端侧模型（opus-mt-en-mul 不含 kor；tc-big-en-ko 的 HF 词表缺源语言，英文全成 <unk>），保持云端。
 */
object NmtRoutes {
    private const val CMN_HANS = ">>cmn_Hans<<"
    private const val YUE_HANT = ">>yue_Hant<<"

    private fun srcKey(tag: String): String = when (tag) { Lang.YUE_HK -> "yue"; else -> Lang.family(tag) }

    private fun direct(src: String, tgt: String): List<NmtLeg> {
        val c = ModelCatalog
        return when (srcKey(src) to tgt) {
            "zh" to Lang.EN, "yue" to Lang.EN -> listOf(NmtLeg(c.nmtZhEn, null))
            "ja" to Lang.EN -> listOf(NmtLeg(c.nmtJaEn, null))
            "ko" to Lang.EN -> listOf(NmtLeg(c.nmtKoEn, null))
            "en" to Lang.ZH_CN, "en" to Lang.ZH_SICHUAN, "en" to Lang.WUU, "en" to Lang.NAN -> listOf(NmtLeg(c.nmtEnZh, CMN_HANS))
            "en" to Lang.YUE_HK -> listOf(NmtLeg(c.nmtEnZh, YUE_HANT))
            "en" to Lang.JA -> listOf(NmtLeg(c.nmtEnJa, null))
            else -> emptyList()
        }
    }

    /** 按已安装情况解析路线；`installed` 由调用方提供（ModelStore.isInstalled），便于测试。 */
    fun resolve(src: String, tgt: String, installed: (ModelPack) -> Boolean): NmtRoute? {
        if (src == tgt || Lang.family(src) == Lang.family(tgt)) return NmtRoute.Identity
        direct(src, tgt).firstOrNull { installed(it.pack) }?.let { return NmtRoute.Direct(it) }
        if (src != Lang.EN && tgt != Lang.EN) {
            val a = direct(src, Lang.EN).firstOrNull { installed(it.pack) } ?: return null
            val b = direct(Lang.EN, tgt).firstOrNull { installed(it.pack) } ?: return null
            return NmtRoute.Pivot(a, b)
        }
        return null
    }

    /** 这个方向端侧能不能做（不管装没装）。 */
    fun possible(src: String, tgt: String): Boolean = resolve(src, tgt) { true } != null

    /** 让某方向可用需要下载的包（已装的不列）；不可能的方向返回空。 */
    fun packsFor(src: String, tgt: String, installed: (ModelPack) -> Boolean): List<ModelPack> {
        if (resolve(src, tgt, installed) != null) return emptyList()
        val route = resolve(src, tgt) { true } ?: return emptyList()
        return route.legs.map { it.pack }.filterNot(installed).distinct()
    }

    /** 一对语言双向都可用需要的包（面对面对话两边都要翻）。 */
    fun packsForPair(a: String, b: String, installed: (ModelPack) -> Boolean): List<ModelPack> = (packsFor(a, b, installed) + packsFor(b, a, installed)).distinct()
}
