package com.flowreader.app.ui.screens.library

import com.flowreader.app.domain.model.Book
import java.util.Date
import org.junit.Assert.*
import org.junit.Test

class FavoriteShelfTest {
    private val books = listOf(
        Book(id = 1, title = "未分组收藏", author = "甲", filePath = "1", isFavorite = true),
        Book(
            id = 2,
            title = "甲组正在读",
            author = "乙",
            filePath = "2",
            categoryId = 10,
            lastReadTime = Date(1),
            readingProgress = .5f,
            isFavorite = true
        ),
        Book(
            id = 3,
            title = "乙组已读",
            author = "丙",
            filePath = "3",
            categoryId = 20,
            lastReadTime = Date(2),
            readingProgress = 1f,
            isFavorite = true
        ),
        Book(id = 4, title = "甲组已读", author = "丁", filePath = "4", categoryId = 10, lastReadTime = Date(3), readingProgress = 1f)
    )
    private fun query(group: Long?, filter: ReadingFilter, hidden: Boolean = true, source: List<Book> = books) =
        LibraryViewModel.ShelfQuery(
            source,
            SortOrder.TITLE,
            group,
            filter,
            LibraryViewModel.ShelfPreferences(hideGroupedBooksFromAll = hidden)
        ).compute().books.map { it.id }.toSet()

    @Test fun favoritesRemainGlobalAcrossGroupsAndHiddenGroupedBooks() {
        for (group in listOf(null, 10L, 20L, 999L)) {
            assertEquals(setOf(1L, 2L, 3L), query(group, ReadingFilter.FAVORITES))
            assertEquals(setOf(1L, 2L, 3L), query(group, ReadingFilter.FAVORITES, false))
        }
    }

    @Test fun readingAndFinishedFollowTheChosenGroup() {
        assertEquals(setOf(2L), query(10, ReadingFilter.READING))
        assertEquals(emptySet<Long>(), query(20, ReadingFilter.READING))
        assertEquals(setOf(4L), query(10, ReadingFilter.FINISHED))
        assertEquals(setOf(3L), query(20, ReadingFilter.FINISHED))
    }

    @Test fun allRetainsExistingHideBehaviorAndEmptyGroupsRemainEmpty() {
        assertEquals(setOf(1L), query(null, ReadingFilter.ALL))
        assertEquals(setOf(1L, 2L, 3L, 4L), query(null, ReadingFilter.ALL, false))
        assertEquals(emptySet<Long>(), query(999, ReadingFilter.ALL))
    }

    @Test fun removingFavoriteOrDeletingBookImmediatelyChangesTheGlobalResult() {
        assertEquals(
            setOf(1L, 3L),
            query(10, ReadingFilter.FAVORITES, source = books.map { if (it.id == 2L) it.copy(isFavorite = false) else it })
        )
        assertEquals(setOf(2L, 3L), query(20, ReadingFilter.FAVORITES, source = books.filter { it.id != 1L }))
    }
}
