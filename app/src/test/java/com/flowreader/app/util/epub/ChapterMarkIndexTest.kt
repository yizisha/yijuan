package com.flowreader.app.util.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * schema v3：呈现标注从**文档坐标**到**章内坐标**的换算 + 结构化编号推导。
 *
 * 两条底线（NEXT-BATCH 规格第 2、6 节）：
 * - 跨文档 section 必须按前序 range 长度平移（与 [ChapterImageIndex] 同一算法）；
 * - 列表编号由结构推导：跨页/跨块**不重置**；section 从文档中间开始时，
 *   编号必须按**全文档**计数后再筛选，半截列表不得重新编号。
 */
class ChapterMarkIndexTest {

    private fun listEntry(
        documentKey: String,
        offset: Int,
        ordered: Boolean = true,
        listStart: Int? = null,
        listBlockId: String = "list-1",
        level: Int = 1,
        itemValue: Int? = null
    ) = PublicationStore.DocumentListEntry(
        listBlockId = listBlockId,
        blockId = "$listBlockId-item-$offset",
        domPath = "body/ul[1]",
        offsetUtf16 = offset,
        ordered = ordered,
        listStart = listStart,
        level = level,
        itemValue = itemValue,
        role = "MAIN"
    )

    // ------------------------------------------------------------ 跨文档换算

    @Test
    fun crossDocumentSectionShiftsMarksByPrecedingRangeLength() {
        val ranges = listOf(
            SectionRange("doc-a", 0, 100),
            SectionRange("doc-b", 40, 140)
        )
        val marksByDocument = mapOf(
            "doc-a" to PublicationStore.DocumentMarks(
                lists = listOf(listEntry("doc-a", 10), listEntry("doc-a", 50))
            ),
            "doc-b" to PublicationStore.DocumentMarks(
                ruby = listOf(rubyEntry(42, 44, listOf("かん")))
            )
        )

        val marks = ChapterMarkIndex.build(ranges, marksByDocument)

        // doc-b 的章内基准 = 100；ruby 42 → 100 + (42 - 40) = 102。
        assertEquals(1, marks.ruby.size)
        assertEquals(102, marks.ruby.single().baseStartUtf16)
        assertEquals(104, marks.ruby.single().baseEndUtf16Exclusive)
        // doc-a 的标注落在 [0, 100) → 原样（10、50）。
        assertEquals(listOf(10, 50), marks.lists.map { it.offsetUtf16 })
    }

    @Test
    fun marksOutsideTheirSectionRangeAreDropped() {
        val ranges = listOf(SectionRange("doc-a", 50, 100))
        val marksByDocument = mapOf(
            "doc-a" to PublicationStore.DocumentMarks(
                lists = listOf(listEntry("doc-a", 10), listEntry("doc-a", 60)),
                tables = listOf(
                    PublicationStore.DocumentTableEntry(
                        blockId = "t1",
                        domPath = "body/table[1]",
                        startUtf16 = 200,
                        endUtf16Exclusive = 300,
                        rows = emptyList(),
                        role = "MAIN"
                    )
                )
            )
        )

        val marks = ChapterMarkIndex.build(ranges, marksByDocument)

        // 换算到章内坐标：0 + (60 - 50) = 10。
        assertEquals(listOf(10), marks.lists.map { it.offsetUtf16 })
        assertTrue(marks.tables.isEmpty())
    }

    @Test
    fun tableHeaderRangesShiftAndStayInclusive() {
        val ranges = listOf(SectionRange("doc-a", 0, 1000))
        val marksByDocument = mapOf(
            "doc-a" to PublicationStore.DocumentMarks(
                tables = listOf(
                    PublicationStore.DocumentTableEntry(
                        blockId = "t1",
                        domPath = "body/table[1]",
                        startUtf16 = 10,
                        endUtf16Exclusive = 100,
                        rows = listOf(
                            listOf(
                                PublicationStore.DocumentTableCellEntry("c1", 11, 15, true, 1, 1),
                                PublicationStore.DocumentTableCellEntry("c2", 16, 20, false, 1, 1)
                            )
                        ),
                        role = "MAIN"
                    )
                )
            )
        )

        val marks = ChapterMarkIndex.build(ranges, marksByDocument)

        assertEquals(listOf(11..14), marks.tables.single().headerRanges)
    }

    // ------------------------------------------------------------ 列表编号

    @Test
    fun orderedListNumbersFromOneAndDoNotResetAcrossItems() {
        val entries = (0 until 30).map { listEntry("doc-a", it * 10) }
        val ranges = listOf(SectionRange("doc-a", 0, 1000))

        val marks = ChapterMarkIndex.build(ranges, mapOf("doc-a" to PublicationStore.DocumentMarks(lists = entries)))

        assertEquals((1..30).toList(), marks.lists.map { it.number })
        // 编号来自结构而不是页码：即使分页把 30 项切到 5 页，第 5 页的编号仍是 25..30。
        val lastPageItems = marks.lists.takeLast(6)
        assertEquals((25..30).toList(), lastPageItems.map { it.number })
    }

    @Test
    fun unorderedListsHaveNoNumber() {
        val marks = ChapterMarkIndex.build(
            listOf(SectionRange("doc-a", 0, 100)),
            mapOf("doc-a" to PublicationStore.DocumentMarks(lists = listOf(listEntry("doc-a", 0, ordered = false))))
        )

        assertNull(marks.lists.single().number)
        assertTrue(marks.lists.single().ordered.not())
    }

    @Test
    fun listStartOffsetsTheFirstNumber() {
        val marks = ChapterMarkIndex.build(
            listOf(SectionRange("doc-a", 0, 100)),
            mapOf(
                "doc-a" to PublicationStore.DocumentMarks(
                    lists = listOf(
                        listEntry("doc-a", 0, listStart = 5),
                        listEntry("doc-a", 10, listStart = 5)
                    )
                )
            )
        )

        assertEquals(listOf(5, 6), marks.lists.map { it.number })
    }

    @Test
    fun liValueOverridesAndSubsequentItemsContinueFromIt() {
        val marks = ChapterMarkIndex.build(
            listOf(SectionRange("doc-a", 0, 100)),
            mapOf(
                "doc-a" to PublicationStore.DocumentMarks(
                    lists = listOf(
                        listEntry("doc-a", 0),
                        listEntry("doc-a", 10, itemValue = 9),
                        listEntry("doc-a", 20)
                    )
                )
            )
        )

        assertEquals(listOf(1, 9, 10), marks.lists.map { it.number })
    }

    @Test
    fun separateListsNumberIndependently() {
        val marks = ChapterMarkIndex.build(
            listOf(SectionRange("doc-a", 0, 100)),
            mapOf(
                "doc-a" to PublicationStore.DocumentMarks(
                    lists = listOf(
                        listEntry("doc-a", 0, listBlockId = "list-1"),
                        listEntry("doc-a", 10, listBlockId = "list-1"),
                        listEntry("doc-a", 20, listBlockId = "list-2"),
                        listEntry("doc-a", 30, listBlockId = "list-2")
                    )
                )
            )
        )

        assertEquals(listOf(1, 2, 1, 2), marks.lists.map { it.number })
    }

    @Test
    fun aListSplitAcrossTwoSectionsDoesNotRenumberItsSecondHalf() {
        // 一个 section 从文档中间开始：列表前半截属于上一章。编号必须按全文档计数后筛选，
        // 后半截的第 1 个可见项必须仍是 4，而不是重新从 1 数。
        val entries = listOf(
            listEntry("doc-a", 0),
            listEntry("doc-a", 10),
            listEntry("doc-a", 20),
            listEntry("doc-a", 30),
            listEntry("doc-a", 40)
        )
        val marks = ChapterMarkIndex.build(
            listOf(SectionRange("doc-a", 25, 100)),
            mapOf("doc-a" to PublicationStore.DocumentMarks(lists = entries))
        )

        // 章内坐标相对本 section 起点（25）：30→5、40→15；编号保持全文档口径（4、5）。
        assertEquals(listOf(5, 15), marks.lists.map { it.offsetUtf16 })
        assertEquals(listOf(4, 5), marks.lists.map { it.number })
    }

    @Test
    fun marksAreSortedByOffset() {
        val marks = ChapterMarkIndex.build(
            listOf(SectionRange("doc-a", 0, 100)),
            mapOf(
                "doc-a" to PublicationStore.DocumentMarks(
                    ruby = listOf(rubyEntry(50, 52, listOf("じ")), rubyEntry(10, 12, listOf("かん")))
                )
            )
        )

        assertEquals(listOf(10, 50), marks.ruby.map { it.baseStartUtf16 })
    }
    private fun rubyEntry(start: Int, end: Int, annotations: List<String>) =
        PublicationStore.DocumentRubyEntry(
            blockId = "r-$start",
            domPath = "body/ruby[1]",
            baseStartUtf16 = start,
            baseEndUtf16Exclusive = end,
            annotations = annotations,
            role = "MAIN"
        )

}
