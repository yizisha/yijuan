package com.flowreader.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.flowreader.app.data.local.AppDatabase
import com.flowreader.app.data.local.entity.BookEntity
import com.flowreader.app.data.local.entity.ReadingStatsEntity
import com.flowreader.app.data.local.entity.ReaderStateEntity
import com.flowreader.app.data.repository.ReadingStatsRepositoryImpl
import com.flowreader.app.di.DatabaseModule
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReadingStatsRestartTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private inline fun <T> AppDatabase.withDatabase(block: (AppDatabase) -> T): T =
        try { block(this) } finally { close() }

    @Test fun resetCallbackRemovesOnlyOldCountersAndIsIdempotentForNewTime() = runBlocking {
        // The disk/cold-process lifecycle is covered by ReleaseExperienceTest on Android.
        // Exercise the exact production callback here without a host Windows WAL filesystem.
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build().withDatabase { db ->
                db.bookDao().insertBook(BookEntity(id = 1, title = "Keep this book", author = "Author", filePath = "book.txt", currentChapter = 7, currentPosition = 29))
                db.readerStateDao().saveIfNewer(ReaderStateEntity(bookId = 1, locatorJson = "kept locator", navigationSequence = 17, updatedAtMillis = 100))
                db.readingStatsDao().insertStats(ReadingStatsEntity(bookId = 1, date = LocalDate.now().minusDays(1).toString(), readTimeSeconds = 86 * 3600, statsVersion = 1))
                db.readingStatsDao().insertStats(ReadingStatsEntity(bookId = 1, date = LocalDate.now().toString(), readTimeSeconds = 17, statsVersion = 3))
            repeat(2) {
                    DatabaseModule.RESTART_READING_STATS.onOpen(db.openHelper.writableDatabase)
                    assertEquals(17L, db.readingStatsDao().getTotalReadTime())
                    assertEquals(1, db.readingStatsDao().getAllStats().size)
                    db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM reading_stats").use { cursor ->
                        cursor.moveToFirst()
                        assertEquals("Old rows are actually removed, not just hidden", 1, cursor.getInt(0))
                    }
                    assertEquals("kept locator", db.readerStateDao().getState(1)!!.locatorJson)
                    assertEquals(17L, db.readerStateDao().getSequence(1))
                    val book = db.bookDao().getBookById(1)!!
                    assertEquals("Keep this book", book.title)
                    assertEquals(7, book.currentChapter)
                    assertEquals(29, book.currentPosition)
            }
        }
    }

    @Test fun anOldBackupCannotBringBackBadTimeAndNewReadingStartsAtZero() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build().withDatabase { db ->
            val date = LocalDate.now().toString()
            // Simulate restoring a pre-restart backup while the database is already open.
            db.readingStatsDao().insertStats(ReadingStatsEntity(bookId = 1, date = date, readPages = 900, readTimeSeconds = 86 * 3600, statsVersion = 1))
            assertNull(db.readingStatsDao().getTodayReadTime(date))
            assertTrue(db.readingStatsDao().getAllStats().isEmpty())
            val repository = ReadingStatsRepositoryImpl(db.readingStatsDao(), db.bookDao())
            repository.updateTodayStats(1, 2, 12)
            val row = db.readingStatsDao().getStatsByDate(1, date)!!
            assertEquals(3, row.statsVersion)
            assertEquals(12L, row.readTimeSeconds)
            assertEquals(2, row.readPages)
            repository.updateTodayStats(1, 1, 8)
            assertEquals(20L, db.readingStatsDao().getTotalReadTime())
        }
    }
}
