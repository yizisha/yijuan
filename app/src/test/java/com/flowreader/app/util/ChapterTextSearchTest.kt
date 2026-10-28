package com.flowreader.app.util

import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.model.BookFormat
import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.repository.ChapterRepository
import com.flowreader.app.domain.repository.SearchProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChapterTextSearchTest {
    private val book = Book(1, "测试书", "作者", "book.txt", format = BookFormat.TXT, publicationId = "pub", activeRevision = "rev")
    private fun scanner(texts: Map<Int, String>, batches: MutableList<List<Int>> = mutableListOf()): ChapterTextSearch {
        val repository = mockk<ChapterRepository>()
        coEvery { repository.getChapterMetadataList(1) } returns texts.keys.reversed().map {
            Chapter(bookId = 1, index = it, title = "章 $it")
        }
        coEvery { repository.getChapterTextLengths(1) } returns texts.mapValues { it.value.length }
        coEvery { repository.getChapterContentsForSearch(1, any()) } coAnswers {
            val indexes = secondArg<List<Int>>()
            batches.add(indexes)
            texts.filterKeys { it in indexes }
        }
        return ChapterTextSearch(repository)
    }

    @Test fun chinesePhraseInMiddleOfSentenceCarriesExactSourceOffset() = runTest {
        val text = "清晨😀，山间有薄雾升起，像一层轻纱。"
        val result = scanner(mapOf(9 to text)).search(listOf(book), "薄雾", 10, 0)[0]
        assertEquals(9, result.chapterIndex)
        assertEquals(text.indexOf("薄雾"), result.firstStartUtf16)
        assertEquals("薄雾", text.substring(result.ranges[0].startUtf16, result.ranges[0].endUtf16Exclusive))
        assertEquals("rev", result.toTextLocator()!!.revision)
        assertTrue(result.matchedText.contains("山间有薄雾升起"))
    }

    @Test fun punctuationAndSqlWildcardsAreLiteral() = runTest {
        val engine = scanner(mapOf(0 to "收入100%_增长，[a+b]* \"引文\"", 2 to "收入100倍增长，aab"))
        for (query in listOf("100%_", "[a+b]*", "\"引文\"")) {
            assertEquals(listOf(0), engine.search(listOf(book), query, 10, 0).map { it.chapterIndex })
        }
    }

    @Test fun caseInsensitivePhrasePreservesOriginalSnippetAndOffset() = runTest {
        val text = "前文。Hello World! 后文。"
        val result = scanner(mapOf(0 to text)).search(listOf(book), "hello world", 10, 0)[0]
        assertTrue(result.matchedText.contains("Hello World"))
        assertEquals(text.indexOf("Hello"), result.firstStartUtf16)
    }

    @Test fun repeatedTermsReturnFirstOccurrenceWithItsActualContext() = runTest {
        val text = "第一个目标词。第二个目标词。"
        val result = scanner(mapOf(0 to text)).search(listOf(book), "目标词", 10, 0)[0]
        assertEquals(text.indexOf("目标词"), result.firstStartUtf16)
        assertEquals(com.flowreader.app.domain.model.SearchHitProjector.Confidence.EXACT, result.confidence)
    }

    @Test fun pagesUseStableChapterOrderAndBoundedReads() = runTest {
        val batches = mutableListOf<List<Int>>()
        val engine = scanner((0..99).associateWith { "正文目标词$it" }, batches)
        val first = engine.search(listOf(book), "目标词", 21, 0)
        val second = engine.search(listOf(book), "目标词", 21, 20)
        assertEquals((0..20).toList(), first.map { it.chapterIndex })
        assertEquals((20..40).toList(), second.map { it.chapterIndex })
        assertTrue(batches.all { it.size <= 32 })
        assertTrue(batches.flatten().max() < 99)
    }

    @Test fun progressTracksNoHitBookAndCancellationStopsBeforeNextBatch() = runTest {
        val batches = mutableListOf<List<Int>>()
        val engine = scanner((0..100).associateWith { "无匹配正文" }, batches)
        val progress = mutableListOf<SearchProgress>()
        try {
            engine.search(listOf(book), "目标词", 20, 0) {
                progress.add(it)
                if (it.completedChapters >= 32) throw CancellationException("cancel")
            }
            fail("必须取消")
        } catch (_: CancellationException) { }
        assertEquals(1, batches.size)
        assertEquals(101, progress.last().totalChapters)
        assertEquals(32, progress.last().completedChapters)
    }

    @Test fun changedTextIsReadAgainInsteadOfUsingStaleIndex() = runTest {
        val repository = mockk<ChapterRepository>()
        coEvery { repository.getChapterMetadataList(1) } returns listOf(Chapter(bookId = 1, index = 0, title = "章"))
        coEvery { repository.getChapterTextLengths(1) } returns mapOf(0 to 3)
        coEvery { repository.getChapterContentsForSearch(1, any()) } returnsMany listOf(mapOf(0 to "旧词语"), mapOf(0 to "新词语"))
        val engine = ChapterTextSearch(repository)
        assertEquals(1, engine.search(listOf(book), "旧词语", 20, 0).size)
        assertTrue(engine.search(listOf(book), "旧词语", 20, 0).isEmpty())
        coVerify(exactly = 0) { repository.getChapterContent(any(), any()) }
    }

    @Test fun blankQueryDoesNotReadLibraryAndSingleCharacterIsSupported() = runTest {
        val batches = mutableListOf<List<Int>>()
        val engine = scanner(mapOf(0 to "山与生活"), batches)
        assertTrue(engine.search(listOf(book), "  ", 20, 0).isEmpty())
        assertTrue(batches.isEmpty())
        assertEquals(1, engine.search(listOf(book), "山", 20, 0).size)
    }

    @Test fun largeChaptersUseCharacterBudgetAndSnippetDoesNotSplitSurrogate() = runTest {
        val batches = mutableListOf<List<Int>>()
        val text = "😀".repeat(200000) + "目标词" + "😀".repeat(100)
        val result = scanner(mapOf(0 to text, 1 to text), batches).search(listOf(book), "目标词", 10, 0)
        assertTrue(batches.all { it.size == 1 })
        for (hit in result) {
            assertTrue(hit.matchedText.length < 200)
            assertFalse(hit.matchedText.first().isLowSurrogate())
            assertFalse(hit.matchedText.last().isHighSurrogate())
        }
    }
}
