package com.flowreader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索命中投影测试（主计划第 9 节）。
 *
 * 核心命题：**绝不能把 FTS 分词/片段位置当成 UTF-16 源坐标。**
 * 位置必须回到正文里重新定位、并用上下文消歧。
 */
class SearchHitProjectorTest {

    private val doc = "chapter:0"

    // ------------------------------------------------------------ 标记解析

    @Test
    fun extractsMarkedTermsFromSnippet() {
        val snippet = "...前面的文字<<关键词>>后面的文字..."
        assertEquals(listOf("关键词"), SearchHitProjector.extractMarkedTerms(snippet))
    }

    @Test
    fun extractsMultipleMarkedTerms() {
        val snippet = "...<<甲>>中间<<乙>>..."
        assertEquals(listOf("甲", "乙"), SearchHitProjector.extractMarkedTerms(snippet))
    }

    @Test
    fun noMarksYieldsEmptyList() {
        assertEquals(emptyList<String>(), SearchHitProjector.extractMarkedTerms("没有标记的片段"))
        assertEquals(emptyList<String>(), SearchHitProjector.extractMarkedTerms(""))
    }

    @Test
    fun unterminatedMarkIsIgnoredInsteadOfCrashing() {
        assertEquals(emptyList<String>(), SearchHitProjector.extractMarkedTerms("...<<没有闭合"))
    }

    @Test
    fun stripMarkersRemovesHighlightAndEllipsis() {
        assertEquals("前面关键词后面", SearchHitProjector.stripMarkers("...前面<<关键词>>后面..."))
    }

    @Test
    fun contextBeforeAndAfterExcludeEllipsisRegion() {
        // 逗号紧邻命中处，属于「紧邻上下文」；省略号之前的内容才是该被丢弃的。
        val snippet = "...远处的内容...,命中处前文<<命中>>命中处后文,...更远的内容"
        assertEquals(",命中处前文", SearchHitProjector.contextBefore(snippet))
        assertEquals("命中处后文,", SearchHitProjector.contextAfter(snippet))
    }

    // ------------------------------------------------------------ 精确定位

    @Test
    fun uniqueTermProjectsToExactOffset() {
        val content = "山风从谷底卷上来，带着潮湿的凉意。"
        val snippet = "...带着<<潮湿>>的凉意..."
        val hit = SearchHitProjector.project(content, doc, "潮湿", snippet)!!
        assertEquals(1, hit.ranges.size)
        assertEquals(content.indexOf("潮湿"), hit.ranges[0].startUtf16)
        assertEquals("潮湿", hit.matchedText)
        assertEquals(SearchHitProjector.Confidence.EXACT, hit.confidence)
        // 位置必须真的能切出命中文
        assertEquals("潮湿", content.substring(hit.ranges[0].startUtf16, hit.ranges[0].endUtf16Exclusive))
    }

    @Test
    fun repeatedTermIsDisambiguatedBySnippetContext() {
        // "夜行" 出现两次；片段上下文指向**第二次**。
        val content = "第一段说夜行很难，第二段又说夜行其实很好走。"
        val secondIndex = content.lastIndexOf("夜行")
        val snippet = "...第二段又说<<夜行>>其实很好走..."
        val hit = SearchHitProjector.project(content, doc, "夜行", snippet)!!
        assertEquals(SearchHitProjector.Confidence.EXACT, hit.confidence)
        assertEquals("上下文必须把定位消歧到第二次出现", secondIndex, hit.ranges[0].startUtf16)
    }

    @Test
    fun ambiguousTermWithoutContextFallsBackWithApproximateConfidenceAndIsLabelled() {
        // 同一个词出现三次，且片段上下文不足以区分：必须**如实标注为近似**，
        // 而不是假装精确。
        val content = "甲甲甲"
        val snippet = "<<甲>>"
        val hit = SearchHitProjector.project(content, doc, "甲", snippet)!!
        assertEquals(SearchHitProjector.Confidence.APPROXIMATE, hit.confidence)
        assertEquals(0, hit.ranges[0].startUtf16)
    }

    // ------------------------------------------------------------ 前缀扩展

    @Test
    fun prefixExpandedMatchUsesMarkedTermNotQuery() {
        // FTS 用 "...*" 做前缀匹配：查询 "潮湿" 可能命中 "潮湿的"。
        // 必须以**标记词**为准，否则找不到位置。
        val content = "带着潮湿的气息走进山谷。"
        val snippet = "...带着<<潮湿的气息>>走进山谷..."
        val hit = SearchHitProjector.project(content, doc, "潮湿", snippet)!!
        assertEquals("潮湿的气息", hit.matchedText)
        assertEquals(content.indexOf("潮湿的气息"), hit.ranges[0].startUtf16)
    }

    @Test
    fun fallsBackToQueryWhenSnippetHasNoMarks() {
        val content = "这段正文包含目标词组。"
        val hit = SearchHitProjector.project(content, doc, "目标词组", null)!!
        assertEquals(content.indexOf("目标词组"), hit.ranges[0].startUtf16)
        assertEquals("目标词组", hit.matchedText)
    }

    // ------------------------------------------------------------ 失败路径

    @Test
    fun termAbsentFromContentReturnsNullInsteadOfFabricatingOffset() {
        // 索引与正文不是同一份（例如重新导入过）：必须判失败，绝不返回 0 偏移。
        val content = "完全无关的正文内容。"
        val snippet = "...<<找不到的词>>..."
        assertNull(SearchHitProjector.project(content, doc, "找不到的词", snippet))
    }

    @Test
    fun emptyContentOrDocumentKeyReturnsNull() {
        assertNull(SearchHitProjector.project("", doc, "词", "<<词>>"))
        assertNull(SearchHitProjector.project("有词", "", "词", "<<词>>"))
    }

    @Test
    fun blankQueryAndSnippetReturnNull() {
        assertNull(SearchHitProjector.project("正文", doc, "   ", null))
    }

    // ------------------------------------------------------------ 多词与合并

    @Test
    fun multiTermQueryProjectsEveryTermInOrder() {
        val content = "春天的花开，秋天的月明。"
        val snippet = "...春天的<<花>>开，秋天的<<月>>明..."
        val hit = SearchHitProjector.project(content, doc, "花 月", snippet)!!
        assertEquals(2, hit.ranges.size)
        assertEquals(content.indexOf("花"), hit.ranges[0].startUtf16)
        assertEquals(content.indexOf("月"), hit.ranges[1].startUtf16)
        assertTrue("范围必须按起点升序", hit.ranges[0].startUtf16 < hit.ranges[1].startUtf16)
    }

    @Test
    fun adjacentTermsMergeIntoOneRangeWithoutDuplicatingText() {
        val content = "甲乙丙丁戊己"
        val snippet = "<<甲乙>><<丙丁>>"
        val hit = SearchHitProjector.project(content, doc, "", snippet)!!
        assertEquals("相接命中必须合并为一段", 1, hit.ranges.size)
        assertEquals(0, hit.ranges[0].startUtf16)
        assertEquals(4, hit.ranges[0].endUtf16Exclusive)
        assertEquals("甲乙丙丁", hit.matchedText)
    }

    @Test
    fun caseInsensitiveMatchStillProducesExactUtf16Boundaries() {
        val content = "The Quick brown fox."
        val snippet = "...The <<quick>> brown..."
        val hit = SearchHitProjector.project(content, doc, "quick", snippet)!!
        assertEquals("位置必须是真实 UTF-16 边界", content.indexOf("Quick"), hit.ranges[0].startUtf16)
        assertEquals("Quick", content.substring(hit.ranges[0].startUtf16, hit.ranges[0].endUtf16Exclusive))
    }

    @Test
    fun chineseAndEmojiBoundariesStayWellFormed() {
        // emoji 占 2 个 UTF-16 单元；命中文必须落在合法边界上（INV04）。
        val content = "开头\uD83D\uDE00中间目标词结尾"
        val snippet = "...中间<<目标词>>结尾..."
        val hit = SearchHitProjector.project(content, doc, "目标词", snippet)!!
        val range = hit.ranges[0]
        assertEquals("目标词", content.substring(range.startUtf16, range.endUtf16Exclusive))
        // 起点不得落在代理对中间
        assertTrue("起点不能是低代理项", !content[range.startUtf16].isLowSurrogate())
    }

    // ------------------------------------------------------------ 与 RangeLocator 协同

    @Test
    fun projectedHitConvertsToRangeLocatorForAnnotation() {
        val content = "这段文字里有目标词，后面还有很多内容。"
        val snippet = "...里有<<目标词>>，后面..."
        val hit = SearchHitProjector.project(content, doc, "目标词", snippet)!!
        val locator = RangeLocator(
            publicationId = "pub-1",
            revision = "rev-1",
            ranges = hit.ranges,
            quality = ResolutionQuality.EXACT
        )
        assertEquals(1, locator.ranges.size)
        assertEquals(3, locator.totalLengthUtf16)
        assertEquals("目标词", content.substring(locator.ranges[0].startUtf16, locator.ranges[0].endUtf16Exclusive))
    }

    @Test
    fun searchResultTextLocatorCanBeResolvedExactly() {
        // 搜索结果最终要能变成一个可跨重排恢复的 TextLocator。
        val content = "前言部分。命中的内容在这里。后记部分。"
        val snippet = "...命中的<<内容>>在这里..."
        val hit = SearchHitProjector.project(content, doc, "内容", snippet)!!
        val locator = TextLocator(
            publicationId = "pub-1",
            revision = "rev-1",
            documentKey = doc,
            offsetUtf16 = hit.firstStartUtf16,
            context = TextContext(
                before = content.substring(0, hit.firstStartUtf16),
                after = content.substring(hit.firstStartUtf16 + 2)
            )
        )
        assertEquals(content.indexOf("内容"), locator.offsetUtf16)
        assertNotNull(locator.context.before)
    }
}
