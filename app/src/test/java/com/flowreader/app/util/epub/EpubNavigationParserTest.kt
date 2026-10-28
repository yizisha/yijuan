package com.flowreader.app.util.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 / 主计划 4.3 验收（T032—T038）。
 *
 * 全部为自造可公开的 XHTML/NCX 片段，不涉及任何版权样书。
 */
class EpubNavigationParserTest {

    private val navPath = "OEBPS/nav.xhtml"

    // ---------------------------------------------------------------- EPUB3 树结构

    @Test
    fun spanVolumeHeadingIsKeptAsGroupAndChildrenKeepTheirAnchors() {
        // 旧实现（F06）用后代选择器把 a 扁平化，<span> 卷头直接消失。
        val nav = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc">
                  <ol>
                    <li><span>第一卷 山河初见</span>
                      <ol>
                        <li><a href="Text/ch1.xhtml">第一章 风起</a></li>
                        <li><a href="Text/ch2.xhtml">第二章 夜行</a></li>
                      </ol>
                    </li>
                    <li><span>第二卷 远行</span>
                      <ol>
                        <li><a href="Text/ch3.xhtml">第三章 出关</a></li>
                      </ol>
                    </li>
                  </ol>
                </nav>
              </body>
            </html>
        """.trimIndent()

        val result = EpubNavigationParser.parseEpub3Nav(nav, navPath)
        assertEquals(2, result.main.size)

        val first = result.main[0]
        assertEquals("第一卷 山河初见", first.label)
        assertEquals(EpubNavigationParser.Node.Kind.GROUP, first.kind)
        assertTrue("卷必须是 GROUP 且带 VOLUME 语义", first.isVolume)
        assertEquals("无链接的卷头不应有 target", null, first.targetPath)
        assertEquals(2, first.children.size)
        assertEquals("第一章 风起", first.children[0].label)
        assertEquals("OEBPS/Text/ch1.xhtml", first.children[0].targetPath)

        val second = result.main[1]
        assertEquals("第二卷 远行", second.label)
        assertEquals(1, second.children.size)
    }

    @Test
    fun threeLevelNestingPreservesParentChildOrder() {
        val nav = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="toc"><ol>
              <li><span>卷甲</span><ol>
                <li><span>分卷甲一</span><ol>
                  <li><a href="a.xhtml">章一</a></li>
                </ol></li>
              </ol></li>
            </ol></nav></body></html>
        """.trimIndent()

        val result = EpubNavigationParser.parseEpub3Nav(nav, navPath)
        val level1 = result.main.single()
        val level2 = level1.children.single()
        val level3 = level2.children.single()
        assertEquals("卷甲", level1.label)
        assertEquals("分卷甲一", level2.label)
        assertEquals("章一", level3.label)
        assertEquals(EpubNavigationParser.Node.Kind.ENTRY, level3.kind)

        // 展平后 parentId 必须指向上层，siblingOrder 从 0 起。
        val flat = EpubNavigationParser.flatten(result.main)
        assertEquals(3, flat.size)
        assertEquals(null, flat[0].parentId)
        assertEquals(level1.nodeId, flat[1].parentId)
        assertEquals(level2.nodeId, flat[2].parentId)
        assertEquals(0, flat[2].siblingOrder)
    }

    @Test
    fun landmarksAndPageListDoNotPolluteTheMainToc() {
        val nav = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="toc"><ol><li><a href="a.xhtml">正文起始</a></li></ol></nav>
            <nav epub:type="landmarks"><ol><li><a href="cover.xhtml">封面</a></li></ol></nav>
            <nav epub:type="page-list"><ol><li><a href="a.xhtml#p1">1</a></li></ol></nav>
            </body></html>
        """.trimIndent()

        val result = EpubNavigationParser.parseEpub3Nav(nav, navPath)
        assertEquals("主目录只能有一个条目", 1, result.main.size)
        assertEquals("正文起始", result.main[0].label)
        assertEquals(1, result.landmarks.size)
        assertEquals("封面", result.landmarks[0].label)
        assertEquals(1, result.pageList.size)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun multipleTocNavsAreDiagnosedInsteadOfConcatenated() {
        val nav = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="toc"><ol><li><a href="a.xhtml">甲</a></li></ol></nav>
            <nav epub:type="toc"><ol><li><a href="b.xhtml">乙</a></li></ol></nav>
            </body></html>
        """.trimIndent()

        val result = EpubNavigationParser.parseEpub3Nav(nav, navPath)
        assertTrue(result.diagnostics.any { it.code == "TOC_NAV_AMBIGUOUS" })
        // 只取第一个，绝不把两个目录拼起来。
        assertEquals(1, result.main.size)
        assertEquals("甲", result.main[0].label)
    }

    @Test
    fun missingTocNavIsDiagnosed() {
        val nav = """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="landmarks"><ol><li><a href="cover.xhtml">封面</a></li></ol></nav>
        </body></html>"""
        val result = EpubNavigationParser.parseEpub3Nav(nav, navPath)
        assertTrue(result.diagnostics.any { it.code == "TOC_NAV_MISSING" })
        assertTrue(result.main.isEmpty())
    }

    // ---------------------------------------------------------------- F05：同文档多锚点

    @Test
    fun twoAnchorsInOneDocumentBecomeDistinctNodes() {
        // 旧实现把两者都变成 "OEBPS/Text/b.xhtml"，Map 里后者覆盖前者（F05）。
        val nav = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="toc"><ol>
              <li><a href="Text/b.xhtml#note1">注一</a></li>
              <li><a href="Text/b.xhtml#note2">注二</a></li>
            </ol></nav></body></html>
        """.trimIndent()

        val result = EpubNavigationParser.parseEpub3Nav(nav, navPath)
        assertEquals(2, result.main.size)
        assertEquals("OEBPS/Text/b.xhtml", result.main[0].targetPath)
        assertEquals("OEBPS/Text/b.xhtml", result.main[1].targetPath)
        assertEquals("note1", result.main[0].targetFragment)
        assertEquals("note2", result.main[1].targetFragment)
        assertNotEquals(
            "同一文档的不同锚点必须是不同节点",
            result.main[0].nodeId,
            result.main[1].nodeId
        )
    }

    @Test
    fun aliasEntriesToTheSameTargetAreBothPreserved() {
        // 指向同一目标的别名条目也要保留（不能覆盖）。
        val nav = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="toc"><ol>
              <li><a href="Text/c.xhtml">正传</a></li>
              <li><a href="Text/c.xhtml">番外</a></li>
            </ol></nav></body></html>
        """.trimIndent()

        val result = EpubNavigationParser.parseEpub3Nav(nav, navPath)
        assertEquals(2, result.main.size)
        assertEquals("正传", result.main[0].label)
        assertEquals("番外", result.main[1].label)
        assertNotEquals(result.main[0].nodeId, result.main[1].nodeId)
    }

    @Test
    fun idsAreDeterministicAcrossParses() {
        val nav = """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="toc"><ol><li><a href="a.xhtml">甲</a></li></ol></nav></body></html>"""
        val first = EpubNavigationParser.parseEpub3Nav(nav, navPath)
        val second = EpubNavigationParser.parseEpub3Nav(nav, navPath)
        assertEquals("同一输入两次解析必须完全一致（INV02）", first.main[0].nodeId, second.main[0].nodeId)
    }

    @Test
    fun sameLabelAtDifferentPositionsGetsDifferentIds() {
        val nav = """<html xmlns:epub="http://www.idpf.org/2007/ops"><body>
            <nav epub:type="toc"><ol>
              <li><a href="a.xhtml">序章</a></li>
              <li><a href="b.xhtml">序章</a></li>
            </ol></nav></body></html>"""
        val result = EpubNavigationParser.parseEpub3Nav(nav, navPath)
        assertEquals("序章", result.main[0].label)
        assertEquals("序章", result.main[1].label)
        assertNotEquals(
            "同名不同位置必须不同 ID（UI key 不能用 label）",
            result.main[0].nodeId,
            result.main[1].nodeId
        )
    }

    // ---------------------------------------------------------------- EPUB2 NCX

    @Test
    fun ncxNestedLabelsDoNotSwallowChildText() {
        val ncx = """
            <?xml version="1.0"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
              <navMap>
                <navPoint id="v1">
                  <navLabel><text>第一卷</text></navLabel>
                  <content src="Text/v1.xhtml"/>
                  <navPoint id="c1">
                    <navLabel><text>第一章</text></navLabel>
                    <content src="Text/c1.xhtml#%E6%B3%A81"/>
                  </navPoint>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent()

        val result = EpubNavigationParser.parseEpub2Ncx(ncx, "OEBPS/toc.ncx")
        val volume = result.main.single()
        // 父标题只能是自己的 label，不能把「第一章」拼进去。
        assertEquals("第一卷", volume.label)
        assertEquals("OEBPS/Text/v1.xhtml", volume.targetPath)
        val chapter = volume.children.single()
        assertEquals("第一章", chapter.label)
        // Unicode fragment 必须保留并解码一次。
        assertEquals("注1", chapter.targetFragment)
    }

    @Test
    fun ncxWithNoNavMapIsDiagnosed() {
        val result = EpubNavigationParser.parseEpub2Ncx("<ncx/>", "OEBPS/toc.ncx")
        assertTrue(result.diagnostics.any { it.code == "NCX_NAVMAP_MISSING" })
        assertTrue(result.main.isEmpty())
    }
}
