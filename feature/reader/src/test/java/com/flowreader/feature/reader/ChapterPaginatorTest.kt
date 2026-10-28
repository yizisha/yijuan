package com.flowreader.feature.reader

import com.flowreader.app.domain.model.Affinity
import com.flowreader.app.domain.model.HeadingBlock
import com.flowreader.app.domain.model.ParagraphBlock
import com.flowreader.app.domain.model.SourceRange
import com.flowreader.app.domain.model.SourceSpan
import com.flowreader.app.domain.model.StructuredDocument
import com.flowreader.app.domain.model.TextLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterPaginatorTest {

    /** Fake measurement: height = text.length * 10px (titles count 2x). */
    private fun paginator() = ChapterPaginator { text, _, _, isTitle ->
        text.length * 10 * (if (isTitle) 2 else 1)
    }

    private fun block(text: String, offset: Int = 0, title: Boolean = false, indent: Boolean = false) =
        Block(text = text, offset = offset, isTitle = title, indent = indent)

    @Test
    fun shortParagraphsShareOnePage() {
        val pages = paginator().paginate(
            blocks = listOf(block("a".repeat(5)), block("b".repeat(5), offset = 5)),
            pageHeightPx = 100,
            gapPx = 0
        )
        assertEquals(1, pages.size)
        assertEquals(2, pages[0].size)
        assertEquals(0, pages[0][0].paragraphStart)
        assertEquals(5, pages[0][1].paragraphStart)
    }

    @Test
    fun longParagraphSplitsAcrossPages() {
        val pages = paginator().paginate(
            blocks = listOf(block("x".repeat(60))),
            pageHeightPx = 100,
            gapPx = 0
        )
        // 10 chars per page (100px): 6 full pages
        assertEquals(6, pages.size)
        pages.forEach { page ->
            assertEquals(10, page[0].text.length)
        }
        // absolute offsets stay monotonic across the fragments
        assertEquals(0, pages[0][0].paragraphStart)
        assertEquals(10, pages[1][0].paragraphStart)
        assertEquals(50, pages[5][0].paragraphStart)
    }

    @Test
    fun blockTallerThanPageSplitsAndOverflowFlows() {
        val pages = paginator().paginate(
            blocks = listOf(block("x".repeat(13))),
            pageHeightPx = 100,
            gapPx = 0
        )
        assertEquals(2, pages.size)
        assertEquals(10, pages[0][0].text.length)
        assertEquals(3, pages[1][0].text.length)
        assertEquals(10, pages[1][0].paragraphStart)
    }

    @Test
    fun aBlockThatDoesNotFitIsSplitToFillTheRemainingSpace() {
        val pages = paginator().paginate(
            blocks = listOf(block("a".repeat(6)), block("b".repeat(6), offset = 6)),
            pageHeightPx = 70,
            gapPx = 0
        )
        // "a" = 60px, leaving 10px: four characters of "b" fill it (the rest flows to page 2).
        assertEquals(2, pages.size)
        assertEquals("a".repeat(6), pages[0][0].text)
        assertEquals("b".repeat(1), pages[0][1].text)
        assertEquals("b".repeat(5), pages[1][0].text)
        assertEquals(7, pages[1][0].paragraphStart)
    }

    @Test
    fun titleSitsOnFirstPageWithFollowingContent() {
        val pages = paginator().paginate(
            blocks = listOf(
                block("章节", title = true),
                block("c".repeat(5), offset = 2, indent = true)
            ),
            pageHeightPx = 100,
            gapPx = 0
        )
        assertEquals(1, pages.size)
        assertTrue(pages[0][0].isTitle)
        assertTrue(pages[0][1].indent)
        assertEquals(2, pages[0][1].paragraphStart)
    }

    @Test
    fun emptyBlocksProduceNoPages() {
        assertTrue(paginator().paginate(emptyList(), 100, 0).isEmpty())
        assertTrue(paginator().paginate(listOf(Block("", 0)), 100, 0).isEmpty())
    }

    @Test
    fun zeroPageHeightProducesNoPages() {
        assertTrue(paginator().paginate(listOf(block("abc")), 0, 0).isEmpty())
    }

    @Test
    fun gapReducesHowMuchFitsPerPage() {
        val blocks = listOf(
            block("a".repeat(3)),
            block("b".repeat(3), offset = 3),
            block("c".repeat(3), offset = 6)
        )
        // 30px per block: without gap all three fit on one page; with 20px gap only two do
        assertEquals(1, paginator().paginate(blocks, 100, 0).size)
        assertEquals(2, paginator().paginate(blocks, 100, 20).size)
    }

    @Test
    fun aPageIsFilledWithTheStartOfATooTallBlock() {
        // Regression: a chapter whose body is one long paragraph (common in EPUB imports) used to
        // leave the title alone on page 1 and start the body on page 2.
        val pages = paginator().paginate(
            blocks = listOf(
                block("章", title = true),
                block("x".repeat(30), offset = 1)
            ),
            pageHeightPx = 100,
            gapPx = 0
        )
        assertEquals(4, pages.size)
        // Title (20px) + 8 characters (80px) on the first page.
        assertEquals(2, pages[0].size)
        assertTrue(pages[0][0].isTitle)
        assertEquals(8, pages[0][1].text.length)
        assertEquals(1, pages[0][1].paragraphStart)
        // The remainder continues on the next page without losing a character.
        assertEquals(9, pages[1][0].paragraphStart)
        assertEquals(30, pages.sumOf { page -> page.filterNot { it.isTitle }.sumOf { it.text.length } })
    }

    @Test
    fun aBlockThatCannotFitEvenOneLineStillStartsOnTheNextPage() {
        val pages = paginator().paginate(
            blocks = listOf(
                block("a".repeat(9)),
                block("b".repeat(9), offset = 9)
            ),
            pageHeightPx = 100,
            gapPx = 0
        )
        // First page holds 10 characters of "a" (90px used), so only 10px remain — too little for
        // one character of "b" at 10px/char plus the zero gap is exactly enough, so "b" continues.
        assertEquals(2, pages.size)
        assertTrue(pages[1][0].text.startsWith("b"))
    }

    @Test
    fun paginatedFragmentsCarryPreciseSourceRanges() {
        val pages = paginator().paginate(
            blocks = listOf(
                Block(
                    text = "abcdefghij",
                    offset = 100,
                    sourceRanges = listOf(SourceRange("doc1.xhtml", 100, 110)),
                    blockId = "p_1"
                )
            ),
            pageHeightPx = 50,
            gapPx = 0
        )
        // 50px holds 5 characters
        assertEquals(2, pages.size)
        assertEquals("abcde", pages[0][0].text)
        assertEquals(1, pages[0][0].sourceRanges.size)
        assertEquals(100, pages[0][0].sourceRanges[0].startUtf16)
        assertEquals(105, pages[0][0].sourceRanges[0].endUtf16Exclusive)
        assertEquals("p_1", pages[0][0].blockId)

        assertEquals("fghij", pages[1][0].text)
        assertEquals(1, pages[1][0].sourceRanges.size)
        assertEquals(105, pages[1][0].sourceRanges[0].startUtf16)
        assertEquals(110, pages[1][0].sourceRanges[0].endUtf16Exclusive)
        assertEquals("p_1", pages[1][0].blockId)
    }

    @Test
    fun findPageForLocatorByBlockIdAndOffset() {
        val pages = paginator().paginate(
            blocks = listOf(
                Block("12345", offset = 0, sourceRanges = listOf(SourceRange("doc.xhtml", 0, 5)), blockId = "b1"),
                Block("67890", offset = 5, sourceRanges = listOf(SourceRange("doc.xhtml", 5, 10)), blockId = "b2")
            ),
            pageHeightPx = 50,
            gapPx = 0
        )
        assertEquals(2, pages.size)

        // Find by blockId
        val locatorByBlock = TextLocator(
            publicationId = "pub1",
            revision = "rev1",
            documentKey = "doc.xhtml",
            offsetUtf16 = 8,
            blockId = "b1"
        )
        assertEquals(0, paginator().findPageForLocator(pages, locatorByBlock))

        // Find by offset AFTER
        val locatorByOffset1 = TextLocator(
            publicationId = "pub1",
            revision = "rev1",
            documentKey = "doc.xhtml",
            offsetUtf16 = 7
        )
        assertEquals(1, paginator().findPageForLocator(pages, locatorByOffset1))

        // Find by offset BEFORE at boundary (offset 5)
        val locatorBoundaryBefore = TextLocator(
            publicationId = "pub1",
            revision = "rev1",
            documentKey = "doc.xhtml",
            offsetUtf16 = 5,
            affinity = Affinity.BEFORE
        )
        assertEquals(0, paginator().findPageForLocator(pages, locatorBoundaryBefore))

        val locatorBoundaryAfter = TextLocator(
            publicationId = "pub1",
            revision = "rev1",
            documentKey = "doc.xhtml",
            offsetUtf16 = 5,
            affinity = Affinity.AFTER
        )
        assertEquals(1, paginator().findPageForLocator(pages, locatorBoundaryAfter))
    }

    @Test
    fun paginateStructuredDocumentCreatesValidPages() {
        val text = "Title Line\nParagraph 1\nParagraph 2\n"
        val doc = StructuredDocument(
            documentKey = "text/ch1.xhtml",
            text = text,
            blocks = listOf(
                HeadingBlock("h1", SourceSpan("text/ch1.xhtml", 0, 11), "body/h1[1]", level = 1),
                ParagraphBlock("p1", SourceSpan("text/ch1.xhtml", 11, 23), "body/p[1]"),
                ParagraphBlock("p2", SourceSpan("text/ch1.xhtml", 23, 35), "body/p[2]")
            ),
            anchorIndex = emptyMap(),
            resourceRefs = emptyList(),
            mainTextUnits = 30
        )

        val pages = paginator().paginateStructuredDocument(doc, pageHeightPx = 1000, gapPx = 10)
        assertTrue(pages.isNotEmpty())
        val allFragments = pages.flatten()
        assertEquals(3, allFragments.size)
        assertTrue(allFragments[0].isTitle)
        assertEquals("h1", allFragments[0].blockId)
        assertEquals("p1", allFragments[1].blockId)
        assertEquals("p2", allFragments[2].blockId)
    }

    @Test fun longParagraphMeasuresViewportSizedPrefixesAndPublishesImmediately() {
        val text = "山河之间，安静阅读。".repeat(10_000)
        var largest = 0
        var measured = 0L
        var callbacks = 0
        var firstReadyWork = 0L
        val paginator = ChapterPaginator { value, _, _, _ ->
            largest = maxOf(largest, value.length)
            measured += value.length
            value.length * 10
        }
        val pages = paginator.paginateProgressively(listOf(Block(text, 0)), 900, 0, { index, fragments ->
            assertEquals(callbacks, index)
            if (index == 0) {
                firstReadyWork = measured
                assertEquals(90, fragments.single().text.length)
            }
            callbacks++
        })
        assertEquals(text, pages.flatten().joinToString("") { it.text })
        assertEquals(pages.size, callbacks)
        assertTrue(firstReadyWork < 2_000)
        assertTrue(largest <= 2048)
        assertTrue(measured < text.length * 20L)
        assertEquals(text.length, pages.last().last().sourceRanges.last().endUtf16Exclusive)
    }

    @Test fun streamingAndCompletePaginationProduceIdenticalImmutableFragments() {
        val blocks = listOf(Block("标题", 0, isTitle = true, paragraph = false), Block("正文。".repeat(4000), 2), Block("结尾", 12002))
        val paginator = ChapterPaginator { value, _, _, title -> value.length * if (title) 20 else 10 }
        val published = mutableListOf<List<ChapterPaginator.Fragment>>()
        val streamed = paginator.paginateProgressively(blocks, 800, 12, { _, page -> published += listOf(page) })
        assertEquals(paginator.paginate(blocks, 800, 12), streamed)
        assertEquals(streamed, published)
        assertEquals("标题", published.first().first().text)
    }
}
