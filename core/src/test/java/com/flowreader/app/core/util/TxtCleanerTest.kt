package com.flowreader.app.core.util

import com.flowreader.app.domain.model.TxtProcessingRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtCleanerTest {

    @Test
    fun normalizeCollapsesWindowsLineEndings() {
        assertEquals("a\nb", TxtCleaner.normalize("a\r\nb"))
        assertEquals("a\nb", TxtCleaner.normalize("a\rb"))
    }

    @Test
    fun adLinesAreRemovedButProseIsKept() {
        val text = "第一章 开始\n请记住本站网址 www.example.com\n正文第一句。\n更新时间：2026-09-09\n正文第二句。"
        val cleaned = TxtCleaner.clean(text, TxtProcessingRules())
        assertTrue(cleaned.contains("第一章 开始"))
        assertTrue(cleaned.contains("正文第一句。"))
        assertTrue(cleaned.contains("正文第二句。"))
        assertFalse(cleaned.contains("请记住本站"))
        assertFalse(cleaned.contains("更新时间"))
    }

    @Test
    fun stripBlankLinesCollapsesRuns() {
        val text = "a\n\n\n\nb\n\n\nc"
        val cleaned = TxtCleaner.clean(text, TxtProcessingRules(stripBlankLines = true, removeAdLines = false))
        assertEquals("a\n\nb\n\nc", cleaned)
    }

    @Test
    fun customReplacementsApplyPerLine() {
        val rules = TxtProcessingRules(
            removeAdLines = false,
            customReplacements = "本章未完.* =>\n（未完待续） =>"
        )
        val cleaned = TxtCleaner.clean("正文\n本章未完，点击下一页继续阅读\n（未完待续）", rules)
        // Both junk lines become empty and are trimmed away by clean().
        assertEquals("正文", cleaned)
    }

    @Test
    fun replacementRulesParseTheArrowSyntax() {
        val rules = TxtCleaner.parseReplacements("foo => bar\nbaz\n# comment")
        assertEquals(2, rules.size)
        assertEquals("bar", rules[0].first.replace("foo", rules[0].second))
        assertEquals("", rules[1].second)
    }

    @Test
    fun invalidReplacementPatternsAreSkipped() {
        // An unclosed group must not throw; the rest of the rules still apply.
        val rules = TxtCleaner.parseReplacements("[unclosed => x\nok => y")
        assertEquals(1, rules.size)
    }

    @Test
    fun chapterRegexCompilesAndRejectsGarbage() {
        assertEquals(true, TxtCleaner.compileChapterRegex("^第\\d+章")?.containsMatchIn("第12章 标题"))
        assertNull(TxtCleaner.compileChapterRegex(""))
        assertNull(TxtCleaner.compileChapterRegex("[unclosed"))
        assertNull(TxtCleaner.compileChapterRegex("a".repeat(400)))
    }

    @Test
    fun cleaningIsIdempotent() {
        val text = "第一章\n\n\n正文\n请记住本站\n"
        val rules = TxtProcessingRules(stripBlankLines = true)
        val once = TxtCleaner.clean(text, rules)
        assertEquals(once, TxtCleaner.clean(once, rules))
    }
}
