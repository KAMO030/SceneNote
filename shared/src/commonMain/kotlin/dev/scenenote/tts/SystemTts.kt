package dev.scenenote.tts

/**
 * 平台系统 TTS 提供者：iOS 永远有（AVSpeechSynthesizer 内置 compact 语音）；
 * Android 取决于机型（vivo V2054A 有 com.vivo.aiservice 的引擎，包名不含 "tts"；探测靠 TextToSpeech.engines）。
 */
interface SystemTtsProvider {
    fun get(): TtsEngine?
    /** Android 引擎初始化是异步的；会话开始前先等它，避免首句误判"无系统语音"。 */
    suspend fun awaitReady() {}
}
