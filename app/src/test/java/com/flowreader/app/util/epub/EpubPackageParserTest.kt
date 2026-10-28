package com.flowreader.app.util.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 / 主计划 4.1 第 1—2 步验收（T037、T042、T098 相关）。
 *
 * 夹具全部为自造 OPF/container XML，不含任何版权样书。
 */
class EpubPackageParserTest {

    private val opfPath = "OEBPS/content.opf"

    private val epub3Opf = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0"
                 unique-identifier="pub-id" page-progression-direction="rtl">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>测试书</dc:title>
            <dc:creator>测试作者</dc:creator>
            <meta property="rendition:layout">pre-paged</meta>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
            <item id="cover" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
            <item id="img1" href="Images/a.png" media-type="image/png"/>
            <item id="style" href="Styles/main.css" media-type="text/css"/>
          </manifest>
          <spine>
            <itemref idref="cover" linear="no"/>
            <itemref idref="c1"/>
            <itemref idref="c2"/>
          </spine>
        </package>
    """.trimIndent()

    @Test
    fun containerXmlYieldsTheOpfPath() {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val (path, diagnostics) = EpubPackageParser.parseRootFilePath(container)
        assertEquals("OEBPS/content.opf", path)
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun missingRootfileIsDiagnosedInsteadOfGuessed() {
        val (path, diagnostics) = EpubPackageParser.parseRootFilePath("<container/>")
        assertNull(path)
        assertEquals("CONTAINER_ROOTFILE_MISSING", diagnostics.single().code)
    }

    @Test
    fun manifestHrefsResolveAgainstTheOpfDirectory() {
        val pkg = EpubPackageParser.parse(epub3Opf, opfPath)
        // OPF 在 OEBPS/ 下，因此 Text/ch1.xhtml 的规范容器路径带 OEBPS 前缀。
        assertEquals("OEBPS/Text/ch1.xhtml", pkg.manifest.getValue("c1").containerPath)
        assertEquals("OEBPS/Images/a.png", pkg.manifest.getValue("img1").containerPath)
        assertEquals("OEBPS", pkg.baseDirectory)
        assertTrue(pkg.manifest.getValue("img1").isImage)
        assertTrue(pkg.manifest.getValue("c1").isXhtml)
        assertTrue(pkg.manifest.getValue("nav").isNav)
    }

    @Test
    fun linearNoEntriesAreAuxiliaryNotPartOfTheMainReadingOrder() {
        val pkg = EpubPackageParser.parse(epub3Opf, opfPath)
        // spine 里的顺序完整保留（含 linear=no）。
        assertEquals(listOf("cover", "c1", "c2"), pkg.spine.map { it.idref })
        assertEquals(listOf(false, true, true), pkg.spine.map { it.linear })

        // 主阅读顺序只含 linear yes/省略项。
        assertEquals(listOf("c1", "c2"), pkg.mainReadingOrder.map { it.id })
        assertEquals(listOf("cover"), pkg.auxiliaryReadingOrder.map { it.id })
    }

    @Test
    fun renditionAndProgressionMetadataAreRecorded() {
        val pkg = EpubPackageParser.parse(epub3Opf, opfPath)
        assertEquals("3.0", pkg.version)
        assertEquals("rtl", pkg.pageProgressionDirection)
        assertEquals("pre-paged", pkg.renditionLayout)
        // 固定版式必须被识别，UI 才能提示「统一排版兼容显示」（0.2 第 4 条）。
        assertTrue(pkg.isFixedLayout)
        assertEquals("pub-id", pkg.uniqueIdentifier)
    }

    @Test
    fun epub2NcxIsFoundThroughTheSpineTocAttribute() {
        val opf2 = """
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="c1"/>
              </spine>
            </package>
        """.trimIndent()

        val pkg = EpubPackageParser.parse(opf2, opfPath)
        assertEquals("2.0", pkg.version)
        assertEquals("ncx", pkg.spineTocId)
        assertEquals("OEBPS/toc.ncx", pkg.ncxItem?.containerPath)
        assertNull("EPUB2 没有 nav 文档", pkg.navItem)
        assertFalse(pkg.isFixedLayout)
    }

    @Test
    fun duplicateManifestIdAndDanglingItemrefAreDiagnosedNotSilentlyAccepted() {
        val broken = """
            <package version="3.0">
              <manifest>
                <item id="dup" href="Text/a.xhtml" media-type="application/xhtml+xml"/>
                <item id="dup" href="Text/b.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="dup"/>
                <itemref idref="missing"/>
              </spine>
            </package>
        """.trimIndent()

        val pkg = EpubPackageParser.parse(broken, opfPath)
        assertTrue(pkg.diagnostics.any { it.code == "DUPLICATE_MANIFEST_ID" })
        assertTrue(pkg.diagnostics.any { it.code == "SPINE_IDREF_UNKNOWN" })
        // 重复 id 保留第一个（与已记录的诊断一起），不随机任选。
        assertEquals("OEBPS/Text/a.xhtml", pkg.manifest.getValue("dup").containerPath)
        assertEquals(1, pkg.manifest.size)
    }

    @Test
    fun emptyManifestAndSpineAreDiagnosed() {
        val pkg = EpubPackageParser.parse("<package version=\"3.0\"></package>", opfPath)
        assertTrue(pkg.diagnostics.any { it.code == "MANIFEST_EMPTY" })
        assertTrue(pkg.diagnostics.any { it.code == "SPINE_EMPTY" })
    }
}
