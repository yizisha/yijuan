package com.flowreader.app.util.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把内容包里「当前 section 的结构图片与呈现标注」解析成章内坐标（主计划 4.6 / S5 / schema v3）。
 *
 * 三条硬约束：
 *
 * 1. **只读当前 section 涉及的文档**（通常 1–2 个），绝不是整本书。
 *    一本书可以有 2870 个文档，逐个读会把换章变成秒级卡顿 —— 这正是 0.2.8 成本契约要防的事。
 * 2. **全部在 IO 线程**执行，且由调用方在发布章节状态**之前**等待，避免"先按无图排版、再重排一次"
 *    造成的跳动（分页结果与图片必须一次算对）。
 * 3. **不抛异常**：包缺失、JSON 损坏、旧格式一律返回空；由渲染层决定占位，
 *    绝不因为一张坏图让整章打不开。
 *
 * 缓存：按 revision 缓存包索引，按文档缓存**整份呈现数据**（图片 + lists/tables/ruby，
 * 一次文件读取同时返回，LRU，容量很小）。
 */
@Singleton
class ChapterImageResolver @Inject constructor(
    private val store: PublicationStore
) {

    private val packageIndexes = object : LinkedHashMap<String, PublicationStore.PackageIndex?>(4, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, PublicationStore.PackageIndex?>?
        ): Boolean = size > MAX_PACKAGES
    }

    private val documentMarks = object : LinkedHashMap<String, PublicationStore.DocumentMarks>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, PublicationStore.DocumentMarks>?
        ): Boolean = size > MAX_DOCUMENTS
    }

    /**
     * @param sectionOrdinal 读序（= `Chapter.index` = `reading_sections.ordinal`）。
     * @return 章内偏移 → 图片；**顺序已按偏移排序**。空列表表示这一章没有结构图。
     */
    suspend fun imagesForSection(
        publicationId: String,
        revision: String,
        sectionOrdinal: Int
    ): List<ChapterImageRef> = withContext(Dispatchers.IO) {
        if (publicationId.isEmpty() || revision.isEmpty()) return@withContext emptyList()
        val index = packageIndex(publicationId, revision) ?: return@withContext emptyList()
        val ranges = index.sectionsByOrdinal[sectionOrdinal] ?: return@withContext emptyList()
        if (ranges.isEmpty()) return@withContext emptyList()

        val imagesByDocument = ranges.map { it.documentKey }.distinct().associateWith { documentKey ->
            documentMarksFor(documentKey, index).images.map { entry ->
                DocumentImage(
                    offsetUtf16 = entry.offsetUtf16,
                    containerPath = entry.containerPath,
                    alt = entry.alt
                )
            }
        }
        ChapterImageIndex.build(ranges, imagesByDocument) { containerPath ->
            containerPath?.let { index.resourcePaths[it] }
        }
    }

    /**
     * 当前 section 的呈现标注（schema v3）：列表项标注（编号已按结构推导）、表格、注音。
     *
     * 与 [imagesForSection] **共用同一次文档读取与同一份缓存**；旧书/无内容包/损坏 JSON
     * 返回 [ChapterMarks.EMPTY]，渲染层按「没有标注」处理，绝不抛异常。
     */
    suspend fun marksForSection(
        publicationId: String,
        revision: String,
        sectionOrdinal: Int
    ): ChapterMarks = withContext(Dispatchers.IO) {
        if (publicationId.isEmpty() || revision.isEmpty()) return@withContext ChapterMarks.EMPTY
        val index = packageIndex(publicationId, revision) ?: return@withContext ChapterMarks.EMPTY
        val ranges = index.sectionsByOrdinal[sectionOrdinal] ?: return@withContext ChapterMarks.EMPTY
        if (ranges.isEmpty()) return@withContext ChapterMarks.EMPTY

        val marksByDocument = ranges.map { it.documentKey }.distinct().associateWith { documentKey ->
            documentMarksFor(documentKey, index)
        }
        ChapterMarkIndex.build(ranges, marksByDocument)
    }

    @Synchronized
    private fun packageIndex(publicationId: String, revision: String): PublicationStore.PackageIndex? {
        val key = "$publicationId@$revision"
        if (packageIndexes.containsKey(key)) return packageIndexes[key]
        val loaded = store.readPackageIndex(publicationId, revision)
        packageIndexes[key] = loaded
        return loaded
    }

    /**
     * 缓存键是**文档 JSON 的绝对路径**（每个 revision 目录唯一），不是 documentKey：
     * 不同书/不同 revision 的 documentKey 可以相同，按名字缓存会把 A 书的标注发给 B 书。
     */
    @Synchronized
    private fun documentMarksFor(
        documentKey: String,
        index: PublicationStore.PackageIndex
    ): PublicationStore.DocumentMarks {
        val jsonPath = index.documentJsonPaths[documentKey]
            ?: return PublicationStore.DocumentMarks()
        documentMarks[jsonPath]?.let { return it }
        val loaded = store.readDocumentMarks(jsonPath)
        documentMarks[jsonPath] = loaded
        return loaded
    }

    private companion object {
        /** 只保留最近几本书的包索引；索引体积远小于正文，但没必要无限增长。 */
        const val MAX_PACKAGES = 3

        /** 最近文档的呈现数据；换章只碰相邻文档，几十条足够。 */
        const val MAX_DOCUMENTS = 64
    }
}
