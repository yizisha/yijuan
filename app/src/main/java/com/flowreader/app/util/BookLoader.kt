package com.flowreader.app.util

import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookLoader @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun loadChapterAsync(
        bookId: Long,
        chapterIndex: Int,
        loadContent: suspend (Long, Int) -> String?
    ): Deferred<String?> = scope.async {
        try {
            loadContent(bookId, chapterIndex)
        } catch (e: Exception) {
            null
        }
    }

    fun preloadChapters(
        bookId: Long,
        currentIndex: Int,
        totalChapters: Int,
        loadContent: suspend (Long, Int) -> String?
    ) {
        scope.launch {
            val preloadIndices = mutableListOf<Int>()

            // Forward neighbour first: that is the direction most readers page in, and the chapter
            // cache is small enough that the order decides whether the next chapter is still
            // resident by the time it is asked for. Previously the backward neighbour and the
            // -2 chapter came first and the +2 chapter was dropped by take(3).
            if (currentIndex < totalChapters - 1) preloadIndices.add(currentIndex + 1)
            if (currentIndex > 0) preloadIndices.add(currentIndex - 1)
            if (currentIndex < totalChapters - 2) preloadIndices.add(currentIndex + 2)
            if (currentIndex > 1) preloadIndices.add(currentIndex - 2)

            preloadIndices.take(PRELOAD_LIMIT).forEach { index ->
                launch {
                    loadContent(bookId, index)
                }
            }
        }
    }

    fun cancelAll() {
        scope.cancel()
    }

    private companion object {
        /** Keeps the two chapters either side of the reader hot without flooding a 2–12 KB cache. */
        const val PRELOAD_LIMIT = 4
    }
}
