package com.flowreader.app.ui.screens.reader.components

import com.flowreader.app.domain.model.Annotation
import com.flowreader.app.domain.model.AnnotationColor
import com.flowreader.app.domain.model.ChineseConversion
import com.flowreader.app.domain.model.RangeLocator
import com.flowreader.app.domain.model.ResolutionQuality
import com.flowreader.app.domain.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多区间高亮渲染测试（主计划第 9 节）。
 *
 * 这是「只高亮被选中的文字」的最后一环：一条批注可以携带多段不相连的源范围，
 * 渲染时每段必须**各自**高亮。旧实现只取批注首尾区间，会把中间未选中的文字一起点亮。
 */
class MultiRangeHighlightTest {

    private val doc = "chapter:0"

    private fun annotation(vararg ranges: SourceRange, start: Int = 0, end: Int = 0) = Annotation(
        bookId = 1L,
        chapterIndex = 0,
        startPosition = start,
        endPosition = end,
        selectedText = "选中",
        color = AnnotationColor.YELLOW,
        rangeLocator = RangeLocator(
            publicationId = "pub",
            revision = "rev",
            ranges = ranges.toList(),
            quality = ResolutionQuality.EXACT
        )
    )

    /** 用生产构造器渲染，模拟 ReaderContent 的真实调用方式。 */
    private fun render(paragraph: String, paragraphStart: Int, annotations: List<Annotation>) =
        buildParagraphContent(
            paragraph = paragraph,
            paragraphStart = paragraphStart,
            annotations = annotations,
            indent = false,
            conversion = ChineseConversion.NONE,
            documentKey = doc
        )

    // ------------------------------------------------------------ 相交筛选

    @Test
    fun annotationSpanningTwoParagraphsIntersectsBoth() {
        val a = annotation(SourceRange(doc, 10, 50))
        assertTrue("跨段批注必须与第一段相交", a.intersectsParagraph(0, 30))
        assertTrue("跨段批注必须与第二段相交", a.intersectsParagraph(30, 60))
        assertFalse("完全在外的段落不得相交", a.intersectsParagraph(200, 300))
    }

    @Test
    fun touchingBoundaryIsNotAnIntersection() {
        val a = annotation(SourceRange(doc, 10, 30))
        assertFalse("恰好相接不算相交", a.intersectsParagraph(30, 60))
    }

    @Test
    fun legacyAnnotationWithoutLocatorStillIntersectsByRawPositions() {
        val legacy = Annotation(
            bookId = 1L, chapterIndex = 0,
            startPosition = 10, endPosition = 50, selectedText = "旧"
        )
        assertTrue(legacy.intersectsParagraph(0, 30))
        assertFalse(legacy.intersectsParagraph(60, 90))
    }

    // ------------------------------------------------------------ 片段裁剪

    @Test
    fun multiRangeWithinOneParagraphProducesTwoSeparateSpans() {
        // 同段内 [5,10) 与 [20,25)：必须是**两个**片段，中间 [10,20) 不点亮。
        val a = annotation(SourceRange(doc, 5, 10), SourceRange(doc, 20, 25))
        val spans = a.highlightSpansFor(0, 100)
        assertEquals(2, spans.size)
        assertEquals(5, spans[0].startPosition)
        assertEquals(10, spans[0].endPosition)
        assertEquals(20, spans[1].startPosition)
        assertEquals(25, spans[1].endPosition)
    }

    @Test
    fun spanOutsideParagraphIsDropped() {
        val a = annotation(SourceRange(doc, 5, 10), SourceRange(doc, 200, 210))
        val spans = a.highlightSpansFor(0, 100)
        assertEquals("段外区间必须被丢弃，不得把范围拉长", 1, spans.size)
        assertEquals(5, spans[0].startPosition)
        assertEquals(10, spans[0].endPosition)
    }

    @Test
    fun spansAreClippedToParagraphBoundaries() {
        val a = annotation(SourceRange(doc, 10, 50))
        val first = a.highlightSpansFor(0, 30)
        assertEquals(1, first.size)
        assertEquals(10, first[0].startPosition)
        assertEquals(30, first[0].endPosition)
        val second = a.highlightSpansFor(30, 60)
        assertEquals(30, second[0].startPosition)
        assertEquals(50, second[0].endPosition)
    }

    // ------------------------------------------------------------ 实际渲染

    @Test
    fun renderedParagraphHasOneStyleSpanPerDisjointRange() {
        // 段落长 30，批注两段不相连。
        val paragraph = "0123456789abcdefghij0123456789"
        assertEquals(30, paragraph.length)
        val a = annotation(SourceRange(doc, 5, 10), SourceRange(doc, 20, 25))
        val content = render(paragraph, 0, listOf(a))

        // AnnotatedString 的 spanStyles 必须恰好 2 个，且各自覆盖正确区间。
        val styles = content.annotatedString.spanStyles
        assertEquals("两个不相连区间必须产出两个高亮片段", 2, styles.size)
        assertEquals(5, styles[0].start)
        assertEquals(10, styles[0].end)
        assertEquals(20, styles[1].start)
        assertEquals(25, styles[1].end)
        // 文本内容不得因高亮而改变或重复。
        assertEquals(paragraph, content.annotatedString.text)
        assertEquals(paragraph.length, content.rawOffsets.size)
    }

    @Test
    fun overlappingAnnotationsMergeIntoOneContinuousSpanWithoutDuplicatingText() {
        val paragraph = "abcdefghijklmnopqrst"
        val a = annotation(SourceRange(doc, 0, 10))
        val b = annotation(SourceRange(doc, 5, 15))
        val content = render(paragraph, 0, listOf(a, b))

        assertEquals("重叠必须合并为一段，不得重复渲染", 1, content.annotatedString.spanStyles.size)
        assertEquals(0, content.annotatedString.spanStyles[0].start)
        assertEquals(15, content.annotatedString.spanStyles[0].end)
        assertEquals("文本不得重复或错乱", paragraph, content.annotatedString.text)
    }

    @Test
    fun adjacentAnnotationsMergeWithoutStyleSeam() {
        val paragraph = "abcdefghijklmnopqrst"
        val a = annotation(SourceRange(doc, 0, 5))
        val b = annotation(SourceRange(doc, 5, 10))
        val content = render(paragraph, 0, listOf(a, b))
        assertEquals("相接区间应合并，避免样式接缝", 1, content.annotatedString.spanStyles.size)
        assertEquals(0, content.annotatedString.spanStyles[0].start)
        assertEquals(10, content.annotatedString.spanStyles[0].end)
    }

    @Test
    fun markdownAndMultiRangeHighlightCoexistWithoutOffsetDrift() {
        // 段落含 markdown 粗体标记；高亮区间在显示坐标上，必须与 markdown 剥离协同正确。
        val paragraph = "前面**加粗**后面还有很多文字内容"
        val displayLength = paragraph.replace("**", "").length
        val a = annotation(SourceRange(doc, 2, 6))
        val content = render(paragraph, 0, listOf(a))
        // 高亮片段必须落在有效显示范围内。
        content.annotatedString.spanStyles.forEach { span ->
            assertTrue("span 起点越界", span.start in 0..displayLength)
            assertTrue("span 终点越界", span.end in 0..displayLength)
            assertTrue("span 必须非空", span.end > span.start)
        }
        assertEquals("显示文本长度必须等于去标记后的长度", displayLength, content.annotatedString.text.length)
        assertEquals(displayLength, content.rawOffsets.size)
    }

    @Test
    fun paragraphStartOffsetIsRespectedForHighlights() {
        // 段落位于章内偏移 100 处；批注用章内坐标，渲染必须正确换算。
        val paragraph = "0123456789abcdefghij"
        val a = annotation(SourceRange(doc, 102, 106))
        val content = render(paragraph, 100, listOf(a))
        assertEquals(1, content.annotatedString.spanStyles.size)
        assertEquals("块内显示下标应为 2", 2, content.annotatedString.spanStyles[0].start)
        assertEquals("块内显示下标应为 6", 6, content.annotatedString.spanStyles[0].end)
    }

    @Test
    fun emptyIntersectionProducesNoStyles() {
        val paragraph = "abcdefghij"
        val a = annotation(SourceRange(doc, 500, 510))
        val content = render(paragraph, 0, listOf(a))
        assertTrue("无关批注不得产生任何高亮", content.annotatedString.spanStyles.isEmpty())
        assertEquals(paragraph, content.annotatedString.text)
    }

    @Test
    fun highlightDoesNotBreakSelectionRoundTrip() {
        // 高亮不应破坏 rawOffsets：任意区间的选择仍能映射回正确的源偏移。
        val paragraph = "0123456789abcdefghij"
        val a = annotation(SourceRange(doc, 5, 10), SourceRange(doc, 15, 18))
        val content = render(paragraph, 0, listOf(a))
        for (i in paragraph.indices) {
            assertEquals("rawOffsets 必须逐字对应", i, content.rawOffsets[i])
        }
        val span = content.selectionSpan(2, 4, paragraph, 0)
        assertEquals(2, span!!.startPosition)
        assertEquals(4, span.endPosition)
    }
}
