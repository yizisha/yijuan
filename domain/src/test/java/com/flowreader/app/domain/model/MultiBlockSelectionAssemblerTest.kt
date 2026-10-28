package com.flowreader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨块选择装配测试（主计划第 9 节 / 3.3 / INV03）。
 *
 * 核心命题：**同一段文字绝不重复输出，未选中的文字绝不进入范围。**
 * 这两条是「跨块批注」真正可用的判据，也是旧单区间模型做不到的地方。
 */
class MultiBlockSelectionAssemblerTest {

    private val doc = "chapter:0"

    /**
     * 构造一个块的映射：[indent] 个 synthetic 字符开头，其后是有源文本（源起点 [sourceStart]）。
     */
    private fun block(indent: Int, text: String, sourceStart: Int): DisplayMapping {
        val builder = DisplayMappingBuilder(doc)
        if (indent > 0) builder.appendSynthetic("　".repeat(indent), blockId = "b")
        builder.appendSource(text, sourceStart = sourceStart, blockId = "b")
        return builder.build()
    }

    private fun provider(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { key -> map[key] }
    }

    // ------------------------------------------------------------ 基本装配

    @Test
    fun singleBlockSelectionProducesOneRangeAndItsText() {
        val source = "春天来了，燕子归巢。"
        val mapping = block(0, source, sourceStart = 0)
        val assembled = MultiBlockSelectionAssembler.assemble(
            listOf(MultiBlockSelectionAssembler.SelectedSegment(mapping, 0, 5)),
            provider(doc to source)
        )
        assertEquals(1, assembled!!.ranges.size)
        assertEquals(SourceRange(doc, 0, 5), assembled.ranges[0])
        assertEquals("春天来了，", assembled.text)
        assertEquals(ResolutionQuality.EXACT, assembled.quality)
    }

    @Test
    fun twoAdjacentParagraphsMergeIntoOneContinuousRange() {
        // 段 1 覆盖源 [0,5)，段 2 覆盖源 [5,10)：这是**连续**文字，必须合并为一条，
        // 否则导出会把同一段内容输出两遍、统计也会翻倍。
        val full = "甲乙丙丁戊己庚辛壬癸"
        val first = block(0, full.substring(0, 5), sourceStart = 0)
        val second = block(0, full.substring(5, 10), sourceStart = 5)

        val assembled = MultiBlockSelectionAssembler.assemble(
            listOf(
                MultiBlockSelectionAssembler.SelectedSegment(first, 0, 5),
                MultiBlockSelectionAssembler.SelectedSegment(second, 0, 5)
            ),
            provider(doc to full)
        )!!

        assertEquals("相邻段落必须合并为单条连续范围", 1, assembled.ranges.size)
        assertEquals(SourceRange(doc, 0, 10), assembled.ranges[0])
        assertEquals("不得重复输出任何文字", full, assembled.text)
    }

    @Test
    fun selectionSpanningGapDoesNotIncludeUnselectedText() {
        // 段 1 选 [0,3)，段 2 选 [7,10)：中间 [3,7) 从未被选中，必须保持**两条**范围，
        // 且 text 只能由这两段拼成，不能把中间内容也带进来。
        val full = "甲乙丙丁戊己庚辛壬癸"
        val first = block(0, full.substring(0, 3), sourceStart = 0)
        val second = block(0, full.substring(7, 10), sourceStart = 7)

        val assembled = MultiBlockSelectionAssembler.assemble(
            listOf(
                MultiBlockSelectionAssembler.SelectedSegment(first, 0, 3),
                MultiBlockSelectionAssembler.SelectedSegment(second, 0, 3)
            ),
            provider(doc to full)
        )!!

        assertEquals(2, assembled.ranges.size)
        assertEquals(SourceRange(doc, 0, 3), assembled.ranges[0])
        assertEquals(SourceRange(doc, 7, 10), assembled.ranges[1])
        // 文本按「范围自身内容直接拼接」生成，以保证 text.length == 范围长度之和；
        // 中间未选中的丁戊己庚不在任何范围里，因此不出现。
        assertEquals("甲乙丙辛壬癸", assembled.text)
        assertEquals(assembled.ranges.sumOf { it.lengthUtf16 }, assembled.text.length)
        assertFalse("未选中的丁戊己庚不得出现", assembled.text.contains("丁"))
        assertEquals("首范围起点必须是选择起点", 0, assembled.firstRangeStartUtf16)
    }

    // ------------------------------------------------------------ synthetic 排除

    @Test
    fun syntheticIndentNeverEntersSourceRanges() {
        // 段首有 2 个 synthetic 缩进；选整段显示文本。
        val source = "风起于青萍之末"
        val mapping = block(2, source, sourceStart = 100)
        val displayLength = mapping.displayText.length

        val assembled = MultiBlockSelectionAssembler.assemble(
            listOf(MultiBlockSelectionAssembler.SelectedSegment(mapping, 0, displayLength)),
            provider(doc to "x".repeat(100) + source)
        )!!
        // 源起点必须被修正为 100，而不是 101（这正是 S5 修复的 +1 漂移）。
        assertEquals(SourceRange(doc, 100, 100 + source.length), assembled.ranges[0])
        assertEquals(source, assembled.text)
    }

    @Test
    fun selectionOfOnlySyntheticCharactersProducesNothing() {
        val mapping = block(2, "正文", sourceStart = 0)
        // 只选前 2 个 synthetic 缩进字符：没有任何真实源内容 → 必须返回 null，
        // 不得伪造一个 0 偏移范围。
        assertNull(
            MultiBlockSelectionAssembler.assemble(
                listOf(MultiBlockSelectionAssembler.SelectedSegment(mapping, 0, 2)),
                provider(doc to "正文")
            )
        )
    }

    @Test
    fun syntheticCharacterBetweenContiguousSourceRunsMergesBackToSingleRange() {
        // 显示是「甲乙·丙丁」：中间夹了一个 synthetic 圆点。
        // 但源文本里「甲乙丙丁」是**连续**的（[0,2) 与 [2,4) 相接）。
        // DisplayMapping 会在 synthetic 处保守地切分，装配层必须把相接的源区间合回一条，
        // 否则下游会把这句连续的话当成两段分别处理。
        val source = "甲乙丙丁"
        val builder = DisplayMappingBuilder(doc)
        builder.appendSource("甲乙", sourceStart = 0)
        builder.appendSynthetic("·")
        builder.appendSource("丙丁", sourceStart = 2)
        val mapping = builder.build()
        assertEquals("甲乙·丙丁", mapping.displayText)

        val assembled = MultiBlockSelectionAssembler.assemble(
            listOf(
                MultiBlockSelectionAssembler.SelectedSegment(mapping, 0, mapping.displayText.length)
            ),
            provider(doc to source)
        )!!

        assertEquals("源区间相接必须合并为一条", 1, assembled.ranges.size)
        assertEquals(SourceRange(doc, 0, 4), assembled.ranges[0])
        assertEquals("甲乙丙丁", assembled.text)
        assertFalse("人工字符不得出现在文本里", assembled.text.contains("·"))
    }

    @Test
    fun syntheticCharacterBetweenDisjointSourceRunsKeepsRangesSeparate() {
        // 源里 [0,2) 与 [10,12) 之间**真的有**一段未被选中的内容（[2,10)）。
        // 此时必须保持两条范围，绝不能因为显示上相邻就连中间 8 个字符一起吞掉。
        // 源长 = 2 + 6 + 2 = 10，因此第二段起点是 8（不是 10，否则越界）。
        val source = "甲乙" + "未选中的一段" + "丙丁"
        val builder = DisplayMappingBuilder(doc)
        builder.appendSource("甲乙", sourceStart = 0)
        builder.appendSynthetic("·")
        builder.appendSource("丙丁", sourceStart = 8)
        val mapping = builder.build()

        val assembled = MultiBlockSelectionAssembler.assemble(
            listOf(
                MultiBlockSelectionAssembler.SelectedSegment(mapping, 0, mapping.displayText.length)
            ),
            provider(doc to source)
        )!!

        assertEquals("源上不相接就必须保持两条", 2, assembled.ranges.size)
        assertEquals(SourceRange(doc, 0, 2), assembled.ranges[0])
        assertEquals(SourceRange(doc, 8, 10), assembled.ranges[1])
        assertEquals("甲乙丙丁", assembled.text)
        assertEquals(assembled.ranges.sumOf { it.lengthUtf16 }, assembled.text.length)
        assertFalse("未选中的内容不得出现", assembled.text.contains("未选"))
    }

    // ------------------------------------------------------------ 跨文档

    @Test
    fun crossDocumentSelectionKeepsRangesGroupedAndNotMerged() {
        val docA = "a.xhtml"
        val docB = "b.xhtml"
        val builderA = DisplayMappingBuilder(docA).appendSource("末尾文字", sourceStart = 10)
        val builderB = DisplayMappingBuilder(docB).appendSource("起始文字", sourceStart = 20)
        val mappingA = builderA.build()
        val mappingB = builderB.build()
        val textA = "x".repeat(10) + "末尾文字"
        val textB = "y".repeat(20) + "起始文字"

        val assembled = MultiBlockSelectionAssembler.assemble(
            listOf(
                MultiBlockSelectionAssembler.SelectedSegment(mappingA, 0, 4),
                MultiBlockSelectionAssembler.SelectedSegment(mappingB, 0, 4)
            ),
            provider(docA to textA, docB to textB)
        )!!

        assertEquals(2, assembled.ranges.size)
        assertEquals(docA, assembled.ranges[0].documentKey)
        assertEquals(docB, assembled.ranges[1].documentKey)
        assertEquals(SourceRange(docA, 10, 14), assembled.ranges[0])
        assertEquals(SourceRange(docB, 20, 24), assembled.ranges[1])
        // 跨文档不做合并；文本由各范围内容直接拼接，长度必须与范围总和一致。
        assertEquals("末尾文字起始文字", assembled.text)
        assertEquals(assembled.ranges.sumOf { it.lengthUtf16 }, assembled.text.length)
    }

    @Test
    fun rangesAreSortedByDocumentThenStart() {
        val builderB = DisplayMappingBuilder("b").appendSource("bb", sourceStart = 5)
        val builderA = DisplayMappingBuilder("a").appendSource("aa", sourceStart = 9)
        val assembled = MultiBlockSelectionAssembler.assemble(
            listOf(
                MultiBlockSelectionAssembler.SelectedSegment(builderB.build(), 0, 2),
                MultiBlockSelectionAssembler.SelectedSegment(builderA.build(), 0, 2)
            ),
            provider("a" to "a".repeat(9) + "aa", "b" to "b".repeat(5) + "bb")
        )!!
        assertEquals(listOf("a", "b"), assembled.ranges.map { it.documentKey })
    }

    // ------------------------------------------------------------ 失败路径

    @Test
    fun emptySegmentListProducesNull() {
        assertNull(MultiBlockSelectionAssembler.assemble(emptyList(), provider(doc to "abc")))
    }

    @Test
    fun unavailableSourceTextProducesNullInsteadOfInconsistentText() {
        // 有范围却取不到文本 → 必须判失败，不能返回「有范围、空文本」的批注。
        val mapping = block(0, "正文", sourceStart = 0)
        assertNull(
            MultiBlockSelectionAssembler.assemble(
                listOf(MultiBlockSelectionAssembler.SelectedSegment(mapping, 0, 2)),
                provider()
            )
        )
    }

    @Test
    fun outOfBoundsRangeProducesNullInsteadOfTruncatedText() {
        val mapping = block(0, "正文", sourceStart = 0)
        // provider 只给出 2 个字符，但范围请求 [0,2) 恰好合法；这里让范围超出文本长度。
        val builder = DisplayMappingBuilder(doc).appendSource("abcdef", sourceStart = 0)
        val wide = builder.build()
        assertNull(
            MultiBlockSelectionAssembler.assemble(
                listOf(MultiBlockSelectionAssembler.SelectedSegment(wide, 0, 6)),
                provider(doc to "abc")
            )
        )
    }

    // ------------------------------------------------------------ 重复输出回归

    @Test
    fun overlappingSelectionsFromTwoBlocksNeverDuplicateText() {
        // 两个块的选择在源上**重叠**（[0,6) 与 [4,10)）。合并后必须是 [0,10) 且文本无重复。
        val full = "零一二三四五六七八九"
        val first = block(0, full.substring(0, 6), sourceStart = 0)
        val second = block(0, full.substring(4, 10), sourceStart = 4)

        val assembled = MultiBlockSelectionAssembler.assemble(
            listOf(
                MultiBlockSelectionAssembler.SelectedSegment(first, 0, 6),
                MultiBlockSelectionAssembler.SelectedSegment(second, 0, 6)
            ),
            provider(doc to full)
        )!!

        assertEquals(1, assembled.ranges.size)
        assertEquals(SourceRange(doc, 0, 10), assembled.ranges[0])
        assertEquals("重叠选择合并后文本不得重复", full, assembled.text)
        // 最强的不重复判据：合并结果长度恰好等于源区间长度，多一个字符都说明有重复。
        assertEquals(10, assembled.text.length)
        assertEquals(assembled.text.length, assembled.ranges.sumOf { it.lengthUtf16 })
    }

    @Test
    fun mergedRangesPreserveQualityAndAreUsableAsAnnotationLocator() {
        val full = "甲乙丙丁戊己"
        val first = block(0, full.substring(0, 3), sourceStart = 0)
        val second = block(0, full.substring(3, 6), sourceStart = 3)
        val assembled = MultiBlockSelectionAssembler.assemble(
            listOf(
                MultiBlockSelectionAssembler.SelectedSegment(first, 0, 3),
                MultiBlockSelectionAssembler.SelectedSegment(second, 0, 3)
            ),
            provider(doc to full)
        )!!

        val locator = RangeLocator(
            publicationId = "pub",
            revision = "rev",
            ranges = assembled.ranges,
            quality = assembled.quality
        )
        assertEquals(1, locator.ranges.size)
        assertEquals(6, locator.totalLengthUtf16)
        assertTrue(locator.isSingleDocument)
    }
}
