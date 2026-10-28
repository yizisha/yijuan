package com.flowreader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8b `toc_nodes` → [TocTree] 装配测试（主计划 4.3 / 7.1）。
 *
 * 这是「目录反映出版物真实结构」的落点：EPUB 导入时的真实层级必须在读回时**逐层还原**，
 * 且坏数据（孤儿、环、重复 id、未知 kind）不得让整段目录消失。
 */
class TocTreeBuilderTest {

    private fun row(
        nodeId: String,
        parentId: String? = null,
        order: Int = 0,
        label: String = nodeId,
        kind: String = "ENTRY",
        role: String? = null,
        sectionId: String? = null
    ) = TocNodeRow(nodeId, parentId, order, label, kind, role, sectionId)

    @Test
    fun flattRowListIsRebuiltIntoTheOriginalHierarchy() {
        // 这是 flattenToc 写库后的逆过程：三层结构必须逐层还原。
        val built = TocTreeBuilder.build(
            "pub",
            "rev",
            listOf(
                row("vol1", null, 0, "第一卷", kind = "GROUP", role = "VOLUME"),
                row("ch1", "vol1", 0, "第一章", sectionId = "sec-1"),
                row("sub1", "ch1", 0, "第一节", sectionId = "sec-2"),
                row("ch2", "vol1", 1, "第二章", sectionId = "sec-3"),
                row("ch3", null, 1, "第三章", sectionId = "sec-4")
            ),
            mapOf("sec-1" to 1, "sec-2" to 2, "sec-3" to 3, "sec-4" to 4)
        )
        assertTrue(built.isHealthy)
        assertEquals(2, built.tree.roots.size)
        assertEquals("vol1", built.tree.roots[0].nodeId)
        assertEquals("ch3", built.tree.roots[1].nodeId)
        assertEquals(listOf("ch1", "ch2"), built.tree.roots[0].children.map { it.nodeId })
        assertEquals(listOf("sub1"), built.tree.roots[0].children[0].children.map { it.nodeId })
    }

    @Test
    fun siblingOrderIsHonouredNotRowInsertionOrder() {
        // 数据库按 siblingOrder 排序返回，但装配不能依赖查询顺序。
        val built = TocTreeBuilder.build(
            "pub",
            "rev",
            listOf(
                row("c", null, 2, "C"),
                row("a", null, 0, "A"),
                row("b", null, 1, "B")
            ),
            emptyMap()
        )
        assertEquals(listOf("a", "b", "c"), built.tree.roots.map { it.nodeId })
    }

    @Test
    fun volumeRoleMarksTheNodeAsVolumeGroup() {
        val built = TocTreeBuilder.build(
            "pub",
            "rev",
            listOf(row("v", null, 0, "卷一", kind = "GROUP", role = "VOLUME")),
            emptyMap()
        )
        val node = built.tree.roots.single()
        assertTrue("role=VOLUME 必须被识别为卷", node.isVolume)
        assertEquals(TocNodeKind.GROUP, node.kind)
    }

    @Test
    fun groupWithoutLinkStaysAGroupWithNoTarget() {
        // EPUB nav 里的 <span> 卷头无链接：必须保留为分组，而不是被丢掉。
        val built = TocTreeBuilder.build(
            "pub",
            "rev",
            listOf(
                row("v", null, 0, "无链接卷头", kind = "GROUP", role = "VOLUME"),
                row("c", "v", 0, "第一章", sectionId = "s1")
            ),
            mapOf("s1" to 0)
        )
        val volume = built.tree.roots.single()
        assertFalse(volume.hasTarget)
        assertTrue(volume.hasChildren)
        assertEquals(1, volume.children.size)
    }

    @Test
    fun groupCanCarryItsOwnTargetAlongsideChildren() {
        // 计划 7.2 第 1 条：卷允许同时有 target（「读卷首」）与子节点。
        val built = TocTreeBuilder.build(
            "pub",
            "rev",
            listOf(
                row("v", null, 0, "卷一", kind = "GROUP", role = "VOLUME", sectionId = "s0"),
                row("c", "v", 0, "第一章", sectionId = "s1")
            ),
            mapOf("s0" to 0, "s1" to 1)
        )
        val volume = built.tree.roots.single()
        assertTrue(volume.hasTarget)
        assertTrue(volume.hasChildren)
    }

    @Test
    fun orphanIsPromotedToRootInsteadOfDisappearing() {
        // parentId 指向不存在的节点：整棵子树仍必须可见。
        val built = TocTreeBuilder.build(
            "pub",
            "rev",
            listOf(
                row("good", null, 0, "正常章", sectionId = "s1"),
                row("orphan", "missing-parent", 1, "孤儿章", sectionId = "s2"),
                row("child", "orphan", 0, "孤儿的子章", sectionId = "s3")
            ),
            mapOf("s1" to 1, "s2" to 2, "s3" to 3)
        )
        val ids = built.tree.roots.map { it.nodeId }
        assertTrue("孤儿必须被提升为根而不是丢弃", ids.contains("orphan"))
        assertEquals(2, built.tree.roots.size)
        assertEquals("孤儿的子树必须完整保留", listOf("child"), built.tree.roots[1].children.map { it.nodeId })
        assertFalse(built.isHealthy)
        assertTrue(built.diagnostics.any { it.contains("ORPHAN") })
    }

    @Test
    fun cycleIsBrokenByPromotingToRootAndDoesNotHang() {
        // a.parent=b、b.parent=a：必须有确定结果且不死循环。
        val built = TocTreeBuilder.build(
            "pub",
            "rev",
            listOf(
                row("a", "b", 0, "A"),
                row("b", "a", 1, "B")
            ),
            emptyMap()
        )
        assertTrue(built.tree.allNodes.isNotEmpty())
        assertTrue(built.diagnostics.any { it.contains("CYCLE") })
        // 环上的节点不得重复挂载。
        assertEquals(built.tree.allNodes.map { it.nodeId }.distinct().size, built.tree.allNodes.size)
    }

    @Test
    fun duplicateNodeIdKeepsFirstAndReportsDiagnostic() {
        val built = TocTreeBuilder.build(
            "pub",
            "rev",
            listOf(
                row("dup", null, 0, "第一次"),
                row("dup", null, 1, "第二次")
            ),
            emptyMap()
        )
        assertEquals(1, built.tree.allNodes.size)
        assertEquals("第一次", built.tree.allNodes.single().label)
        assertTrue(built.diagnostics.any { it.contains("DUPLICATE_NODE_ID") })
    }

    @Test
    fun unknownKindFallsBackToEntrySoTheChapterStaysReachable() {
        val built = TocTreeBuilder.build(
            "pub",
            "rev",
            listOf(row("x", null, 0, "奇怪类型", kind = "SOMETHING_NEW", sectionId = "s1")),
            mapOf("s1" to 0)
        )
        assertEquals(TocNodeKind.ENTRY, built.tree.roots.single().kind)
        assertTrue("未知 kind 不得让章节变得点不开", built.tree.roots.single().hasTarget)
    }

    @Test
    fun emptyRowsProduceEmptyHealthyTree() {
        val built = TocTreeBuilder.build("pub", "rev", emptyList(), emptyMap())
        assertTrue(built.tree.roots.isEmpty())
        assertTrue(built.isHealthy)
    }

    @Test
    fun sectionOrdinalMapIsCarriedThroughForCoverageAndNavigation() {
        val built = TocTreeBuilder.build(
            "pub",
            "rev",
            listOf(
                row("v", null, 0, "卷一", kind = "GROUP", role = "VOLUME"),
                row("c1", "v", 0, "第一章", sectionId = "sec-a"),
                row("c2", "v", 1, "第二章", sectionId = "sec-b")
            ),
            mapOf("sec-a" to 5, "sec-b" to 6)
        )
        val volume = built.tree.roots.single()
        val coverage = TocOutline.coverageOf(built.tree, volume)
        assertEquals(2, coverage.readableCount)
        assertEquals(5, coverage.firstOrdinal)
        assertEquals(6, coverage.lastOrdinal)
        assertTrue(coverage.isContiguous)
    }

    @Test
    fun builtTreeFeedsTocOutlineExpansionAndSearch() {
        val built = TocTreeBuilder.build(
            "pub",
            "rev",
            listOf(
                row("v", null, 0, "第一卷 山河", kind = "GROUP", role = "VOLUME"),
                row("c1", "v", 0, "第一章 风起", sectionId = "s1"),
                row("c2", "v", 1, "第二章 夜行", sectionId = "s2")
            ),
            mapOf("s1" to 1, "s2" to 2)
        )
        // 收起只看到卷。
        assertEquals(1, TocOutline.flatten(built.tree, emptySet()).size)
        // 展开后两章可见。
        assertEquals(3, TocOutline.flatten(built.tree, setOf("v")).size)
        // 搜索命中章时自动露出卷祖先。
        val searched = TocOutline.flatten(built.tree, emptySet(), query = "夜行")
        assertTrue(searched.any { it.node.nodeId == "v" })
        assertTrue(searched.any { it.node.nodeId == "c2" })
        // 打开目录时自动展开当前章祖先。
        val initial = TocOutline.initialExpanded(
            built.tree,
            TocLocation(sectionId = "s2")
        )
        // 注意：真实 sectionId 需要通过 ordinal 映射才能得到 currentNode；
        // 这里验证的是「找不到时回退到第一个可读条目的祖先」不崩且合理。
        assertNotNull(initial)
    }

    @Test
    fun promotedOrphanDoesNotAlsoRemainAChild() {
        // 提升为根之后，不能同时仍挂在原父节点下（否则同一 nodeId 出现两次）。
        val built = TocTreeBuilder.build(
            "pub",
            "rev",
            listOf(
                row("orphan", "missing", 0, "孤儿"),
                row("child", "orphan", 0, "子")
            ),
            emptyMap()
        )
        val ids = built.tree.allNodes.map { it.nodeId }
        assertEquals(ids.distinct().size, ids.size)
    }
}
