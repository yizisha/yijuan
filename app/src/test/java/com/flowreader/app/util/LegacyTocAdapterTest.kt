package com.flowreader.app.util

import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.model.TocNodeKind
import com.flowreader.app.domain.model.TocOutline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8 旧书目录适配测试（主计划 7.1 末段）。
 *
 * 这些规则此前散落在 Composable 里、没有任何测试；本层把它们变成可判定的纯逻辑。
 */
class LegacyTocAdapterTest {

    private fun chapter(index: Int, title: String, volume: String? = null) =
        Chapter(bookId = 1L, index = index, title = title, volume = volume)

    @Test
    fun chaptersWithExplicitVolumeAreGroupedUnderThatVolume() {
        val tree = LegacyTocAdapter.toTocTree(
            publicationId = "p",
            revision = "r",
            chapters = listOf(
                chapter(0, "第一章 风起", volume = "第一卷 山河"),
                chapter(1, "第二章 远行", volume = "第一卷 山河"),
                chapter(2, "第三章 归途", volume = "第二卷 远方")
            )
        )
        assertEquals(2, tree.roots.size)
        assertEquals("第一卷 山河", tree.roots[0].label)
        assertEquals(TocNodeKind.GROUP, tree.roots[0].kind)
        assertTrue(tree.roots[0].isVolume)
        assertEquals(2, tree.roots[0].children.size)
        assertEquals(1, tree.roots[1].children.size)
    }

    @Test
    fun everyChapterBecomesAReadableEntryWithSectionId() {
        val tree = LegacyTocAdapter.toTocTree(
            "p", "r",
            listOf(chapter(10, "A", volume = "卷一"), chapter(13, "B", volume = "卷一"))
        )
        val entries = tree.allNodes.filter { it.kind == TocNodeKind.ENTRY }
        assertEquals(2, entries.size)
        assertTrue("每个章节条目都必须可读", entries.all { it.hasTarget })
        // section id 按**阅读序**（列表下标），不是物理 chapter.index。
        assertEquals("section_0", entries[0].targetSectionId)
        assertEquals("section_1", entries[1].targetSectionId)
        assertNotNull(tree.sectionOrdinalById["section_0"])
    }

    @Test
    fun legacyVolumeHeadingChapterBecomesGroupWithoutReadableEntry() {
        // 旧书把卷标题存成了一章：它只做分组，不占正常导航步骤（主计划 4.4）。
        val tree = LegacyTocAdapter.toTocTree(
            "p", "r",
            listOf(
                chapter(0, "第一卷 山河"),
                chapter(1, "第一章 风起"),
                chapter(2, "第二章 远行")
            )
        )
        assertEquals(1, tree.roots.size)
        assertEquals(TocNodeKind.GROUP, tree.roots[0].kind)
        assertEquals("第一卷 山河", tree.roots[0].label)
        assertFalse("结构性卷标题不得可读", tree.roots[0].hasTarget)
        assertEquals("其后的章节挂在卷下", 2, tree.roots[0].children.size)
    }

    @Test
    fun chaptersWithoutVolumeAttachToRoot() {
        val tree = LegacyTocAdapter.toTocTree(
            "p", "r",
            listOf(chapter(0, "第一章"), chapter(1, "第二章"))
        )
        assertEquals(2, tree.roots.size)
        assertTrue(tree.roots.all { it.kind == TocNodeKind.ENTRY })
        assertTrue(tree.roots.all { it.parentId == null })
    }

    @Test
    fun sameNamedVolumesGetDistinctNodeIds() {
        // F07：旧实现用标题当 key，同名卷会冲突。
        val tree = LegacyTocAdapter.toTocTree(
            "p", "r",
            listOf(
                chapter(0, "第一卷 序"),
                chapter(1, "第一章 A"),
                chapter(2, "第一卷 序"),
                chapter(3, "第二章 B")
            )
        )
        assertEquals(2, tree.roots.size)
        assertEquals("同名卷必须都能保留", 2, tree.roots.count { it.label == "第一卷 序" })
        assertEquals("nodeId 必须互不相同", 2, tree.roots.map { it.nodeId }.distinct().size)
    }

    @Test
    fun explicitVolumeFieldTakesPrecedenceOverPatternMatching() {
        // 有显式分卷字段时，即使标题看起来像卷标题，也必须仍作为可读章节处理。
        val tree = LegacyTocAdapter.toTocTree(
            "p", "r",
            listOf(chapter(0, "第一卷 山河", volume = "上卷"))
        )
        assertEquals(1, tree.roots.size)
        assertEquals(TocNodeKind.GROUP, tree.roots[0].kind)
        assertEquals("上卷", tree.roots[0].label)
        assertEquals(1, tree.roots[0].children.size)
        assertTrue("显式分卷下的章节必须可读", tree.roots[0].children[0].hasTarget)
    }

    @Test
    fun outputFeedsTocOutlineForExpansionAndSearch() {
        // 适配器产物必须能直接给目录 UI 用：可折叠、可搜索、可算覆盖。
        val tree = LegacyTocAdapter.toTocTree(
            "p", "r",
            listOf(
                chapter(0, "第一章 风起", volume = "第一卷"),
                chapter(1, "第二章 夜行", volume = "第一卷"),
                chapter(2, "第三章 归途", volume = "第二卷")
            )
        )
        // 收起时只看到卷。
        assertEquals(2, TocOutline.flatten(tree, expanded = emptySet()).size)
        // 展开第一卷后能看到两章。
        val expanded = TocOutline.flatten(tree, expanded = setOf(tree.roots[0].nodeId))
        assertEquals(4, expanded.size)
        // 搜索命中章时自动露出祖先。
        val searched = TocOutline.flatten(tree, emptySet(), query = "夜行")
        assertTrue(searched.any { it.node.label == "第一卷" })
        assertTrue(searched.any { it.node.label == "第二章 夜行" })
        // 覆盖范围可计算（section 映射由适配器提供）。
        val coverage = TocOutline.coverageOf(tree, tree.roots[0])
        assertNotNull(coverage)
        assertEquals(2, coverage.readableCount)
        assertTrue(coverage.isContiguous)
    }

    @Test
    fun emptyChapterListProducesEmptyTree() {
        val tree = LegacyTocAdapter.toTocTree("p", "r", emptyList())
        assertTrue(tree.roots.isEmpty())
        assertTrue(tree.allNodes.isEmpty())
    }

    @Test
    fun blankVolumeFieldIsTreatedAsNoVolume() {
        val tree = LegacyTocAdapter.toTocTree(
            "p", "r",
            listOf(chapter(0, "第一章", volume = "   "))
        )
        assertEquals(1, tree.roots.size)
        assertEquals(TocNodeKind.ENTRY, tree.roots[0].kind)
    }

    @Test
    fun sectionIdMatchesReaderConvention() {
        // 与 ReaderViewModel 构造 locator 的 section_<ordinal> 约定一致，
        // 否则目录高亮当前章会永远找不到节点。
        assertEquals("section_0", LegacyTocAdapter.sectionIdOf(0))
        assertEquals("section_7", LegacyTocAdapter.sectionIdOf(7))
    }
}
