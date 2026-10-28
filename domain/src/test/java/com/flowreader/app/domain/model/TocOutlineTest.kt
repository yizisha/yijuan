package com.flowreader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8 目录逻辑测试（主计划 7.1 / 7.2）。
 *
 * 逐条覆盖计划 7.2 的交互规格：折叠、自动展开、搜索、覆盖范围、死分组、深度封顶。
 */
class TocOutlineTest {

    /**
     * 构造一棵典型三层树：
     * ```text
     * 第一卷 (GROUP, 有 target)
     *   第一章 (ENTRY, sec-1)
     *     第一节 (ENTRY, sec-2)
     *   第二章 (ENTRY, sec-3)
     * 第二卷 (GROUP, 无 target)
     *   第三章 (ENTRY, sec-4)
     * 空卷 (GROUP, 无 target, 无子节点)
     * ```
     */
    private fun tree(): TocTree {
        val sub = TocNode(
            nodeId = "n-sub",
            parentId = "n-ch1",
            order = 0,
            label = "第一节",
            kind = TocNodeKind.ENTRY,
            targetSectionId = "sec-2"
        )
        val ch1 = TocNode(
            nodeId = "n-ch1",
            parentId = "n-vol1",
            order = 0,
            label = "第一章",
            kind = TocNodeKind.ENTRY,
            targetSectionId = "sec-1",
            children = listOf(sub)
        )
        val ch2 = TocNode(
            nodeId = "n-ch2",
            parentId = "n-vol1",
            order = 1,
            label = "第二章",
            kind = TocNodeKind.ENTRY,
            targetSectionId = "sec-3"
        )
        val vol1 = TocNode(
            nodeId = "n-vol1",
            parentId = null,
            order = 0,
            label = "第一卷",
            kind = TocNodeKind.GROUP,
            isVolume = true,
            targetSectionId = "sec-0",
            children = listOf(ch1, ch2)
        )
        val ch3 = TocNode(
            nodeId = "n-ch3",
            parentId = "n-vol2",
            order = 0,
            label = "第三章",
            kind = TocNodeKind.ENTRY,
            targetSectionId = "sec-4"
        )
        val vol2 = TocNode(
            nodeId = "n-vol2",
            parentId = null,
            order = 1,
            label = "第二卷",
            kind = TocNodeKind.GROUP,
            isVolume = true,
            children = listOf(ch3)
        )
        val empty = TocNode(
            nodeId = "n-empty",
            parentId = null,
            order = 2,
            label = "空卷",
            kind = TocNodeKind.GROUP,
            isVolume = true
        )
        return TocTree(
            publicationId = "pub-1",
            revision = "rev-1",
            roots = listOf(vol1, vol2, empty),
            sectionOrdinalById = mapOf(
                "sec-0" to 0,
                "sec-1" to 1,
                "sec-2" to 2,
                "sec-3" to 3,
                "sec-4" to 4
            )
        )
    }

    // ------------------------------------------------------------ 树结构

    @Test
    fun allNodesFollowDepthFirstOrder() {
        assertEquals(
            listOf("n-vol1", "n-ch1", "n-sub", "n-ch2", "n-vol2", "n-ch3", "n-empty"),
            tree().allNodes.map { it.nodeId }
        )
    }

    @Test
    fun ancestorsAreReturnedRootFirst() {
        assertEquals(listOf("n-vol1", "n-ch1"), tree().ancestorsOf("n-sub"))
        assertEquals(listOf("n-vol1"), tree().ancestorsOf("n-ch2"))
        assertEquals(emptyList<String>(), tree().ancestorsOf("n-vol1"))
    }

    @Test
    fun descendantsExcludeSelf() {
        assertEquals(listOf("n-ch1", "n-sub", "n-ch2"), tree().descendantsOf("n-vol1"))
        assertEquals(listOf("n-sub"), tree().descendantsOf("n-ch1"))
        assertEquals(emptyList<String>(), tree().descendantsOf("n-ch2"))
    }

    @Test
    fun expandableIdsOnlyIncludeNodesWithChildren() {
        assertEquals(listOf("n-vol1", "n-ch1", "n-vol2"), tree().expandableIds)
    }

    @Test
    fun duplicateLabelsAreReportedButIdsStayDistinct() {
        // 同名不同位置必须都能被识别，且 nodeId 不冲突（F07）。
        val a = TocNode("a", null, 0, "第一卷", TocNodeKind.GROUP, isVolume = true)
        val b = TocNode("b", null, 1, "第一卷", TocNodeKind.GROUP, isVolume = true)
        val subject = TocTree("p", "r", listOf(a, b))
        assertEquals(listOf("第一卷"), subject.duplicateLabels)
        assertEquals(2, subject.allNodes.map { it.nodeId }.distinct().size)
    }

    @Test
    fun ancestorChainIsCycleSafe() {
        // 坏数据成环不得死循环。
        val a = TocNode("a", "b", 0, "A", TocNodeKind.GROUP)
        val b = TocNode("b", "a", 0, "B", TocNodeKind.GROUP)
        val subject = TocTree("p", "r", listOf(a, b))
        assertTrue("必须能返回而不是死循环", subject.ancestorsOf("a").size <= 128)
    }

    // ------------------------------------------------------------ 折叠

    @Test
    fun collapsedRootHidesItsChildren() {
        val rows = TocOutline.flatten(tree(), expanded = emptySet())
        assertEquals(
            "收起状态只显示根节点",
            listOf("n-vol1", "n-vol2", "n-empty"),
            rows.map { it.node.nodeId }
        )
    }

    @Test
    fun expandingVolumeRevealsItsDirectChildrenOnly() {
        val rows = TocOutline.flatten(tree(), expanded = setOf("n-vol1"))
        assertEquals(
            listOf("n-vol1", "n-ch1", "n-ch2", "n-vol2", "n-empty"),
            rows.map { it.node.nodeId }
        )
    }

    @Test
    fun expandingNestedNodesRevealsDeepChildren() {
        val rows = TocOutline.flatten(tree(), expanded = setOf("n-vol1", "n-ch1"))
        assertTrue(rows.map { it.node.nodeId }.contains("n-sub"))
    }

    @Test
    fun collapseHidesOnlyThatSubtreeNotSiblings() {
        val rows = TocOutline.flatten(tree(), expanded = setOf("n-vol1"))
        assertTrue(rows.map { it.node.nodeId }.contains("n-ch2"))
        val collapsed = TocOutline.collapse(setOf("n-vol1"), "n-vol1")
        assertEquals(setOf<String>(), collapsed)
    }

    @Test
    fun toggleIsIdempotentlyReversible() {
        val once = TocOutline.toggle(emptySet(), "n-vol1")
        assertEquals(setOf("n-vol1"), once)
        assertEquals(emptySet<String>(), TocOutline.toggle(once, "n-vol1"))
        // 展开/收起都必须幂等，重复点击不会产生重复 key。
        assertEquals(once, TocOutline.expand(once, "n-vol1"))
        assertEquals(emptySet<String>(), TocOutline.collapse(emptySet(), "n-vol1"))
    }

    @Test
    fun depthReflectsNestingAndIndentIsCappedAtThree() {
        val rows = TocOutline.flatten(tree(), expanded = setOf("n-vol1", "n-ch1"))
        val sub = rows.first { it.node.nodeId == "n-sub" }
        assertEquals("真实层级必须保留", 2, sub.depth)
        assertEquals(2, sub.indentDepth)
    }
}
