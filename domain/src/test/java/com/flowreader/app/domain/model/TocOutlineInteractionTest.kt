package com.flowreader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8 目录交互规格测试（主计划 7.2 第 4、5、7、8、9、10、11、12、13 条）。
 */
class TocOutlineInteractionTest {

    private fun tree(): TocTree {
        val sub = TocNode("n-sub", "n-ch1", 0, "第一节 夜行", TocNodeKind.ENTRY, targetSectionId = "sec-2")
        val ch1 = TocNode(
            "n-ch1",
            "n-vol1",
            0,
            "第一章 风起",
            TocNodeKind.ENTRY,
            targetSectionId = "sec-1",
            children = listOf(sub)
        )
        val ch2 = TocNode("n-ch2", "n-vol1", 1, "第二章 远行", TocNodeKind.ENTRY, targetSectionId = "sec-3")
        val vol1 = TocNode(
            "n-vol1",
            null,
            0,
            "第一卷 山河",
            TocNodeKind.GROUP,
            isVolume = true,
            targetSectionId = "sec-0",
            children = listOf(ch1, ch2)
        )
        val ch3 = TocNode("n-ch3", "n-vol2", 0, "第三章 归途", TocNodeKind.ENTRY, targetSectionId = "sec-4")
        val vol2 = TocNode(
            "n-vol2",
            null,
            1,
            "第二卷 远行",
            TocNodeKind.GROUP,
            isVolume = true,
            children = listOf(ch3)
        )
        val empty = TocNode("n-empty", null, 2, "附录", TocNodeKind.GROUP, isVolume = true)
        return TocTree(
            "pub-1",
            "rev-1",
            listOf(vol1, vol2, empty),
            mapOf("sec-0" to 0, "sec-1" to 1, "sec-2" to 2, "sec-3" to 3, "sec-4" to 4)
        )
    }

    // ------------------------------------------------------------ 第 4 条：自动展开祖先

    @Test
    fun initialExpansionOpensOnlyTheCurrentChaptersAncestors() {
        val expanded = TocOutline.initialExpanded(tree(), TocLocation(sectionId = "sec-3"))
        assertEquals("只展开当前章的祖先卷", setOf("n-vol1"), expanded)
    }

    @Test
    fun initialExpansionOpensNestedAncestorsForDeepCurrentNode() {
        val expanded = TocOutline.initialExpanded(tree(), TocLocation(sectionId = "sec-2"))
        assertEquals(setOf("n-vol1", "n-ch1"), expanded)
    }

    @Test
    fun initialExpansionFallsBackToFirstReadableChapterWhenNoLocation() {
        val expanded = TocOutline.initialExpanded(tree(), TocLocation())
        assertEquals("无定位时展开第一个可读章祖先", setOf("n-vol1"), expanded)
    }

    // ------------------------------------------------------------ 第 12 条：别名优先

    @Test
    fun aliasPrefersTheEntryNodeUserCameThrough() {
        // 两个目录项指向同一 section：用户从第二个进来时高亮第二个。
        val a = TocNode("a", null, 0, "别名甲", TocNodeKind.ENTRY, targetSectionId = "sec-x")
        val b = TocNode("b", null, 1, "别名乙", TocNodeKind.ENTRY, targetSectionId = "sec-x")
        val subject = TocTree("p", "r", listOf(a, b), mapOf("sec-x" to 0))
        assertEquals("b", TocOutline.currentNode(subject, TocLocation("sec-x", "b"))?.nodeId)
    }

    @Test
    fun aliasFallsBackToFirstCanonicalEntry() {
        val a = TocNode("a", null, 0, "别名甲", TocNodeKind.ENTRY, targetSectionId = "sec-x")
        val b = TocNode("b", null, 1, "别名乙", TocNodeKind.ENTRY, targetSectionId = "sec-x")
        val subject = TocTree("p", "r", listOf(a, b), mapOf("sec-x" to 0))
        assertEquals("无入口信息时取第一个规范入口", "a", TocOutline.currentNode(subject, TocLocation("sec-x"))?.nodeId)
    }

    @Test
    fun entryNodePointingAtDifferentSectionIsIgnored() {
        val a = TocNode("a", null, 0, "A", TocNodeKind.ENTRY, targetSectionId = "sec-1")
        val b = TocNode("b", null, 1, "B", TocNodeKind.ENTRY, targetSectionId = "sec-2")
        val subject = TocTree("p", "r", listOf(a, b), mapOf("sec-1" to 0, "sec-2" to 1))
        // entryNodeId 指向别处时不得采信，必须按 section 回退。
        assertEquals("a", TocOutline.currentNode(subject, TocLocation("sec-1", "b"))?.nodeId)
    }

    @Test
    fun noLocationYieldsNoCurrentNode() {
        assertNull(TocOutline.currentNode(tree(), TocLocation()))
    }

    // ------------------------------------------------------------ 第 7 条：搜索

    @Test
    fun searchMatchesChapterLabels() {
        // 用只出现在章标签里的词：「风起」只在「第一章 风起」中。
        val rows = TocOutline.flatten(tree(), emptySet(), TocLocation(), "风起")
        assertEquals(listOf("n-ch1"), rows.filter { it.matchKind == TocMatchKind.SELF }.map { it.node.nodeId })
    }

    @Test
    fun searchMatchingBothVolumeAndChapterReturnsBothAsSelfMatches() {
        // 「远行」同时出现在「第二卷 远行」与「第二章 远行」中：两者都应命中，
        // 且祖先不得重复。
        val rows = TocOutline.flatten(tree(), emptySet(), TocLocation(), "远行")
        val selfIds = rows.filter { it.matchKind == TocMatchKind.SELF }.map { it.node.nodeId }
        assertEquals(listOf("n-ch2", "n-vol2"), selfIds)
        assertEquals(rows.map { it.node.nodeId }.distinct().size, rows.size)
    }

    @Test
    fun searchMatchesVolumeLabelsAndShowsTheirDescendants() {
        val rows = TocOutline.flatten(tree(), emptySet(), TocLocation(), "第二卷")
        val ids = rows.map { it.node.nodeId }
        assertTrue("卷命中必须展示该卷全部后代", ids.contains("n-ch3"))
        assertEquals(listOf("n-vol2"), rows.filter { it.matchKind == TocMatchKind.SELF }.map { it.node.nodeId })
    }

    @Test
    fun searchShowsFullAncestorChainForMatchedChapter() {
        val rows = TocOutline.flatten(tree(), emptySet(), TocLocation(), "夜行")
        val ids = rows.map { it.node.nodeId }
        assertEquals("命中项的完整祖先链必须出现", listOf("n-vol1", "n-ch1", "n-sub"), ids)
        assertEquals(TocMatchKind.ANCESTOR, rows[0].matchKind)
        assertEquals(TocMatchKind.ANCESTOR, rows[1].matchKind)
        assertEquals(TocMatchKind.SELF, rows[2].matchKind)
    }

    @Test
    fun searchDoesNotMutateUserExpansionState() {
        // 第 7 条：搜索强制展开命中路径，但**不覆写**用户长期折叠状态。
        val userExpanded = emptySet<String>()
        val rows = TocOutline.flatten(tree(), userExpanded, TocLocation(), "夜行")
        assertTrue("搜索时命中路径必须展开", rows.map { it.node.nodeId }.contains("n-sub"))
        assertEquals("但用户状态本身不得被改动", emptySet<String>(), userExpanded)
    }

    @Test
    fun searchDeduplicatesSharedAncestors() {
        // 两个命中项共享同一个祖先：祖先只出现一次。
        val rows = TocOutline.flatten(tree(), emptySet(), TocLocation(), "第")
        val ids = rows.map { it.node.nodeId }
        assertEquals("shared 祖先不得重复", ids.distinct().size, ids.size)
        assertTrue(ids.contains("n-vol1"))
    }

    @Test
    fun searchWithNoMatchReturnsEmptyList() {
        assertTrue(
            "无结果必须返回空列表（UI 显示空态）",
            TocOutline.flatten(tree(), emptySet(), TocLocation(), "完全不存在的词").isEmpty()
        )
    }

    @Test
    fun blankQueryBehavesAsBrowsingNotSearch() {
        val rows = TocOutline.flatten(tree(), emptySet(), TocLocation(), "   ")
        assertEquals(
            "空白查询走浏览路径",
            listOf("n-vol1", "n-vol2", "n-empty"),
            rows.map { it.node.nodeId }
        )
    }

    @Test
    fun searchIsCaseInsensitive() {
        val en = TocTree(
            "p",
            "r",
            listOf(TocNode("n", null, 0, "Chapter One", TocNodeKind.ENTRY, targetSectionId = "s")),
            mapOf("s" to 0)
        )
        assertEquals(1, TocOutline.flatten(en, emptySet(), TocLocation(), "chapter one").size)
        assertEquals(1, TocOutline.flatten(en, emptySet(), TocLocation(), "CHAPTER").size)
    }

    // ------------------------------------------------------------ 第 13 条 / 7.1：覆盖范围

    @Test
    fun contiguousCoverageReportsFirstAndLastOrdinal() {
        val coverage = TocOutline.coverageOf(tree(), tree().node("n-vol1")!!)
        assertNotNull(coverage)
        assertEquals(TocCoverage.DisplayKind.RANGE, coverage.displayKind)
        assertEquals(0, coverage.firstOrdinal)
        assertEquals(3, coverage.lastOrdinal)
        assertEquals(4, coverage.readableCount)
    }

    @Test
    fun nonContiguousCoverageIsLabelledAsSuchInsteadOfFakingARange() {
        // 卷 1 覆盖 sec-1..sec-3；卷 2 只有 sec-9，中间断档。
        val chA = TocNode("a", "g", 0, "A", TocNodeKind.ENTRY, targetSectionId = "s1")
        val chB = TocNode("b", "g", 1, "B", TocNodeKind.ENTRY, targetSectionId = "s9")
        val group = TocNode("g", null, 0, "非连续卷", TocNodeKind.GROUP, children = listOf(chA, chB))
        val subject = TocTree("p", "r", listOf(group), mapOf("s1" to 1, "s9" to 9))
        val coverage = TocOutline.coverageOf(subject, group)!!
        assertEquals(TocCoverage.DisplayKind.NON_CONTIGUOUS, coverage.displayKind)
        assertEquals(2, coverage.readableCount)
    }

    @Test
    fun groupWithNoReadableDescendantsReportsNone() {
        val coverage = TocOutline.coverageOf(tree(), tree().node("n-empty")!!)
        assertEquals(TocCoverage.DisplayKind.NONE, coverage.displayKind)
        assertEquals(0, coverage.readableCount)
    }

    @Test
    fun sectionItselfCountsTowardItsGroupCoverage() {
        // 卷自己有 target（读卷首）时，它的 section 也计入覆盖。
        val coverage = TocOutline.coverageOf(tree(), tree().node("n-vol1")!!)
        assertTrue("卷自身的 target 必须计入", coverage.readableCount >= 4)
    }

    @Test
    fun missingOrdinalMappingDoesNotFabricateContiguity() {
        val chA = TocNode("a", "g", 0, "A", TocNodeKind.ENTRY, targetSectionId = "s1")
        val chB = TocNode("b", "g", 1, "B", TocNodeKind.ENTRY, targetSectionId = "s2")
        val group = TocNode("g", null, 0, "G", TocNodeKind.GROUP, children = listOf(chA, chB))
        val subject = TocTree("p", "r", listOf(group), emptyMap())
        val coverage = TocOutline.coverageOf(subject, group)!!
        assertEquals("缺映射时不得声称连续范围", TocCoverage.DisplayKind.NON_CONTIGUOUS, coverage.displayKind)
        assertEquals(2, coverage.readableCount)
    }

    // ------------------------------------------------------------ 死分组 / 箭头

    @Test
    fun emptyGroupIsMarkedDeadSoUiHidesTheMisleadingArrow() {
        val rows = TocOutline.flatten(tree(), emptySet())
        val empty = rows.first { it.node.nodeId == "n-empty" }
        assertTrue(empty.isDeadGroup)
        assertFalse("无子节点不得显示展开箭头", empty.showExpandArrow)
    }

    @Test
    fun groupWithChildrenShowsArrowAndIsNotDead() {
        val rows = TocOutline.flatten(tree(), emptySet())
        val vol1 = rows.first { it.node.nodeId == "n-vol1" }
        assertFalse(vol1.isDeadGroup)
        assertTrue(vol1.showExpandArrow)
    }

    @Test
    fun groupWithTargetButNoChildrenIsNotDeadSoVolumePrefaceIsReachable() {
        // 有独立真实目标的卷可以提供「读卷首」，不算死分组。
        val group = TocNode(
            "g",
            null,
            0,
            "只有卷首的卷",
            TocNodeKind.GROUP,
            isVolume = true,
            targetSectionId = "s1"
        )
        val subject = TocTree("p", "r", listOf(group), mapOf("s1" to 0))
        val row = TocOutline.flatten(subject, emptySet()).single()
        assertFalse(row.isDeadGroup)
    }
}
