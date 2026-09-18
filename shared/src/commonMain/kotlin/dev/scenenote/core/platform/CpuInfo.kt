package dev.scenenote.core.platform

/** 可用 CPU 核数；取不到返回 -1。 */
expect object CpuInfo {
    fun cores(): Int
}

/**
 * ONNX 推理的线程预算（05 篇内存分级的算力版）。
 *
 * 实时会话里几路推理是同时在跑的：流式识别每 20 ms 一次、定稿一句一次、端侧翻译逐 token 解码、TTS 合成 + 声码器。
 * 各自按库的默认值开线程（2 + 2 + 4 = 8 条）会超过中低端机的大核数，抢起来谁都慢，而最先塌的是 TTS ——
 * 合成追不上说话，播放队列越积越多，听感上就成了"边听边播不工作，得先暂停"。
 *
 * 分配原则：识别必须跟上实时（句子丢了就补不回来），优先保住；TTS 按核数给，核少时绝不超配；
 * 翻译一句跑一次、本来就串行（[dev.scenenote.nmt.OnnxNmtTranslator] 的 mutex），保底两条够用。
 */
object ThreadBudget {
    val cores: Int by lazy { CpuInfo.cores().takeIf { it > 0 } ?: 4 }

    /** 流式识别 + 定稿。 */
    val asr: Int get() = 2

    /** TTS（Matcha + Vocos 声码器）：8 核给 3、4 核给 2，再多也不超过 4。 */
    val tts: Int get() = ttsFor(cores)

    internal fun ttsFor(cores: Int): Int = (cores / 2 - 1).coerceIn(2, 4)

    /** 端侧翻译（Marian encoder + 逐 token decoder）。 */
    val nmt: Int get() = 2

    /** 进诊断页 / 日志：一眼看出这台机器分了多少。 */
    val summary: String get() = "cores=$cores asr=$asr tts=$tts nmt=$nmt"
}
