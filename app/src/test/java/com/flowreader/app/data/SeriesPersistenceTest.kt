package com.flowreader.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.flowreader.app.data.local.AppDatabase
import com.flowreader.app.data.local.entity.BookEntity
import com.flowreader.app.data.repository.BookRepositoryImpl
import com.flowreader.app.di.DatabaseModule
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SeriesPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun database() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()

    @Test fun realV11SchemaMigratesWithoutChangingBooksOrNotesAndMatchesRoomV12() = runBlocking {
        val file = listOf(File("../data/schemas/com.flowreader.app.data.local.AppDatabase/11.json"), File("data/schemas/com.flowreader.app.data.local.AppDatabase/11.json")).first { it.isFile }
        val entities = JSONObject(file.readText()).getJSONObject("database").getJSONArray("entities")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null).callback(object : SupportSQLiteOpenHelper.Callback(11) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.getJSONArray("indices")
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    db.execSQL("INSERT INTO books (id,title,author,filePath,description,fileSize,wordCount,format,totalChapters,currentChapter,currentPosition,readingProgress,addedTime,tags,isFavorite) VALUES(1,'旧书','作者','old.txt','简介',1024,90,'TXT',4,2,19,0.6,1,'文学',1)")
                    db.execSQL("INSERT INTO annotations (id,bookId,chapterIndex,startPosition,endPosition,selectedText,note,color,type,createdTime,modifiedTime) VALUES(1,1,2,3,6,'摘录','旧笔记','YELLOW','HIGHLIGHT',1,1)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        val current = database()
        try {
            val old = helper.writableDatabase
            DatabaseModule.MIGRATION_11_12.migrate(old)
            assertEquals(TableInfo.read(current.openHelper.writableDatabase, "books"), TableInfo.read(old, "books"))
            old.query("SELECT title,currentChapter,currentPosition,readingProgress,isFavorite,seriesName,seriesOrder FROM books").use {
                assertTrue(it.moveToFirst()); assertEquals("旧书", it.getString(0)); assertEquals(2, it.getInt(1)); assertEquals(19, it.getInt(2))
                assertEquals(.6, it.getDouble(3), .001); assertEquals(1, it.getInt(4)); assertTrue(it.isNull(5)); assertEquals(0, it.getInt(6))
            }
            old.query("SELECT note FROM annotations").use { it.moveToFirst(); assertEquals("旧笔记", it.getString(0)) }
        } finally { current.close(); helper.close() }
    }

    @Test fun assigningOrderingAndMovingKeepUnrelatedBookData() = runBlocking {
        val db = database()
        try {
            val dao = db.bookDao(); val repo = BookRepositoryImpl(dao)
            val ids = (1..3).map { dao.insertBook(BookEntity(title = "$it", author = "作者", filePath = "$it", isFavorite = true, categoryId = 8, readingProgress = .4f)) }
            repo.setBooksSeries(ids, " 山海 ")
            repo.reorderSeries("山海", ids.reversed())
            assertEquals(ids.reversed(), dao.getSeriesBooks("山海").map { it.id })
            val first = dao.getBookById(ids.first())!!
            assertEquals(first, BookEntity.fromDomain(first.toDomain()))
            repo.updateBook(first.toDomain().copy(seriesName = null, seriesOrder = 0, title = "改名"))
            repo.updateReadingProgress(first.id, 1, 29, .6f)
            repo.moveBooksToCategory(listOf(first.id), 9)
            val kept = dao.getBookById(first.id)!!
            assertEquals("山海", kept.seriesName); assertEquals(3, kept.seriesOrder); assertTrue(kept.isFavorite); assertEquals(9L, kept.categoryId)
            repo.setBooksSeries(listOf(ids[1]), "其他")
            assertEquals(2, dao.getSeriesBooks("山海").size)
            repo.setBooksSeries(listOf(first.id), null)
            assertNull(dao.getBookById(first.id)!!.seriesName)
        } finally { db.close() }
    }

    @Test fun staleOrderingAndFailedAssignmentsRollBackAtomically() = runBlocking {
        val db = database()
        try {
            val dao = db.bookDao(); val repo = BookRepositoryImpl(dao)
            val a = dao.insertBook(BookEntity(title = "一", author = "", filePath = "a"))
            val b = dao.insertBook(BookEntity(title = "二", author = "", filePath = "b"))
            repo.setBooksSeries(listOf(a, b), "山海")
            assertTrue(runCatching { repo.reorderSeries("山海", listOf(b)) }.isFailure)
            assertTrue(runCatching { repo.setBooksSeries(listOf(a, 99999), "其他") }.isFailure)
            assertEquals(listOf(a, b), dao.getSeriesBooks("山海").map { it.id })
            assertTrue(dao.getSeriesBooks("其他").isEmpty())
            repo.renameSeries("山海", "长篇")
            assertEquals(listOf(a, b), dao.getSeriesBooks("长篇").map { it.id })
        } finally { db.close() }
    }
}
