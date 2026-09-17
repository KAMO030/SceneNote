package dev.scenenote.polish

/**
 * S0 规则层（02 篇 §2.4）：不改事实，只做可逆的表层整理——去口癖、去结巴重复、中英之间加空格、补句末标点。
 * 零 Key 也能跑；输出写入 Segment.text，rawText 保留原文。
 */
object TextCleaner {
    /** 任何位置都可删的纯口癖字（几乎不构成词）。 */
    private val zhAnywhere = Regex("[呃嗯唔]")
    /** 只在词边界成立时才删的口癖（避免把"那个人"的"那个"、"啊呀"删掉：只删独立出现、句首或句尾的）。 */
    private val zhFillers = listOf("啊", "哦", "额", "那个那个", "就是就是", "然后然后")
    private val enFillers = Regex("""\b(um+|uh+|erm+|hmm+)\b""", RegexOption.IGNORE_CASE)
    private const val HAN = "[\\u4E00-\\u9FFF\\u3400-\\u4DBF]"   // Kotlin/Native 正则不支持 \\p{IsHan}
    private val cjk = HAN
    private val latinDigit = "[A-Za-z0-9]"
    private val cjkLatinGap = Regex("($cjk)($latinDigit)")
    private val latinCjkGap = Regex("($latinDigit)($cjk)")
    private val stutter = Regex("($HAN)\\1{1,2}(?=$HAN)")   // 第第二 → 第二
    private val multiSpace = Regex("\\s{2,}")

    fun clean(raw: String, lang: String): String {
        if (raw.isBlank()) return ""
        var t = raw.trim()
        // 1) 口癖
        t = enFillers.replace(t, "")
        t = zhAnywhere.replace(t, "")
        for (f in zhFillers) {
            t = t.replace(Regex("^$f[，,。\\s]*"), "")           // 句首
            t = t.replace(Regex("[，,\\s]$f[，,\\s]"), "，")       // 独立出现
            t = t.replace(Regex("[，,\\s]$f$"), "")               // 句尾
        }
        // 2) 结巴重复（仅汉字单字重复且后面还有字：这这个 → 这个）
        t = stutter.replace(t) { it.groupValues[1] }
        // 3) 中英之间空格
        t = cjkLatinGap.replace(t) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        t = latinCjkGap.replace(t) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        t = multiSpace.replace(t, " ").trim().trim('，', ',', ' ')
        if (t.isEmpty()) return ""
        // 4) 大小写：zipformer 的 BPE 词表全大写（"TODAY IS MONDAY"），整段全大写时归一为句首大写 + 独立 I
        t = normalizeCase(t)
        // 5) 句末标点（已有则不加；英文句子里的中文句号换成英文）
        val zhLang = lang.startsWith("zh") || lang.startsWith("yue") || lang.startsWith("wuu") || lang.startsWith("nan")
        val last = t.last()
        if (last !in "。！？.!?…") t += if (zhLang && Regex(HAN).containsMatchIn(t) || zhLang && !Regex("[A-Za-z]").containsMatchIn(t)) "。" else "."
        else if (last == '。' && !Regex(HAN).containsMatchIn(t)) t = t.dropLast(1) + "."
        return t
    }

    private val allCapsWord = Regex("\\b[A-Z]{2,}\\b")
    private fun normalizeCase(t: String): String {
        val letters = t.filter { it in 'A'..'Z' || it in 'a'..'z' }
        if (letters.length < 2 || letters.any { it in 'a'..'z' }) return t
        var out = t.lowercase()
        out = Regex("\\bi\\b").replace(out, "I")
        // 句首（整段开头与 . ! ? 之后）大写
        val sb = StringBuilder(out)
        var cap = true
        for (i in sb.indices) {
            val c = sb[i]
            if (cap && c.isLetter()) { sb[i] = c.uppercaseChar(); cap = false }
            if (c in ".!?。！？") cap = true
        }
        return sb.toString()
    }
}
