package com.flowreader.app.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.flowreader.app.data.local.AppDatabase
import com.flowreader.app.data.local.entity.BookEntity
import com.flowreader.app.data.local.entity.ChapterEntity
import com.flowreader.app.util.CacheManager
import com.flowreader.app.util.ChapterTextSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SearchRepositoryDatabaseTest {
    @Test fun realRoomTextCanBeSearchedWithoutAnyFtsDatabaseOrIndexRebuild() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val a = db.bookDao().insertBook(BookEntity(title = "甲书", author = "作者", filePath = "/a.txt"))
            val b = db.bookDao().insertBook(BookEntity(title = "乙书", author = "作者", filePath = "/b.epub"))
            db.chapterDao().insertChapters(listOf(
                ChapterEntity(bookId = a, index = 7, title = "甲章", content = "😀雨夜群山，山间薄雾渐起。"),
                ChapterEntity(bookId = b, index = 11, title = "乙章", content = "这是100%_原文，不是通配符。")
            ))
            val chapters = ChapterRepositoryImpl(db.chapterDao(), CacheManager(context, io.mockk.mockk(relaxed = true)))
            val engine = ChapterTextSearch(chapters)
            val repository = SearchRepositoryImpl(BookRepositoryImpl(db.bookDao()), engine)
            assertFalse(context.getDatabasePath("flowreader_fts.db").exists())
            val result = repository.searchChapters("薄雾", 20, 0).single()
            assertEquals(a, result.bookId)
            assertEquals(7, result.chapterIndex)
            assertEquals(9, result.firstStartUtf16)
            assertEquals(b, repository.searchChapters("100%_", 20, 0).single().bookId)
            assertTrue(engine.search(listOf(db.bookDao().getBookById(a)!!.toDomain()), "100%_", 20, 0).isEmpty())
            // Simulate a stale reading cache; search must read the new database text directly.
            chapters.getChapterContent(a, 7)
            val oldChapter = db.chapterDao().getChapter(a, 7)!!
            db.chapterDao().updateChapter(oldChapter.copy(content = "修改后的全新词语。"))
            assertTrue(repository.searchChapters("薄雾", 20, 0).isEmpty())
            assertEquals(a, repository.searchChapters("全新词语", 20, 0).single().bookId)
            assertFalse(context.getDatabasePath("flowreader_fts.db").exists())
            assertEquals(12, db.openHelper.writableDatabase.version)
        } finally { db.close() }
    }

    @Test fun readyEpubUsesItsCompleteActiveIndexWithoutScanningChapterText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queries = java.util.concurrent.CopyOnWriteArrayList<String>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> queries += sql }, java.util.concurrent.Executor { it.run() }).build()
        try {
            val id = db.bookDao().insertBook(BookEntity(title = "Indexed EPUB", author = "Author", filePath = "/indexed.epub",
                activeRevision = "r1", contentState = "READY"))
            db.chapterDao().insertChapters(listOf(
                ChapterEntity(bookId = id, index = 0, title = "A", content = "天地 人间"),
                ChapterEntity(bookId = id, index = 1, title = "B", content = "雨\n山")
            ))
            val sql = db.openHelper.writableDatabase
            fun section(revision: String, ordinal: Int, units: Int) {
                sql.execSQL("INSERT INTO reading_sections (bookId,revision,sectionId,ordinal,title,kind,rangesJson,mainTextUnits) VALUES(?,?,?,?,?,'NORMAL','[]',?)",
                    arrayOf(id, revision, "section-$ordinal", ordinal, "Section $ordinal", units))
            }
            section("r1", 0, 31); section("r1", 1, 97); section("stale", 0, 999)
            val repository = ChapterRepositoryImpl(db.chapterDao(), io.mockk.mockk(relaxed = true))
            queries.clear()
            assertEquals(listOf(0 to 31L, 1 to 97L), repository.getTextUnitCounts(id))
            assertFalse("Complete imported EPUB must not run the full-text REPLACE/LENGTH scan", queries.any { it.contains("LENGTH(REPLACE") })

            sql.execSQL("DELETE FROM reading_sections WHERE bookId=? AND revision='r1' AND ordinal=1", arrayOf(id))
            queries.clear()
            assertEquals(listOf(0 to 4L, 1 to 2L), repository.getTextUnitCounts(id))
            assertTrue("Incomplete indexes must use actual chapter text instead of silently dropping units", queries.any { it.contains("LENGTH(REPLACE") })

            sql.execSQL("UPDATE books SET activeRevision=NULL, contentState='LEGACY' WHERE id=?", arrayOf(id))
            assertEquals(listOf(0 to 4L, 1 to 2L), repository.getTextUnitCounts(id))
        } finally { db.close() }
    }
}
