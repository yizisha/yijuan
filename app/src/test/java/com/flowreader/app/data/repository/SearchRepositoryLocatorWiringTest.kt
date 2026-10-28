package com.flowreader.app.data.repository

import com.flowreader.app.domain.model.*
import com.flowreader.app.domain.repository.BookRepository
import com.flowreader.app.domain.repository.ChapterRepository
import com.flowreader.app.util.ChapterTextSearch
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Canonical text -> exact UTF-16 range -> existing reader navigation locator. */
class SearchRepositoryLocatorWiringTest {
    private val book = Book(id = 1, title = "测试书", author = "作者", filePath = "book.txt", format = BookFormat.TXT,
        publicationId = "pub-1", activeRevision = "rev-1")
    private fun repository(text: String?): SearchRepositoryImpl {
        val books = mockk<BookRepository>()
        val chapters = mockk<ChapterRepository>()
        every { books.getAllBooks() } returns flowOf(listOf(book))
        coEvery { chapters.getChapterMetadataList(1) } returns listOf(Chapter(bookId = 1, index = 9, title = "第一章"))
        coEvery { chapters.getChapterTextLengths(1) } returns mapOf(9 to (text?.length ?: 0))
        coEvery { chapters.getChapterContentsForSearch(1, any()) } returns if (text == null) emptyMap() else mapOf(9 to text)
        return SearchRepositoryImpl(books, ChapterTextSearch(chapters))
    }
    @Test fun resultUsesStoredChapterIndexAndOriginalUtf16Range() = runTest {
        val text = "😀这是目标词。"
        val result = repository(text).searchChapters("目标词", 10, 0).single()
        assertEquals(9, result.chapterIndex)
        assertEquals(text.indexOf("目标词"), result.firstStartUtf16)
        assertEquals("目标词", text.substring(result.ranges[0].startUtf16, result.ranges[0].endUtf16Exclusive))
        assertEquals(SearchHitProjector.Confidence.EXACT, result.confidence)
        assertEquals("chapter:9", result.documentKey)
    }
    @Test fun originalPunctuationAndMarkerLikeTextAreNotStripped() = runTest {
        val result = repository("原文 <<目标词>>...后文").searchChapters("目标词", 10, 0).single()
        assertTrue(result.matchedText.contains("<<目标词>>..."))
    }
    @Test fun staleOrMissingTextCannotCreateFakeLocator() = runTest {
        assertTrue(repository("已经修改正文").searchChapters("目标词", 10, 0).isEmpty())
        assertTrue(repository(null).searchChapters("目标词", 10, 0).isEmpty())
    }
    @Test fun resultLocatorCarriesPublicationAndRevision() = runTest {
        val result = repository("前文目标词后文").searchChapters("目标词", 10, 0).single()
        val locator = result.toTextLocator()!!
        assertEquals("pub-1", locator.publicationId)
        assertEquals("rev-1", locator.revision)
        assertEquals(Affinity.AFTER, locator.affinity)
        assertEquals(2, locator.offsetUtf16)
    }
    @Test fun repeatedTermSnippetDescribesFirstExactHit() = runTest {
        val result = repository("第一处目标词，第二处目标词。").searchChapters("目标词", 10, 0).single()
        assertEquals(3, result.firstStartUtf16)
        assertTrue(result.matchedText.contains("第一处目标词"))
        assertEquals(SearchHitProjector.Confidence.EXACT, result.confidence)
    }
    @Test fun progressCompletesEvenWhenNoChapterMatches() = runTest {
        val updates = mutableListOf<com.flowreader.app.domain.repository.SearchProgress>()
        assertTrue(repository("没有目标").searchChaptersWithProgress("目标词", 10, 0) { updates.add(it) }.isEmpty())
        assertEquals(1, updates.last().completedChapters)
        assertEquals(1, updates.last().totalChapters)
    }
}
