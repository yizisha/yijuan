package com.flowreader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.flowreader.app.data.local.entity.ContentMigrationJobEntity
import com.flowreader.app.data.local.entity.PublicationDocumentEntity
import com.flowreader.app.data.local.entity.PublicationRevisionEntity
import com.flowreader.app.data.local.entity.ReadingSectionEntity
import com.flowreader.app.data.local.entity.TocNodeEntity

/**
 * 内容索引读写（主计划 5.2 / 12.3）。
 *
 * 约定：
 * - 书目/目录的万行查询**只投影所需 metadata**，绝不把 `rangesJson` 或正文读进列表。
 * - 激活新 revision 是单事务：先写 READY 修订与全部文档/章节/目录，最后切 `books.activeRevision`。
 * - 删除旧 revision 只能删无引用的 staging/缓存，不得在失败路径删旧数据。
 */
@Dao
interface ContentIndexDao {

    // ---------------------------------------------------------------- 修订

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRevision(revision: PublicationRevisionEntity)

    @Query("SELECT * FROM publication_revisions WHERE bookId = :bookId AND revision = :revision")
    suspend fun getRevision(bookId: Long, revision: String): PublicationRevisionEntity?

    @Query("SELECT * FROM publication_revisions WHERE bookId = :bookId AND state = :state ORDER BY createdAtMillis DESC")
    suspend fun getRevisionsByState(bookId: Long, state: String): List<PublicationRevisionEntity>

    @Query("SELECT COUNT(*) FROM publication_revisions WHERE bookId = :bookId")
    suspend fun countRevisions(bookId: Long): Int

    @Query("UPDATE publication_revisions SET state = :state WHERE bookId = :bookId AND revision = :revision")
    suspend fun updateRevisionState(bookId: Long, revision: String, state: String)

    // ---------------------------------------------------------------- 文档

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocuments(documents: List<PublicationDocumentEntity>)

    @Query(
        "SELECT bookId, revision, documentKey, ordinal, linear, role, textLengthUtf16, " +
            "mainTextUnits, manifestRelativePath, sourceHash FROM publication_documents " +
            "WHERE bookId = :bookId AND revision = :revision ORDER BY ordinal"
    )
    suspend fun getDocumentSummaries(bookId: Long, revision: String): List<PublicationDocumentEntity>

    @Query("SELECT * FROM publication_documents WHERE bookId = :bookId AND revision = :revision AND documentKey = :documentKey")
    suspend fun getDocument(bookId: Long, revision: String, documentKey: String): PublicationDocumentEntity?

    @Query("SELECT COUNT(*) FROM publication_documents WHERE bookId = :bookId AND revision = :revision")
    suspend fun countDocuments(bookId: Long, revision: String): Int

    // ---------------------------------------------------------------- 章节（逻辑 section）

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSections(sections: List<ReadingSectionEntity>)

    /** 目录列表只需要标题与权重，不读 rangesJson。 */
    @Query(
        "SELECT bookId, revision, sectionId, ordinal, title, kind, '' AS rangesJson, mainTextUnits " +
            "FROM reading_sections WHERE bookId = :bookId AND revision = :revision ORDER BY ordinal"
    )
    suspend fun getSectionSummaries(bookId: Long, revision: String): List<ReadingSectionEntity>

    @Query("SELECT * FROM reading_sections WHERE bookId = :bookId AND revision = :revision AND sectionId = :sectionId")
    suspend fun getSection(bookId: Long, revision: String, sectionId: String): ReadingSectionEntity?

    @Query("SELECT COALESCE(SUM(mainTextUnits), 0) FROM reading_sections WHERE bookId = :bookId AND revision = :revision")
    suspend fun sumSectionUnits(bookId: Long, revision: String): Long

    @Query("SELECT COUNT(*) FROM reading_sections WHERE bookId = :bookId AND revision = :revision")
    suspend fun countSections(bookId: Long, revision: String): Int

    // ---------------------------------------------------------------- 目录节点

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTocNodes(nodes: List<TocNodeEntity>)

    @Query("SELECT * FROM toc_nodes WHERE bookId = :bookId AND revision = :revision ORDER BY siblingOrder")
    suspend fun getTocNodes(bookId: Long, revision: String): List<TocNodeEntity>

    @Query("SELECT COUNT(*) FROM toc_nodes WHERE bookId = :bookId AND revision = :revision")
    suspend fun countTocNodes(bookId: Long, revision: String): Int

    // ---------------------------------------------------------------- 迁移任务

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMigrationJob(job: ContentMigrationJobEntity)

    @Query("SELECT * FROM content_migration_jobs WHERE stage != 'DONE' ORDER BY updatedAtMillis")
    suspend fun getPendingMigrationJobs(): List<ContentMigrationJobEntity>

    @Query("SELECT * FROM content_migration_jobs WHERE bookId = :bookId")
    suspend fun getMigrationJobs(bookId: Long): List<ContentMigrationJobEntity>

    // ---------------------------------------------------------------- 原子激活

    /**
     * 把一个 revision 的全部索引一次性写入并切换为激活内容（主计划 5.3 第 4 步）。
     *
     * 文件系统与 Room 无法共享物理事务，因此文件侧必须先完成原子重命名；本方法保证
     * **数据库侧要么全部可见、要么完全不切**：`books.activeRevision` 是唯一激活开关。
     */
    @Transaction
    suspend fun activateRevision(
        revision: PublicationRevisionEntity,
        documents: List<PublicationDocumentEntity>,
        sections: List<ReadingSectionEntity>,
        tocNodes: List<TocNodeEntity>,
        activate: suspend (bookId: Long, revision: String) -> Unit
    ) {
        upsertRevision(revision.copy(state = PublicationRevisionEntity.STATE_READY))
        upsertDocuments(documents)
        upsertSections(sections)
        upsertTocNodes(tocNodes)
        activate(revision.bookId, revision.revision)
    }
}
