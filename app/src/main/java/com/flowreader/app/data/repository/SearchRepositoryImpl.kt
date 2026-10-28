package com.flowreader.app.data.repository

import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.model.GlobalSearchResult
import com.flowreader.app.domain.repository.BookRepository
import com.flowreader.app.domain.repository.SearchProgress
import com.flowreader.app.domain.repository.SearchRepository
import com.flowreader.app.util.ChapterTextSearch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class SearchRepositoryImpl @Inject constructor(
    private val bookRepository: BookRepository,
    private val chapterTextSearch: ChapterTextSearch
) : SearchRepository {
    // Compatibility entry point: searches now read canonical text on demand, without a rebuild.
    override suspend fun rebuildIndex() = Unit

    override suspend fun searchAll(query: String): List<GlobalSearchResult> = searchChapters(query, 100, 0)

    override suspend fun searchChapters(query: String, limit: Int, offset: Int): List<GlobalSearchResult> =
        searchChaptersWithProgress(query, limit, offset) {}

    override suspend fun searchChaptersWithProgress(
        query: String,
        limit: Int,
        offset: Int,
        onProgress: (SearchProgress) -> Unit
    ): List<GlobalSearchResult> = chapterTextSearch.search(bookRepository.getAllBooks().first(), query, limit, offset, onProgress)

    override suspend fun searchBooks(query: String): List<Book> = withContext(Dispatchers.Default) {
        val term = query.trim()
        if (term.isEmpty()) {
            emptyList()
        } else {
            bookRepository.getAllBooks().first().filter {
                it.title.contains(term, ignoreCase = true) || it.author.contains(term, ignoreCase = true)
            }
        }
    }
}
