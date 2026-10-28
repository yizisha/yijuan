package com.flowreader.app.domain.repository

import com.flowreader.app.domain.model.Chapter
import kotlinx.coroutines.flow.Flow

interface ChapterRepository {
    fun getChaptersByBookId(bookId: Long): Flow<List<Chapter>>
    suspend fun getChaptersListByBookId(bookId: Long): List<Chapter>
    suspend fun getChapterMetadataList(bookId: Long): List<Chapter>
    suspend fun getChapter(bookId: Long, index: Int): Chapter?
    suspend fun getChapterContent(bookId: Long, index: Int): String?

    /**
     * 批量取正文（章序号 → 正文）。**搜索结果验证命中位置时必须用它**，
     * 逐条 [getChapterContent] 会产生 N+1 查询，一次搜索打出上百次单章查询。
     */
    suspend fun getChapterContents(bookId: Long, indexes: List<Int>): Map<Int, String>

    /** Canonical search reads bypass the reader's LRU, avoiding stale hits and cache eviction. */
    suspend fun getChapterContentsForSearch(bookId: Long, indexes: List<Int>): Map<Int, String> = getChapterContents(bookId, indexes)

    /** Total characters of a book's stored text, for backfilling 字数 on older imports. */
    suspend fun getTotalContentLength(bookId: Long): Long

    /**
     * 按章返回（章序号，非空白字符数），用于构建全书进度权重（主计划 10.2 / INV11）。
     * 实现必须避免加载正文；分母来自完整出版物索引，不依赖当前已加载的章节集合。
     */
    suspend fun getTextUnitCounts(bookId: Long): List<Pair<Int, Long>>

    /**
     * 章序号 → 正文长度。**实现不得加载正文**（一次聚合查询）。
     *
     * 合成 locator 恢复清单需要全书每章长度，但正文只需要 locator 真正指向那一章；
     * 逐章读正文会让「打开书」变成 N+1。
     */
    suspend fun getChapterTextLengths(bookId: Long): Map<Int, Int>
    suspend fun getChapterById(id: Long): Chapter?
    suspend fun insertChapter(chapter: Chapter): Long
    suspend fun insertChapters(chapters: List<Chapter>)
    suspend fun updateChapter(chapter: Chapter)
    suspend fun deleteChaptersByBookId(bookId: Long)
    suspend fun getChapterCount(bookId: Long): Int
}
