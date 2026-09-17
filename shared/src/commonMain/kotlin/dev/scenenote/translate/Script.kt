package dev.scenenote.translate

import dev.scenenote.core.model.Lang

/** 输出脚本判向（06 篇 §3.2：声纹 + 脚本；MVP 先只用脚本，中 ↔ 英足够）。 */
object Script {
    private fun isHan(c: Char): Boolean = c in '一'..'鿿' || c in '㐀'..'䶿' || c in '　'..'〿' || c in '＀'..'￯'
    private fun isLatin(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z'
    private fun isKana(c: Char): Boolean = c in '぀'..'ヿ'
    private fun isHangul(c: Char): Boolean = c in '가'..'힯' || c in 'ᄀ'..'ᇿ'

    /** 按字符脚本猜语言：返回 zh-CN / en / ja / ko；无法判断返回 [fallback]。 */
    fun guessLang(text: String, fallback: String): String {
        var han = 0; var latin = 0; var kana = 0; var hangul = 0
        for (c in text) when {
            isKana(c) -> kana++
            isHangul(c) -> hangul++
            isHan(c) -> han++
            isLatin(c) -> latin++
        }
        val total = han + latin + kana + hangul
        if (total == 0) return fallback
        return when {
            kana > 0 && kana >= han / 4 -> Lang.JA
            hangul > 0 -> Lang.KO
            han * 2 >= latin -> Lang.ZH_CN   // 一个汉字 ≈ 一个词，按 2 个拉丁字母计权
            else -> Lang.EN
        }
    }

    /** 两个候选语言中挑一个：识别文本的脚本更像哪个（中文方言与普通话同脚本）。 */
    fun pick(text: String, a: String, b: String): String {
        val g = guessLang(text, a)
        fun family(l: String) = when (l) { Lang.ZH_CN, Lang.YUE_HK, Lang.ZH_SICHUAN, Lang.WUU, Lang.NAN -> "zh"; else -> l }
        return when {
            family(a) == family(g) -> a
            family(b) == family(g) -> b
            else -> a
        }
    }
}
