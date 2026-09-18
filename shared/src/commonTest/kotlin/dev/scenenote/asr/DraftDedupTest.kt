package dev.scenenote.asr

import kotlin.test.Test
import kotlin.test.assertEquals

class DraftDedupTest {
    private fun run(vararg pairs: Pair<String, Int>) =
        DraftDedup.keep(pairs.map { it.first }, pairs.map { it.second / 1000f }.toFloatArray()).joinToString("")

    @Test fun dropsSameTokenReemittedWithin120ms() {
        // vivo 近讲实测：很@520 很@560 / 见@1040 见@1079 / 拜@2120 拜@2240
        assertEquals("你好很高兴", run("你" to 0, "好" to 359, "很" to 520, "很" to 560, "高" to 719, "兴" to 880))
        assertEquals("礼拜二", run("礼" to 2000, "拜" to 2120, "拜" to 2240, "二" to 2440))
    }

    @Test fun keepsRealReduplication() {
        assertEquals("谢谢你", run("谢" to 0, "谢" to 180, "你" to 400))
        assertEquals("偏偏这时候", run("偏" to 0, "偏" to 150, "这" to 320, "时" to 460, "候" to 600))
    }

    @Test fun dropsPairReemittedWithin200ms() {
        // 到@1199 你@1240 到@1360 你@1560
        assertEquals("见到你今天", run("见" to 1040, "到" to 1199, "你" to 1240, "到" to 1360, "你" to 1560, "今" to 1800, "天" to 1880))
        assertEquals("研究研究", run("研" to 0, "究" to 160, "研" to 380, "究" to 540))   // 正常语速的 ABAB 保留
    }

    @Test fun englishPiecesUntouched() {
        assertEquals(" TODAY AFTER TOMORROW", run(" TO" to 399, "DAY" to 599, " AFTER" to 1040, " TO" to 1640, "M" to 1760, "OR" to 1839, "ROW" to 1880))
    }

    @Test fun mismatchedTimestampsPassThrough() {
        assertEquals(listOf("a", "a"), DraftDedup.keep(listOf("a", "a"), FloatArray(0)))
    }
}
