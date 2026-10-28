package com.flowreader.app.domain.model

import org.junit.Assert.*
import org.junit.Test

class BookSeriesTest {
    private fun book(id: Long, series: String? = "山海", order: Int = id.toInt()) =
        Book(id = id, title = "第${id}卷", author = "作者", filePath = "$id.txt", seriesName = series, seriesOrder = order)

    @Test fun volumeOrderAndNextUseManualSequenceRatherThanTitleOrImportOrder() {
        val books = listOf(book(1, order = 3), book(2, order = 1), book(3, order = 2))
        assertEquals(listOf(2L, 3L, 1L), BookSeries.ordered(books).map { it.id })
        assertEquals(3L, BookSeries.next(books[1], books)!!.id)
        assertNull(BookSeries.next(books[0], books))
    }

    @Test fun deletingOrMovingAVolumeDoesNotLeaveAStaleNextBook() {
        val first = book(1)
        assertEquals(3L, BookSeries.next(first, listOf(first, book(3)))!!.id)
        assertNull(BookSeries.next(first, listOf(first, book(2, series = "其他"))))
        assertNull(BookSeries.next(book(1, series = null), listOf(first, book(2))))
    }

    @Test fun shelfCollapsesOnlyMatchingVisibleBooksAndKeepsShelfOrder() {
        val visible = listOf(book(3), book(5, null), book(1))
        val entries = BookSeries.shelf(visible)
        assertEquals(listOf("series:山海", "book:5"), entries.map { it.key })
        assertEquals(listOf(1L, 3L), entries.first().volumes.map { it.id })
        assertFalse(entries.flatMap { it.volumes }.any { it.id == 2L })
    }

    @Test fun favouriteAndCategoryFilteringCanNeverBeUndoneByCollapsingSeries() {
        val books = listOf(book(1).copy(isFavorite = true, categoryId = 2), book(2).copy(categoryId = 3))
        val favourites = BookSeries.shelf(books.filter { it.isFavorite })
        assertEquals(listOf(1L), favourites.single().volumes.map { it.id })
        assertEquals(listOf(2L), BookSeries.shelf(books.filter { it.categoryId == 3L }).single().volumes.map { it.id })
    }

    @Test fun equalPositionsHaveADeterministicIdOrderAndSingletonRemainsASeries() {
        assertEquals(listOf(1L, 2L), BookSeries.ordered(listOf(book(2, order = 0), book(1, order = 0))).map { it.id })
        assertEquals("山海", BookSeries.shelf(listOf(book(1))).single().name)
    }

    @Test fun namesTrimButDoNotGuessSeriesFromFilenames() {
        assertEquals("山海", BookSeries.requireName("  山海  "))
        assertNull(BookSeries.shelf(listOf(book(1, null))).single().name)
        for (bad in listOf(" ", "a".repeat(81), "山\n海")) {
            assertTrue(runCatching { BookSeries.requireName(bad) }.isFailure)
        }
    }
}
