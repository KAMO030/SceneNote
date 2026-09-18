package dev.scenenote.asr

/**
 * 流式 zipformer（int8 双语）的时序叠字过滤：同一 token 在 ≤ [SAME_MS] 内再次发出（「很@520 很@560」）、
 * 或两 token 组合在 ≤ [PAIR_MS] 内整体重发（「到你@1199 到你@1360」），都是同一段声音被解码器重复吐出——
 * 人说不了这么快（40 ms 一个字 / 200 ms 四个字）。真正的叠词（谢谢、研究研究）间隔 ≥ 150 ms，不受影响。
 * 只作用于草稿（partial / rev0），定稿（SenseVoice）不经过这里。vivo V2436A 近讲实测阈值。
 */
object DraftDedup {
    const val SAME_MS = 120
    const val PAIR_MS = 200

    /** 返回保留下来的 token（保持顺序）。 */
    fun keep(tokens: List<String>, timestamps: FloatArray): List<String> {
        if (tokens.size < 2 || timestamps.size != tokens.size) return tokens
        val out = ArrayList<String>(tokens.size); val outTs = ArrayList<Int>(tokens.size)
        for (i in tokens.indices) {
            val t = tokens[i]; val ts = (timestamps[i] * 1000).toInt()
            val n = out.size
            if (n >= 1 && out[n - 1] == t && ts - outTs[n - 1] <= SAME_MS) continue
            if (n >= 3 && out[n - 3] == out[n - 1] && out[n - 2] == t && outTs[n - 1] - outTs[n - 3] <= PAIR_MS) {
                // 已保留 …A B A，当前是 B 且两个 A 间隔 ≤ PAIR_MS：去掉后一组的 A（已入 out）与本 B
                out.removeAt(n - 1); outTs.removeAt(n - 1); continue
            }
            out += t; outTs += ts
        }
        return out
    }
}
