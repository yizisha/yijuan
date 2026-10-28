package com.flowreader.app.ui.screens.reader.components

import androidx.compose.ui.unit.sp
import com.flowreader.app.util.epub.ChapterListMark
import com.flowreader.app.util.epub.ChapterMarks
import com.flowreader.app.util.epub.ChapterRubyMark
import com.flowreader.app.util.epub.ChapterTableMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * schema v3 呈现标注的**渲染纯函数**层：段内换算、列表前缀的偏移映射、TAB 展开、注音口径。
 *
 * 底线（NEXT-BATCH 规格第 2 节）：
 * - 不得改动主文本偏移与字数口径 —— 注音只加显示层；
 * - 列表前缀必须映射到该项的源起始偏移（否则重演"人工缩进 +1"缺陷）；
 * - TAB 替换必须保持显式映射，否则批注/选择错位。
 */
class ReaderMarksTest {

    @Test fun movingTableHeadersChangesTheCacheKeyEvenWhenTheCountIsUnchanged() {
        val left = ChapterMarks(tables = listOf(ChapterTableMark(0, 10, listOf(0..2))))
        val right = ChapterMarks(tables = listOf(ChapterTableMark(0, 10, listOf(6..8))))
        assertNotEquals(marksKeyOf(left), marksKeyOf(right))
    }

    // ------------------------------------------------------------ paragraphMarksFor：坐标换算

    @Test
    fun marksAreShiftedByBodyShiftIntoParagraphRelativeCoordinates() {
        // 章正文: "标题\n\n•苹果\n香蕉\n"（示意）；body 剥掉 "标题\n\n"（shift=5）。
        // 列表项在章坐标 8，段落在 body 坐标 3 起（段文本 "苹果\n香蕉\n"）。
        val marks = ChapterMarks(
            lists = listOf(ChapterListMark(offsetUtf16 = 8, ordered = false, number = null, level = 1))
        )
        val result = paragraphMarksFor(marks, paraStart = 3, paraLength = 6, bodyShift = 5)

        assertEquals(1, result.listPrefixes.size)
        assertEquals("• ", result.listPrefixes[0])
    }

    @Test
    fun marksOutsideTheParagraphAreDropped() {
        val marks = ChapterMarks(
            lists = listOf(
                ChapterListMark(offsetUtf16 = 100, ordered = true, number = 1, level = 1)
            ),
            ruby = listOf(ChapterRubyMark(200, 202, listOf("かん")))
        )
        assertTrue(paragraphMarksFor(marks, 0, 10, 0).isEmpty)
    }

    @Test
    fun rubyBaseIsClampedToTheParagraphFragment() {
        // 基字 [3, 7) 只有前半落在片段 [0, 5) 里：裁剪到 [3, 5)（分页切断基字的场景）。
        val marks = ChapterMarks(ruby = listOf(ChapterRubyMark(3, 7, listOf("かん"))))
        val result = paragraphMarksFor(marks, paraStart = 0, paraLength = 5, bodyShift = 0)

        assertEquals(1, result.rubyBases.size)
        assertEquals(3, result.rubyBases.single().baseStart)
        assertEquals(5, result.rubyBases.single().baseEndExclusive)
    }

    @Test
    fun headerRangesAreClampedToTheParagraph() {
        val marks = ChapterMarks(
            tables = listOf(
                ChapterTableMark(startUtf16 = 0, endUtf16Exclusive = 10, headerRanges = listOf(0..1, 9..20))
            )
        )
        val result = paragraphMarksFor(marks, paraStart = 0, paraLength = 5, bodyShift = 0)

        assertEquals(listOf(0..1), result.headerRanges)
    }

    // ------------------------------------------------------------ 列表前缀

    @Test
    fun listPrefixLevelIndentUsesIdeographicSpaces() {
        assertEquals("• ", listPrefixFor(ChapterListMark(0, ordered = false, number = null, level = 1)))
        assertEquals("1. ", listPrefixFor(ChapterListMark(0, ordered = true, number = 1, level = 1)))
        assertEquals("　7. ", listPrefixFor(ChapterListMark(0, ordered = true, number = 7, level = 2)))
        assertEquals("　　• ", listPrefixFor(ChapterListMark(0, ordered = false, number = null, level = 3)))
    }

    @Test
    fun listPrefixCharactersMapToTheItemSourceOffsetAndFollowingTextKeepsItsOffsets() {
        // 段 "苹果\n香蕉"（无缩进）；项 2 在相对偏移 3（"香"）。前缀 "2. " 的每个字符
        // 都必须映射到项的源偏移（paragraphStart + 3），后续正文偏移一概不动。
        val marks = ParagraphMarks(listPrefixes = mapOf(3 to "2. "))
        val content = buildParagraphContent(
            paragraph = "苹果\n香蕉",
            paragraphStart = 40,
            annotations = emptyList(),
            indent = false,
            marks = marks
        )

        assertEquals("苹果\n2. 香蕉", content.annotatedString.text)
        // 前 3 个显示字符 = 苹果\n，真实偏移 40..42。
        assertEquals(40, content.rawOffsets[0])
        assertEquals(42, content.rawOffsets[2])
        // 前缀 3 个字符（显示下标 3,4,5）→ 全部映射到项的源偏移 43。
        assertEquals(43, content.rawOffsets[3])
        assertEquals(43, content.rawOffsets[4])
        assertEquals(43, content.rawOffsets[5])
        // 正文从"香"继续：43（无 +1 漂移）。
        assertEquals(43, content.rawOffsets[6])
        assertEquals(44, content.rawOffsets[7])
    }

    @Test
    fun listItemAtParagraphStartSuppressesTheManualIndent() {
        val marks = ParagraphMarks(listPrefixes = mapOf(0 to "• "))
        val content = buildParagraphContent(
            paragraph = "苹果",
            paragraphStart = 0,
            annotations = emptyList(),
            indent = true,
            marks = marks
        )

        assertEquals("• 苹果", content.annotatedString.text)
        assertEquals(0, content.rawOffsets[0])
        assertEquals(0, content.rawOffsets[1])
        assertEquals(0, content.rawOffsets[2])
        assertTrue(marks.startsWithListItem)
    }

    @Test
    fun nonListItemParagraphsKeepTheManualIndent() {
        val marks = ParagraphMarks(listPrefixes = mapOf(3 to "• "))
        val content = buildParagraphContent(
            paragraph = "续行",
            paragraphStart = 0,
            annotations = emptyList(),
            indent = true,
            marks = marks
        )

        assertEquals("　　续行", content.annotatedString.text)
    }

    // ------------------------------------------------------------ 表格 TAB 展开

    @Test
    fun tabsExpandToVisibleSpacingWithExplicitSourceMapping() {
        val paragraph = "甲\t乙"
        val content = buildParagraphContent(paragraph, 0, emptyList(), indent = false)

        assertEquals("甲" + TAB_DISPLAY_TEXT + "乙", content.annotatedString.text)
        assertFalse("显示文本不得残留 TAB", content.annotatedString.text.contains('\t'))
        assertEquals(1 + TAB_DISPLAY_TEXT.length + 1, content.rawOffsets.size)
        // 展开出来的两个字符都映射到 TAB 自己的源偏移 1（显式多对一），后续正文不漂移。
        assertEquals(1, content.rawOffsets[1])
        assertEquals(1, content.rawOffsets[2])
        assertEquals(2, content.rawOffsets[3])
    }

    @Test
    fun headerCellsRenderBoldWithoutChangingText() {
        // "乙" 的源偏移是 2（TAB 占 1）；显示文本 = 甲 + 两个全角空格 + 乙。
        val marks = ParagraphMarks(headerRanges = listOf(2..2))
        val content = buildParagraphContent("甲\t乙", 0, emptyList(), indent = false, marks = marks)

        val boldRanges = content.annotatedString.spanStyles
            .filter { it.item.fontWeight != null }
            .map { it.start..it.end }
        assertTrue("表头单元格必须加粗", boldRanges.contains(3..4))
        assertEquals("甲" + TAB_DISPLAY_TEXT + "乙", content.annotatedString.text)
    }

    // ------------------------------------------------------------ 注音（ruby）

    @Test
    fun rubyAnnotationsNeverEnterTheDisplayTextOrOffsets() {
        // 注音只进 hints：主文本、rawOffsets 与无注音时完全一致（3.2 第 6 条）。
        val paragraph = "漢字単語"
        val plain = buildParagraphContent(paragraph, 0, emptyList(), indent = false)
        val withRuby = buildParagraphContent(
            paragraph, 0, emptyList(), indent = false,
            marks = ParagraphMarks(rubyBases = listOf(ParagraphRubySource(0, 2, listOf("かんじ"))))
        )

        assertEquals(plain.annotatedString.text, withRuby.annotatedString.text)
        assertTrue(plain.rawOffsets.contentEquals(withRuby.rawOffsets))
        assertEquals("漢字単語", withRuby.annotatedString.text)
        // 整条注音（1 段 rt、2 个基字）→ 整体居中，hint 只有 1 个单元。
        assertEquals(1, withRuby.rubyHints.size)
        assertEquals(listOf("かんじ"), withRuby.rubyHints.single().units)
        assertEquals(listOf(0, 1), withRuby.rubyHints.single().baseDisplayIndices)
    }

    @Test
    fun perSegmentRtAlignsOneToOneWithBaseCharacters() {
        // <ruby>漢<rt>かん</rt>字<rt>じ</rt></ruby>：两段 rt 对两个基字 → 逐字对位。
        val content = buildParagraphContent(
            "漢字", 0, emptyList(), indent = false,
            marks = ParagraphMarks(rubyBases = listOf(ParagraphRubySource(0, 2, listOf("かん", "じ"))))
        )

        assertEquals(1, content.rubyHints.size)
        assertEquals(listOf("かん", "じ"), content.rubyHints.single().units)
        assertEquals(listOf(0, 1), content.rubyHints.single().baseDisplayIndices)
    }

    @Test
    fun rubyHintIndicesSkipMarkdownStrippedMarkers() {
        val content = buildParagraphContent(
            "**漢**字", 0, emptyList(), indent = false,
            marks = ParagraphMarks(rubyBases = listOf(ParagraphRubySource(2, 5, listOf("かんじ"))))
        )

        // 显示文本 = "漢字"；基字源 [2,5) 只覆盖显示下标 0（漢）。
        assertEquals(listOf(0), content.rubyHints.single().baseDisplayIndices)
    }

    // ------------------------------------------------------------ 缓存指纹

    @Test
    fun marksKeySeparatesMarkedAndUnmarkedLayouts() {
        val empty = ChapterMarks.EMPTY
        val marked = ChapterMarks(
            lists = listOf(ChapterListMark(5, ordered = true, number = 1, level = 1))
        )
        val renumbered = ChapterMarks(
            lists = listOf(ChapterListMark(5, ordered = true, number = 2, level = 1))
        )

        assertEquals("", marksKeyOf(empty))
        assertNotEquals("", marksKeyOf(marked))
        assertNotEquals(marksKeyOf(marked), marksKeyOf(renumbered))
        assertEquals(marksKeyOf(marked), marksKeyOf(marked.copy()))
    }

    @Test
    fun emptyMarksReturnTheSharedEmptyInstance() {
        assertEquals(ParagraphMarks.EMPTY, paragraphMarksFor(ChapterMarks.EMPTY, 0, 10, 0))
        // 空段落没有前缀需求。
        assertNull(ParagraphMarks.EMPTY.listPrefixes[0])
    }

    // ------------------------------------------------------------ 注音预留行高（0.2.15）

    @Test
    fun rubyLineReserveGrowsLineHeightAndPushesTextToTheBottom() {
        val base = androidx.compose.ui.text.TextStyle(
            fontSize = 25.sp,
            lineHeight = 33.sp
        )
        val reserved = base.withRubyLineReserve(25f)

        assertEquals(33f + rubyLineReserveSp(25f), reserved.lineHeight.value, 0.01f)
        // 多出来的空间必须全部落在基字上方，注音才画得进预留带。
        assertEquals(
            androidx.compose.ui.text.style.LineHeightStyle.Alignment.Bottom,
            reserved.lineHeightStyle?.alignment
        )
        // 预留高度必须装得下注音小字自身的高度，否则又会压回基字里。
        assertTrue(
            rubyLineReserveSp(25f) >= 25f * RUBY_ANNOTATION_SCALE * RUBY_LINE_HEIGHT_FACTOR
        )
    }

    @Test
    fun unspecificLineHeightFallsBackToTheBodyFontSize() {
        val base = androidx.compose.ui.text.TextStyle(fontSize = 20.sp)
        val reserved = base.withRubyLineReserve(20f)
        assertEquals(20f + rubyLineReserveSp(20f), reserved.lineHeight.value, 0.01f)
    }
}
