package com.flowreader.app.ui.screens.reader.components

import com.flowreader.app.domain.model.Annotation
import com.flowreader.app.domain.model.MultiBlockSelectionAssembler
import com.flowreader.app.domain.model.SourceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨块选择**端到端**装配测试（主计划第 9 节）。
 *
 * 与 domain 层的 `MultiBlockSelectionAssemblerTest`（手工映射）不同，本测试使用
 * **生产代码路径** `buildParagraphContent` 生成每段的 `DisplayMapping`，
 * 再用生产的选择模型 `CrossBlockSelectionModel` 串起区间，最后交给装配器。
 *
 * 这样才能证明「跨块批注不重复输出文字」在真实交互链路上成立。
 */
class CrossBlockAnnotationIntegrationTest {

    private val doc = "chapter:0"

    private fun buildParagraph(text: String, paragraphStart: Int, indent: Boolean = false): ParagraphContent =
        buildParagraphContent(
            paragraph = text,
            paragraphStart = paragraphStart,
            annotations = emptyList<Annotation>(),
            indent = indent,
            conversion = com.flowreader.app.domain.model.ChineseConversion.NONE,
            documentKey = doc,
            blockId = "b"
        )

    private fun provider(fullText: String): (String) -> String? = { key ->
        if (key == doc) fullText else null
    }

    private fun assemble(
        model: CrossBlockSelectionModel,
        mappings: Map<String, com.flowreader.app.domain.model.DisplayMapping>,
        fullText: String
    ) = MultiBlockSelectionAssembler.assemble(
        model.selectedBlocks().mapNotNull { (id, range) ->
            mappings[id]?.let { MultiBlockSelectionAssembler.SelectedSegment(it, range.first, range.last + 1) }
        },
        provider(fullText)
    )

    @Test
    fun crossParagraphSelectionProducesExactTextWithoutDuplicationOrLoss() {
        val p1 = "清晨的雾气笼罩着整座山谷，远处传来几声鸟鸣。"
        val p2 = "少年背起行囊，沿着溪流一路向南走去。"
        val p3 = "他并不知道，前方等待他的是怎样一段旅程。"

        val start1 = 0
        val start2 = start1 + p1.length + 2
        val start3 = start2 + p2.length + 2
        val fullText = p1 + "\n\n" + p2 + "\n\n" + p3

        val c1 = buildParagraph(p1, start1)
        val c2 = buildParagraph(p2, start2, indent = true)
        val c3 = buildParagraph(p3, start3, indent = true)
        val m1 = c1.displayMapping!!
        val m2 = c2.displayMapping!!
        val m3 = c3.displayMapping!!

        val model = CrossBlockSelectionModel()
        model.registerBlock("b0", m1.displayText.length)
        model.registerBlock("b1", m2.displayText.length)
        model.registerBlock("b2", m3.displayText.length)
        model.beginAt(CrossBlockSelectionModel.Point("b0", 5))
        model.extendTo(CrossBlockSelectionModel.Point("b2", m3.displayText.length - 3))

        val result = assemble(model, mapOf("b0" to m1, "b1" to m2, "b2" to m3), fullText)
        assertNotNull("装配必须成功", result)
        val assembled = result!!

        // 段落之间的分隔属于源文本，三段应连成一条连续区间。
        assertEquals(1, assembled.ranges.size)
        val range = assembled.ranges[0]
        assertEquals(start1 + 5, range.startUtf16)

        // 文本必须与范围严格自洽。
        assertEquals(range.lengthUtf16, assembled.text.length)
        assertEquals(fullText.substring(range.startUtf16, range.endUtf16Exclusive), assembled.text)

        // 人工缩进不得进入文本。
        assertFalse("人工全角空格不得出现在批注文本里", assembled.text.contains("\u3000"))

        // 不重复：首段被选中的文字恰好出现一次。
        val firstSentence = p1.substring(5)
        var occurrences = 0
        var index = assembled.text.indexOf(firstSentence)
        while (index >= 0) {
            occurrences++
            index = assembled.text.indexOf(firstSentence, index + 1)
        }
        assertEquals("首段被选中的文字必须恰好出现一次", 1, occurrences)
    }

    @Test
    fun contiguousCrossParagraphSelectionIncludesMiddleParagraphExactlyOnce() {
        val p1 = "第一段的内容比较短。"
        val p2 = "第二段在中间。"
        val p3 = "第三段的内容也很短。"
        val start1 = 0
        val start2 = start1 + p1.length + 2
        val start3 = start2 + p2.length + 2
        val fullText = p1 + "\n\n" + p2 + "\n\n" + p3

        val m1 = buildParagraph(p1, start1).displayMapping!!
        val m2 = buildParagraph(p2, start2).displayMapping!!
        val m3 = buildParagraph(p3, start3).displayMapping!!

        val model = CrossBlockSelectionModel()
        model.registerBlock("b0", m1.displayText.length)
        model.registerBlock("b1", m2.displayText.length)
        model.registerBlock("b2", m3.displayText.length)
        model.beginAt(CrossBlockSelectionModel.Point("b0", 2))
        model.extendTo(CrossBlockSelectionModel.Point("b2", 2))

        val assembled = assemble(model, mapOf("b0" to m1, "b1" to m2, "b2" to m3), fullText)!!

        // 用户从 p1 连续拖到 p3，中间没有空隙，所以 p2 整块属于选区。
        assertTrue("连续跨块选择应包含中间段", assembled.text.contains("第二段"))
        // 关键：不得重复。中间段只应出现一次。
        var occurrences = 0
        var index = assembled.text.indexOf("第二段")
        while (index >= 0) {
            occurrences++
            index = assembled.text.indexOf("第二段", index + 1)
        }
        assertEquals("中间段必须恰好出现一次", 1, occurrences)
        assertEquals(assembled.ranges.sumOf { it.lengthUtf16 }, assembled.text.length)
    }

    @Test
    fun disjointSelectionsKeepRangesSeparateAndExcludeSkippedText() {
        // RangeLocator 支持多段：用户先选一段、再独立选另一段时，
        // 中间未选中的内容绝不能被吞掉。
        val p0 = "甲乙丙丁戊己"
        val p2 = "庚辛壬癸子丑"
        val skipped = "中间不选的文字"
        val fullText = p0 + skipped + p2
        val start0 = 0
        val start2 = p0.length + skipped.length

        val m0 = buildParagraph(p0, start0).displayMapping!!
        val m2 = buildParagraph(p2, start2).displayMapping!!
        val assembled = MultiBlockSelectionAssembler.assemble(
            listOf(
                MultiBlockSelectionAssembler.SelectedSegment(m0, 0, 2),
                MultiBlockSelectionAssembler.SelectedSegment(m2, 0, 2)
            ),
            provider(fullText)
        )!!

        assertEquals("两段不相连的选区必须保持分离", 2, assembled.ranges.size)
        assertEquals(SourceRange(doc, start0, start0 + 2), assembled.ranges[0])
        assertEquals(SourceRange(doc, start2, start2 + 2), assembled.ranges[1])
        assertFalse("中间未选中的文字绝不能被包含", assembled.text.contains("中间"))
    }

    @Test
    fun singleParagraphSelectionMatchesBetweenModelAndAssembler() {
        val p = "一句话的测试内容。"
        val mapping = buildParagraph(p, 0).displayMapping!!
        val model = CrossBlockSelectionModel()
        model.registerBlock("b0", mapping.displayText.length)
        model.beginAt(CrossBlockSelectionModel.Point("b0", 1))
        model.extendTo(CrossBlockSelectionModel.Point("b0", 4))

        val range = model.rangeFor("b0")!!
        val assembled = MultiBlockSelectionAssembler.assemble(
            listOf(MultiBlockSelectionAssembler.SelectedSegment(mapping, range.first, range.last + 1)),
            provider(p)
        )!!
        assertEquals(SourceRange(doc, 1, 4), assembled.ranges[0])
        assertEquals(p.substring(1, 4), assembled.text)
    }
}
