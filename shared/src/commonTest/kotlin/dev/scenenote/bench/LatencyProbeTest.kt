package dev.scenenote.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LatencyProbeTest {
    @Test fun nearestRankPercentiles() {
        val v = listOf(1200L, 900L, 1500L, 1100L, 2200L, 1300L, 1000L, 1400L, 1250L, 1600L)
        assertEquals(1250L, Percentiles.p(v, 50.0))
        assertEquals(2200L, Percentiles.p(v, 95.0))
        assertNull(Percentiles.p(emptyList(), 50.0))
    }

    @Test fun recordDeltasAndSummary() {
        val lines = mutableListOf<String>()
        val probe = LatencyProbe { lines += it }
        probe.mark("u1", Mark.VAD_END, profile = "hybrid", atMs = 1000)
        probe.mark("u1", Mark.ASR_FINAL, atMs = 1700)
        probe.mark("u1", Mark.MT_FIRST, atMs = 2200)
        probe.mark("u1", Mark.MT_FIRST, atMs = 2300)      // 第二次不覆盖首字
        probe.mark("u1", Mark.TTS_FIRST, atMs = 2500)
        probe.mark("u1", Mark.SINK_WRITE, atMs = 2550)
        val r = probe.records.value.single()
        assertEquals(1550L, r.endToEndMs)
        assertEquals(700L, r.asrMs)
        assertEquals(500L, r.mtMs)
        assertEquals(300L, r.ttsMs)
        assertEquals(1, lines.size)
        assertEquals(1550L, probe.summary().e2eP50)
    }

    @Test fun marksWithoutVadEndAreIgnored() {
        val probe = LatencyProbe()
        probe.mark("x", Mark.ASR_FINAL, atMs = 10)
        assertEquals(0, probe.records.value.size)
    }
}
