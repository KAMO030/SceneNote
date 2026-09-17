package dev.scenenote.polish

import kotlin.test.Test
import kotlin.test.assertEquals

class TextCleanerTest {
    @Test fun removesFillersAndStutter() {
        assertEquals("这是第一种第二种叫与 always 什么意思。", TextCleaner.clean("这是第一种第第二种叫呃与 ALWAYS什么意思", "zh-CN"))
    }
    @Test fun keepsFactualWordsContainingFillerChars() {
        assertEquals("那个人来了。", TextCleaner.clean("那个人来了", "zh-CN"))
    }
    @Test fun englishFillerAndPunct() {
        assertEquals("I think it is fine.", TextCleaner.clean("um I think uh it is fine", "en"))
    }
    @Test fun cjkLatinSpacing() {
        assertEquals("昨天是 monday today is libr the day after tomorrow 是星期三。", TextCleaner.clean("昨天是 MONDAY TODAY IS LIBR THE DAY AFTER TOMORROW 是星期三", "zh-CN"))
    }
    @Test fun blankStaysBlank() { assertEquals("", TextCleaner.clean("  嗯 ", "zh-CN")) }
}

class TextCleanerCaseTest {
    @kotlin.test.Test fun allCapsEnglishIsNormalized() {
        kotlin.test.assertEquals("Today is monday.", TextCleaner.clean("TODAY IS MONDAY", "en"))
        kotlin.test.assertEquals("The day after tomorrow.", TextCleaner.clean("THE DAY AFTER TOMORROW。", "zh-CN"))
        kotlin.test.assertEquals("昨天是 monday。", TextCleaner.clean("昨天是MONDAY", "zh-CN"))
        kotlin.test.assertEquals("I think so.", TextCleaner.clean("I THINK SO", "en"))
    }
    @kotlin.test.Test fun mixedCaseUntouched() {
        kotlin.test.assertEquals("Hello World.", TextCleaner.clean("Hello World", "en"))
    }
}
