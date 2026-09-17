package dev.scenenote.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinksTest {
    @Test fun parsesSelfTest() {
        val l = DeepLinks.parse("scenenote://selftest?autostart=1")!!
        assertEquals("selftest", l.host); assertEquals("1", l.query["autostart"]); assertEquals(emptyList(), l.path)
    }
    @Test fun parsesScenePath() {
        val l = DeepLinks.parse("scenenote://scene/live_talk")!!
        assertEquals("scene", l.host); assertEquals(listOf("live_talk"), l.path)
    }
    @Test fun parsesJoinQuery() {
        val l = DeepLinks.parse("scenenote://join?host=192.168.1.2&port=4870&pk=abc&salt=xyz&lang=en")!!
        assertEquals("join", l.host); assertEquals("4870", l.query["port"]); assertEquals("en", l.query["lang"])
    }
    @Test fun rejectsOtherSchemes() { assertNull(DeepLinks.parse("https://example.com")); assertNull(DeepLinks.parse("scenenote://")) }
}
