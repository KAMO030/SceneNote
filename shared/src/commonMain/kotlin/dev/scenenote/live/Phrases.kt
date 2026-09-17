package dev.scenenote.live

import dev.scenenote.core.model.Lang

/** 礼貌卡与退出语（规格 §4.2 / §4.4）：按对方语言取；风格 × 场景的礼貌度差异留到 I7 术语 / 风格回流。 */
object Phrases {
    fun politeCard(lang: String): String = when (lang.substringBefore('-')) {
        "zh", "yue", "wuu", "nan" -> "我在用翻译听你说话，请正常说。"
        "ja" -> "翻訳アプリで聞いています。普通に話してください。"
        "ko" -> "번역 앱으로 듣고 있어요. 평소처럼 말씀해 주세요."
        else -> "I'm using a translator to listen to you. Please speak normally."
    }
    fun listening(lang: String): String = when (lang.substringBefore('-')) { "zh", "yue", "wuu", "nan" -> "在听…"; "ja" -> "聞いています…"; "ko" -> "듣고 있어요…"; else -> "Listening…" }
    fun received(lang: String): String = when (lang.substringBefore('-')) { "zh", "yue", "wuu", "nan" -> "已收到"; "ja" -> "受け取りました"; "ko" -> "받았어요"; else -> "Received" }
    fun noThanks(lang: String): String = when (lang.substringBefore('-')) { "zh", "yue", "wuu", "nan" -> "我不用了，谢谢"; "ja" -> "結構です、ありがとう"; "ko" -> "괜찮아요, 감사합니다"; else -> "No thanks, I'm fine" }
    /** 对方按下「我不用了」后播给对方听的退出语（唯一默认允许的一次外放，−6 dB）。 */
    fun exitPhrase(lang: String): String = when (lang.substringBefore('-')) {
        "zh", "yue", "wuu", "nan" -> "好的，谢谢您，打扰了。"
        "ja" -> "わかりました。ありがとうございました。"
        "ko" -> "알겠습니다. 감사합니다."
        else -> "Okay, thank you. Sorry to bother you."
    }
    fun opener(lang: String): String = when (lang.substringBefore('-')) {
        "zh", "yue", "wuu", "nan" -> "你好，我在用翻译，请正常说话。"
        else -> "Hi, I'm using a translator. Please speak normally."
    }
    val ZH = Lang.ZH_CN
}
