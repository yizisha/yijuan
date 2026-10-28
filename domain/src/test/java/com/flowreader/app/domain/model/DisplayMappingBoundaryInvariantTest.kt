package com.flowreader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DisplayMappingBuilder] 边界不变量回归（主计划 3.3 / INV05）。
 *
 * 这些用例来自一次真实缺陷：`appendSynthetic` 在 `boundaries` 为空时先补一个起点边界、
 * 再逐字符各补一个，产出 `length + 2` 个边界；且它留下的收尾边界是 synthetic，
 * `appendSource` 沿用它作为本段起点，导致**整段真实文字源偏移 +1**。
 *
 * 人工缩进（`　　 `）恰好排在每个正文段落最前，所以这个缺陷会让整本书的
 * 源坐标系统性偏移 —— 正是 INV05「显示转换不能修改规范源文本定位」明令禁止的行为。
 */
class DisplayMappingBoundaryInvariantTest {

    private val doc = "text/main"

    private fun assertInvariant(mapping: DisplayMapping) {
        assertEquals(
            "boundaries.size 必须严格等于 displayText.length + 1",
            mapping.displayText.length + 1,
            mapping.boundaries.size
        )
    }

    @Test
    fun syntheticPrefixDoesNotShiftSourceOffsets() {
        val builder = DisplayMappingBuilder(doc)
        builder.appendSynthetic("　　", blockId = "p0")
        builder.appendSource("春天来了", sourceStart = 10, blockId = "p0")
        val mapping = builder.build()

        assertInvariant(mapping)
        assertEquals("　　春天来了", mapping.displayText)

        // 关键断言：第一个真实字符"春"必须映射到源 10，而不是 11。
        val ranges = mapping.displayRangeToSourceRanges(0, mapping.displayText.length)
        assertEquals(1, ranges.size)
        assertEquals(10, ranges[0].startUtf16)
        assertEquals(14, ranges[0].endUtf16Exclusive)

        // 仅 synthetic 区间必须不产生任何源范围。
        assertTrue(mapping.displayRangeToSourceRanges(0, 2).isEmpty())
    }

    @Test
    fun multipleInterleavedAppendSegmentsKeepOffsetsExact() {
        val builder = DisplayMappingBuilder(doc)
        builder.appendSource("甲乙", sourceStart = 0)
        builder.appendSynthetic("·")
        builder.appendSource("丙丁", sourceStart = 2)
        builder.appendSynthetic(" ")
        builder.appendSource("戊", sourceStart = 4)
        val mapping = builder.build()

        assertInvariant(mapping)
        assertEquals("甲乙·丙丁 戊", mapping.displayText)

        // 每个真实字符单独求源偏移，逐字核对，任何 +1 漂移都会被抓到。
        assertEquals(0, mapping.displayToSource(0))
        assertEquals(1, mapping.displayToSource(1))
        assertEquals(2, mapping.displayToSource(3))
        assertEquals(3, mapping.displayToSource(4))
        assertEquals(4, mapping.displayToSource(6))
    }

    @Test
    fun sourceToDisplayRoundTripsEveryRealCharacter() {
        val builder = DisplayMappingBuilder(doc)
        builder.appendSynthetic("　　")
        builder.appendSource("abcdef", sourceStart = 100)
        val mapping = builder.build()

        for (i in 0 until 6) {
            val source = mapping.displayToSource(i + 2)
            assertEquals(100 + i, source)
            val back = mapping.sourceToDisplay(source)
            assertEquals(i + 2, back.displayOffset)
            assertFalse("真实字符回环不得被判定为调整", back.isAdjusted)
        }
    }

    @Test
    fun syntheticOnlyMappingKeepsInvariantAndReportsNoSource() {
        val builder = DisplayMappingBuilder(doc)
        builder.appendSynthetic("　　")
        val mapping = builder.build()

        assertInvariant(mapping)
        assertEquals("　　", mapping.displayText)
        assertTrue(
            "全 synthetic 映射不得伪造源范围",
            mapping.displayRangeToSourceRanges(0, mapping.displayText.length).isEmpty()
        )
    }

    @Test
    fun emptyBuilderProducesSingleBoundary() {
        val mapping = DisplayMappingBuilder(doc).build()
        assertInvariant(mapping)
        assertEquals("", mapping.displayText)
        assertEquals(1, mapping.boundaries.size)
    }

    @Test
    fun emptyAppendsAreNoOpsAndKeepInvariant() {
        val builder = DisplayMappingBuilder(doc)
        builder.appendSource("", sourceStart = 0)
        builder.appendSynthetic("")
        builder.appendSource("正文", sourceStart = 5)
        val mapping = builder.build()

        assertInvariant(mapping)
        assertEquals("正文", mapping.displayText)
        assertEquals(5, mapping.displayToSource(0))
        assertEquals(6, mapping.displayToSource(1))
    }

    @Test
    fun identityMappingStillSatisfiesInvariant() {
        val text = "这是一段用于验证 identity 映射的样本。"
        val mapping = DisplayMapping.identity(doc, text)
        assertInvariant(mapping)
        for (i in text.indices) {
            assertEquals(i, mapping.displayToSource(i))
            assertEquals(i, mapping.sourceToDisplay(i).displayOffset)
        }
        assertEquals(text.length, mapping.displayToSource(text.length))
    }

    @Test
    fun hiddenRangeDoesNotInsertPhantomBoundaries() {
        val builder = DisplayMappingBuilder(doc)
        builder.appendHidden(SourceRange(doc, 0, 5))
        builder.appendSource("可见", sourceStart = 5)
        val mapping = builder.build()

        assertInvariant(mapping)
        assertEquals("可见", mapping.displayText)
        assertEquals(5, mapping.displayToSource(0))
    }

    @Test
    fun hiddenRangeDocumentKeyMismatchIsRejected() {
        val builder = DisplayMappingBuilder(doc)
        val failed = runCatching { builder.appendHidden(SourceRange("other/doc", 0, 5)) }.isFailure
        assertTrue("隐藏区间必须属于同一文档", failed)
    }

    @Test
    fun displayRangeSplitsIntoMultipleRangesAcrossSyntheticGap() {
        val builder = DisplayMappingBuilder(doc)
        builder.appendSource("甲乙", sourceStart = 0)
        builder.appendSynthetic("　") // 中间人工字符
        builder.appendSource("丙", sourceStart = 2)
        val mapping = builder.build()

        assertInvariant(mapping)
        val ranges = mapping.displayRangeToSourceRanges(0, mapping.displayText.length)
        assertEquals("synthetic 断点必须切分成两个不相连的源范围", 2, ranges.size)
        assertEquals(0, ranges[0].startUtf16)
        assertEquals(2, ranges[0].endUtf16Exclusive)
        assertEquals(2, ranges[1].startUtf16)
        assertEquals(3, ranges[1].endUtf16Exclusive)
    }

    @Test
    fun sourceToDisplayClampsBeyondEndInsteadOfCrashing() {
        val mapping = DisplayMappingBuilder(doc)
            .appendSource("abc", sourceStart = 0)
            .build()
        val beyond = mapping.sourceToDisplay(999)
        assertEquals(3, beyond.displayOffset)
        assertTrue(beyond.isAdjusted)
        assertNull(null)
    }
}
