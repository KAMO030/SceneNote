package dev.scenenote.translate

import dev.scenenote.core.model.Lang
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeTranslator(override val id: String, override val cloud: Boolean, val delayMs: Long = 0, val fail: Throwable? = null) : Translator {
    var calls = 0
    override fun supports(src: String, tgt: String) = true
    override suspend fun translate(req: MtRequest): MtResult {
        calls++
        if (delayMs > 0) delay(delayMs)
        fail?.let { throw it }
        return MtResult("[$id] ${req.text}", req.src, req.tgt, id, "m", delayMs)
    }
}

class FastTranslatorTest {
    private val req = MtRequest("你好", Lang.ZH_CN, Lang.EN)

    @Test fun cloudFirstWhenFast() = runTest {
        val cloud = FakeTranslator("cloud", true); val local = FakeTranslator("local", false)
        val ft = FastTranslator({ cloud }, { local })
        val r = ft.translate(req)
        assertEquals("[cloud] 你好", r.result?.text); assertFalse(r.degraded); assertEquals(0, local.calls)
    }

    @Test fun timeoutFallsBackToLocal() = runTest {
        val cloud = FakeTranslator("cloud", true, delayMs = 5_000); val local = FakeTranslator("local", false)
        val ft = FastTranslator({ cloud }, { local }, timeoutMs = 1500)
        val r = ft.translate(req)
        assertEquals("[local] 你好", r.result?.text); assertTrue(r.degraded); assertEquals("fallback", ft.health)
    }

    @Test fun tripsAfterConsecutiveTimeouts() = runTest {
        val cloud = FakeTranslator("cloud", true, delayMs = 5_000); val local = FakeTranslator("local", false)
        val ft = FastTranslator({ cloud }, { local }, timeoutMs = 100, trips = 2)
        ft.translate(req); ft.translate(req)
        assertTrue(ft.tripped)
        val calls = cloud.calls
        ft.translate(req)
        assertEquals(calls, cloud.calls)   // 熔断期内不再打云端
    }

    @Test fun unavailableWhenNoKeyAndNoLocal() = runTest {
        val ft = FastTranslator({ null }, { null })
        val r = ft.translate(req)
        assertNull(r.result); assertTrue(r.degraded); assertEquals("unavailable", ft.health)
    }

    @Test fun nonRetryableErrorTrips() = runTest {
        val cloud = FakeTranslator("cloud", true, fail = MtFailed("401", retryable = false))
        val ft = FastTranslator({ cloud }, { null })
        ft.translate(req)
        assertTrue(ft.tripped)
    }

    @Test fun identityWhenSameLang() = runTest {
        val cloud = FakeTranslator("cloud", true)
        val r = FastTranslator({ cloud }, { null }).translate(MtRequest("hi", Lang.EN, Lang.EN))
        assertEquals("hi", r.result?.text); assertEquals(0, cloud.calls)
    }
}

class ScriptTest {
    @Test fun guessesByScript() {
        assertEquals(Lang.ZH_CN, Script.guessLang("今天天气不错", "en"))
        assertEquals(Lang.EN, Script.guessLang("How are you", "zh-CN"))
        assertEquals(Lang.ZH_CN, Script.guessLang("昨天是 monday", "en"))
        assertEquals(Lang.JA, Script.guessLang("今日はいい天気ですね", "en"))
        assertEquals("en", Script.guessLang("123 …", "en"))
    }
    @Test fun picksBetweenPair() {
        assertEquals(Lang.ZH_SICHUAN, Script.pick("要得", Lang.ZH_SICHUAN, Lang.EN))
        assertEquals(Lang.EN, Script.pick("okay sure", Lang.ZH_CN, Lang.EN))
        assertEquals(Lang.ZH_CN, Script.pick("", Lang.ZH_CN, Lang.EN))
    }
}
