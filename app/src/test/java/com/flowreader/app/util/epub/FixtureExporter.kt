package com.flowreader.app.util.epub

import org.junit.Test
import java.io.File

/**
 * 夹具导出工具。
 *
 * 把自造 EPUB 写到 `app/build/device-fixtures/`，供**真机端到端导入验证**使用
 * （单元测试用的是同一个 `EpubFixtureBuilder`，所以真机验的是同一份字节）。
 *
 * 走固定路径而不是 Gradle 传参：`-P` 无法可靠地透传进 fork 出来的测试 JVM，
 * 而这里的目标只是"让产物可复现地出现在已知位置"。产物在 `build/` 下，随 clean 消失。
 */
class FixtureExporter {

    @Test
    fun exportDeviceFixtures() {
        // Gradle 单测的工作目录是模块目录（app/）。
        val directory = File("build/device-fixtures").apply { mkdirs() }
        writeFx03(File(directory, "fx03.epub"))
        writeFx04(File(directory, "fx04.epub"))
        // 让产物可见：测试成功时也打印路径，便于人工核对。
        println("FIXTURES " + directory.absolutePath)
    }

    /** FX03：EPUB3，同 XHTML 三锚点、`<span>` 卷头、三层树、图片路径含 `+`。 */
    private fun writeFx03(target: File) {
        val builder = EpubFixtureBuilder()
        builder.addEntry("mimetype", "application/epub+zip")
        builder.addEntry(
            "META-INF/container.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/content.opf",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="pub-id">urn:uuid:fx03-device</dc:identifier>
                <dc:title>夹具三 山河初见</dc:title>
                <dc:creator>夹具作者</dc:creator>
                <dc:description>端到端导入验证用自造样书</dc:description>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="img" href="Images/fig+a.png" media-type="image/png"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/nav.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc"><ol>
                  <li><span>第一卷 山河</span><ol>
                    <li><a href="Text/ch1.xhtml#a1">第一章 风起</a><ol>
                      <li><a href="Text/ch1.xhtml#a2">第一节 夜行</a></li>
                    </ol></li>
                    <li><a href="Text/ch1.xhtml#a3">第二章 远行</a></li>
                  </ol></li>
                  <li><a href="Text/ch2.xhtml">第三章 归途</a></li>
                </ol></nav>
                <nav epub:type="landmarks"><ol><li><a epub:type="bodymatter" href="Text/ch1.xhtml">正文</a></li></ol></nav>
                <nav epub:type="page-list"><ol><li><a href="Text/ch1.xhtml#a1">1</a></li></ol></nav>
              </body>
            </html>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/Text/ch1.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>ch1</title></head>
              <body>
                <h1 id="a1">第一章 风起</h1>
                <p>山风从谷底卷上来，<strong>带着潮湿的凉意</strong>。他站在崖边，看见云在脚下翻涌。</p>
                <h2 id="a2">第一节 夜行</h2>
                <p>入夜之后路更难走，好在<em>月色还算明亮</em>。</p>
                <ul><li>带上水囊</li><li>检查绳索</li></ul>
                <table><tr><th>时辰</th><th>行程</th></tr><tr><td>子时</td><td>过溪</td></tr></table>
                <h2 id="a3">第二章 远行</h2>
                <p>他终究还是走了，留下一盏未灭的灯。</p>
                <figure><img src="../Images/fig+a.png" alt="山谷夜景"/><figcaption>图一 山谷夜景</figcaption></figure>
              </body>
            </html>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/Text/ch2.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>ch2</title></head>
              <body>
                <h1 id="b1">第三章 归途</h1>
                <p>多年以后他回到这里，<a href="ch1.xhtml#a2">那一夜的路</a>已经长满了草。</p>
                <p>脚注引用<sup><a epub:type="noteref" href="#fn1">1</a></sup>，主线文字不受影响。</p>
                <aside id="fn1"><p>注一：这是脚注文字，不计入主线进度。</p></aside>
              </body>
            </html>
            """.trimIndent()
        )
        // 一张真实的 1x1 PNG（足以验证资源落盘与解码路径，不含版权内容）。
        builder.addEntry("OEBPS/Images/fig+a.png", MINIMAL_PNG)
        builder.writeTo(target)
    }

    /** FX04：EPUB2 + NCX、同名不同锚点、Unicode 锚点。 */
    private fun writeFx04(target: File) {
        val builder = EpubFixtureBuilder()
        builder.addEntry("mimetype", "application/epub+zip")
        builder.addEntry(
            "META-INF/container.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/content.opf",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="pub-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="pub-id">urn:uuid:fx04-device</dc:identifier>
                <dc:title>夹具四 卷与注</dc:title>
                <dc:creator>夹具作者</dc:creator>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c1" href="Text/one.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="c1"/></spine>
            </package>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/toc.ncx",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="n1"><navLabel><text>卷首</text></navLabel><content src="Text/one.xhtml#p1"/>
                  <navPoint id="n2"><navLabel><text>同名</text></navLabel><content src="Text/one.xhtml#p2"/></navPoint>
                </navPoint>
                <navPoint id="n3"><navLabel><text>同名</text></navLabel><content src="Text/one.xhtml#%E6%B3%A81"/></navPoint>
              </navMap>
            </ncx>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/Text/one.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <h1 id="p1">卷首</h1><p>这一节在卷首之下。</p>
                <h2 id="p2">同名</h2><p>同名小节之一。</p>
                <h2 id="注1">注一节</h2><p>Unicode 锚点指向这里。</p>
              </body>
            </html>
            """.trimIndent()
        )
        builder.writeTo(target)
    }

    private companion object {
        /** 1×1 透明 PNG。 */
        val MINIMAL_PNG: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
            0x42, 0x60, 0x82.toByte()
        )
    }
}
