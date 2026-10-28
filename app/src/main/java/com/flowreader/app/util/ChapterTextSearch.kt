package com.flowreader.app.util

import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.model.BookFormat
import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.model.GlobalSearchResult
import com.flowreader.app.domain.model.SearchHitProjector
import com.flowreader.app.domain.model.SourceRange
import com.flowreader.app.domain.repository.ChapterRepository
import com.flowreader.app.domain.repository.SearchProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Literal substring search: Chinese, punctuation and Latin use the same device-independent rules. */
@Singleton
class ChapterTextSearch @Inject constructor(private val chapterRepository: ChapterRepository) {
    private companion object {
        const val BATCH_CHAPTERS = 32
        const val BATCH_CHARACTERS = 262144L
        const val SNIPPET_BEFORE = 36
        const val SNIPPET_AFTER = 60
    }
    private data class BookChapters(val book: Book, val chapters: List<Chapter>, val lengths: Map<Int, Int>)

    suspend fun search(
        books: List<Book>,
        query: String,
        limit: Int,
        offset: Int,
        onProgress: (SearchProgress) -> Unit = {}
    ): List<GlobalSearchResult> = withContext(Dispatchers.Default) {
        require(limit in 1..1000 && offset >= 0)
        val term = query.trim()
        if (term.isEmpty()) return@withContext emptyList()
        val sources = books.filter { it.format != BookFormat.COMIC }.sortedBy { it.id }.map { book ->
            ensureActive()
            BookChapters(
                book,
                chapterRepository.getChapterMetadataList(book.id).sortedBy { it.index },
                chapterRepository.getChapterTextLengths(book.id)
            )
        }
        val total = sources.sumOf { it.chapters.size }
        var completed = 0
        var skipped = 0
        val results = mutableListOf<GlobalSearchResult>()
        onProgress(SearchProgress(0, total))
        for ((book, chapters, lengths) in sources) {
            var cursor = 0
            while (cursor < chapters.size) {
                ensureActive()
                val start = cursor
                var characters = 0L
                do {
                    characters += lengths[chapters[cursor].index]?.coerceAtLeast(0) ?: 0
                    cursor++
                } while (cursor < chapters.size && cursor - start < BATCH_CHAPTERS &&
                    characters + (lengths[chapters[cursor].index] ?: 0) <= BATCH_CHARACTERS
                )
                // Bounded batches; never retain or index a whole library's body in memory.
                val batch = chapters.subList(start, cursor)
                val contents = chapterRepository.getChapterContentsForSearch(book.id, batch.map { it.index })
                ensureActive()
                for (chapter in batch) {
                    ensureActive()
                    val content = contents[chapter.index].orEmpty()
                    val match = content.indexOf(term, ignoreCase = true)
                    if (match >= 0) {
                        if (skipped < offset) {
                            skipped++
                        } else {
                            val key = "chapter:${chapter.index}"
                            results.add(
                                GlobalSearchResult(
                                    bookId = book.id,
                                    bookTitle = book.title,
                                    chapterIndex = chapter.index,
                                    chapterTitle = chapter.title,
                                    matchedText = snippet(content, match, term.length),
                                    documentKey = key,
                                    revision = book.activeRevision.orEmpty(),
                                    publicationId = book.publicationId.orEmpty(),
                                    ranges = listOf(SourceRange(key, match, match + term.length)),
                                    confidence = SearchHitProjector.Confidence.EXACT
                                )
                            )
                        }
                    }
                    completed++
                    // Progress once per batch, or at the final hit, to avoid excessive recompositions.
                    if (results.size == limit) {
                        onProgress(SearchProgress(completed, total))
                        return@withContext results
                    }
                }
                onProgress(SearchProgress(completed, total))
            }
        }
        results
    }

    private fun snippet(content: String, offset: Int, length: Int): String {
        var start = (offset - SNIPPET_BEFORE).coerceAtLeast(0)
        var end = (offset + length + SNIPPET_AFTER).coerceAtMost(content.length)
        if (start > 0 && content[start].isLowSurrogate() && content[start - 1].isHighSurrogate()) start--
        if (end < content.length && end > 0 && content[end - 1].isHighSurrogate() && content[end].isLowSurrogate()) end++
        return (if (start > 0) "…" else "") +
            content.substring(start, end).replace('\n', ' ').replace('\r', ' ') +
            (if (end < content.length) "…" else "")
    }
}
