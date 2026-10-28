package com.flowreader.app.util

import com.flowreader.app.domain.model.SearchHitProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 书内搜索命中坐标测试（主计划第 9 节）。
 *
 * 书内搜索与全局搜索必须共享同一套坐标语义：命中必须能精确定位，
 * 无法定位时必须**如实标注**而不是退回「只有 chapterIndex」。
 */
class FtsSearchResultLocatorTest {

    @Test
    fun resultWithoutOffsetIsNotLocatableAndDefaultsToApproximate() {
        val result = FtsSearchResult(
            chapterIndex = 2,
            chapterTitle = "第二章",
            matchedText = "命中文本"
        )
        assertFalse(result.isLocatable)
        assertEquals(-1, result.locatorOffset)
        assertEquals(
            "默认必须是保守的近似，不能默认精确",
            SearchHitProjector.Confidence.APPROXIMATE,
            result.locatorConfidence
        )
    }

    @Test
    fun resultWithOffsetIsLocatableAndCarriesConfidence() {
        val result = FtsSearchResult(
            chapterIndex = 2,
            chapterTitle = "第二章",
            matchedText = "命中文本",
            locatorOffset = 137,
            locatorConfidence = SearchHitProjector.Confidence.EXACT
        )
        assertTrue(result.isLocatable)
        assertEquals(137, result.locatorOffset)
        assertEquals(SearchHitProjector.Confidence.EXACT, result.locatorConfidence)
    }

    @Test
    fun zeroOffsetIsAValidLocationNotAMissingOne() {
        // 章首命中（offset = 0）是**合法位置**，不能被当成「未定位」。
        val result = FtsSearchResult(
            chapterIndex = 0,
            chapterTitle = "第一章",
            matchedText = "开头命中",
            locatorOffset = 0,
            locatorConfidence = SearchHitProjector.Confidence.EXACT
        )
        assertTrue("offset 0 必须被视为可定位", result.isLocatable)
    }

    @Test
    fun copyPreservesLocatorFieldsWhenOnlyTextIsStripped() {
        // 仓库/ViewModel 会用 copy 替换展示文本，坐标不得因此丢失。
        val original = FtsSearchResult(
            chapterIndex = 1,
            chapterTitle = "C",
            matchedText = "...<<关键词>>...",
            locatorOffset = 42,
            locatorConfidence = SearchHitProjector.Confidence.EXACT
        )
        val stripped = original.copy(matchedText = SearchHitProjector.stripMarkers(original.matchedText))
        assertEquals(42, stripped.locatorOffset)
        assertEquals(SearchHitProjector.Confidence.EXACT, stripped.locatorConfidence)
        assertFalse(stripped.matchedText.contains("<<"))
        assertEquals("关键词", stripped.matchedText)
    }
}
