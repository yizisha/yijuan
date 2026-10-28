package com.flowreader.app.util.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S5 / 主计划 4.6 的**坐标换算**验收：文档偏移 → 章内偏移。
 *
 * 为什么必须单独测：`projectChapters` 把 section 的多个 range 按阅读顺序**拼接**成
 * `chapters.content`，一个 section 可以跨多个 XHTML。把文档偏移直接当章内偏移用，
 * 跨文档的 section 会把图片插到别的段落上（而单文档的中文书恰好看不出来）。
 */
class ChapterImageIndexTest {

    @Test
    fun imagesAreShiftedByThePrecedingRangesLength() {
        val ranges = listOf(
            SectionRange("doc-a", 0, 100),
            SectionRange("doc-b", 10, 60)
        )
        val images = mapOf(
            "doc-a" to listOf(DocumentImage(offsetUtf16 = 30, containerPath = "a.jpg", alt = null)),
            "doc-b" to listOf(DocumentImage(offsetUtf16 = 40, containerPath = "b.jpg", alt = "乙图"))
        )

        val refs = ChapterImageIndex.build(ranges, images) { path -> path?.let { "/abs/$it" } }

        assertEquals(2, refs.size)
        assertEquals(30, refs[0].offsetUtf16)
        assertEquals("/abs/a.jpg", refs[0].absolutePath)
        // 第二个文档的图片：前面 100 个单位 + (40-10)
        assertEquals(130, refs[1].offsetUtf16)
        assertEquals("/abs/b.jpg", refs[1].absolutePath)
        assertEquals("乙图", refs[1].alt)
    }

    @Test
    fun imagesOutsideTheirRangeAreNotPulledIntoTheChapter() {
        val ranges = listOf(SectionRange("doc-a", 0, 50))
        val images = mapOf(
            "doc-a" to listOf(
                DocumentImage(offsetUtf16 = 10, containerPath = "in.jpg", alt = null),
                DocumentImage(offsetUtf16 = 50, containerPath = "past-end.jpg", alt = null),
                DocumentImage(offsetUtf16 = 0, containerPath = "first.jpg", alt = null)
            )
        )

        val refs = ChapterImageIndex.build(ranges, images) { it }

        assertEquals(listOf(0, 10), refs.map { it.offsetUtf16 })
    }

    @Test
    fun unresolvedResourcesKeepTheirSlotWithANullPath() {
        val ranges = listOf(SectionRange("doc-a", 0, 10))
        val images = mapOf("doc-a" to listOf(DocumentImage(5, containerPath = "missing.jpg", alt = "缺失")))

        val refs = ChapterImageIndex.build(ranges, images) { null }

        assertEquals(1, refs.size)
        assertEquals(5, refs[0].offsetUtf16)
        assertNull("解析不出路径时必须保留 null，让 UI 显示占位而不是丢图", refs[0].absolutePath)
        assertEquals("缺失", refs[0].alt)
    }

    @Test
    fun externalReferencesWithoutContainerPathCountAsMissing() {
        val ranges = listOf(SectionRange("doc-a", 0, 10))
        val images = mapOf("doc-a" to listOf(DocumentImage(2, containerPath = null, alt = null)))

        val refs = ChapterImageIndex.build(ranges, images) { it }

        assertEquals(1, refs.size)
        assertNull(refs[0].absolutePath)
    }

    @Test
    fun resultIsSortedByOffsetEvenForUnorderedInput() {
        val ranges = listOf(SectionRange("doc-a", 0, 100))
        val images = mapOf(
            "doc-a" to listOf(
                DocumentImage(80, "c.jpg", null),
                DocumentImage(20, "a.jpg", null),
                DocumentImage(50, "b.jpg", null)
            )
        )

        val refs = ChapterImageIndex.build(ranges, images) { it }

        assertEquals(listOf(20, 50, 80), refs.map { it.offsetUtf16 })
    }
}
