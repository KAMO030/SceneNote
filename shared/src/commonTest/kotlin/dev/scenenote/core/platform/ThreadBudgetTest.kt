package dev.scenenote.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 线程预算：核少的机器绝不超配，核多也不无限放大（ONNX 线程多了只会互相抢）。 */
class ThreadBudgetTest {
    @Test fun ttsNeverOversubscribesSmallDevices() {
        assertEquals(2, ThreadBudget.ttsFor(2))
        assertEquals(2, ThreadBudget.ttsFor(4))
        assertEquals(2, ThreadBudget.ttsFor(6))
        assertEquals(3, ThreadBudget.ttsFor(8))
        assertEquals(4, ThreadBudget.ttsFor(12))
        assertEquals(4, ThreadBudget.ttsFor(16))
    }

    /** 三路加起来别超过核数太多：识别要跟上实时，它是最不能让的一路。 */
    @Test fun totalStaysCloseToCoreCount() {
        for (cores in intArrayOf(2, 4, 6, 8, 12, 16)) {
            val total = ThreadBudget.asr + ThreadBudget.ttsFor(cores) + ThreadBudget.nmt
            assertTrue(total <= maxOf(6, cores), "cores=$cores total=$total")
        }
    }

    @Test fun coresFallsBackWhenPlatformReturnsNothing() {
        assertTrue(ThreadBudget.cores > 0)
        assertEquals(2, ThreadBudget.asr)
        assertEquals(2, ThreadBudget.nmt)
    }
}
