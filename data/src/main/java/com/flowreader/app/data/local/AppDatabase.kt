package com.flowreader.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.flowreader.app.data.local.dao.AnnotationDao
import com.flowreader.app.data.local.dao.BookDao
import com.flowreader.app.data.local.dao.BookmarkDao
import com.flowreader.app.data.local.dao.CategoryDao
import com.flowreader.app.data.local.dao.ChapterDao
import com.flowreader.app.data.local.dao.ContentIndexDao
import com.flowreader.app.data.local.dao.ReaderStateDao
import com.flowreader.app.data.local.dao.ReadingListDao
import com.flowreader.app.data.local.dao.ReadingStatsDao
import com.flowreader.app.data.local.entity.AnnotationEntity
import com.flowreader.app.data.local.entity.BookEntity
import com.flowreader.app.data.local.entity.BookmarkEntity
import com.flowreader.app.data.local.entity.CategoryEntity
import com.flowreader.app.data.local.entity.ChapterEntity
import com.flowreader.app.data.local.entity.ContentMigrationJobEntity
import com.flowreader.app.data.local.entity.PublicationDocumentEntity
import com.flowreader.app.data.local.entity.PublicationRevisionEntity
import com.flowreader.app.data.local.entity.ReaderStateEntity
import com.flowreader.app.data.local.entity.ReadingListEntity
import com.flowreader.app.data.local.entity.ReadingListItemEntity
import com.flowreader.app.data.local.entity.ReadingSectionEntity
import com.flowreader.app.data.local.entity.ReadingSessionDeltaEntity
import com.flowreader.app.data.local.entity.ReadingStatsEntity
import com.flowreader.app.data.local.entity.TocNodeEntity

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        BookmarkEntity::class,
        AnnotationEntity::class,
        CategoryEntity::class,
        ReadingStatsEntity::class,
        ReadingListEntity::class,
        ReadingListItemEntity::class,
        // v10 内容引擎（主计划 5.2）。全部 additive，v9 的列一律保留。
        PublicationRevisionEntity::class,
        PublicationDocumentEntity::class,
        ReadingSectionEntity::class,
        TocNodeEntity::class,
        ReaderStateEntity::class,
        ContentMigrationJobEntity::class,
        ReadingSessionDeltaEntity::class
    ],
    version = 12,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun categoryDao(): CategoryDao
    abstract fun readingStatsDao(): ReadingStatsDao
    abstract fun readingListDao(): ReadingListDao
    abstract fun contentIndexDao(): ContentIndexDao
    abstract fun readerStateDao(): ReaderStateDao

    companion object {
        const val DATABASE_NAME = "flowreader_db"
    }
}
