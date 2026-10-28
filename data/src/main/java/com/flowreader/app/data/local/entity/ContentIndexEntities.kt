package com.flowreader.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 内容引擎的索引与状态表（主计划 5.2，Room v10）。
 *
 * 设计取舍（固定，不得改动）：
 * - **Room 保存索引与用户状态，内部文件保存版本化结构正文与资源**。整本结构 JSON 或 Bitmap
 *   不塞进 `books` 一条记录。
 * - 全部为 additive：v9 的列一律保留，旧位移/旧统计字段原样存放，新字段只增不改语义。
 * - 所有子表 `bookId` 外键指向 `books(id)` 并 `ON DELETE CASCADE`。
 * - JSON 一律 UTF-8；枚举使用稳定字符串；计数非负；时间毫秒 Long。
 * - `revision` 关联只指向已激活内容，不留悬空索引（由 PublicationStore 在单事务内保证）。
 */

/** 一次内容修订。`state`：STAGED / READY / RETIRED / FAILED。 */
@Entity(
    tableName = "publication_revisions",
    primaryKeys = ["bookId", "revision"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId"), Index("state")]
)
data class PublicationRevisionEntity(
    val bookId: Long,
    val revision: String,
    val normalizationVersion: Int,
    val parserSchemaVersion: Int,
    val sourceHash: String,
    val manifestRelativePath: String,
    val state: String,
    /** 主线有效文本量（非空白 code point 数），进度分母；用 Long 防止大书溢出。 */
    val totalMainTextUnits: Long,
    val createdAtMillis: Long
) {
    companion object {
        const val STATE_STAGED = "STAGED"
        const val STATE_READY = "READY"
        const val STATE_RETIRED = "RETIRED"
        const val STATE_FAILED = "FAILED"
    }
}

/**
 * 一个内容文档（EPUB 的 XHTML 或 TXT 的虚拟文档）。
 *
 * `ordinal` 是该文档首次出现在 spine 中的序号，未列入 spine 为 -1，**不加唯一约束**；
 * `linear` 表示它至少一次属于主线。同一文档在 spine 中重复出现由 manifest 的
 * `spineOccurrences` 表达，不在本表里覆盖（主计划 10.2 的去重策略）。
 */
@Entity(
    tableName = "publication_documents",
    primaryKeys = ["bookId", "revision", "documentKey"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId", "revision"), Index("bookId", "revision", "ordinal")]
)
data class PublicationDocumentEntity(
    val bookId: Long,
    val revision: String,
    val documentKey: String,
    val ordinal: Int,
    val linear: Boolean,
    /** MAIN | AUXILIARY（脚注/尾注等辅助文档不参与主进度计量）。 */
    val role: String,
    val textLengthUtf16: Int,
    val mainTextUnits: Long,
    val manifestRelativePath: String,
    val sourceHash: String
) {
    companion object {
        const val ROLE_MAIN = "MAIN"
        const val ROLE_AUXILIARY = "AUXILIARY"
    }
}

/**
 * 正常阅读顺序中的一个逻辑段/章。`kind`：NORMAL / PREFACE / VOLUME_PREFACE。
 *
 * `rangesJson` 是按阅读顺序排列的源区间数组，**不得重叠计量**（主计划 5.2）。
 * 本表不承载性能分片——分片只是 I/O 与布局单位，绝不生成 `(1)(2)` 章节。
 */
@Entity(
    tableName = "reading_sections",
    primaryKeys = ["bookId", "revision", "sectionId"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId", "revision"), Index("bookId", "revision", "ordinal", unique = true)]
)
data class ReadingSectionEntity(
    val bookId: Long,
    val revision: String,
    val sectionId: String,
    val ordinal: Int,
    val title: String,
    val kind: String,
    val rangesJson: String,
    val mainTextUnits: Long
) {
    companion object {
        const val KIND_NORMAL = "NORMAL"
        const val KIND_PREFACE = "PREFACE"
        const val KIND_VOLUME_PREFACE = "VOLUME_PREFACE"
    }
}

/**
 * 目录树节点。`kind`：GROUP（卷/分组，允许同时有 target）/ ENTRY。
 * `role` 用于标记 VOLUME 等语义；`targetJson` 可为空（无链接的分组标题）。
 *
 * `nodeId` 由源 nav/NCX 路径 + 稳定节点路径/id 哈希得到，**同名不同位置必须不同 ID**；
 * UI 的 key 不能用 label。
 */
@Entity(
    tableName = "toc_nodes",
    primaryKeys = ["bookId", "revision", "nodeId"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("bookId", "revision"),
        Index("bookId", "revision", "parentId"),
        Index("bookId", "revision", "siblingOrder")
    ]
)
data class TocNodeEntity(
    val bookId: Long,
    val revision: String,
    val nodeId: String,
    val parentId: String? = null,
    val siblingOrder: Int,
    val label: String,
    val kind: String,
    val role: String? = null,
    val targetJson: String? = null
) {
    companion object {
        const val KIND_GROUP = "GROUP"
        const val KIND_ENTRY = "ENTRY"
        const val ROLE_VOLUME = "VOLUME"
    }
}

/**
 * 一本书的阅读状态：权威内容定位 + 浏览辅助内容前的主线定位。
 *
 * `legacySnapshotJson` 保存迁移前的原始值（旧章节下标/裸位置及其单位证据），**永不丢弃**；
 * `navigationSequence` 单调递增，数据库条件检查拒绝倒写（主计划 6.7）。
 */
@Entity(
    tableName = "reader_states",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class ReaderStateEntity(
    @PrimaryKey
    val bookId: Long,
    val locatorJson: String? = null,
    val quality: String = "UNRESOLVED",
    val legacySnapshotJson: String? = null,
    /** 浏览非主线脚注/辅助文档时，主线进度仍用这个定位计算。 */
    val lastMainLocatorJson: String? = null,
    val navigationSequence: Long = 0,
    val updatedAtMillis: Long
)

/** 逐书可恢复的重解析任务。禁止开库时整库阻塞重解析。 */
@Entity(
    tableName = "content_migration_jobs",
    primaryKeys = ["bookId", "targetRevision"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId"), Index("stage")]
)
data class ContentMigrationJobEntity(
    val bookId: Long,
    val targetRevision: String,
    val stage: String,
    val errorCode: String? = null,
    val attemptCount: Int = 0,
    val updatedAtMillis: Long
)

/**
 * 幂等的阅读增量（主计划 10.5）。
 *
 * 唯一键 `(sessionId, sequence, localDate)` 保证重试不重复累计；日期按当前用户时区计算，
 * 跨本地午夜的活动区间按墙上时间边界拆分单调时长。
 */
@Entity(
    tableName = "reading_session_deltas",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("bookId"),
        Index(value = ["sessionId", "sequence", "localDate"], unique = true),
        Index("localDate"),
        Index("statsVersion")
    ]
)
data class ReadingSessionDeltaEntity(
    @PrimaryKey
    val deltaId: String,
    val bookId: Long,
    val sessionId: String,
    val sequence: Int,
    val localDate: String,
    val zoneId: String,
    val visualReadMillis: Long,
    val ttsReadMillis: Long,
    val qualifiedTextUnits: Long,
    val settledTurns: Int,
    val statsVersion: Int
)
