package dev.scenenote.tts

import kotlin.test.Test
import kotlin.test.assertEquals

class ClausesTest {
    @Test fun splitsOnChinesePunctuation() {
        assertEquals(listOf("你好，欢迎使用场记。", "现在是语音自检。"), Clauses.split("你好，欢迎使用场记。现在是语音自检。"))
    }
    @Test fun keepsShortTailAttached() {
        assertEquals(listOf("Hello, this is the voice self test."), Clauses.split("Hello, this is the voice self test."))
        assertEquals(listOf("Hello there,", "friend. How are you?"), Clauses.split("Hello there, friend. How are you?"))
    }
    @Test fun neverEmpty() {
        assertEquals(listOf("…"), Clauses.split("…"))
        assertEquals(listOf("好"), Clauses.split("好"))
    }
    @Test fun resampleLength() {
        val out = Resample.toPcm16(FloatArray(22050) { 0f }, 22050)
        assertEquals(16000, out.size)
    }
}
