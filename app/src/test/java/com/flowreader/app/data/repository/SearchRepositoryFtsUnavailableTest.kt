package com.flowreader.app.data.repository

import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.model.BookFormat
import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.repository.BookRepository
import com.flowreader.app.domain.repository.ChapterRepository
import com.flowreader.app.util.ChapterTextSearch
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Search no longer opens an FTS database: lacking FTS5 cannot disable either search path. */
class SearchRepositoryFtsUnavailableTest {
    private val books = mockk<BookRepository>()
    private val chapters = mockk<ChapterRepository>()
    private val book = Book(id = 1, title = "100%_测试书", author = "作者", filePath = "book.txt", format = BookFormat.TXT)
    private fun repository(): SearchRepositoryImpl {
        every { books.getAllBooks() } returns flowOf(listOf(book))
        coEvery { chapters.getChapterMetadataList(1) } returns listOf(Chapter(bookId = 1, index = 0, title = "测试章"))
        coEvery { chapters.getChapterTextLengths(1) } returns mapOf(0 to 15)
        coEvery { chapters.getChapterContentsForSearch(1, any()) } returns mapOf(0 to "这一句包含目标词，不需要分词引擎")
        return SearchRepositoryImpl(books, ChapterTextSearch(chapters))
    }
    @Test fun searchesCanonicalTextWithoutOpeningFtsDatabase() = runTest {
        assertEquals(1, repository().searchChapters("目标词", 10, 0).size)
        coVerify(exactly = 0) { chapters.getChapterContent(any(), any()) }
    }
    @Test fun explicitRebuildDoesNotLoadOrDuplicateLibraryBody() = runTest {
        repository().rebuildIndex()
        coVerify(exactly = 0) { chapters.getChapterContentsForSearch(any(), any()) }
    }
    @Test fun titleSearchTreatsWildcardsAsLiteralAndSupportsOneCharacter() = runTest {
        val repository = repository()
        assertEquals(listOf(book), repository.searchBooks("%_"))
        assertEquals(listOf(book), repository.searchBooks("测"))
        assertTrue(repository.searchBooks("100___").isEmpty())
    }
}
