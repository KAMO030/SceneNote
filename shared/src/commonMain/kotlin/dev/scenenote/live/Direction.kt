package dev.scenenote.live

import dev.scenenote.asr.NativeSpeakerExtractor
import dev.scenenote.asr.cosine
import dev.scenenote.core.model.Lang
import dev.scenenote.core.model.Speaker
import dev.scenenote.translate.Script

/** 一次判向结果：tentative = 与上一句相反但证据不足，先沿用上一句方向（两句防抖）。 */
data class DirectionDecision(val speaker: Speaker, val confidence: Float, val tentative: Boolean, val basis: String)

/**
 * 判向阶段（规格 §3.2）：① ASR 输出脚本（中 ↔ 英零体积主判据）；② 声纹投票（3D-Speaker CAM++，会话内注册「我」，余弦 ≥ 0.6）；
 * ③ 两句防抖：方向翻转需连续两句同向，或单句高置信；④ 用户纠错（翻转 / 固定）在线调高声纹权重。
 * 同脚本（普通话 vs 方言、两人都说中文）时纯靠声纹；没有声纹模型 / 未注册时退化为脚本 + 防抖。
 * [otherLang] = [Lang.AUTO] 时按脚本猜语种（中 / 英 / 日 / 韩），不是我的家族就算对方；定稿 LID 到后由快路径 [overrideBy] 改判。
 */
class DirectionStage(
    private val myLang: String,
    private val otherLang: String,
    private val extractor: NativeSpeakerExtractor? = null,
    /** 上一场会话留下的「我」声纹（进程内跨会话复用）。 */
    initialMe: FloatArray? = null,
) {
    private var meEmbedding: FloatArray? = initialMe
    private var meCount = if (initialMe != null) 1 else 0
    /** 两句一致才落定：第一句只存候选。 */
    private var candidate: FloatArray? = null
    val meEmbeddingOrNull: FloatArray? get() = meEmbedding
    private var last: Speaker? = null
    private var pendingFlip: Speaker? = null
    /** 声纹权重：用户纠错后上调（规格 §3.2 ④）。 */
    var voiceWeight: Float = 1f; private set
    /** 固定方向（连续误判 3 次后用户可选退化为单工）。 */
    var fixed: Speaker? = null
        set(v) { field = v; corrections = 0 }
    private var corrections = 0
    val enrolled: Boolean get() = meEmbedding != null

    private fun sameScript(a: String, b: String) = family(a) == family(b)
    private fun family(l: String) = if (l.startsWith("zh") || l.startsWith("yue") || l.startsWith("wuu") || l.startsWith("nan")) "zh" else l

    /**
     * 注册 / 累加「我」的声纹（M4 我说的话、或对话里高置信的我方句）。
     * 首次注册要两句一致（余弦 ≥ 0.6）才落定，避免对方用我的语言打招呼被误注册；返回 true = 已落定。
     */
    fun enrollMe(samples: FloatArray): Boolean {
        val e = extractor?.runCatching { embed(samples) }?.getOrNull() ?: return false
        val cur = meEmbedding
        if (cur != null) { meEmbedding = FloatArray(e.size) { i -> (cur[i] * meCount + e[i]) / (meCount + 1) }; meCount++; return true }
        val c = candidate
        if (c == null) { candidate = e; return false }
        if (cosine(c, e) >= 0.6f) { meEmbedding = FloatArray(e.size) { i -> (c[i] + e[i]) / 2f }; meCount = 2; candidate = null; return true }
        candidate = e   // 两句不像：换成最新的作候选
        return false
    }
    /** 用户主动重置（设置 / 会话页「重置声纹」）。 */
    fun forgetMe() { meEmbedding = null; meCount = 0; candidate = null }

    /** 只有脚本证据时的判向（Final 到达即刻用，音频稍后到）。 */
    fun decideByText(text: String): DirectionDecision {
        fixed?.let { return DirectionDecision(it, 1f, false, "fixed") }
        if (sameScript(myLang, otherLang)) return DirectionDecision(last ?: Speaker.OTHER, 0.3f, tentative = true, basis = "same-script")   // 无声纹证据前一律标「?」
        val lang = if (otherLang == Lang.AUTO) Script.guessLang(text, Lang.EN) else Script.pick(text, myLang, otherLang)
        val guess = if (Lang.family(lang) == Lang.family(myLang)) Speaker.ME else Speaker.OTHER
        val letters = text.count { it.isLetter() }
        val conf = if (letters >= 6) 0.85f else 0.55f
        return debounce(guess, conf, "script")
    }

    /** 声纹证据到达后的复核：返回 null = 不改；否则给出新的判定（调用方决定是否重译）。 */
    fun refineByVoice(samples: FloatArray, current: DirectionDecision): DirectionDecision? {
        if (fixed != null) return null   // 固定方向 = 退化单工，声纹不再推翻
        val me = meEmbedding ?: return null
        val e = extractor?.runCatching { embed(samples) }?.getOrNull() ?: return null
        val sim = cosine(me, e)
        val voice = if (sim >= 0.6f) Speaker.ME else Speaker.OTHER
        val voiceConf = (kotlin.math.abs(sim - 0.6f) * 2.5f).coerceIn(0.2f, 1f) * voiceWeight
        if (voice == current.speaker) return null
        // 声纹与脚本相反：声纹更有把握或脚本本来就弱 → 改判
        return if (voiceConf >= current.confidence || sameScript(myLang, otherLang)) DirectionDecision(voice, voiceConf.coerceAtMost(1f), false, "voice").also { last = voice; pendingFlip = null } else null
    }

    /** 更强的证据（定稿语种识别）直接改判并落定防抖状态；固定方向时不改。 */
    fun overrideBy(speaker: Speaker, confidence: Float, basis: String): DirectionDecision? {
        if (fixed != null) return null
        last = speaker; pendingFlip = null
        return DirectionDecision(speaker, confidence, false, basis)
    }

    /** 用户纠错（翻转）：记入统计并上调声纹权重；连续 3 次返回 true 建议固定方向。 */
    fun userFlipped(to: Speaker): Boolean {
        last = to; pendingFlip = null
        corrections++
        voiceWeight = (voiceWeight * 1.25f).coerceAtMost(2.5f)
        return corrections >= 3
    }
    /** 一句高置信且未被纠正 → 「连续」计数清零。 */
    fun noteAccepted() { corrections = 0 }
    fun clearCorrections() { corrections = 0 }

    private fun debounce(guess: Speaker, conf: Float, basis: String): DirectionDecision {
        val prev = last
        if (prev == null || guess == prev || conf >= 0.8f) { last = guess; pendingFlip = null; return DirectionDecision(guess, conf, false, basis) }
        // 与上一句相反且证据不足：第一次先沿用旧方向（tentative），第二次连续同向才翻转
        return if (pendingFlip == guess) { last = guess; pendingFlip = null; DirectionDecision(guess, conf, false, "$basis+2") }
        else { pendingFlip = guess; DirectionDecision(prev, conf, true, "$basis-hold") }
    }

    fun reset() { last = null; pendingFlip = null; corrections = 0 }
}
