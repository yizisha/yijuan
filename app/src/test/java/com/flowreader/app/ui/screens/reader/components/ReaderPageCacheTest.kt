package com.flowreader.app.ui.screens.reader.components

import com.flowreader.app.domain.model.ReadingSettings
import com.flowreader.feature.reader.ChapterPaginator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the chapter-switch fixes.
 *
 * The reported defect was "跨章节很卡，加载时还会显示『本章暂无内容』": the pager re-measured every
 * chapter from an empty initial page list, so "still measuring" and "genuinely empty" were the same
 * state, and nothing was reused between chapters.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPageCacheTest {

    @Test fun abandonedLayoutCannotBlockANewForegroundChapter() = runTest {
        val cache = ReaderPageCache()
        val gate = CompletableDeferred<Unit>()
        val oldReader = async { cache.getOrCompute(key(chapter = 1), backgroundScope) { gate.await(); pages(5) } }
        runCurrent()
        oldReader.cancelAndJoin()
        val destination = async { cache.getOrCompute(key(chapter = 4), backgroundScope) { pages(1) } }
        runCurrent()
        assertTrue("The requested chapter must not wait for abandoned/background pagination", destination.isCompleted)
        assertEquals(1, destination.await().size)
    }

    @Test fun aWindowCannotBridgeMissingOrEmptyChapters() {
        fun prepared(index: Int, count: Int = 1) = ReaderChapterPages(index,
            com.flowreader.app.domain.model.Chapter(bookId = 1, index = index, title = "$index"),
            pages(count), emptyList(), com.flowreader.app.util.epub.ChapterMarks.EMPTY, 0)
        assertEquals(listOf(ReaderPageAddress(1, 0)), readerWindowPages(listOf(prepared(1), prepared(3)), 1))
        assertEquals(listOf(ReaderPageAddress(1, 0)), readerWindowPages(listOf(prepared(1), prepared(2, 0), prepared(3)), 1))
        assertEquals(listOf(ReaderPageAddress(2, 0), ReaderPageAddress(3, 0)),
            readerWindowPages(listOf(prepared(0), prepared(2), prepared(3)), 2))
    }

    @Test fun aContiguousWindowKeepsEveryLocalPageInReadingOrder() {
        fun prepared(index: Int, count: Int) = ReaderChapterPages(index,
            com.flowreader.app.domain.model.Chapter(bookId = 1, index = index, title = "$index"),
            pages(count), emptyList(), com.flowreader.app.util.epub.ChapterMarks.EMPTY, 0)
        assertEquals(listOf(ReaderPageAddress(7, 0), ReaderPageAddress(7, 1), ReaderPageAddress(8, 0),
            ReaderPageAddress(8, 1), ReaderPageAddress(8, 2), ReaderPageAddress(9, 0)),
            readerWindowPages(listOf(prepared(9, 1), prepared(7, 2), prepared(8, 3)), 8))
    }

    @Test
    fun chapterSwitchKeepsInFlightPaginationAndSharesItWithTheDestination() = runTest {
        val cache = ReaderPageCache()
        val gate = CompletableDeferred<Unit>()
        var computations = 0
        val prefetch = async {
            cache.getOrCompute(key(chapter = 1), backgroundScope) {
                computations++
                gate.await()
                pages(3)
            }
        }
        runCurrent()
        prefetch.cancelAndJoin()
        val destination = async {
            cache.getOrCompute(key(chapter = 1), backgroundScope) {
                computations++
                pages(9)
            }
        }
        runCurrent()
        assertEquals(1, computations)
        gate.complete(Unit)
        runCurrent()
        assertEquals(3, destination.await().size)
        assertEquals(3, cache.get(key(chapter = 1))?.size)
    }

    @Test
    fun clearingCacheCancelsPendingPagination() = runTest {
        val cache = ReaderPageCache()
        val gate = CompletableDeferred<Unit>()
        val waiter = async {
            cache.getOrCompute(key(), backgroundScope) {
                gate.await()
                pages(2)
            }
        }
        runCurrent()
        cache.clear()
        runCurrent()
        assertTrue(waiter.isCancelled)
        assertNull(cache.get(key()))
        assertEquals(1, cache.getOrCompute(key(), backgroundScope) { pages(1) }.size)
    }

    private fun key(
        bookId: Long = 1L,
        chapter: Int = 0,
        content: String = "body",
        title: String = "T",
        imageKey: String = "",
        marksKey: String = ""
    ) = ReaderPageKey(
        bookId = bookId,
        chapterIndex = chapter,
        contentLength = content.length,
        contentHash = content.hashCode(),
        title = title,
        settings = ReadingSettings(),
        fontFamilyKey = "default",
        widthPx = 600,
        heightPx = 900,
        gapPx = 12,
        imageKey = imageKey,
        marksKey = marksKey
    )

    private fun pages(pageCount: Int): List<List<ChapterPaginator.Fragment>> = List(pageCount) { index ->
        listOf(
            ChapterPaginator.Fragment(
                text = "paragraph $index",
                paragraphStart = 0,
                indent = false,
                isTitle = false,
                isHeading = false
            )
        )
    }

    @Test
    fun pendingAndEmptyAreDifferentStates() {
        assertEquals(PageRenderState.TYPESETTING, pageRenderState(null))
        assertEquals(PageRenderState.EMPTY, pageRenderState(emptyList()))
        assertEquals(PageRenderState.PAGES, pageRenderState(pages(1)))
    }

    @Test
    fun cachedPagesAreReturnedForTheSameGeometryAndText() {
        val cache = ReaderPageCache()
        cache.put(key(), pages(3))

        assertEquals(3, cache.get(key())?.size)
    }

    @Test
    fun editingTheChapterTextInvalidatesTheEntry() {
        val cache = ReaderPageCache()
        cache.put(key(content = "original"), pages(2))

        assertNull(cache.get(key(content = "edited")))
    }

    @Test
    fun changingTypographyInvalidatesTheEntry() {
        val cache = ReaderPageCache()
        cache.put(key(), pages(2))

        assertNull(cache.get(key().copy(settings = ReadingSettings(fontSize = 24))))
    }

    @Test
    fun changingThePageGeometryInvalidatesTheEntry() {
        val cache = ReaderPageCache()
        cache.put(key(), pages(2))

        assertNull(cache.get(key().copy(widthPx = 480)))
    }

    @Test
    fun changingResolvedImagesInvalidatesTheEntry() {
        // S5：有图与无图必须是两份排版。否则会复用一个"少了几张图"的旧页列表。
        val cache = ReaderPageCache()
        cache.put(key(imageKey = "5:/abs/a.jpg"), pages(2))

        assertNull(cache.get(key(imageKey = "")))
        assertNotNull(cache.get(key(imageKey = "5:/abs/a.jpg")))
        assertNull(cache.get(key(imageKey = "5:/abs/b.jpg")))
    }

    @Test
    fun changingMarksInvalidatesTheEntry() {
        // schema v3：有标注与无标注、编号不同都必须是两份排版（列表前缀参与测量）。
        val cache = ReaderPageCache()
        cache.put(key(marksKey = "L5:true:1:1|"), pages(2))

        assertNull(cache.get(key(marksKey = "")))
        assertNotNull(cache.get(key(marksKey = "L5:true:1:1|")))
        assertNull(cache.get(key(marksKey = "L5:true:2:1|")))
    }

    @Test
    fun pagesAreNeverSharedBetweenBooks() {
        val cache = ReaderPageCache()
        cache.put(key(bookId = 1L), pages(2))

        assertNull(cache.get(key(bookId = 2L)))
    }

    @Test
    fun leastRecentlyUsedEntryIsEvictedFirst() {
        val cache = ReaderPageCache(maxEntries = 2)
        cache.put(key(chapter = 0), pages(1))
        cache.put(key(chapter = 1), pages(1))
        // Touch chapter 0 so that chapter 1 is now the least recently used.
        assertNotNull(cache.get(key(chapter = 0)))

        cache.put(key(chapter = 2), pages(1))

        assertEquals(2, cache.size())
        assertNotNull(cache.get(key(chapter = 0)))
        assertNull(cache.get(key(chapter = 1)))
        assertNotNull(cache.get(key(chapter = 2)))
    }

    @Test
    fun blockBuilderStartsWithTheTitleBlockAtOffsetZero() {
        val blocks = buildChapterBlocks("Title", "para one\n\npara two", indentEnabled = true, images = emptyList())

        assertTrue(blocks.first().isTitle)
        assertEquals("Title", blocks.first().text)
        assertEquals(0, blocks.first().offset)
    }

    @Test
    fun blockBuilderKeepsRawParagraphOffsets() {
        val content = "para one\n\n## Sec\n\npara two"
        val blocks = buildChapterBlocks("T", content, indentEnabled = true, images = emptyList())

        // Offsets must stay in the raw chapter text: highlights and bookmarks are stored as
        // character ranges against it.
        assertEquals(content.indexOf("para one"), blocks.first { it.text == "para one" }.offset)
        assertEquals(content.indexOf("## Sec"), blocks.first { it.text == "## Sec" }.offset)
        assertEquals(content.indexOf("para two"), blocks.first { it.text == "para two" }.offset)
        assertTrue(blocks.first { it.text == "## Sec" }.isHeading)
    }

    @Test
    fun blockBuilderTreatsImageMarkersAsBlocksRatherThanParagraphs() {
        val blocks = buildChapterBlocks("T", "T\n\n[IMG:/tmp/a.png]", indentEnabled = true, images = emptyList())

        assertFalse(blocks.first { it.text.startsWith("[IMG:") }.paragraph)
    }

    @Test
    fun blankContentProducesOnlyTheTitleBlock() {
        val blocks = buildChapterBlocks("T", "   ", indentEnabled = true, images = emptyList())

        assertEquals(1, blocks.size)
        assertTrue(blocks.first().isTitle)
    }

    @Test fun colourAndControlsDoNotInvalidateLayoutButTypographyDoes() {
        val original = ReadingSettings()
        val recoloured = original.copy(
            palette = com.flowreader.app.domain.model.ReaderPaletteId.NIGHT,
            annotationDisplayStyle = com.flowreader.app.domain.model.AnnotationDisplayStyle.SOFT_HIGHLIGHT,
            volumeKeyTurnPage = false,
            autoNightMode = true
        )
        assertEquals(paginationSettings(original), paginationSettings(recoloured))
        assertFalse(paginationSettings(original) == paginationSettings(original.copy(fontSize = 25)))
        val cache = ReaderPageCache()
        cache.put(key().copy(environmentKey = "density:2:scale1"), pages(1))
        assertNull(cache.get(key().copy(environmentKey = "density:2:scale1.4")))
    }

    @Test fun equalLegacyStringHashesCannotReuseDifferentBookContent() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val cache = ReaderPageCache()
        cache.put(key(content = "Aa").copy(contentFingerprint = contentFingerprint("Aa")), pages(1))
        assertNull(cache.get(key(content = "BB").copy(contentFingerprint = contentFingerprint("BB"))))
    }

    @Test fun requestedPageIsAvailableBeforeFullPaginationAndClearsOnCancellation() = runTest {
        val cache = ReaderPageCache()
        val gate = CompletableDeferred<Unit>()
        val job = async {
            cache.getOrCompute(key(), backgroundScope) {
                cache.publishPreview(key(), 0, pages(1).single())
                gate.await()
                pages(2)
            }
        }
        runCurrent()
        assertNull(cache.get(key()))
        assertEquals(pages(1).single(), cache.preview(key(), 0))
        cache.clear()
        runCurrent()
        assertTrue(job.isCancelled)
        assertNull(cache.preview(key(), 0))
    }
}
