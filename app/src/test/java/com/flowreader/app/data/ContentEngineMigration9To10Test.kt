package com.flowreader.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.flowreader.app.data.local.AppDatabase
import com.flowreader.app.data.local.dao.ReaderStateSaveOutcome
import com.flowreader.app.data.local.entity.BookEntity
import com.flowreader.app.data.local.entity.ReaderStateEntity
import com.flowreader.app.data.local.entity.ReadingSessionDeltaEntity
import com.flowreader.app.di.DatabaseModule
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * S2 / Gate G2：v9 → v10 迁移的 **schema 校验与数据保全**（主计划 5.2、T084—T087）。
 *
 * 做法刻意“真实”：
 * 1. v9 的建表语句**逐字取自 Room 自己导出的 `data/schemas/.../9.json`**（`createSql` 字段），
 *    而不是测试里手写一份“看起来对”的 DDL —— 手写 DDL 一旦与真实历史 schema 有偏差，测试就会
 *    假装通过。
 * 2. 建库并写入带特征值的旧数据，`user_version = 9`。
 * 3. 用 **Room 打开**并挂上生产用的同一个 `MIGRATION_9_10`：Room 会真正执行迁移，然后用它自己
 *    生成的 v10 schema 校验结果（列类型/非空/默认值不符即抛异常）。这就是 G2 要求的 schema 校验。
 * 4. 通过 DAO 查询断言旧数据一条不少、旧列一字不改、新列按默认值就位、`publicationId` 已回填且唯一。
 */
@RunWith(RobolectricTestRunner::class)
class ContentEngineMigration9To10Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 找到 Room 导出的 v9 schema。Gradle 单测的工作目录是模块目录（app/）。 */
    private fun schemaV9File(): File {
        val candidates = listOf(
            File("../data/schemas/com.flowreader.app.data.local.AppDatabase/9.json"),
            File("data/schemas/com.flowreader.app.data.local.AppDatabase/9.json")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 Room 导出的 9.json；候选路径：${candidates.joinToString { it.absolutePath }}")
    }

    /**
     * 用 9.json 里的 `createSql` **与 `indices`** 重建 v9 库。
     *
     * 这里必须连索引一起建。曾经的版本只建表、注释写着"索引对本次断言无关" —— 结果正好绕过了
     * Room `TableInfo` 校验里最要命的一条：迁移建了 `index_books_publicationId` 而实体没声明，
     * **真机每次开库都抛 `Migration didn't properly handle`**，单测却全绿。
     * 夹具比真机"干净"时，迁移测试就是在测一个不存在的世界。
     */
    private fun createV9Database(file: File) {
        val json = JSONObject(schemaV9File().readText())
        val entities = json.getJSONObject("database").getJSONArray("entities")

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath)
            .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val tableName = entity.getString("tableName")
                        val createSql = entity.getString("createSql").replace("\${TABLE_NAME}", tableName)
                        db.execSQL(createSql)
                        val indices = entity.optJSONArray("indices") ?: continue
                        for (index in 0 until indices.length()) {
                            val indexJson = indices.getJSONObject(index)
                            val columns = indexJson.getJSONArray("columnNames")
                            val columnList = (0 until columns.length()).joinToString(", ") { "`${columns.getString(it)}`" }
                            val unique = if (indexJson.optBoolean("unique", false)) "UNIQUE " else ""
                            db.execSQL(
                                "CREATE ${unique}INDEX IF NOT EXISTS `${indexJson.getString("name")}` " +
                                    "ON `$tableName` ($columnList)"
                            )
                        }
                    }
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val db = FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
        try {
            seedV9Rows(db, entities)
        } finally {
            db.close()
        }
    }

    /** 写入带特征值的旧数据：迁移后要能逐条认出来。 */
    private fun seedV9Rows(db: SupportSQLiteDatabase, entities: JSONArray) {
        fun hasTable(name: String) = (0 until entities.length()).any {
            entities.getJSONObject(it).getString("tableName") == name
        }

        db.execSQL(
            "INSERT INTO `books` (`id`, `title`, `author`, `filePath`, `coverPath`, `description`, " +
                "`fileSize`, `wordCount`, `format`, `totalChapters`, `currentChapter`, `currentPosition`, " +
                "`readingProgress`, `lastReadTime`, `addedTime`, `categoryId`, `tags`) " +
                "VALUES (1, '旧书甲', '作者甲', '/tmp/a.txt', NULL, '', 100, 5000, 'TXT', 3, 1, 777, 0.33, 111, 222, NULL, 'a,b'), " +
                "(2, '旧书乙', '作者乙', '/tmp/b.epub', NULL, '', 200, 9000, 'EPUB', 5, 0, 0, 0.0, NULL, 333, NULL, '')"
        )
        db.execSQL(
            "INSERT INTO `bookmarks` (`id`, `bookId`, `chapterIndex`, `position`, `text`, `createdTime`) " +
                "VALUES (1, 1, 1, 777, '划线的句子', 555)"
        )
        db.execSQL(
            "INSERT INTO `annotations` (`id`, `bookId`, `chapterIndex`, `startPosition`, `endPosition`, " +
                "`selectedText`, `note`, `color`, `type`, `createdTime`, `modifiedTime`) " +
                "VALUES (1, 1, 1, 10, 20, '高亮文字', '我的批注', 'YELLOW', 'HIGHLIGHT', 666, 666)"
        )
        db.execSQL(
            "INSERT INTO `reading_stats` (`id`, `bookId`, `date`, `readPages`, `readChapters`, " +
                "`readTimeSeconds`, `lastReadTime`) VALUES (1, 1, '2026-09-01', 12, 2, 3600, 999)"
        )
        if (hasTable("categories")) {
            // v9 的 categories 只有 (id, name) —— 列名以 9.json 为准，不凭记忆写。
            db.execSQL("INSERT INTO `categories` (`id`, `name`) VALUES (1, '分组甲')")
        }
        if (hasTable("chapters")) {
            db.execSQL(
                "INSERT INTO `chapters` (`id`, `bookId`, `index`, `title`, `content`, `startPosition`, " +
                    "`endPosition`, `volume`) VALUES (1, 1, 1, '第二章', '正文内容', 0, 4, '第一卷')"
            )
        }
        // 显式把版本压回 9，确保 Room 走 onUpgrade → 迁移路径。
        db.execSQL("PRAGMA user_version = 9")
    }

    private fun migrateWithRoom(file: File): AppDatabase {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .addMigrations(DatabaseModule.MIGRATION_9_10, DatabaseModule.MIGRATION_10_11, DatabaseModule.MIGRATION_11_12)
            .allowMainThreadQueries()
            .build()
        // 打开即触发迁移 + Room 自身的 schema 校验（不匹配会抛 IllegalStateException）。
        database.openHelper.writableDatabase
        return database
    }

    @Test
    fun migrationPreservesEveryLegacyRowAndColumn() {
        val file = File.createTempFile("flowreader-migration-", ".db").also { it.delete() }
        createV9Database(file)

        val database = migrateWithRoom(file)
        try {
            val books = database.bookDao().getAllBooksSync()
            assertEquals("两本旧书一条都不能少", 2, books.size)

            val first = books.first { it.id == 1L }
            // 旧列逐项保持原值——迁移不得覆写或倒推。
            assertEquals("旧书甲", first.title)
            assertEquals("作者甲", first.author)
            assertEquals(1, first.currentChapter)
            assertEquals("旧的裸位置必须原样保留（不得被当成字符偏移写进新字段）", 777, first.currentPosition)
            assertEquals(0.33f, first.readingProgress, 0.0001f)
            assertEquals(5000, first.wordCount)
            assertEquals("a,b", first.tags)

            val second = books.first { it.id == 2L }
            assertEquals("旧书乙", second.title)
            assertEquals(0, second.currentPosition)
        } finally {
            database.close()
        }
    }

    @Test
    fun newContentStateColumnsAreAdditiveWithDeclaredDefaults() {
        val file = File.createTempFile("flowreader-migration-", ".db").also { it.delete() }
        createV9Database(file)

        val database = migrateWithRoom(file)
        try {
            val rows = database.bookDao().getAllBooksSync()
            // 每本旧书都拿到 publicationId（迁移内 randomblob 回填的 UUID 形态），且互不相同。
            val ids = rows.map { it.publicationId }
            assertTrue("publicationId 必须逐行回填", ids.all { !it.isNullOrBlank() })
            assertEquals("回填的 UUID 不能重复", rows.size, ids.distinct().size)
            assertTrue(
                "回填值应为 UUID 形态",
                ids.all { it!!.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) }
            )
            // 内容状态保持 LEGACY、激活修订为 null：旧书仍可读，等待逐书重解析。
            assertTrue(rows.all { it.contentState == BookEntity.CONTENT_STATE_LEGACY })
            assertTrue(rows.all { it.activeRevision == null })
        } finally {
            database.close()
        }
    }

    @Test
    fun legacyBookmarkAndAnnotationAndStatsSurviveUntouched() {
        val file = File.createTempFile("flowreader-migration-", ".db").also { it.delete() }
        createV9Database(file)

        val database = migrateWithRoom(file)
        try {
            runBlocking {
                val bookmarks = database.bookmarkDao().getBookmarksListByBookId(1L)
                assertEquals(1, bookmarks.size)
                assertEquals("划线的句子", bookmarks[0].text)
                assertEquals(777, bookmarks[0].position)

                val annotations = database.annotationDao().getAnnotationsListByBookId(1L)
                assertEquals(1, annotations.size)
                assertEquals("高亮文字", annotations[0].selectedText)
                assertEquals("我的批注", annotations[0].note)
                assertEquals(10, annotations[0].startPosition)
                assertEquals(20, annotations[0].endPosition)
            }
        } finally {
            database.close()
        }
    }

    /** 新表存在且默认可写：种子 delta 与 reader_state，验证唯一键与序列检查。 */
    @Test
    fun newTablesAreUsableAndDeltaCommitIsIdempotent() {
        val file = File.createTempFile("flowreader-migration-", ".db").also { it.delete() }
        createV9Database(file)

        val database = migrateWithRoom(file)
        try {
            runBlocking {
                val stateDao = database.readerStateDao()

                // 序列检查：旧的 sequence 必须被拒绝，不得倒写覆盖。
                val saved = stateDao.saveIfNewer(
                    ReaderStateEntity(bookId = 1L, locatorJson = "{\"v\":2}", navigationSequence = 5, updatedAtMillis = 10)
                )
                assertEquals(ReaderStateSaveOutcome.SAVED, saved)
                val stale = stateDao.saveIfNewer(
                    ReaderStateEntity(bookId = 1L, locatorJson = "{\"v\":1}", navigationSequence = 3, updatedAtMillis = 20)
                )
                assertEquals(ReaderStateSaveOutcome.STALE_REJECTED, stale)
                assertEquals("{\"v\":2}", stateDao.getState(1L)?.locatorJson)
                assertEquals(5L, stateDao.getSequence(1L))

                // 幂等增量：同一 (sessionId, sequence, localDate) 提交两次只累加一次。
                val delta = ReadingSessionDeltaEntity(
                    deltaId = "d1",
                    bookId = 1L,
                    sessionId = "s1",
                    sequence = 1,
                    localDate = "2026-09-01",
                    zoneId = "Asia/Shanghai",
                    visualReadMillis = 90_000,
                    ttsReadMillis = 0,
                    qualifiedTextUnits = 1_200,
                    settledTurns = 0,
                    statsVersion = 2
                )
                assertTrue("首次提交应累加", stateDao.commitDelta(delta, now = 1_000))
                assertEquals(1, stateDao.countDeltas(1L))

                // 换一个 deltaId 但同一 (sessionId, sequence, localDate)：命中唯一键，必须被识别为
                // 已消费，不得再次累加日统计（T080）。
                val retry = delta.copy(deltaId = "d1-retry")
                assertTrue("重复增量不得再次累加", !stateDao.commitDelta(retry, now = 2_000))
                assertEquals(1, stateDao.countDeltas(1L))

                // 命中旧行的 v2 列已就位、历史列未被写入。
                val today = database.readingStatsDao().getStatsByDate(1L, "2026-09-01")
                assertNotNull(today)
                assertEquals("历史页数不得被 v2 覆盖", 12, today!!.readPages)
                assertEquals("历史时长不得被 v2 覆盖", 3600L, today.readTimeSeconds)
                assertEquals(90_000L, today.visualReadMillis)
                assertEquals(1_200L, today.qualifiedTextUnits)
                assertEquals("旧行应升级为 statsVersion=2", 2, today.statsVersion)
            }
        } finally {
            database.close()
        }
    }

    /** 迁移后的库能被 Room 正常打开（schema 校验通过）且新表为空。 */
    @Test
    fun migratedDatabasePassesRoomSchemaValidationWithEmptyContentIndex() {
        val file = File.createTempFile("flowreader-migration-", ".db").also { it.delete() }
        createV9Database(file)

        val database = migrateWithRoom(file)
        try {
            runBlocking {
                assertEquals(0, database.contentIndexDao().countRevisions(1L))
                assertEquals(0, database.contentIndexDao().countDocuments(1L, "r1"))
                assertEquals(0, database.contentIndexDao().countSections(1L, "r1"))
                assertEquals(0, database.contentIndexDao().countTocNodes(1L, "r1"))
                assertEquals(0, database.readerStateDao().countStates())
            }
            assertNull(runBlocking { database.readerStateDao().getState(2L) })
        } finally {
            database.close()
        }
    }

    /**
     * v10 内容身份字段必须能在 **domain ↔ entity 往返**中存活。
     *
     * 这是实测抓出来的缺陷：`BookEntity.toDomain()` / `fromDomain()` 起初漏了
     * `publicationId` 与 `activeRevision`，于是任何一次 load→save 都会把它们丢掉 ——
     * 重新导入同一本书会拿到新的 UUID，旧内容包立刻变成孤儿引用。
     * 迁移回填 UUID 的意义也一起被抵消。
     */
    @Test
    fun contentIdentityFieldsSurviveDomainEntityRoundTrip() {
        val entity = BookEntity(
            id = 42L,
            title = "书",
            author = "作者",
            filePath = "/books/a.epub",
            publicationId = "pub-1111",
            activeRevision = "rev-2222",
            contentState = BookEntity.CONTENT_STATE_READY
        )

        val domain = entity.toDomain()
        assertEquals("pub-1111", domain.publicationId)
        assertEquals("rev-2222", domain.activeRevision)

        val back = BookEntity.fromDomain(domain)
        assertEquals("pub-1111", back.publicationId)
        assertEquals("rev-2222", back.activeRevision)
        assertEquals(BookEntity.CONTENT_STATE_READY, back.contentState)
    }

    /** legacy 书（迁移未完成）仍允许 publicationId/activeRevision 为 null，不能因此崩。 */
    @Test
    fun legacyBookWithoutContentIdentityRoundTripsAsNull() {
        val entity = BookEntity(id = 7L, title = "旧书", author = "作者", filePath = "/books/old.epub")

        val domain = entity.toDomain()
        assertNull(domain.publicationId)
        assertNull(domain.activeRevision)

        val back = BookEntity.fromDomain(domain)
        assertNull(back.publicationId)
        assertNull(back.activeRevision)
        assertEquals(BookEntity.CONTENT_STATE_LEGACY, back.contentState)
    }
}
