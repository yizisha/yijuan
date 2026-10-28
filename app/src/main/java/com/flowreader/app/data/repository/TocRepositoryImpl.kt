package com.flowreader.app.data.repository

import com.flowreader.app.data.local.dao.ContentIndexDao
import com.flowreader.app.domain.model.TocNodeRow
import com.flowreader.app.domain.model.TocTargetJson
import com.flowreader.app.domain.model.TocTree
import com.flowreader.app.domain.model.TocTreeBuilder
import com.flowreader.app.domain.repository.TocRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从 `toc_nodes` + `reading_sections` 还原真实目录树（主计划 4.3 / 7.1）。
 *
 * ## 为什么需要 reading_sections
 *
 * `toc_nodes.targetJson.sectionId` 存的是**清单里的 sectionId**（由结构锚点哈希而来），
 * 而阅读器的工作单位是「章节下标 = section.ordinal」。两者必须通过 `reading_sections`
 * 才能对上；只靠 `toc_nodes` 无法把目录项跳到正确的章。
 */
@Singleton
class TocRepositoryImpl @Inject constructor(
    private val contentIndexDao: ContentIndexDao
) : TocRepository {

    override suspend fun getTocTree(
        bookId: Long,
        revision: String,
        publicationId: String
    ): TocTree? {
        if (bookId <= 0L || revision.isBlank()) return null
        val entities = contentIndexDao.getTocNodes(bookId, revision)
        // 没有目录节点 = 这本书还没走新导入链路：交由调用方回退到 LegacyTocAdapter。
        if (entities.isEmpty()) return null

        val sectionOrdinals = contentIndexDao.getSectionSummaries(bookId, revision)
            .associate { it.sectionId to it.ordinal }

        val rows = entities.map { entity ->
            TocNodeRow(
                nodeId = entity.nodeId,
                parentId = entity.parentId,
                siblingOrder = entity.siblingOrder,
                label = entity.label,
                kind = entity.kind,
                role = entity.role,
                // 目标解析放在 domain，解析失败返回 null（该节点退化为不可读分组，
                // 不会因为一条坏 JSON 让整本书打不开目录）。
                targetSectionId = TocTargetJson.sectionIdOf(entity.targetJson)
            )
        }
        return TocTreeBuilder.build(
            publicationId = publicationId,
            revision = revision,
            rows = rows,
            sectionOrdinalById = sectionOrdinals
        ).tree
    }
}
