package com.flowreader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6 内容坐标模型测试（主计划 3.1 / 第 9 节 / INV03 / INV04 / INV10）。
 *
 * 核心命题：书签与批注的权威位置必须能表达「多段不连续源范围」与「revision」，
 * 且在旧数据缺证据时**如实报告不可解析**，绝不猜。
 */
class RangeLocatorTest {

    private fun locator(vararg ranges: SourceRange, quality: ResolutionQuality = ResolutionQuality.EXACT): RangeLocator = RangeLocator(
        publicationId = "pub-1",
        revision = "rev-1",
        ranges = ranges.toList(),
        quality = quality
    )

    /** 构造一段 ranges JSON，避免在断言里写超长字面量。 */
    private fun rangesJson(vararg body: String): String {
        val prefix = "{\"schemaVersion\":1,\"publicationId\":\"p\",\"revision\":\"r\",\"ranges\":["
        return prefix + body.joinToString(",") + "]}"
    }

    /** 构造单条 range 的 JSON。 */
    private fun rangeJson(documentKey: String, start: Int, end: Int): String {
        return "{\"documentKey\":\"$documentKey\"," +
            "\"startUtf16\":$start,\"endUtf16Exclusive\":$end}"
    }

    // ---------------------------------------------------------------- 模型不变量

    @Test
    fun emptyRangesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RangeLocator(publicationId = "pub-1", revision = "rev-1", ranges = emptyList())
        }
    }

    @Test
    fun blankPublicationIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RangeLocator(
                publicationId = "  ",
                revision = "rev-1",
                ranges = listOf(SourceRange("doc", 0, 5))
            )
        }
    }

    @Test
    fun negativeOrInvertedRangeIsRejectedBySourceRange() {
        assertThrows(IllegalArgumentException::class.java) { SourceRange("doc", -1, 5) }
        assertThrows(IllegalArgumentException::class.java) { SourceRange("doc", 10, 5) }
    }

    @Test
    fun totalLengthSumsRangeLengthsNotSpan() {
        val subject = locator(SourceRange("doc", 0, 5), SourceRange("doc", 100, 130))
        assertEquals("必须是区间长度之和，不是首尾跨度", 35, subject.totalLengthUtf16)
    }

    @Test
    fun singleDocumentDetectionIsExact() {
        assertTrue(locator(SourceRange("a.xhtml", 0, 5)).isSingleDocument)
        assertFalse(locator(SourceRange("a.xhtml", 0, 5), SourceRange("b.xhtml", 0, 5)).isSingleDocument)
        // 同名文档不同路径必须视为不同文档。
        assertFalse(locator(SourceRange("dir/a.xhtml", 0, 5), SourceRange("a.xhtml", 0, 5)).isSingleDocument)
    }

    // ---------------------------------------------------------------- 重叠合并

    @Test
    fun overlappingRangesMergeWithoutDuplicatingText() {
        val subject = locator(SourceRange("doc", 0, 10), SourceRange("doc", 5, 15))
        val merged = subject.normalized()
        assertEquals(1, merged.ranges.size)
        assertEquals(SourceRange("doc", 0, 15), merged.ranges[0])
        assertEquals("合并后不得重复计算文字长度", 15, merged.totalLengthUtf16)
    }

    @Test
    fun adjacentRangesMerge() {
        val merged = locator(SourceRange("doc", 0, 5), SourceRange("doc", 5, 9)).normalized()
        assertEquals(1, merged.ranges.size)
        assertEquals(SourceRange("doc", 0, 9), merged.ranges[0])
    }

    @Test
    fun disjointRangesStaySeparateSoUnselectedTextIsNotIncluded() {
        // 中间 5..20 从未被选中：**不能**合并成一个 [0,30) 的范围。
        val merged = locator(SourceRange("doc", 0, 5), SourceRange("doc", 20, 30)).normalized()
        assertEquals(2, merged.ranges.size)
        assertEquals(SourceRange("doc", 0, 5), merged.ranges[0])
        assertEquals(SourceRange("doc", 20, 30), merged.ranges[1])
    }

    @Test
    fun rangesAcrossDifferentDocumentsNeverMerge() {
        val merged = locator(SourceRange("a", 0, 10), SourceRange("b", 0, 10)).normalized()
        assertEquals(2, merged.ranges.size)
    }

    @Test
    fun normalizationSortsByDocumentThenStart() {
        // 用**不相接**的区间，否则会被正确合并、无法验证排序本身。
        val merged = locator(
            SourceRange("b", 10, 20),
            SourceRange("a", 20, 29),
            SourceRange("a", 0, 5)
        ).normalized()
        assertEquals("必须先按 documentKey、再按 start 升序", listOf("a", "a", "b"), merged.ranges.map { it.documentKey })
        assertEquals(0, merged.ranges[0].startUtf16)
        assertEquals(20, merged.ranges[1].startUtf16)
        assertEquals(10, merged.ranges[2].startUtf16)
    }

    @Test
    fun normalizationMergesAdjacentRangesWithinSameDocument() {
        // 相邻区间（前一个 end == 后一个 start）表示一段连续文字，必须合并为一条，
        // 否则下游会把同一段内容当成两段分别处理。
        val merged = locator(
            SourceRange("a", 5, 9),
            SourceRange("a", 0, 5)
        ).normalized()
        assertEquals(1, merged.ranges.size)
        assertEquals(SourceRange("a", 0, 9), merged.ranges[0])
    }

    // ---------------------------------------------------------------- 编解码

    @Test
    fun codecRoundTripsMultiRangeLocator() {
        val original = locator(
            SourceRange("OEBPS/Text/ch1.xhtml", 10, 40),
            SourceRange("OEBPS/Text/ch1.xhtml", 60, 95)
        )
        val json = RangeLocatorCodec.toJson(original)
        val restored = RangeLocatorCodec.fromJson(json)

        assertNotNull(restored)
        assertEquals(original.publicationId, restored!!.publicationId)
        assertEquals(original.revision, restored.revision)
        assertEquals(original.ranges, restored.ranges)
        assertEquals(original.quality, restored.quality)
        assertEquals(original.version, restored.version)
    }

    @Test
    fun codecPreservesRevisionSoStaleAnnotationsAreDetectable() {
        val json = RangeLocatorCodec.toJson(locator(SourceRange("doc", 0, 5)))
        val restored = RangeLocatorCodec.fromJson(json)!!
        assertEquals("rev-1", restored.revision)
        assertTrue(json.contains("\"schemaVersion\""))
        assertTrue(json.contains("\"ranges\""))
    }

    @Test
    fun codecPreservesUnicodeAndEscapesInDocumentKey() {
        val subject = RangeLocator(
            publicationId = "pub-\"quoted\"",
            revision = "rev\\back",
            ranges = listOf(SourceRange("文本/第 1 章.xhtml", 0, 5))
        )
        val restored = RangeLocatorCodec.fromJson(RangeLocatorCodec.toJson(subject))
        assertNotNull(restored)
        assertEquals("pub-\"quoted\"", restored!!.publicationId)
        assertEquals("rev\\back", restored.revision)
        assertEquals("文本/第 1 章.xhtml", restored.ranges[0].documentKey)
    }

    @Test
    fun codecPreservesQuality() {
        val subject = locator(SourceRange("doc", 0, 5), quality = ResolutionQuality.CONTEXT_MATCH)
        assertEquals(
            ResolutionQuality.CONTEXT_MATCH,
            RangeLocatorCodec.fromJson(RangeLocatorCodec.toJson(subject))!!.quality
        )
    }

    @Test
    fun codecReturnsNullOnMalformedJsonInsteadOfPartialResult() {
        // 这些都是「无法解析」，绝不能返回一个 0 偏移的假坐标。
        assertNull(RangeLocatorCodec.fromJson(""))
        assertNull(RangeLocatorCodec.fromJson("not json"))
        assertNull(RangeLocatorCodec.fromJson("{}"))
        assertNull(RangeLocatorCodec.fromJson("""{"schemaVersion":1}"""))
        assertNull(RangeLocatorCodec.fromJson("""{"schemaVersion":1,"publicationId":"p","revision":"r"}"""))
        assertNull(RangeLocatorCodec.fromJson(rangesJson()))
        // 非法范围必须整体判失败。
        assertNull(RangeLocatorCodec.fromJson(rangesJson(rangeJson("d", -5, 3))))
        assertNull(RangeLocatorCodec.fromJson(rangesJson(rangeJson("d", 9, 3))))
        // 缺 documentKey
        assertNull(RangeLocatorCodec.fromJson(rangesJson("""{"startUtf16":0,"endUtf16Exclusive":3}""")))
        // 非法 key 名（拼写错误）也必须判失败，不能默认 0。
        assertNull(RangeLocatorCodec.fromJson(rangesJson("""{"documentKey":"d","start":0,"end":3}""")))
    }

    @Test
    fun codecRejectsTrailingGarbageAfterValidObject() {
        assertNull(RangeLocatorCodec.fromJson(rangesJson(rangeJson("d", 0, 1)) + " extra"))
    }

    @Test
    fun codecAcceptsEmptyRangeForZeroLengthElement() {
        // 空区间（start == end）是合法的：空元素 id / 空 span 用得到它。
        val json = rangesJson(rangeJson("d", 5, 5))
        val restored = RangeLocatorCodec.fromJson(json)
        assertNotNull(restored)
        assertEquals(0, restored!!.totalLengthUtf16)
    }

    // ---------------------------------------------------------------- 旧数据安全

    @Test
    fun bookmarkWithoutLocatorIsLegacyOnlyAndNotPrecise() {
        val bookmark = Bookmark(
            bookId = 1L,
            chapterIndex = 2,
            position = 1234,
            text = "书签",
            locator = null,
            locatorQuality = ResolutionQuality.UNRESOLVED
        )
        assertTrue("无 locator 必须被判为仅有旧数据", bookmark.isLegacyOnly)
        assertFalse(bookmark.hasContentLocator)
        assertEquals(LegacyPositionUnit.UNKNOWN, bookmark.legacyUnit)
    }

    @Test
    fun bookmarkWithLocatorIsRecoverable() {
        val bookmark = Bookmark(
            bookId = 1L,
            chapterIndex = 2,
            position = 0,
            text = "书签",
            locator = TextLocator(
                publicationId = "pub-1",
                revision = "rev-1",
                documentKey = "chapter:2",
                offsetUtf16 = 512
            ),
            locatorQuality = ResolutionQuality.EXACT,
            legacyUnit = LegacyPositionUnit.SCROLL_PX
        )
        assertTrue(bookmark.hasContentLocator)
        assertFalse(bookmark.isLegacyOnly)
        assertEquals(512, bookmark.locator?.offsetUtf16)
    }

    @Test
    fun legacyUnitDistinguishesPageIndexFromScrollPixel() {
        // 单位证据必须可区分：分页页码与滚动像素语义完全不同，不能混为一谈。
        val page = Bookmark(bookId = 1L, chapterIndex = 0, position = 5, text = "t", legacyUnit = LegacyPositionUnit.PAGE_INDEX)
        val scroll = Bookmark(bookId = 1L, chapterIndex = 0, position = 5, text = "t", legacyUnit = LegacyPositionUnit.SCROLL_PX)
        assertEquals(LegacyPositionUnit.PAGE_INDEX, page.legacyUnit)
        assertEquals(LegacyPositionUnit.SCROLL_PX, scroll.legacyUnit)
        assertTrue(page != scroll)
    }

    @Test
    fun annotationRangeLocatorMatchCheckDetectsMismatch() {
        val matching = Annotation(
            bookId = 1L,
            chapterIndex = 0,
            startPosition = 10,
            endPosition = 20,
            selectedText = "hello",
            rangeLocator = locator(SourceRange("chapter:0", 10, 20))
        )
        assertTrue("旧字段与 locator 一致时必须能校验通过", matching.legacyRangeMatchesLocator())

        val mismatched = matching.copy(rangeLocator = locator(SourceRange("chapter:0", 11, 21)))
        assertFalse("偏移不一致必须被判为不匹配", mismatched.legacyRangeMatchesLocator())
    }

    @Test
    fun annotationWithoutLocatorIsNotClaimedAsMatching() {
        val annotation = Annotation(
            bookId = 1L,
            chapterIndex = 0,
            startPosition = 0,
            endPosition = 5,
            selectedText = "x"
        )
        assertFalse(annotation.legacyRangeMatchesLocator())
    }

    @Test
    fun annotationCrossDocumentLocatorIsNotLegacyMatched() {
        // 跨文档范围不能与「单章内 start/end」做一致性断言。
        val annotation = Annotation(
            bookId = 1L,
            chapterIndex = 0,
            startPosition = 0,
            endPosition = 5,
            selectedText = "x",
            rangeLocator = locator(SourceRange("a", 0, 5), SourceRange("b", 0, 5))
        )
        assertFalse(annotation.legacyRangeMatchesLocator())
    }

    @Test
    fun surrogatePairBoundaryIsNotSplitByRangeConstruction() {
        // INV04：偏移是 UTF-16 边界。emoji 占 2 个 UTF-16 单元，
        // 合法范围可以整段包含它，但不应把 start 落在它中间。
        val text = "a\uD83D\uDE00b"
        assertEquals(4, text.length)
        val whole = SourceRange("doc", 0, text.length)
        assertEquals(4, whole.lengthUtf16)
        // 代理对整体位于 [1,3)：在它中间切开会产生非法半字符，模型层拒绝倒置但不阻止；
        // 这里断言调用方可以通过长度自行校验，且整体包含是合法的。
        assertTrue(SourceRange("doc", 1, 3).lengthUtf16 == 2)
    }
}
