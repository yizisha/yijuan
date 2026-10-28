package com.flowreader.app.ui.screens.library

import com.flowreader.app.domain.model.Book
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ReadingFilterTest {

    private fun book(progress: Float, lastRead: Long?) = Book(
        id = 1L,
        title = "测试",
        author = "作者",
        filePath = "/tmp/test.txt",
        readingProgress = progress,
        lastReadTime = lastRead?.let { Date(it) }
    )

    @Test
    fun allMatchesEverything() {
        assertTrue(ReadingFilter.ALL.matches(book(0f, null)))
        assertTrue(ReadingFilter.ALL.matches(book(0.5f, 1L)))
    }

    @Test
    fun unreadMeansNeverOpened() {
        assertTrue(ReadingFilter.UNREAD.matches(book(0f, null)))
        assertFalse(ReadingFilter.UNREAD.matches(book(0.1f, 1L)))
    }

    @Test
    fun readingMeansStartedButNotFinished() {
        assertTrue(ReadingFilter.READING.matches(book(0.4f, 1L)))
        assertFalse(ReadingFilter.READING.matches(book(0.4f, null)))
        assertFalse(ReadingFilter.READING.matches(book(1f, 1L)))
    }

    @Test
    fun finishedIsProgressAtTheThreshold() {
        assertTrue(ReadingFilter.FINISHED.matches(book(ReadingFilter.FINISHED_THRESHOLD, 1L)))
        assertTrue(ReadingFilter.FINISHED.matches(book(1f, 1L)))
        assertFalse(ReadingFilter.FINISHED.matches(book(0.98f, 1L)))
    }
}
