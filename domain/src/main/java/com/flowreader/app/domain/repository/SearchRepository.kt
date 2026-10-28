package com.flowreader.app.domain.repository

import com.flowreader.app.domain.model.GlobalSearchResult

/** Number of canonical chapters checked, independent of whether any match was found. */
data class SearchProgress(val completedChapters: Int, val totalChapters: Int)

interface SearchRepository {
    suspend fun rebuildIndex()
    suspend fun searchAll(query: String): List<GlobalSearchResult>

    /** Paged cross-book chapter hits; callers page through with [offset]. */
    suspend fun searchChapters(query: String, limit: Int, offset: Int): List<GlobalSearchResult>

    suspend fun searchChaptersWithProgress(
        query: String,
        limit: Int,
        offset: Int,
        onProgress: (SearchProgress) -> Unit
    ): List<GlobalSearchResult> = searchChapters(query, limit, offset)

    /** Books whose title or author contains [query]. */
    suspend fun searchBooks(query: String): List<com.flowreader.app.domain.model.Book>
}
