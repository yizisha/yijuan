package com.flowreader.app.data.repository

import com.flowreader.app.data.local.dao.ContentIndexDao
import com.flowreader.app.data.local.entity.ReadingSectionEntity
import com.flowreader.app.data.local.entity.TocNodeEntity
import com.flowreader.app.domain.model.TocNodeKind
import com.flowreader.app.domain.model.TocOutline
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8b 真实目录树读取测试（主计划 7.1）。
 *
 * 验证 `toc_nodes` + `reading_sections` → TocTree 的接线真的通了，
 * 并且**旧书（无节点）必须返回 null**，让 UI 有机会回退到兼容适配器。
 */
class TocRepositoryImplTest {

    private val dao = mockk<ContentIndexDao>()
    private val repository = TocRepositoryImpl(dao)

    private fun tocNode(
        nodeId: String,
        parentId: String? = null,
        order: Int = 0,
        label: String = nodeId,
        kind: String = "ENTRY",
        role: String? = null,
        sectionId: String? = null
    ) = TocNodeEntity(
        bookId = 1L,
        revision = "rev-1",
        nodeId = nodeId,
        parentId = parentId,
        siblingOrder = order,
        label = label,
        kind = kind,
        role = role,
        targetJson = sectionId?.let {
            "{\"documentKey\":\"a.xhtml\",\"fragment\":null,\"sectionId\":\"$it\"}"
        }
    )

    private fun section(sectionId: String, ordinal: Int, title: String = sectionId) =
        ReadingSectionEntity(
            bookId = 1L, revision = "rev-1", sectionId = sectionId, ordinal = ordinal,
            title = title, kind = "NORMAL", rangesJson = "", mainTextUnits = 100L
        )

    @Test
    fun returnsNullWhenBookHasNoTocNodesSoUiCanFallBackToLegacyAdapter() = runTest {
        coEvery { dao.getTocNodes(1L, "rev-1") } returns emptyList()
        assertNull(
            "无节点必须返回 null（旧书），否则 UI 会显示空目录",
            repository.getTocTree(1L, "rev-1", "pub-1")
        )
    }

    @Test
    fun invalidArgumentsReturnNullWithoutHittingDatabase() = runTest {
        assertNull(repository.getTocTree(0L, "rev-1", "pub"))
        assertNull(repository.getTocTree(1L, "", "pub"))
    }

    @Test
    fun buildsRealHierarchyFromTocNodes() = runTest {
        coEvery { dao.getTocNodes(1L, "rev-1") } returns listOf(
            tocNode("v1", null, 0, "第一卷 山河", kind = "GROUP", role = "VOLUME"),
            tocNode("c1", "v1", 0, "第一章 风起", sectionId = "sec-a"),
            tocNode("c2", "v1", 1, "第二章 远行", sectionId = "sec-b"),
            tocNode("c3", null, 1, "第三章 归途", sectionId = "sec-c")
        )
        coEvery { dao.getSectionSummaries(1L, "rev-1") } returns listOf(
            section("sec-a", 0), section("sec-b", 1), section("sec-c", 2)
        )

        val tree = repository.getTocTree(1L, "rev-1", "pub-1")!!
        assertEquals("pub-1", tree.publicationId)
        assertEquals("rev-1", tree.revision)
        assertEquals(2, tree.roots.size)
        val volume = tree.roots[0]
        assertEquals("第一卷 山河", volume.label)
        assertTrue("role=VOLUME 必须被识别", volume.isVolume)
        assertEquals(TocNodeKind.GROUP, volume.kind)
        assertEquals(listOf("c1", "c2"), volume.children.map { it.nodeId })
        assertEquals("sec-a", volume.children[0].targetSectionId)
    }

    @Test
    fun sectionOrdinalsAreMappedSoTocCanJumpToTheRightChapter() = runTest {
        // 关键：target 存的是**清单 sectionId**，必须靠 reading_sections 才能换成章节下标。
        coEvery { dao.getTocNodes(1L, "rev-1") } returns listOf(
            tocNode("c1", null, 0, "第一章", sectionId = "hash-abc")
        )
        coEvery { dao.getSectionSummaries(1L, "rev-1") } returns listOf(
            section("hash-abc", 7)
        )
        val tree = repository.getTocTree(1L, "rev-1", "pub-1")!!
        assertEquals("清单 sectionId 必须能映射到阅读序", 7, tree.sectionOrdinalById["hash-abc"])
        // 覆盖范围据此可算。
        val coverage = TocOutline.coverageOf(tree, tree.roots.single())
        assertEquals(1, coverage.readableCount)
    }

    @Test
    fun brokenTargetJsonDegradesToNonReadableNodeWithoutKillingTheTree() = runTest {
        // 一条坏 JSON 只能让该节点不可读，不能抛异常、不能丢整棵目录。
        coEvery { dao.getTocNodes(1L, "rev-1") } returns listOf(
            tocNode("good", null, 0, "正常章", sectionId = "sec-a"),
            TocNodeEntity(
                bookId = 1L, revision = "rev-1", nodeId = "bad", parentId = null,
                siblingOrder = 1, label = "坏目标", kind = "ENTRY", role = null,
                targetJson = "{不是 JSON"
            )
        )
        coEvery { dao.getSectionSummaries(1L, "rev-1") } returns listOf(section("sec-a", 0))

        val tree = repository.getTocTree(1L, "rev-1", "pub-1")!!
        assertEquals("两个节点都要保留", 2, tree.roots.size)
        val good = tree.node("good")!!
        val bad = tree.node("bad")!!
        assertTrue(good.hasTarget)
        assertFalse("坏 JSON 的节点退化为不可读，但仍在树上", bad.hasTarget)
    }

    @Test
    fun unlinkedSpanVolumeIsPreservedAsGroup() = runTest {
        // EPUB nav 的 <span> 卷头无链接：必须保留为分组而不是被丢弃。
        coEvery { dao.getTocNodes(1L, "rev-1") } returns listOf(
            tocNode("v", null, 0, "无链接卷头", kind = "GROUP", role = "VOLUME"),
            tocNode("c", "v", 0, "第一章", sectionId = "sec-a")
        )
        coEvery { dao.getSectionSummaries(1L, "rev-1") } returns listOf(section("sec-a", 0))
        val tree = repository.getTocTree(1L, "rev-1", "pub-1")!!
        val volume = tree.roots.single()
        assertNotNull(volume)
        assertFalse(volume.hasTarget)
        assertEquals(1, volume.children.size)
    }

    @Test
    fun volumeWithOwnTargetAndChildrenKeepsBoth() = runTest {
        coEvery { dao.getTocNodes(1L, "rev-1") } returns listOf(
            tocNode("v", null, 0, "卷一", kind = "GROUP", role = "VOLUME", sectionId = "sec-v"),
            tocNode("c", "v", 0, "第一章", sectionId = "sec-a")
        )
        coEvery { dao.getSectionSummaries(1L, "rev-1") } returns listOf(
            section("sec-v", 0), section("sec-a", 1)
        )
        val tree = repository.getTocTree(1L, "rev-1", "pub-1")!!
        val volume = tree.roots.single()
        assertTrue("卷可以同时有「读卷首」目标与子节点", volume.hasTarget)
        assertTrue(volume.hasChildren)
    }
}
