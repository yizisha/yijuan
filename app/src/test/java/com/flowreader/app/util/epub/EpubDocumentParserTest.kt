package com.flowreader.app.util.epub

import com.flowreader.app.domain.model.BlockRole
import com.flowreader.app.domain.model.DocumentDiagnostic
import com.flowreader.app.domain.model.FigureBlock
import com.flowreader.app.domain.model.HeadingBlock
import com.flowreader.app.domain.model.ImageBlock
import com.flowreader.app.domain.model.InlineSpanKind
import com.flowreader.app.domain.model.ListBlock
import com.flowreader.app.domain.model.ParagraphBlock
import com.flowreader.app.domain.model.PreformattedBlock
import com.flowreader.app.domain.model.QuoteBlock
import com.flowreader.app.domain.model.ResourceTargetKind
import com.flowreader.app.domain.model.StructuredDocument
import com.flowreader.app.domain.model.TableBlock
import com.flowreader.app.domain.model.TextUnits
import com.flowreader.app.domain.model.UnsupportedBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 / 主计划 3.2 规范源文本 v1、4.5 结构化正文合同、8.1 锚点索引验收
 * （T002、T003、T004、T005、T006、T035、T048、T057、T058、T093、T094）。
 *
 * 夹具全部为自造 XHTML 片段，不含任何版权样书。
 *
 * **本文件里最关键的一条是 [minimalSourceTextGolden]**：它是计划 14.1 指定的最小源文本 golden，
 * 用来确认全系统对"一个字"的理解一致。任何一条断言放宽都等于放弃单位一致性。
 */
class EpubDocumentParserTest {

    private val documentKey = "OEBPS/Text/ch1.xhtml"

    private fun parse(xhtml: String, key: String = documentKey): StructuredDocument =
        EpubDocumentParser.parse(key, wrap(xhtml))

    private fun wrap(body: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <head><title>测试</title></head>
          <body>$body</body>
        </html>
    """.trimIndent()

    private fun diagnosticCodes(document: StructuredDocument): List<String> = document.diagnostics.map { it.code }

    // ------------------------------------------------------------ 14.1 最小源文本 golden

    @Test
    fun minimalSourceTextGolden() {
        val document = parse("""<p id="p1">甲<strong>乙</strong>😀<img src="../Images/a+b.png" alt="图"/>丙</p>""")

        // 规范文本：甲乙😀\uFFFC丙\n —— 图片占一个 U+FFFC，段末一个 LF。
        assertEquals("甲乙😀\uFFFC丙\n", document.text)
        // UTF-16 长度 7：甲1 乙1 😀2 FFFC1 丙1 LF1。
        assertEquals(7, document.textLengthUtf16)
        // 有效计数 4：甲、乙、😀（code point）、丙；U+FFFC 与 LF 不计。
        assertEquals(4L, document.mainTextUnits)
        assertEquals(4L, TextUnits.count(document.text))

        // p1 起点 0。
        assertEquals(0, document.anchorIndex.getValue("p1").offsetUtf16)

        val paragraph = document.blocks.single() as ParagraphBlock
        assertEquals("ParagraphBlock", paragraph::class.simpleName)
        assertEquals(0, paragraph.range.startUtf16)

        // 图对象范围 [4,5)：唯一的 U+FFFC 落在偏移 4。
        assertEquals('\uFFFC', document.text[4])
        // 丙范围 [5,6)；强强调范围 [1,2)。
        val strong = paragraph.spans.single { it.kind == InlineSpanKind.STRONG }
        assertEquals(1, strong.range.startUtf16)
        assertEquals(2, strong.range.endUtf16Exclusive)
        assertEquals("乙", document.text.substring(1, 2))
        assertEquals("丙", document.text.substring(5, 6))

        // 图片引用按引用它的文档解析：`../Images/a+b.png` 从 `OEBPS/Text/` 上跳一层。
        val reference = document.resourceRefs.single()
        assertEquals("../Images/a+b.png", reference.raw)
        assertEquals(ResourceTargetKind.INTERNAL, reference.target)
        assertEquals("OEBPS/Images/a+b.png", reference.containerPath)
        // `+` 不得变成空格（F11）。
        assertTrue(reference.containerPath!!.contains("a+b.png"))
    }
    // ------------------------------------------------------------ 3.2 规则 2：空白与换行

    @Test
    fun collapsibleWhitespaceIsNormalizedButNbspIsKept() {
        val document = parse("<p>甲   \t 乙</p><p>丙\u00A0丁</p>")
        assertEquals("甲 乙\n丙\u00A0丁\n", document.text)
        // NBSP 是可见字符，参与计数；折叠出来的空格不参与。甲乙丙丁 + NBSP = 5。
        assertEquals(5L, document.mainTextUnits)
    }

    @Test
    fun crlfAndCrAreUnifiedToLf() {
        val document = parse("<pre>甲\r\n乙\r丙</pre>")
        assertEquals("甲\n乙\n丙\n", document.text)
        assertTrue(diagnosticsDoesNotContain(document, "INVALID_RANGE"))
    }

    @Test
    fun whitespaceAcrossInlineBoundariesCollapsesOnce() {
        val document = parse("<p>甲 <strong> 乙 </strong> 丙</p>")
        assertEquals("甲 乙 丙\n", document.text)
    }

    // ------------------------------------------------------------ 3.2 规则 4：LF、列表、表格

    @Test
    fun everyIndependentLeafBlockEndsWithOneLineFeed() {
        val document = parse("<h1>标题</h1><p>一段</p><p>二段</p>")
        assertEquals("标题\n一段\n二段\n", document.text)
        assertEquals(3, document.blocks.size)
    }

    @Test
    fun emptyBlocksDoNotProduceBlankLines() {
        val document = parse("<p>甲</p><p>   </p><p></p><p>乙</p>")
        assertEquals("甲\n乙\n", document.text)
        assertEquals(2, document.blocks.size)
    }

    @Test
    fun brInsideParagraphBecomesLineFeed() {
        val document = parse("<p>甲<br/>乙</p>")
        assertEquals("甲\n乙\n", document.text)
    }

    @Test
    fun listItemTextCountsButGeneratedMarkersDoNot() {
        val document = parse("<ol><li>甲</li><li>乙</li></ol>")
        assertEquals("甲\n乙\n", document.text)
        val list = document.blocks.single() as ListBlock
        assertTrue(list.ordered)
        assertEquals(2, list.items.size)
        assertEquals(1, list.items[0].level)
        assertEquals(2L, document.mainTextUnits)
    }

    @Test
    fun nestedListKeepsLevelsAndNestedItems() {
        val document = parse("<ul><li>甲<ul><li>乙</li></ul></li><li>丙</li></ul>")
        val list = document.blocks.single() as ListBlock
        assertEquals(2, list.items.size)
        val nested = list.items[0].children.single() as ListBlock
        assertEquals(1, nested.items.size)
        assertEquals(1, nested.items[0].level)
        assertEquals("甲\n乙\n丙\n", document.text)
    }

    @Test
    fun tableCellsUseTabsAndRowLineFeeds() {
        val document = parse(
            "<table><tr><th>甲</th><th>乙</th></tr><tr><td>丙</td><td>丁</td></tr></table>"
        )
        val table = document.blocks.single() as TableBlock
        assertEquals(2, table.rows.size)
        assertEquals(2, table.rows[0].cells.size)
        assertTrue(table.rows[0].cells[0].isHeader)
        assertEquals("甲", table.rows[0].cells[0].text)
        // 单元格间 TAB；行末 LF 由叶块收尾统一给出，生成的分隔符不计字数。
        assertEquals("甲\t乙\n丙\t丁\n", document.text)
        assertEquals(4L, document.mainTextUnits)
    }
    @Test
    fun tableSpanAttributesArePreserved() {
        val document = parse("<table><tr><td colspan=\"2\" rowspan=\"3\">甲</td></tr></table>")
        val cell = (document.blocks.single() as TableBlock).rows.single().cells.single()
        assertEquals(2, cell.colSpan)
        assertEquals(3, cell.rowSpan)
    }

    @Test
    fun preformattedKeepsWhitespace() {
        val document = parse("<pre>甲  乙\n  丙</pre>")
        val pre = document.blocks.single() as PreformattedBlock
        // 块内空白与换行保留（`pre` 不走可折叠空白归一）。
        //
        // 注意期望值里是 `\n丙` 而不是 `\n  丙`：Jsoup 的 **XML 解析器**会把换行后的行首缩进
        // 直接丢掉（`<pre>` 的 TextNode 就已经是 `甲  乙\n丙`），这不是本解析器改写源文本。
        // 因此 `pre` 的"缩进"在本链路里保不住，属于解析器能力边界，已在报告中登记；
        // 要恢复必须换成保留空白的 XML 前端。
        assertEquals("甲  乙\n丙", pre.text)
        // 字数口径（3.2 末段）是"非空白 code point"：甲乙丙 = 3，两个空格与换行都不计。
        assertEquals(3L, document.mainTextUnits)
    }

    // ------------------------------------------------------------ 3.2 规则 5：图片是对象

    @Test
    fun imageBetweenTextDoesNotSwallowAdjacentText() {
        val document = parse("<p>前<img src=\"Images/x.png\" alt=\"说明\"/>后</p>")
        assertEquals("前\uFFFC后\n", document.text)
        assertEquals(2L, document.mainTextUnits)
        // alt 只在元数据里，不混进正文字符计数。
        assertTrue(document.diagnostics.any { it.code == "IMAGE_ALT" && it.detail == "说明" })
    }

    @Test
    fun blockLevelImageKeepsItsObjectReplacement() {
        val document = parse("<div><img src=\"Images/x.png\" alt=\"图\"/></div>")
        val image = document.blocks.single() as ImageBlock
        assertEquals("\uFFFC\n", document.text)
        assertEquals(0, image.objectOffsetUtf16)
        // `Images/x.png` 相对 `OEBPS/Text/` 解析。
        assertEquals("OEBPS/Text/Images/x.png", image.reference.containerPath)
        assertEquals(0L, document.mainTextUnits)
    }

    @Test
    fun figureKeepsImageAndCaption() {
        val document = parse(
            "<figure><img src=\"../Images/p.png\" alt=\"插图\"/><figcaption>图 1 说明</figcaption></figure>"
        )
        val figure = document.blocks.single() as FigureBlock
        assertNotNull(figure.image)
        assertEquals("OEBPS/Images/p.png", figure.image!!.reference.containerPath)
        assertEquals("图 1 说明", figure.caption)
        // 图占一个 U+FFFC，随后是图注文字与收尾 LF。
        assertEquals("\uFFFC图 1 说明\n", document.text)
        assertEquals(4L, document.mainTextUnits)
    }

    @Test
    fun rejectedImageReferenceIsDiagnosedNotSilentlyDropped() {
        val document = parse("<p>甲<img src=\"javascript:alert(1)\"/>乙</p>")
        val reference = document.resourceRefs.single()
        assertEquals(ResourceTargetKind.REJECTED, reference.target)
        assertEquals("UNSUPPORTED_MEDIA", reference.errorCode)
        assertTrue(diagnosticCodes(document).contains("IMAGE_REFERENCE_REJECTED"))
        // 图片对象位置仍然存在，不能静默消失。
        assertEquals("甲\uFFFC乙\n", document.text)
    }

    @Test
    fun externalImageIsRecognisedButNotFetched() {
        val document = parse("<p>甲<img src=\"https://example.com/x.png\"/>乙</p>")
        val reference = document.resourceRefs.single()
        assertEquals(ResourceTargetKind.EXTERNAL, reference.target)
        assertEquals("https", reference.scheme)
        assertNull(reference.containerPath)
    }

    // ------------------------------------------------------------ 3.2 规则 6：ruby

    @Test
    fun rubyKeepsBaseInMainTextAndAnnotationSeparately() {
        val document = parse("<p><ruby>漢<rt>かん</rt></ruby>字</p>")
        // 主文本只留基字，注音不重复计入。
        assertEquals("漢字\n", document.text)
        assertEquals(2L, document.mainTextUnits)

        val paragraph = document.blocks.single() as ParagraphBlock
        val base = paragraph.spans.single { it.kind == InlineSpanKind.RUBY_BASE }
        assertEquals("漢", document.text.substring(base.range.startUtf16, base.range.endUtf16Exclusive))
        val annotation = paragraph.spans.single { it.kind == InlineSpanKind.RUBY_TEXT }
        assertEquals(listOf("かん"), annotation.annotations)
    }

    // ------------------------------------------------------------ schema v3：呈现标注在唯一创建点收集

    @Test
    fun listMarksAreCollectedWithListLevelInfoAndItemOffsets() {
        val document = parse("<ol start=\"3\"><li>甲</li><li value=\"9\">乙</li></ol>")
        val list = document.blocks.single() as ListBlock

        assertEquals(2, document.lists.size)
        val first = document.lists[0]
        assertEquals(list.blockId, first.listBlockId)
        assertEquals(list.items[0].blockId, first.blockId)
        assertEquals("标注偏移必须等于该项的源起始偏移", list.items[0].range.startUtf16, first.offsetUtf16)
        assertTrue(first.ordered)
        assertEquals(3, first.listStart)
        assertEquals(1, first.level)
        assertNull(first.itemValue)
        assertEquals(9, document.lists[1].itemValue)
        // 空项（无可见文字）不产生标注。
        val empty = parse("<ul><li>　</li></ul>")
        assertEquals(0, empty.lists.size)
    }

    @Test
    fun nestedListMarksUseDomDepthForDisplayLevel() {
        val document = parse("<ul><li>甲<ul><li>乙</li></ul></li><li>丙</li></ul>")

        // 外层的甲、丙 + 嵌套的乙 = 3 条标注。
        assertEquals(3, document.lists.size)
        val outer = document.lists.first { it.offsetUtf16 == 0 }
        val nested = document.lists.first { it.offsetUtf16 != 0 }
        assertEquals("外层列表项显示层级 = 1", 1, outer.level)
        assertEquals("嵌套列表项显示层级 = 2（DOM 深度；ListItem.level 仍为 1，语义不同）", 2, nested.level)
        assertTrue("嵌套列表是独立分组（独立编号）", nested.listBlockId != outer.listBlockId)
    }

    @Test
    fun tableMarksCarryHeaderAndCellRanges() {
        val document = parse("<table><tr><th>甲</th><td>乙</td></tr></table>")
        val table = document.blocks.single() as TableBlock

        assertEquals(1, document.tables.size)
        val mark = document.tables.single()
        assertEquals(table.blockId, mark.blockId)
        assertEquals(table.rows.size, mark.rows.size)
        val markRow = mark.rows.single()
        assertEquals(2, markRow.cells.size)
        assertTrue(markRow.cells[0].isHeader)
        assertTrue(markRow.cells[1].isHeader.not())
        assertEquals(table.rows.single().cells[0].range.startUtf16, markRow.cells[0].startUtf16)
        assertEquals(table.rows.single().cells[0].range.endUtf16Exclusive, markRow.cells[0].endUtf16Exclusive)
        assertEquals(1, markRow.cells[0].colSpan)
    }

    @Test
    fun rubyMarksCarryBaseRangeAndAnnotationsWithoutEnteringText() {
        val document = parse("<p>前<ruby>漢字<rt>かんじ</rt></ruby>后</p>")

        assertEquals(1, document.ruby.size)
        val mark = document.ruby.single()
        assertEquals("漢字", document.text.substring(mark.baseStartUtf16, mark.baseEndUtf16Exclusive))
        assertEquals(listOf("かんじ"), mark.annotations)
        // 注音不进主文本（3.2 第 6 条）：正文里没有注音字符。
        assertTrue(document.text.contains("かん").not())
        // 无注音的 ruby（只有基字）不产生标注。
        val baseOnly = parse("<p><ruby>漢</ruby>字</p>")
        assertEquals(0, baseOnly.ruby.size)
    }

    // ------------------------------------------------------------ 3.2 规则 7：脚注与辅助 role

    @Test
    fun footnoteTargetsBecomeAuxiliaryAndDoNotCount() {
        val document = parse(
            """
            <p>正文<sup><a epub:type="noteref" href="#fn1">1</a></sup></p>
            <aside id="fn1"><p>这是脚注文字</p></aside>
            """.trimIndent()
        )
        val mainParagraph = document.blocks[0] as ParagraphBlock
        assertEquals(BlockRole.MAIN, mainParagraph.role)
        val noteref = mainParagraph.spans.single { it.kind == InlineSpanKind.NOTEREF }
        assertNotNull(noteref.target)
        assertEquals("fn1", noteref.target!!.fragment)

        val footnote = document.blocks[1] as ParagraphBlock
        assertEquals(BlockRole.AUXILIARY, footnote.role)
        // 脚注文字保留源坐标（仍在源文本里），但不计入主线字数。
        assertTrue(document.text.contains("这是脚注文字"))
        // 正文 2 字 + 注号 1 字 = 3；脚注 6 字不计。
        assertEquals(3L, document.mainTextUnits)
    }

    @Test
    fun epubTypeFootnoteIsAuxiliaryEvenWithoutNoteref() {
        val document = parse("<p epub:type=\"footnote\">注释文字</p>")
        assertEquals(BlockRole.AUXILIARY, document.blocks.single().role)
        assertEquals(0L, document.mainTextUnits)
    }

    // ------------------------------------------------------------ 3.2 规则 8：不绘制的标签

    @Test
    fun scriptAndStyleNeverEnterTheTextStream() {
        val document = parse("<style>p{color:red}</style><p>甲</p><script>var a=1;</script><p>乙</p>")
        assertEquals("甲\n乙\n", document.text)
        assertEquals(2L, document.mainTextUnits)
    }

    // ------------------------------------------------------------ 3.2 规则 9 + 8.1：锚点

    @Test
    fun anchorIndexCoversIdsAndNamesAndStaysOnValidBoundaries() {
        val document = parse(
            """
            <h1 id="h1">标题</h1>
            <p>甲<a name="mark1"></a>乙</p>
            <p id="p3">丙</p>
            """.trimIndent()
        )
        assertEquals(0, document.anchorIndex.getValue("h1").offsetUtf16)
        // `mark1` 在标题块收尾之后：标题 2 字 + 1 个 LF = 3，段落首字之前。
        assertEquals(4, document.anchorIndex.getValue("mark1").offsetUtf16)
        assertTrue(document.anchorIndex.getValue("p3").offsetUtf16 > 0)
        // 所有锚点都落在合法 UTF-16 边界上。
        document.anchorIndex.values.forEach { anchor ->
            assertTrue(anchor.offsetUtf16 in 0..document.textLengthUtf16)
            assertTrue(epubBoundaryOk(document.text, anchor.offsetUtf16))
        }
    }

    @Test
    fun duplicateAnchorIsDiagnosedAndFirstOneWins() {
        val document = parse("<p id=\"dup\">甲</p><p id=\"dup\">乙</p>")
        assertTrue(diagnosticCodes(document).contains("DUPLICATE_ANCHOR"))
        assertEquals(0, document.anchorIndex.getValue("dup").offsetUtf16)
        assertEquals(1, document.anchorIndex.size)
    }

    // ------------------------------------------------------------ 4.5：标题、引用、不支持

    @Test
    fun headingLevelsArePreservedAndTitlesAreNotDeduplicated() {
        val document = parse("<h1>同名</h1><h2>同名</h2><h6>层六</h6>")
        val headings = document.blocks.filterIsInstance<HeadingBlock>()
        assertEquals(listOf(1, 2, 6), headings.map { it.level })
        // 3.2 第 3 条：不删"重复标题"。
        assertEquals(listOf("同名", "同名", "层六"), headings.map { document.text.substring(it.range.startUtf16, it.range.endUtf16Exclusive) })
    }

    @Test
    fun quoteKeepsNestingLevelAndChildren() {
        val document = parse("<blockquote><p>甲</p><blockquote><p>乙</p></blockquote></blockquote>")
        val outer = document.blocks.single() as QuoteBlock
        assertEquals(1, outer.level)
        assertEquals("甲\n乙\n", document.text)
        val inner = outer.children.filterIsInstance<QuoteBlock>().single()
        assertEquals(2, inner.level)
    }

    @Test
    fun unsupportedBlockKeepsFallbackTextAndDiagnostic() {
        val document = parse("<p>甲</p><object data=\"Images/x.svg\"><p>替代文字</p></object>")
        val unsupported = document.blocks.filterIsInstance<UnsupportedBlock>().single()
        assertEquals("object", unsupported.tagName)
        assertEquals("替代文字", unsupported.fallbackText)
        // 引用以**引用它的文档目录**为基准（4.2 第 2 条）：`Images/x.svg` 相对 `OEBPS/Text/`。
        assertEquals("OEBPS/Text/Images/x.svg", unsupported.reference?.containerPath)
        assertTrue(diagnosticCodes(document).contains("UNSUPPORTED_ELEMENT"))
    }

    @Test
    fun inlineStrongEmphasisNestsWithDepth() {
        val document = parse("<p><strong>甲<em>乙</em></strong>丙</p>")
        val paragraph = document.blocks.single() as ParagraphBlock
        val strong = paragraph.spans.single { it.kind == InlineSpanKind.STRONG }
        val em = paragraph.spans.single { it.kind == InlineSpanKind.EM }
        assertEquals("甲乙", document.text.substring(strong.range.startUtf16, strong.range.endUtf16Exclusive))
        assertEquals("乙", document.text.substring(em.range.startUtf16, em.range.endUtf16Exclusive))
        assertEquals(2, em.depth)
    }

    @Test
    fun linkTargetIsResolvedThroughThePathLogicOnce() {
        val document = parse("<p>甲<a href=\"ch2.xhtml#%E6%B3%A81\">乙</a></p>")
        val paragraph = document.blocks.single() as ParagraphBlock
        val link = paragraph.spans.single { it.kind == InlineSpanKind.LINK }
        assertEquals("OEBPS/Text/ch2.xhtml", link.target!!.containerPath)
        assertEquals("注1", link.target!!.fragment)
    }

    // ------------------------------------------------------------ INV02 / 11.2：确定性与上限

    @Test
    fun parsingTwiceProducesIdenticalBlocksAndAnchors() {
        val xhtml = wrap("<h1 id=\"h\">标题</h1><p>甲<img src=\"Images/a.png\"/>乙</p><p id=\"x\">丙</p>")
        val first = EpubDocumentParser.parse(documentKey, xhtml)
        val second = EpubDocumentParser.parse(documentKey, xhtml)
        assertEquals(first.text, second.text)
        assertEquals(first.blocks.map { it.blockId }, second.blocks.map { it.blockId })
        assertEquals(first.anchorIndex, second.anchorIndex)
        assertEquals(first.mainTextUnits, second.mainTextUnits)
    }

    @Test
    fun blockIdsAreStableHashesNotRandomValues() {
        val document = parse("<p>甲</p><p>乙</p>")
        document.blocks.forEach { block ->
            assertTrue("块 ID 必须是确定性摘要: ${block.blockId}", block.blockId.startsWith("b:"))
            assertEquals(26, block.blockId.length)
        }
        // 同名不同位置的块必须不同 ID（UI key 不能用文本）。
        assertTrue(document.blocks[0].blockId != document.blocks[1].blockId)
    }

    @Test
    fun excessiveNestingIsRejectedInsteadOfReturningHalfAParse() {
        val deep = StringBuilder()
        repeat(200) { deep.append("<div>") }
        deep.append("甲")
        repeat(200) { deep.append("</div>") }
        val document = parse(deep.toString())
        assertTrue(diagnosticCodes(document).contains("DOCUMENT_TOO_DEEP"))
        assertEquals("", document.text)
        assertTrue(document.blocks.isEmpty())
    }

    @Test
    fun nestingUpToTheLimitIsAccepted() {
        val deep = StringBuilder()
        repeat(100) { deep.append("<div>") }
        deep.append("甲")
        repeat(100) { deep.append("</div>") }
        val document = parse(deep.toString())
        assertTrue(diagnosticCodes(document).isEmpty())
        assertEquals("甲\n", document.text)
    }

    @Test
    fun missingBodyIsDiagnosedInsteadOfCrashing() {
        val document = EpubDocumentParser.parse(documentKey, "<html><head><title>x</title></head></html>")
        assertTrue(diagnosticCodes(document).contains("DOCUMENT_BODY_MISSING"))
        assertEquals("", document.text)
    }

    @Test
    fun sourceRangesNeverSplitSurrogatePairs() {
        val document = parse("<p>😀😀</p><p>a😀b</p>")
        document.blocks.forEach { block ->
            assertTrue(epubBoundaryOk(document.text, block.range.startUtf16))
            assertTrue(epubBoundaryOk(document.text, block.range.endUtf16Exclusive))
        }
        // emoji 各计 1 字：2 + 3 = 5。
        assertEquals(5L, document.mainTextUnits)
        assertEquals(2, document.blocks.size)
    }

    private fun diagnosticsDoesNotContain(document: StructuredDocument, code: String): Boolean =
        document.diagnostics.none { it.code == code }

    /** 偏移不落在代理对中间（INV04）。 */
    private fun epubBoundaryOk(text: String, offset: Int): Boolean {
        if (offset <= 0 || offset >= text.length) return true
        val previous = text[offset - 1]
        val current = text[offset]
        return !(Character.isHighSurrogate(previous) && Character.isLowSurrogate(current))
    }
}
