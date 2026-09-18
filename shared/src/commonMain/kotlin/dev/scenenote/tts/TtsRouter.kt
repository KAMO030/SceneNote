package dev.scenenote.tts

import dev.scenenote.core.i18n.UiText
import dev.scenenote.shared.resources.*

/** 用户偏好：auto = 系统优先、无系统语音时用端侧；system / local 强制；off 只出字。 */
enum class TtsPreference(val id: String) { AUTO("auto"), SYSTEM("system"), LOCAL("local"), OFF("off");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO }
}

/** [reasonCode] 进日志 / 健康面板；[reason] 是给用户看的文案。 */
data class TtsChoice(val engine: TtsEngine?, val reasonCode: String, val reason: UiText) {
    val available: Boolean get() = engine != null
}

/** 按语言与偏好选引擎（05 篇 §7.3：系统 TTS 默认，端侧 sherpa 兜底；vivo 无任何系统引擎 → 端侧是主路径）。 */
class TtsRouter(private val system: () -> TtsEngine?, private val local: () -> TtsEngine?) {
    fun choose(lang: String, pref: TtsPreference): TtsChoice {
        if (pref == TtsPreference.OFF) return TtsChoice(null, "off", UiText.Res(Res.string.tts_reason_off))
        val s = system()?.takeIf { it.supports(lang) }
        val l = local()?.takeIf { it.supports(lang) }
        return when (pref) {
            TtsPreference.SYSTEM -> if (s != null) TtsChoice(s, "system", UiText.Res(Res.string.tts_reason_system)) else TtsChoice(null, "no_system_voice", UiText.Res(Res.string.tts_reason_no_system_voice))
            TtsPreference.LOCAL -> if (l != null) TtsChoice(l, "local", UiText.Res(Res.string.tts_reason_local)) else TtsChoice(null, "no_local_pack", UiText.Res(Res.string.tts_reason_no_local_pack))
            else -> when {
                s != null -> TtsChoice(s, "system", UiText.Res(Res.string.tts_reason_system))
                l != null -> TtsChoice(l, "local", UiText.Res(Res.string.tts_reason_local))
                else -> TtsChoice(null, "none", UiText.Res(Res.string.tts_reason_none))
            }
        }
    }
}
