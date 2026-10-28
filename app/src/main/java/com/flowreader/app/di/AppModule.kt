package com.flowreader.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.flowreader.app.data.local.AppDatabase
import com.flowreader.app.data.local.dao.AnnotationDao
import com.flowreader.app.data.local.dao.BookDao
import com.flowreader.app.data.local.dao.BookmarkDao
import com.flowreader.app.data.local.dao.CategoryDao
import com.flowreader.app.data.local.dao.ChapterDao
import com.flowreader.app.data.local.dao.ContentIndexDao
import com.flowreader.app.data.repository.TocRepositoryImpl
import com.flowreader.app.domain.repository.TocRepository
import com.flowreader.app.data.local.dao.ReaderStateDao
import com.flowreader.app.data.local.dao.ReadingListDao
import com.flowreader.app.data.local.dao.ReadingStatsDao
import com.flowreader.app.data.repository.*
import com.flowreader.app.domain.repository.*
import com.flowreader.app.util.epub.PublicationStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideReaderPageCache(@ApplicationContext context: Context): com.flowreader.app.ui.screens.reader.components.ReaderPageCache =
        com.flowreader.app.ui.screens.reader.components.ReaderPageCache(
            store = com.flowreader.app.ui.screens.reader.components.FileReaderPageStore(java.io.File(context.cacheDir, "reader-pages-v1"))
        )


    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_bookId_chapterIndex_position ON bookmarks(bookId, chapterIndex, position)")
        }
    }

    /** v53 reading lists. Additive only — no existing table is touched. */
    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reading_lists` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`createdTime` INTEGER NOT NULL, " +
                    "`updatedTime` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reading_list_items` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`listId` INTEGER NOT NULL, " +
                    "`bookId` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, " +
                    "`addedTime` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`listId`) REFERENCES `reading_lists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_reading_list_items_listId_bookId` " +
                    "ON `reading_list_items` (`listId`, `bookId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reading_list_items_bookId` ON `reading_list_items` (`bookId`)"
            )
        }
    }

    /**
     * v0.1.0: chapters carry the volume ("分卷") they belong to, so the table of contents can group
     * them instead of losing the structure the source file declared. Additive column, nullable.
     */
    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chapters` ADD COLUMN `volume` TEXT")
        }
    }

    /** v0.1.3: books carry the character count measured at import time (书籍详情 显示书字数). */
    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `books` ADD COLUMN `wordCount` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v10：内容引擎索引与状态（主计划 5.2）。
     *
     * 全部 **additive**：v9 的每一列都保留，旧位移/旧统计字段原样存放，绝不覆写、不倒推。
     * 唯一需要回填的是 `books.publicationId` —— 迁移内**逐行生成 UUID**，使每本旧书立即拥有
     * 可迁移身份；`activeRevision` 保持 NULL、`contentState` 保持 LEGACY，表示内容仍是旧格式，
     * 由后续的逐书重解析（S3/S10）升级，不阻塞开库、不要求用户删书重导。
     *
     * 该对象为 `internal` 以便迁移保全测试直接调用同一份 SQL（测试与生产共用一套迁移，
     * 避免测试里另写一份“看起来对”的 SQL）。
     */
    internal val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN seriesName TEXT")
            db.execSQL("ALTER TABLE books ADD COLUMN seriesOrder INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_books_seriesName ON books (seriesName)")
        }
    }

    internal val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE books ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
        }
    }

    internal val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ---- books：内容身份与激活开关
            db.execSQL("ALTER TABLE `books` ADD COLUMN `publicationId` TEXT")
            db.execSQL("ALTER TABLE `books` ADD COLUMN `activeRevision` TEXT")
            db.execSQL("ALTER TABLE `books` ADD COLUMN `contentState` TEXT NOT NULL DEFAULT 'LEGACY'")

            // 逐行回填 UUID。SQLite 没有内置 uuid()，用 randomblob 拼出 RFC 4122 v4 形态。
            db.execSQL(
                "UPDATE `books` SET `publicationId` = (" +
                    "lower(hex(randomblob(4))) || '-' || " +
                    "lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' || " +
                    "substr('89ab', abs(random()) % 4 + 1, 1) || substr(lower(hex(randomblob(2))), 2) || '-' || " +
                    "lower(hex(randomblob(6)))) " +
                    "WHERE `publicationId` IS NULL"
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_publicationId` ON `books` (`publicationId`)")

            // ---- bookmarks：内容定位与旧单位标注（旧 chapterIndex/position 保留）
            db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `locatorJson` TEXT")
            db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `locatorQuality` TEXT NOT NULL DEFAULT 'UNRESOLVED'")
            db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `legacyUnit` TEXT NOT NULL DEFAULT 'UNKNOWN'")

            // ---- annotations：范围定位（旧 startPosition/endPosition 保留）
            db.execSQL("ALTER TABLE `annotations` ADD COLUMN `rangeLocatorJson` TEXT")
            db.execSQL("ALTER TABLE `annotations` ADD COLUMN `locatorQuality` TEXT NOT NULL DEFAULT 'UNRESOLVED'")

            // ---- reading_stats：有效阅读 v2（历史 readPages/readTimeSeconds 不覆写）
            db.execSQL("ALTER TABLE `reading_stats` ADD COLUMN `visualReadMillis` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `reading_stats` ADD COLUMN `ttsReadMillis` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `reading_stats` ADD COLUMN `qualifiedTextUnits` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `reading_stats` ADD COLUMN `settledTurns` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `reading_stats` ADD COLUMN `statsVersion` INTEGER NOT NULL DEFAULT 1")

            // ---- 新表：内容索引
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `publication_revisions` (" +
                    "`bookId` INTEGER NOT NULL, `revision` TEXT NOT NULL, " +
                    "`normalizationVersion` INTEGER NOT NULL, `parserSchemaVersion` INTEGER NOT NULL, " +
                    "`sourceHash` TEXT NOT NULL, `manifestRelativePath` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                    "`totalMainTextUnits` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`bookId`, `revision`), " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_revisions_bookId` ON `publication_revisions` (`bookId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_revisions_state` ON `publication_revisions` (`state`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `publication_documents` (" +
                    "`bookId` INTEGER NOT NULL, `revision` TEXT NOT NULL, `documentKey` TEXT NOT NULL, " +
                    "`ordinal` INTEGER NOT NULL, `linear` INTEGER NOT NULL, `role` TEXT NOT NULL, " +
                    "`textLengthUtf16` INTEGER NOT NULL, `mainTextUnits` INTEGER NOT NULL, " +
                    "`manifestRelativePath` TEXT NOT NULL, `sourceHash` TEXT NOT NULL, " +
                    "PRIMARY KEY(`bookId`, `revision`, `documentKey`), " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_publication_documents_bookId_revision` " +
                    "ON `publication_documents` (`bookId`, `revision`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_publication_documents_bookId_revision_ordinal` " +
                    "ON `publication_documents` (`bookId`, `revision`, `ordinal`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reading_sections` (" +
                    "`bookId` INTEGER NOT NULL, `revision` TEXT NOT NULL, `sectionId` TEXT NOT NULL, " +
                    "`ordinal` INTEGER NOT NULL, `title` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`rangesJson` TEXT NOT NULL, `mainTextUnits` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`bookId`, `revision`, `sectionId`), " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reading_sections_bookId_revision` " +
                    "ON `reading_sections` (`bookId`, `revision`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_reading_sections_bookId_revision_ordinal` " +
                    "ON `reading_sections` (`bookId`, `revision`, `ordinal`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `toc_nodes` (" +
                    "`bookId` INTEGER NOT NULL, `revision` TEXT NOT NULL, `nodeId` TEXT NOT NULL, " +
                    "`parentId` TEXT, `siblingOrder` INTEGER NOT NULL, `label` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, `role` TEXT, `targetJson` TEXT, " +
                    "PRIMARY KEY(`bookId`, `revision`, `nodeId`), " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_toc_nodes_bookId_revision` ON `toc_nodes` (`bookId`, `revision`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_toc_nodes_bookId_revision_parentId` " +
                    "ON `toc_nodes` (`bookId`, `revision`, `parentId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_toc_nodes_bookId_revision_siblingOrder` " +
                    "ON `toc_nodes` (`bookId`, `revision`, `siblingOrder`)"
            )

            // ---- 新表：阅读状态
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reader_states` (" +
                    "`bookId` INTEGER NOT NULL, `locatorJson` TEXT, `quality` TEXT NOT NULL, " +
                    "`legacySnapshotJson` TEXT, `lastMainLocatorJson` TEXT, " +
                    "`navigationSequence` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`bookId`), " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_reader_states_bookId` ON `reader_states` (`bookId`)")

            // ---- 新表：逐书迁移任务
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `content_migration_jobs` (" +
                    "`bookId` INTEGER NOT NULL, `targetRevision` TEXT NOT NULL, `stage` TEXT NOT NULL, " +
                    "`errorCode` TEXT, `attemptCount` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`bookId`, `targetRevision`), " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_content_migration_jobs_bookId` " +
                    "ON `content_migration_jobs` (`bookId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_content_migration_jobs_stage` " +
                    "ON `content_migration_jobs` (`stage`)"
            )

            // ---- 新表：幂等阅读增量
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reading_session_deltas` (" +
                    "`deltaId` TEXT NOT NULL, `bookId` INTEGER NOT NULL, `sessionId` TEXT NOT NULL, " +
                    "`sequence` INTEGER NOT NULL, `localDate` TEXT NOT NULL, `zoneId` TEXT NOT NULL, " +
                    "`visualReadMillis` INTEGER NOT NULL, `ttsReadMillis` INTEGER NOT NULL, " +
                    "`qualifiedTextUnits` INTEGER NOT NULL, `settledTurns` INTEGER NOT NULL, " +
                    "`statsVersion` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`deltaId`), " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reading_session_deltas_bookId` " +
                    "ON `reading_session_deltas` (`bookId`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_reading_session_deltas_sessionId_sequence_localDate` " +
                    "ON `reading_session_deltas` (`sessionId`, `sequence`, `localDate`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reading_session_deltas_localDate` " +
                    "ON `reading_session_deltas` (`localDate`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reading_session_deltas_statsVersion` " +
                    "ON `reading_session_deltas` (`statsVersion`)"
            )
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).addMigrations(
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12
        ).addCallback(RESTART_READING_STATS).build()
    }

    /** User-authorized restart: only old statistics are removed; books and reader state survive.
     * The row version is the durable boundary, so reopening never erases newly recorded time.
     * Old backup rows are also excluded by the stats DAO until the next database open.
     */
    internal val RESTART_READING_STATS = object : androidx.room.RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM reading_stats WHERE statsVersion < 3")
                db.execSQL("DELETE FROM reading_session_deltas WHERE statsVersion < 3")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    @Provides
    @Singleton
    fun provideContentIndexDao(database: AppDatabase): ContentIndexDao = database.contentIndexDao()

    /**
     * 内容包存储（主计划 5.1）。正文落在 `files/books/...`，**不放 cache**。
     */
    @Provides
    @Singleton
    fun providePublicationStore(@ApplicationContext context: Context): PublicationStore = PublicationStore(context)

    @Provides
    @Singleton
    fun provideReaderStateDao(database: AppDatabase): ReaderStateDao = database.readerStateDao()

    @Provides
    @Singleton
    fun provideBookDao(database: AppDatabase): BookDao = database.bookDao()

    @Provides
    @Singleton
    fun provideChapterDao(database: AppDatabase): ChapterDao = database.chapterDao()

    @Provides
    @Singleton
    fun provideBookmarkDao(database: AppDatabase): BookmarkDao = database.bookmarkDao()

    @Provides
    @Singleton
    fun provideAnnotationDao(database: AppDatabase): AnnotationDao = database.annotationDao()

    @Provides
    @Singleton
    fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()

    @Provides
    @Singleton
    fun provideReadingStatsDao(database: AppDatabase): ReadingStatsDao = database.readingStatsDao()

    @Provides
    @Singleton
    fun provideReadingListDao(database: AppDatabase): ReadingListDao = database.readingListDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBookRepository(impl: BookRepositoryImpl): BookRepository

    @Binds
    @Singleton
    abstract fun bindChapterRepository(impl: ChapterRepositoryImpl): ChapterRepository

    @Binds
    @Singleton
    abstract fun bindBookmarkRepository(impl: BookmarkRepositoryImpl): BookmarkRepository

    @Binds
    @Singleton
    abstract fun bindAnnotationRepository(impl: AnnotationRepositoryImpl): AnnotationRepository

    @Binds
    @Singleton
    abstract fun bindCategoryRepository(impl: CategoryRepositoryImpl): CategoryRepository

    @Binds
    @Singleton
    abstract fun bindReadingStatsRepository(impl: ReadingStatsRepositoryImpl): ReadingStatsRepository

    @Binds
    @Singleton
    abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository

    @Binds
    @Singleton
    abstract fun bindReadingListRepository(impl: ReadingListRepositoryImpl): ReadingListRepository

    @Binds
    @Singleton
    abstract fun bindTocRepository(impl: TocRepositoryImpl): TocRepository
}
