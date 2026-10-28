package com.flowreader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 搜索结果模型测试（主计划第 9 节）。
 *
 * 核心命题：搜索结果必须携带**可用的内容坐标**，且在没有坐标时**如实标注**，
 * 不能退回「只有 chapterIndex」的旧模型让 UI 假装能精确定位。
 */
class GlobalSearchResultLocatorTest {

    private val doc = "chapter:3"

    private fun result(
        ranges: List<SourceRange> = emptyList(),
        confidence: SearchHitProjector.Confidence = SearchHitProjector.Confidence.EXACT
    ) = GlobalSearchResult(
        bookId = 1L,
        bookTitle = "书",
        chapterIndex = 3,
        chapterTitle = "第三章",
        matchedText = "命中的文字",
        documentKey = doc,
        revision = "rev-1",
        publicationId = "pub-1",
        ranges = ranges,
        confidence = confidence
    )

    @Test
    fun resultWithRangesIsLocatableAndConvertsToTextLocator() {
        val subject = result(listOf(SourceRange(doc, 120, 124)))
        assertTrue(subject.isLocatable)
        assertEquals(120, subject.firstStartUtf16)

        val locator = subject.toTextLocator()
        assertNotNull(locator)
        assertEquals(120, locator!!.offsetUtf16)
        assertEquals(doc, locator.documentKey)
        assertEquals("pub-1", locator.publicationId)
        assertEquals("rev-1", locator.revision)
        assertEquals("section_3", locator.sectionId)
    }

    @Test
    fun resultWithoutRangesIsNotLocatableAndYieldsNoLocator() {
        val subject = result()
        assertFalse(subject.isLocatable)
        assertNull(subject.firstStartUtf16)
        // 关键：不得用 chapterIndex 伪造一个偏移。
        assertNull("无坐标时必须拒绝构造 locator", subject.toTextLocator())
    }

    @Test
    fun blankPublicationIdPreventsFabricatedLocator() {
        val subject = result(listOf(SourceRange(doc, 5, 9))).copy(publicationId = "")
        assertNull("没有 publicationId 就不能声称可定位", subject.toTextLocator())
    }

    @Test
    fun blankDocumentKeyPreventsFabricatedLocator() {
        val subject = result(listOf(SourceRange(doc, 5, 9))).copy(documentKey = "")
        assertNull(subject.toTextLocator())
    }

    @Test
    fun resultConvertsToRangeLocatorForHighlightOrBookmark() {
        val subject = result(
            listOf(SourceRange(doc, 10, 14), SourceRange(doc, 30, 34))
        )
        val locator = subject.toRangeLocator()
        assertNotNull(locator)
        assertEquals(2, locator!!.ranges.size)
        assertEquals(8, locator.totalLengthUtf16)
        assertEquals(ResolutionQuality.EXACT, locator.quality)
    }

    @Test
    fun approximateConfidenceDowngradesRangeLocatorQuality() {
        // 近似定位绝不能对外声称 EXACT——批注/书签会据此决定是否自动贴合。
        val subject = result(
            listOf(SourceRange(doc, 10, 14)),
            confidence = SearchHitProjector.Confidence.APPROXIMATE
        )
        assertEquals(ResolutionQuality.APPROXIMATE, subject.toRangeLocator()!!.quality)
    }

    @Test
    fun resultWithoutRangesYieldsNoRangeLocator() {
        assertNull(result().toRangeLocator())
    }

    @Test
    fun multiRangeResultKeepsFirstStartForJumping() {
        val subject = result(listOf(SourceRange(doc, 50, 54), SourceRange(doc, 90, 94)))
        assertEquals("跳转必须用最靠前的命中", 50, subject.firstStartUtf16)
    }

    @Test
    fun locatorCarriesProvidedContextForLaterRecovery() {
        val subject = result(listOf(SourceRange(doc, 20, 24)))
        val locator = subject.toTextLocator(TextContext(before = "前文", after = "后文"))
        assertEquals("前文", locator!!.context.before)
        assertEquals("后文", locator.context.after)
    }

    @Test
    fun confidenceDefaultsToApproximateWhenNotSpecified() {
        // 默认必须是**保守**的：调用方忘了标注质量时，不能默认为精确。
        val subject = GlobalSearchResult(
            bookId = 1L,
            bookTitle = "b",
            chapterIndex = 0,
            chapterTitle = "c",
            matchedText = "m"
        )
        assertEquals(SearchHitProjector.Confidence.APPROXIMATE, subject.confidence)
        assertFalse(subject.isLocatable)
    }
}
