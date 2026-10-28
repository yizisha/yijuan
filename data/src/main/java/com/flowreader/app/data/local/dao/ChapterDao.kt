package com.flowreader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.flowreader.app.data.local.entity.ChapterContentRow
import com.flowreader.app.data.local.entity.ChapterEntity
import com.flowreader.app.data.local.entity.ChapterMetadata
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index`")
    fun getChaptersByBookId(bookId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index`")
    suspend fun getChaptersListByBookId(bookId: Long): List<ChapterEntity>

    @Query("SELECT id, bookId, `index`, title, startPosition, endPosition, volume FROM chapters WHERE bookId = :bookId ORDER BY `index`")
    suspend fun getChapterMetadataList(bookId: Long): List<ChapterMetadata>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND `index` = :index")
    suspend fun getChapter(bookId: Long, index: Int): ChapterEntity?

    @Query("SELECT content FROM chapters WHERE bookId = :bookId AND `index` = :index")
    suspend fun getChapterContent(bookId: Long, index: Int): String?

    /**
     * 批量取多章正文：一次查询代替「结果条数」次单章查询。
     *
     * 搜索结果验证命中位置时必须回到正文重新定位，若逐条查库，一次搜索会打出上百次
     * 单章查询（N+1），在真机上表现为明显卡顿。
     */
    @Query("SELECT `index`, content FROM chapters WHERE bookId = :bookId AND `index` IN (:indexes)")
    suspend fun getChapterContents(bookId: Long, indexes: List<Int>): List<ChapterContentRow>

    /**
     * Total character count of a book's stored text.
     *
     * SQLite does the counting, so backfilling 字数 for books imported before the field existed
     * costs one aggregate query instead of loading every chapter into memory.
     */
    @Query("SELECT COALESCE(SUM(LENGTH(content)), 0) FROM chapters WHERE bookId = :bookId")
    suspend fun getTotalContentLength(bookId: Long): Long

    /**
     * 按章返回**非空白**字符数，用于构建全书进度权重（主计划 10.2 / INV11）。
     *
     * 逐项去掉换行、回车、制表、半角空格与全角空格后取长度，因此不必把正文加载进内存，也不
     * 依赖当前已加载的章节集合。返回的是 Unicode code point 数（SQLite `length()` 对 TEXT 的
     * 语义），与 `feature:reader` 的 `TextUnits.count` 口径一致。
     */
    @Query(
        "SELECT `index` AS chapterIndex, " +
            "LENGTH(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(content, char(10), ''), char(13), ''), " +
            "char(9), ''), ' ', ''), char(12288), '')) AS units " +
            "FROM chapters WHERE bookId = :bookId ORDER BY `index`"
    )
    suspend fun getTextUnitCounts(bookId: Long): List<ChapterTextUnits>

    /** Ready publications already measured these units at import; do not rescan their text. */
    @Query(
        "SELECT s.ordinal AS chapterIndex, s.mainTextUnits AS units FROM reading_sections AS s " +
            "JOIN books AS b ON b.id = s.bookId AND b.activeRevision = s.revision " +
            "JOIN chapters AS c ON c.bookId = s.bookId AND c.`index` = s.ordinal " +
            "WHERE s.bookId = :bookId AND b.contentState = 'READY' ORDER BY s.ordinal"
    )
    suspend fun getIndexedTextUnitCounts(bookId: Long): List<ChapterTextUnits>

    /**
     * 只取每章正文**长度**，不加载正文（合成 locator 恢复清单用）。
     *
     * 逐章 `getChapterContent` 会把「打开书」变成 N+1：真机 1434 章的书 = 1434 次正文读取，
     * 而恢复器实际只需要**一章**正文。长度一次聚合查询就够。
     */
    @Query(
        "SELECT `index` AS chapterIndex, LENGTH(content) AS lengthUtf16 " +
            "FROM chapters WHERE bookId = :bookId ORDER BY `index`"
    )
    suspend fun getChapterTextLengths(bookId: Long): List<ChapterTextLength>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getChapterById(id: Long): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: ChapterEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Update
    suspend fun updateChapter(chapter: ChapterEntity)

    @Delete
    suspend fun deleteChapter(chapter: ChapterEntity)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersByBookId(bookId: Long)

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    suspend fun getChapterCount(bookId: Long): Int
}

/** 一章的（章序号，非空白字符数）投影，用于进度权重；不携带正文。 */
data class ChapterTextUnits(
    val chapterIndex: Int,
    val units: Long
)

/**
 * 一章的（章序号，正文长度）投影，**不携带正文**。
 *
 * 长度来自 SQLite `length()`，即**字符数（code point）**；在 BMP 范围内与 UTF-16 code unit 相同
 * （真机中文正文即如此）。调用方若需要精确 UTF-16 长度，必须用已加载正文的 `.length` 覆盖。
 */
data class ChapterTextLength(
    val chapterIndex: Int,
    val lengthUtf16: Int
)
