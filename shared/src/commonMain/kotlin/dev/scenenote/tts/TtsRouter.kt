package dev.scenenote.tts

/** 用户偏好：auto = 系统优先、无系统语音时用端侧；system / local 强制；off 只出字。 */
enum class TtsPreference(val id: String) { AUTO("auto"), SYSTEM("system"), LOCAL("local"), OFF("off");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO }
}

data class TtsChoice(val engine: TtsEngine?, val reason: String) {
    val available: Boolean get() = engine != null
}

/** 按语言与偏好选引擎（05 篇 §7.3：系统 TTS 默认，端侧 sherpa 兜底；vivo 无任何系统引擎 → 端侧是主路径）。 */
class TtsRouter(private val system: () -> TtsEngine?, private val local: () -> TtsEngine?) {
    fun choose(lang: String, pref: TtsPreference): TtsChoice {
        if (pref == TtsPreference.OFF) return TtsChoice(null, "语音输出已关闭")
        val s = system()?.takeIf { it.supports(lang) }
        val l = local()?.takeIf { it.supports(lang) }
        return when (pref) {
            TtsPreference.SYSTEM -> if (s != null) TtsChoice(s, "系统语音") else TtsChoice(null, "系统没有该语言的语音包")
            TtsPreference.LOCAL -> if (l != null) TtsChoice(l, "端侧语音") else TtsChoice(null, "端侧语音包未下载")
            else -> when {
                s != null -> TtsChoice(s, "系统语音")
                l != null -> TtsChoice(l, "端侧语音")
                else -> TtsChoice(null, "无可用语音：系统没有该语言的语音包，端侧语音包也未下载")
            }
        }
    }
}
