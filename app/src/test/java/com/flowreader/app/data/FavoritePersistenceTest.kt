package com.flowreader.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.flowreader.app.data.local.AppDatabase
import com.flowreader.app.data.local.entity.BookEntity
import com.flowreader.app.data.repository.BackupRepositoryImpl
import com.flowreader.app.data.repository.BookRepositoryImpl
import com.flowreader.app.di.DatabaseModule
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoritePersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun database(file: File? = null): AppDatabase = (
        if (file == null) {
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        } else {
            Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
        }
        )
        .allowMainThreadQueries().addMigrations(DatabaseModule.MIGRATION_10_11, DatabaseModule.MIGRATION_11_12).build()

    @Test fun realV10MigrationPreservesBooksNotesAndFavoritesAcrossReopen() = runBlocking {
        val file = File.createTempFile("favorite-v10-", ".db", context.cacheDir).also { it.delete() }
        val schema = listOf(
            File("../data/schemas/com.flowreader.app.data.local.AppDatabase/10.json"),
            File("data/schemas/com.flowreader.app.data.local.AppDatabase/10.json")
        ).first { it.exists() }
        val entities = JSONObject(schema.readText()).getJSONObject("database").getJSONArray("entities")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(file.absolutePath).callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (i in 0 until entities.length()) {
                            val e = entities.getJSONObject(i)
                            val table = e.getString("tableName")
                            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = e.getJSONArray("indices")
                            for (j in 0 until indices.length()) db.execSQL(
                                indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
                            )
                        }
                        db.execSQL(
                            "INSERT INTO books (id,title,author,filePath,description,fileSize,wordCount,format,totalChapters,currentChapter,currentPosition,readingProgress,addedTime,tags) VALUES (1,'旧书','作者','/book.txt','简介',100,900,'TXT',10,3,57,0.42,123,'文学')"
                        )
                        db.execSQL(
                            "INSERT INTO annotations (id,bookId,chapterIndex,startPosition,endPosition,selectedText,note,color,type,createdTime,modifiedTime) VALUES (1,1,3,4,8,'摘录','旧笔记','YELLOW','HIGHLIGHT',1,1)"
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        helper.writableDatabase
        helper.close()
        var db = database(file)
        try {
            val old = db.bookDao().getBookById(1)!!
            assertFalse(old.isFavorite)
            assertEquals("旧书", old.title)
            assertEquals(57, old.currentPosition)
            assertEquals(.42f, old.readingProgress, .001f)
            assertEquals("旧笔记", db.annotationDao().getAnnotationsListByBookId(1).single().note)
            assertEquals(1, db.bookDao().setFavorite(1, true))
            db.close()
            db = database(file)
            assertTrue(db.bookDao().getBookById(1)!!.isFavorite)
            assertEquals(12, db.openHelper.writableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test fun staleMetadataProgressAndGroupWritesCannotEraseFavorite() = runBlocking {
        val db = database()
        try {
            val dao = db.bookDao()
            val id = dao.insertBook(BookEntity(title = "书", author = "作者", filePath = "/book.txt"))
            val stale = dao.getBookById(id)!!.toDomain()
            val repository = BookRepositoryImpl(dao)
            repository.setFavorite(id, true)
            repository.updateBook(stale.copy(title = "改名", tags = listOf("文学")))
            repository.updateReadingProgress(id, 2, 14, .3f)
            repository.moveBooksToCategory(listOf(id), null)
            val current = dao.getBookById(id)!!
            assertTrue(current.isFavorite)
            assertEquals("改名", current.title)
            assertEquals(2, current.currentChapter)
            assertTrue(BookEntity.fromDomain(current.toDomain()).isFavorite)
            repository.setFavorite(id, false)
            assertFalse(dao.getAllBooks().first().single().isFavorite)
        } finally {
            db.close()
        }
    }

    private fun backup(db: AppDatabase) = BackupRepositoryImpl(
        context,
        db,
        db.bookDao(),
        db.chapterDao(),
        db.bookmarkDao(),
        db.annotationDao(),
        db.categoryDao(),
        db.readingStatsDao()
    )

    @Test fun newAndLegacyBackupsPreserveFavoritesWithTheirDefinedDefaults() = runBlocking {
        val db = database()
        val file = File.createTempFile("favorite-backup-", ".json", context.cacheDir)
        try {
            val dao = db.bookDao()
            val source = File(context.cacheDir, "favorite.txt").apply { writeText("收藏书正文") }
            val id = dao.insertBook(BookEntity(title = "收藏书", author = "作者", filePath = source.absolutePath, isFavorite = true))
            backup(db).exportDataToFile(file).getOrThrow()
            val bookRow = java.util.zip.ZipFile(file).use { zip ->
                JSONObject(zip.getInputStream(zip.getEntry("tables/books.jsonl")).bufferedReader().readText().trim())
            }
            assertEquals(1, bookRow.getInt("isFavorite"))
            val json = JSONObject().put("books", org.json.JSONArray().put(bookRow.put("isFavorite", true)))
            dao.setFavorite(id, false)
            backup(db).importDataFromFile(file).getOrThrow()
            assertTrue(dao.getBookById(id)!!.isFavorite)
            json.getJSONArray("books").getJSONObject(0).remove("isFavorite")
            file.writeText(json.toString())
            backup(db).importDataFromFile(file).getOrThrow()
            assertTrue(dao.getBookById(id)!!.isFavorite)
            dao.deleteBookById(id)
            backup(db).importDataFromFile(file).getOrThrow()
            assertFalse(dao.getAllBooks().first().single().isFavorite)
        } finally {
            db.close()
        }
    }
}
