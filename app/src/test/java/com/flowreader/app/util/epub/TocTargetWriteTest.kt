package com.flowreader.app.util.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import io.mockk.mockk
import org.junit.Test

/**
 * 回归守护：`flattenToc` 必须把**目录目标**写进 `targetJson`（S8b 真机验收发现的缺陷）。
 *
 * ## 缺陷经过
 *
 * `flattenToc` 的 `tocTargetSections` 参数原本有 `emptyMap()` 默认值，而调用方
 * （`PublicationImportCoordinator.activate`）漏传了 `manifest.tocTargetSections`。
 * 编译与既有测试全绿，但真机上**每个目录节点的 sectionId 都是 null**：
 * 目录能显示标签与层级，却**任何一项都跳不到对应章节**。
 *
 * 既有设备测试只断言了 `countTocNodes >= 5`（节点数量），所以这个缺陷一路漏过。
 * 本文件直接断言「目标确实被写进去了」。
 */
class TocTargetWriteTest {

    /** `flattenToc` 是纯函数，不触碰任何依赖；这里用 relaxed mock 仅为取得实例。 */
    private val coordinator = PublicationImportCoordinator(
        store = mockk(relaxed = true),
        contentIndexDao = mockk(relaxed = true),
        bookDao = mockk(relaxed = true)
    )

    private fun node(
        nodeId: String,
        label: String,
        kind: EpubNavigationParser.Node.Kind,
        path: String? = null,
        fragment: String? = null,
        children: List<EpubNavigationParser.Node> = emptyList()
    ) = EpubNavigationParser.Node(
        nodeId = nodeId,
        label = label,
        kind = kind,
        isVolume = kind == EpubNavigationParser.Node.Kind.GROUP,
        role = if (kind == EpubNavigationParser.Node.Kind.GROUP) EpubNavigationParser.ROLE_VOLUME else null,
        targetPath = path,
        targetFragment = fragment,
        children = children
    )

    @Test
    fun targetSectionIsWrittenIntoTheNodeRow() {
        val toc = listOf(
            node("vol", "第一卷", EpubNavigationParser.Node.Kind.GROUP),
            node("ch1", "第一章", EpubNavigationParser.Node.Kind.ENTRY, "OEBPS/Text/c1.xhtml", "a1"),
            node("ch2", "第二章", EpubNavigationParser.Node.Kind.ENTRY, "OEBPS/Text/c1.xhtml", "a2")
        )
        val entities = coordinator.flattenToc(
            nodes = toc,
            bookId = 1L,
            revision = "rev",
            tocTargetSections = mapOf("ch1" to "sec-1", "ch2" to "sec-2")
        )

        assertEquals(3, entities.size)
        val ch1 = entities.first { it.nodeId == "ch1" }
        val ch2 = entities.first { it.nodeId == "ch2" }
        assertNotNull("可读目录项必须带 targetJson", ch1.targetJson)
        assertTrue("targetJson 必须含 sectionId，否则目录点不动", ch1.targetJson!!.contains("sec-1"))
        assertTrue("同文档不同锚点必须各写各的 section", ch2.targetJson!!.contains("sec-2"))
        assertTrue("文档 key 也必须保留", ch1.targetJson!!.contains("OEBPS/Text/c1.xhtml"))
        assertTrue("锚点 fragment 也必须保留", ch1.targetJson!!.contains("a1"))
    }

    @Test
    fun unlinkedGroupGetsNoTargetJsonAtAll() {
        // 无 targetPath 且无 section 的纯分组：整体为 null（不是空对象）。
        val toc = listOf(node("vol", "无链接卷头", EpubNavigationParser.Node.Kind.GROUP))
        val entities = coordinator.flattenToc(toc, 1L, "rev", emptyMap())
        assertNull("无链接分组不得写出空对象当目标", entities.single().targetJson)
    }

    @Test
    fun groupWithOwnTargetKeepsItSoVolumePrefaceIsReachable() {
        val toc = listOf(
            node("vol", "卷一", EpubNavigationParser.Node.Kind.GROUP, "OEBPS/Text/c0.xhtml", "top")
        )
        val entities = coordinator.flattenToc(
            toc, 1L, "rev", mapOf("vol" to "sec-vol")
        )
        assertNotNull("有独立目标的卷必须能跳到卷首", entities.single().targetJson)
        assertTrue(entities.single().targetJson!!.contains("sec-vol"))
    }

    @Test
    fun hierarchyAndSiblingOrderArePreserved() {
        val toc = listOf(
            node(
                "vol", "卷一", EpubNavigationParser.Node.Kind.GROUP,
                children = listOf(
                    node("c1", "第一章", EpubNavigationParser.Node.Kind.ENTRY, "a.xhtml", "a1"),
                    node("c2", "第二章", EpubNavigationParser.Node.Kind.ENTRY, "a.xhtml", "a2")
                )
            )
        )
        val entities = coordinator.flattenToc(toc, 1L, "rev", emptyMap())
        val vol = entities.first { it.nodeId == "vol" }
        val c1 = entities.first { it.nodeId == "c1" }
        val c2 = entities.first { it.nodeId == "c2" }
        assertNull("根节点 parentId 必须为 null", vol.parentId)
        assertEquals("vol", c1.parentId)
        assertEquals("vol", c2.parentId)
        assertEquals(0, c1.siblingOrder)
        assertEquals(1, c2.siblingOrder)
        assertEquals("GROUP", vol.kind)
        assertEquals(EpubNavigationParser.ROLE_VOLUME, vol.role)
    }

    @Test
    fun everyReadableNodeCarriesASectionWhenMappingIsProvided() {
        // 直接对应真机暴露的现象：映射非空时不允许出现 sectionId 为 null 的可读节点。
        val toc = listOf(
            node("vol", "卷一", EpubNavigationParser.Node.Kind.GROUP),
            node("c1", "第一章", EpubNavigationParser.Node.Kind.ENTRY, "a.xhtml", "a1"),
            node("c2", "第二章", EpubNavigationParser.Node.Kind.ENTRY, "a.xhtml", "a2"),
            node("c3", "第三章", EpubNavigationParser.Node.Kind.ENTRY, "b.xhtml", null)
        )
        val mapping = mapOf("c1" to "s1", "c2" to "s2", "c3" to "s3")
        val entities = coordinator.flattenToc(toc, 1L, "rev", mapping)

        val readable = entities.filter { it.nodeId in mapping.keys }
        assertEquals(3, readable.size)
        readable.forEach { entity ->
            val json = entity.targetJson
            assertNotNull(entity.nodeId + " 必须带 targetJson", json)
            val expected = mapping.getValue(entity.nodeId)
            assertTrue(entity.nodeId + " 的 targetJson 必须含其 sectionId", json!!.contains(expected))
            assertTrue(
                entity.nodeId + " 不得出现字面 null sectionId",
                !json.contains("\"sectionId\":null")
            )
        }
    }
}
