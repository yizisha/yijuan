package com.flowreader.app.domain.model

/**
 * 出版物阅读清单（主计划 2.1 五层对象、4.1 第 6 步、5.1、12.3 第 4 条）。
 *
 * 这是 `ReadingManifestBuilder` 的输出、`PublicationStore` 的输入，也是 reader 侧唯一允许读取的
 * "这本书长什么样"的事实来源。它把四个原本混在一起的概念分开：
 *
 * | 概念 | 载体 | 不允许做的事 |
 * |---|---|---|
 * | 原始 spine 的每一次出现 | [SpineOccurrence] | 被去重逻辑删掉；用 documentKey 覆盖 |
 * | 唯一文档 | [PublicationDocument] | 以分页序号或数据库自增 id 冒充身份 |
 * | 正常阅读顺序的逻辑章 | [ReadingSection] | 为性能拆片生成 `(1)(2)` 目录章节 |
 * | 目录树节点 | `TocNodeEntity`（写入 toc_nodes） | 让卷折叠决定正文是否可读 |
 *
 * 不变量：
 * - `totalMainTextUnits == sections.sumOf { it.mainTextUnits }`（INV11：分母来自完整出版物索引）。
 * - `sections` 的 `(documentKey, ranges)` 按阅读顺序排列且**互不重叠计量**。
 * - `tocTargetSections` 的每个值都指向真实存在的 sectionId 或记录在 [_diagnostics]。
 *
 * 本文件不引用 Android 类型，也不做 I/O。
 */
data class PublicationManifest(
    val publicationId: String,
    /** 原始文件 SHA-256 + normalizationVersion + parserSchemaVersion 的确定性摘要。 */
    val revision: String,
    val normalizationVersion: Int,
    val parserSchemaVersion: Int,
    val sourceHash: String,
    /** 激活 revision 的目录相对路径（`files/books/<publicationId>/revisions/<revision>/`）。 */
    val manifestRelativePath: String,
    /** spine 的**每一次**原始出现，顺序原样保留（10.2 第 2 条）。 */
    val spineOccurrences: List<SpineOccurrence>,
    /** 去重后、真正有结构化内容的文档；按首次出现顺序。 */
    val documents: List<PublicationDocument>,
    /** 逻辑阅读单元；同一 sectionId 在同一 revision 内只允许出现一次（对应 reading_sections 主键）。 */
    val sections: List<ReadingSection>,
    /** 目录节点的**直接**目标 → sectionId。GROUP 的覆盖范围由调用方沿树归并（4.3 末段）。 */
    val tocTargetSections: Map<String, String>,
    val totalMainTextUnits: Long,
    /** 包内资源引用（`resourceId` 未解析时为空），供 `PublicationStore` 落盘。 */
    val resources: List<ManifestResource>,
    val diagnostics: List<ManifestDiagnostic> = emptyList()
) {
    val mainDocuments: List<PublicationDocument> get() = documents.filter { it.linear }

    /** 主线可读文档 key，按首次出现顺序。 */
    val mainDocumentKeys: List<String> get() = mainDocuments.map { it.documentKey }

    /** 主线路径上出现过的文档，含重复出现的顺序（10.2 第 2 条）。 */
    val mainPathDocumentKeys: List<String>
        get() = spineOccurrences.filter { it.linear }.mapNotNull { it.documentKey }

    /**
     * 缺陷/能力诊断，带稳定错误码（第 16 节末）。UI 做中文映射，禁止各层散落字符串比较。
     */
    sealed interface ManifestDiagnostic {
        val code: String
        val detail: String

        /** 同一档位里同一文档可有多段（如不同锚点缺失）。 */
        data class DocumentDiagnostic(
            override val code: String,
            override val detail: String,
            val documentKey: String? = null,
            val fragment: String? = null
        ) : ManifestDiagnostic
    }
}

/**
 * spine 的一次出现（4.2 末段 `spineOccurrences`、10.2 第 2 条）。
 *
 * `occurrenceId` 是 **OPF 路径 + itemref 序号** 的确定性摘要 —— 同一本书重复解析必须一致（INV02），
 * 且**不能**用 documentKey 代替它，否则同一文档的两次出现会被覆盖成一次。
 */
data class SpineOccurrence(
    val occurrenceId: String,
    /** itemref 在 spine 中的序号，从 0 开始。 */
    val ordinal: Int,
    /** 解析成功时为文档的规范容器路径；解析失败（条目缺失、空文档）为 null。 */
    val documentKey: String?,
    /** `linear` 省略视为 true（标准默认值）。 */
    val linear: Boolean,
    /** 声明的 media-type，诊断用。 */
    val mediaType: String,
    /** 该出现是否已计入主线文本量（重复出现的第二次起为 false）。 */
    val counted: Boolean
)

/**
 * 唯一文档（PK `(bookId, revision, documentKey)`）。
 *
 * `textLengthUtf16` / `mainTextUnits` 描述的是**该文档的去重后内容**，不是出现次数之和。
 */
data class PublicationDocument(
    val documentKey: String,
    /** 该文档首次出现在 spine 中的序号；未列 spine 为 -1。 */
    val ordinal: Int,
    /** 至少一次属于主线。 */
    val linear: Boolean,
    val role: DocumentRole,
    val textLengthUtf16: Int,
    /** 该文档对主线文本量的贡献；重复出现不再累加。 */
    val mainTextUnits: Long,
    /** 内容包的文档清单相对路径。 */
    val manifestRelativePath: String,
    /** 该文档规范化源文本的 SHA-256。 */
    val sourceHash: String
)

/** 文档在阅读计量中的地位。 */
enum class DocumentRole {
    /** 主线正文文档。 */
    MAIN,

    /** 辅助文档：`linear=no`、或内容全是脚注/尾注等非主线注释（3.2 第 7 条）。 */
    AUXILIARY
}

/**
 * 逻辑阅读单元（4.3 末段）。一个 section 可以跨多个相邻 spine 文档，一个文档也可以含多个 section。
 *
 * `sectionId` 生成规则（3.1 末段）：优先 `SHA256(documentKey + sourceAnchorIdentity)`；
 * 无锚点用文档起点或规范结构块身份。**同一目标多次目录引用不生成多个 section。**
 */
data class ReadingSection(
    val sectionId: String,
    /** 在本 revision 阅读顺序内的序号，从 0 开始。 */
    val ordinal: Int,
    /** canonical title：同一边界多个标题时取第一个合法主目录标签（4.3 末段）。 */
    val title: String,
    /** 别名标题（同一边界的其余目录标签），保留不丢。 */
    val aliases: List<String>,
    val kind: SectionKind,
    /** 按阅读顺序的源区间；同一文档内互不重叠。 */
    val ranges: List<SourceRange>,
    /** 本 section 的主线文本量；重复 spine 出现恒为 0。 */
    val mainTextUnits: Long,
    /** 该 section 是否由重复 spine 出现产生（内容已由首次出现计量）。 */
    val isRepeatOccurrence: Boolean = false
) {
    /** 便捷：首个源区间。 */
    val firstRange: SourceRange? get() = ranges.firstOrNull()

    /** 该 section 是否跨多个文档。 */
    val spansMultipleDocuments: Boolean get() = ranges.map { it.documentKey }.distinct().size > 1
}

/** section 类型。 */
enum class SectionKind {
    /** 正常章节。 */
    NORMAL,

    /** 第一个目录目标之前的真实正文（4.3 末段"前言/正文起始"）。 */
    PREFACE,

    /** 卷标题之后、第一章之前的非空正文（4.4 TXT 规则；EPUB 卷扉页同义）。 */
    VOLUME_PREFACE
}

/**
 * 待物化的包内资源（4.6 第 2 条）。
 *
 * `resourceId` 由 `SHA256(containerPath + contentHash)` 决定，**这里只承载"有哪些引用"**；
 * 真正的字节读取、哈希与落盘在 `PublicationStore`（需要容器 I/O）。
 */
data class ManifestResource(
    /** 规范容器路径，大小写敏感。 */
    val containerPath: String,
    /** 引用它的文档（诊断与归属用）。 */
    val documentKeys: List<String>,
    /** 引用原文，诊断回显用。 */
    val rawReferences: List<String>
)
