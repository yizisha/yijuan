package com.flowreader.app.data.repository

import com.flowreader.app.data.local.dao.ChapterDao
import com.flowreader.app.data.local.entity.ChapterEntity
import com.flowreader.app.data.local.entity.ChapterMetadata
import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.repository.ChapterRepository
import com.flowreader.app.util.CacheManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChapterRepositoryImpl @Inject constructor(
    private val chapterDao: ChapterDao,
    private val cacheManager: CacheManager
) : ChapterRepository {

    override fun getChaptersByBookId(bookId: Long): Flow<List<Chapter>> {
        return chapterDao.getChaptersByBookId(bookId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getChaptersListByBookId(bookId: Long): List<Chapter> {
        return chapterDao.getChaptersListByBookId(bookId).map { it.toDomain() }
    }

    override suspend fun getChapterMetadataList(bookId: Long): List<Chapter> {
        val metadataList = chapterDao.getChapterMetadataList(bookId)
        return metadataList.map { meta ->
            Chapter(
                id = meta.id,
                bookId = meta.bookId,
                index = meta.index,
                title = meta.title,
                content = "",
                startPosition = meta.startPosition,
                endPosition = meta.endPosition
            )
        }
    }

    override suspend fun getChapter(bookId: Long, index: Int): Chapter? {
        val meta = chapterDao.getChapterMetadataList(bookId).firstOrNull { it.index == index }

        if (meta == null) {
            val entity = chapterDao.getChapter(bookId, index) ?: return null
            return entity.toDomain()
        }

        val content = cacheManager.getChapterContent(bookId, index)
            ?: chapterDao.getChapterContent(bookId, index)
            ?: ""

        if (content.isNotEmpty()) {
            cacheManager.putChapterContent(bookId, index, content)
        }

        return Chapter(
            id = meta.id,
            bookId = meta.bookId,
            index = meta.index,
            title = meta.title,
            content = content,
            startPosition = meta.startPosition,
            endPosition = meta.endPosition
        )
    }

    override suspend fun getChapterContent(bookId: Long, index: Int): String? {
        val cached = cacheManager.getChapterContent(bookId, index)
        if (cached != null) return cached

        val content = chapterDao.getChapterContent(bookId, index)
        if (content != null) {
            cacheManager.putChapterContent(bookId, index, content)
        }
        return content
    }

    override suspend fun getChapterContents(bookId: Long, indexes: List<Int>): Map<Int, String> {
        if (indexes.isEmpty()) return emptyMap()
        val distinct = indexes.distinct()
        val result = mutableMapOf<Int, String>()
        // 先吃缓存，剩下的**一次**查询补齐：逐条查库会让搜索结果验证变成 N+1。
        val missing = mutableListOf<Int>()
        distinct.forEach { index ->
            val cached = cacheManager.getChapterContent(bookId, index)
            if (cached != null) result[index] = cached else missing.add(index)
        }
        if (missing.isNotEmpty()) {
            chapterDao.getChapterContents(bookId, missing).forEach { row ->
                val content = row.content.orEmpty()
                result[row.chapterIndex] = content
                if (content.isNotEmpty()) {
                    cacheManager.putChapterContent(bookId, row.chapterIndex, content)
                }
            }
        }
        return result
    }

    override suspend fun getTotalContentLength(bookId: Long): Long =
        chapterDao.getTotalContentLength(bookId)

    override suspend fun getChapterContentsForSearch(bookId: Long, indexes: List<Int>): Map<Int, String> =
        if (indexes.isEmpty()) emptyMap() else chapterDao.getChapterContents(bookId, indexes.distinct())
            .associate { it.chapterIndex to it.content.orEmpty() }

    override suspend fun getTextUnitCounts(bookId: Long): List<Pair<Int, Long>> {
        val indexed = chapterDao.getIndexedTextUnitCounts(bookId)
        val counts = if (indexed.isNotEmpty() && indexed.size == chapterDao.getChapterCount(bookId)) indexed
            else chapterDao.getTextUnitCounts(bookId)
        return counts.map { it.chapterIndex to it.units }
    }

    override suspend fun getChapterTextLengths(bookId: Long): Map<Int, Int> =
        chapterDao.getChapterTextLengths(bookId).associate { it.chapterIndex to it.lengthUtf16 }

    override suspend fun getChapterById(id: Long): Chapter? {
        return chapterDao.getChapterById(id)?.toDomain()
    }

    override suspend fun insertChapter(chapter: Chapter): Long {
        return chapterDao.insertChapter(ChapterEntity.fromDomain(chapter))
    }

    override suspend fun insertChapters(chapters: List<Chapter>) {
        chapterDao.insertChapters(chapters.map { ChapterEntity.fromDomain(it) })
    }

    override suspend fun updateChapter(chapter: Chapter) {
        chapterDao.updateChapter(ChapterEntity.fromDomain(chapter))
    }

    override suspend fun deleteChaptersByBookId(bookId: Long) {
        chapterDao.deleteChaptersByBookId(bookId)
    }

    override suspend fun getChapterCount(bookId: Long): Int {
        return chapterDao.getChapterCount(bookId)
    }
}
