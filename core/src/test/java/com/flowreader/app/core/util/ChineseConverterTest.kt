package com.flowreader.app.core.util

import com.flowreader.app.domain.model.ChineseConversion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseConverterTest {

    @Test
    fun noneReturnsTheSameInstance() {
        val text = "这是一段简体中文"
        assertSame(text, ChineseConverter.convert(text, ChineseConversion.NONE))
    }

    @Test
    fun simplifiedConvertsToTraditional() {
        val converted = ChineseConverter.convert("这个国家说汉语", ChineseConversion.SIMPLIFIED_TO_TRADITIONAL)
        assertEquals("這個國家說漢語", converted)
    }

    @Test
    fun traditionalConvertsToSimplified() {
        val converted = ChineseConverter.convert("這個國家說漢語", ChineseConversion.TRADITIONAL_TO_SIMPLIFIED)
        assertEquals("这个国家说汉语", converted)
    }

    @Test
    fun conversionPreservesLength() {
        // This is the property the reader relies on: stored annotation offsets stay valid because
        // every mapped character is replaced 1:1.
        val text = "中华人民共和国成立了，这是历史性的时刻。"
        assertEquals(text.length, ChineseConverter.convert(text, ChineseConversion.SIMPLIFIED_TO_TRADITIONAL).length)
        assertEquals(text.length, ChineseConverter.convert(text, ChineseConversion.TRADITIONAL_TO_SIMPLIFIED).length)
    }

    @Test
    fun punctuationAndDigitsAreUntouched() {
        val text = "2026 年，第 3 章：「你好」。"
        assertEquals(text, ChineseConverter.convert(text, ChineseConversion.SIMPLIFIED_TO_TRADITIONAL))
    }

    @Test
    fun singleCharacterOverloadMatchesTheStringOverload() {
        val char = ChineseConverter.convert('国', ChineseConversion.SIMPLIFIED_TO_TRADITIONAL)
        assertEquals('國', char)
        assertEquals('国', ChineseConverter.convert('國', ChineseConversion.TRADITIONAL_TO_SIMPLIFIED))
    }

    @Test
    fun isActiveOnlyForRealConversions() {
        assertTrue(!ChineseConverter.isActive(ChineseConversion.NONE))
        assertTrue(ChineseConverter.isActive(ChineseConversion.SIMPLIFIED_TO_TRADITIONAL))
    }
}
