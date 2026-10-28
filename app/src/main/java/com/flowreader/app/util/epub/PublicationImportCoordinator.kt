package com.flowreader.app.util.epub

import com.flowreader.app.data.local.dao.BookDao
import com.flowreader.app.data.local.dao.ContentIndexDao
import com.flowreader.app.data.local.entity.BookEntity
import com.flowreader.app.data.local.entity.PublicationDocumentEntity
import com.flowreader.app.data.local.entity.PublicationRevisionEntity
import com.flowreader.app.data.local.entity.ReadingSectionEntity
import com.flowreader.app.data.local.entity.TocNodeEntity
import com.flowreader.app.domain.model.PublicationManifest
import com.flowreader.app.domain.model.SectionKind
import com.flowreader.app.domain.model.StructuredDocument
import com.flowreader.app.domain.model.TextUnits
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把解析结果落成"可激活的内容包 + 数据库索引"（主计划 4.1 第 7 步、5.3）。
 *
 * 这是 S3 的收尾环节：文件侧先落地，再用 `ContentIndexDao.activateRevision` 单事务切换指针。
 * 失败时数据库仍指向旧 revision，且**不删旧数据**（5.3 第 6 条）。
 *
 * 分工：
 * - 文件侧（分片、资源摘要命名、staging/提交）在 [PublicationStore]；
 * - 数据库侧（实体映射、`rangesJson`、目录树、激活回调）在本类；
 * - 熵最大的"解析"在 `EpubDocumentParser` / `ReadingManifestBuilder`，本类不重新解析任何正文。
 */
@Singleton
class PublicationImportCoordinator @Inject constructor(
    private val store: PublicationStore,
    private val contentIndexDao: ContentIndexDao,
    private val bookDao: BookDao
) {

    /** 读原始 EPUB 时使用的容器（测试可注入一个不落盘的实现）。 */
    fun interface ContainerProvider {
        fun open(): EpubZipContainer.OpenResult
    }

    /** 已提交的 revision 目录是否存在（激活前用它确认文件侧真的完成了）。 */
    fun existingRevisionFiles(publicationId: String, revision: String): PublicationStore.RevisionFiles? {
        val directory = store.revisionRoot(publicationId, revision)
        if (!directory.isDirectory) return null
        return PublicationStore.RevisionFiles(publicationId, revision, directory, reusedExisting = true)
    }

    /** 准备阶段的结果：文件已提交、数据库实体已就绪，尚未激活。 */
    data class Prepared(
        val manifest: PublicationManifest,
        val documents: Map<String, StructuredDocument>,
        /** 主目录树：激活时要写进 `toc_nodes`，也是 legacy 章节投影的卷来源。 */
        val toc: List<EpubNavigationParser.Node>,
        /** 已提交的 revision 目录；激活只读它。 */
        val files: PublicationStore.RevisionFiles,
        val diagnostics: List<String>
    ) {
        companion object {
            /**
             * 从"文件侧已经落地"的产物重建 [Prepared]。
             *
             * 解析发生在拿到 `bookId` 之前，跨阶段只能传不可变数据（manifest + 文档 + 目录），
             * 所以这里用 revision 目录重建描述，而不是把 staging 对象一路带过阶段边界。
             */
            fun ofFiles(
                manifest: PublicationManifest,
                documents: Map<String, StructuredDocument>,
                toc: List<EpubNavigationParser.Node>,
                files: PublicationStore.RevisionFiles,
                diagnostics: List<String> = emptyList()
            ): Prepared = Prepared(manifest, documents, toc, files, diagnostics)
        }
    }

    /** 激活结果。 */
    sealed interface Activation {
        data class Activated(val revision: String, val files: PublicationStore.RevisionFiles) : Activation

        data class Failed(val code: String, val detail: String) : Activation
    }

    // ------------------------------------------------------------------ 阶段 1：文件侧

    /**
     * 从已打开的容器读出全部内容并提交为一个不可变 revision 目录。
     *
     * 顺序严格按 4.1：container.xml → OPF → nav/NCX → 每个 spine 文档 → 清单 → staging → 提交。
     * 任何一步的失败都返回带 stage 的失败，**不返回表面成功的空书**。
     */
    fun prepareFromContainer(
        publicationId: String,
        revision: String,
        containerFile: File,
        sourceBytes: File? = null
    ): Result<Prepared> = runCatching {
        val diagnostics = mutableListOf<String>()
        val container = when (val opened = EpubZipContainer.open(containerFile)) {
            is EpubZipContainer.OpenResult.Failed -> error("CONTAINER: ${opened.message}")
            is EpubZipContainer.OpenResult.Opened -> opened.container
        }
        container.diagnostics.forEach { diagnostics += "${it.code}: ${it.detail}" }

        container.use { open ->
            val containerXml = open.readEntryText("META-INF/container.xml")
                .getOrElse { error("CONTAINER_XML: ${it.message}") }
            val (opfPath, rootDiagnostics) = EpubPackageParser.parseRootFilePath(containerXml)
            rootDiagnostics.forEach { diagnostics += "${it.code}: ${it.detail}" }
            val opf = opfPath ?: error("CONTAINER_ROOTFILE_MISSING")

            val packageXml = open.readEntryText(opf).getOrElse { error("OPF_READ: ${it.message}") }
            val pkg = EpubPackageParser.parse(packageXml, opf)
            pkg.diagnostics.forEach { diagnostics += "${it.code}: ${it.detail}" }
            if (pkg.isFixedLayout) {
                // 0.2 第 4 条：固定版式必须提示"统一排版兼容显示"，不能静默当普通书。
                diagnostics += "FIXED_LAYOUT: 该 EPUB 为固定版式，将以统一排版兼容显示"
            }

            // 目录：EPUB3 nav 优先，EPUB2 NCX 回退（4.1 第 4 步）。
            val tocResult = readToc(open, pkg).also { result -> result.diagnostics.forEach { diagnostics += "${it.code}: ${it.detail}" } }

            // 结构文档：只解析能解引用的 spine 项。
            val documents = LinkedHashMap<String, StructuredDocument>()
            val spineEntries = mutableListOf<ReadingManifestBuilder.SpineEntry>()
            pkg.spine.forEach { item ->
                val manifestItem = pkg.manifest[item.idref]
                val path = manifestItem?.containerPath
                if (path == null) {
                    spineEntries += ReadingManifestBuilder.SpineEntry(null, item.linear, "")
                    return@forEach
                }
                if (documents.containsKey(path)) {
                    // 重复出现仍然要保留这次出现（10.2），但内容只解析一次。
                    spineEntries += ReadingManifestBuilder.SpineEntry(path, item.linear, manifestItem.mediaType)
                    return@forEach
                }
                if (!manifestItem.isXhtml) {
                    spineEntries += ReadingManifestBuilder.SpineEntry(null, item.linear, manifestItem.mediaType)
                    diagnostics += "SPINE_ITEM_NOT_XHTML: $path (${manifestItem.mediaType})"
                    return@forEach
                }
                val xhtml = open.readEntryText(path).getOrElse {
                    diagnostics += "DOCUMENT_READ_FAILED: $path (${it.message})"
                    spineEntries += ReadingManifestBuilder.SpineEntry(null, item.linear, manifestItem.mediaType)
                    return@forEach
                }
                val parsed = EpubDocumentParser.parse(path, xhtml)
                parsed.diagnostics.forEach { diagnostics += "$path ${it.code}: ${it.detail}" }
                documents[path] = parsed
                spineEntries += ReadingManifestBuilder.SpineEntry(path, item.linear, manifestItem.mediaType)
            }

            val manifestResult = ReadingManifestBuilder.build(
                ReadingManifestBuilder.Input(
                    publicationId = publicationId,
                    revision = revision,
                    normalizationVersion = StructuredDocument.CURRENT_NORMALIZATION_VERSION,
                    parserSchemaVersion = StructuredDocument.CURRENT_SCHEMA_VERSION,
                    sourceHash = store.sha256Hex(sourceBytes?.readBytes() ?: containerFile.readBytes()),
                    manifestRelativePath = "files/books/$publicationId/revisions/$revision",
                    opfPath = opf,
                    spine = spineEntries,
                    mediaTypes = pkg.manifest.values.associate { it.containerPath to it.mediaType },
                    documents = documents,
                    toc = tocResult.main
                )
            )
            manifestResult.manifest.diagnostics.forEach { diagnostics += "${it.code}: ${it.detail}" }
            if (manifestResult is ReadingManifestBuilder.Result.Rejected) {
                error("MANIFEST: ${manifestResult.code} — ${manifestResult.detail}")
            }
            val manifest = manifestResult.manifest

            // 资源字节：只读清单里被真正引用的包内资源。
            val resourceBytes = LinkedHashMap<String, ByteArray>()
            manifest.resources.forEach { resource ->
                open.readEntryBytes(resource.containerPath).onSuccess { bytes ->
                    resourceBytes[resource.containerPath] = bytes
                }.onFailure { diagnostics += "RESOURCE_READ_FAILED: ${resource.containerPath} (${it.message})" }
            }

            val staged = store.stage(
                manifest = manifest,
                documents = documents,
                sourceBytes = sourceBytes?.readBytes() ?: containerFile.readBytes(),
                resourceBytes = resourceBytes
            ).getOrElse { error("STAGE: ${it.message}") }
            val files = store.commitFiles(staged).getOrElse { error("COMMIT: ${it.message}") }
            Prepared(manifest, documents, tocResult.main, files, diagnostics)
        }
    }

    private fun readToc(
        container: EpubZipContainer.Container,
        pkg: EpubPackageParser.Package
    ): EpubNavigationParser.Result {
        val navItem = pkg.navItem
        if (navItem != null) {
            val navXml = container.readEntryText(navItem.containerPath).getOrNull()
            if (navXml != null) return EpubNavigationParser.parseEpub3Nav(navXml, navItem.containerPath)
        }
        val ncxItem = pkg.ncxItem
        if (ncxItem != null) {
            val ncxXml = container.readEntryText(ncxItem.containerPath).getOrNull()
            if (ncxXml != null) return EpubNavigationParser.parseEpub2Ncx(ncxXml, ncxItem.containerPath)
        }
        return EpubNavigationParser.Result(
            diagnostics = listOf(EpubNavigationParser.Diagnostic("TOC_UNAVAILABLE", "既没有可用 nav 也没有 NCX"))
        )
    }

    // ------------------------------------------------------------------ 阶段 2：数据库侧激活

    /**
     * 单事务激活：写 READY revision、文档、section、目录，最后切 `books.activeRevision`。
     *
     * `activate` 回调在**同一事务**内执行，因此"索引已写但指针没切"这种半激活状态不可能出现
     * （5.3 第 4 步）。
     */
    suspend fun activate(
        bookId: Long,
        prepared: Prepared,
        toc: List<EpubNavigationParser.Node> = emptyList()
    ): Activation {
        val manifest = prepared.manifest
        return try {
            contentIndexDao.activateRevision(
                revision = PublicationRevisionEntity(
                    bookId = bookId,
                    revision = manifest.revision,
                    normalizationVersion = manifest.normalizationVersion,
                    parserSchemaVersion = manifest.parserSchemaVersion,
                    sourceHash = manifest.sourceHash,
                    manifestRelativePath = manifest.manifestRelativePath,
                    state = PublicationRevisionEntity.STATE_READY,
                    totalMainTextUnits = manifest.totalMainTextUnits,
                    createdAtMillis = System.currentTimeMillis()
                ),
                documents = manifest.documents.map { document ->
                    PublicationDocumentEntity(
                        bookId = bookId,
                        revision = manifest.revision,
                        documentKey = document.documentKey,
                        ordinal = document.ordinal,
                        linear = document.linear,
                        role = document.role.name,
                        textLengthUtf16 = document.textLengthUtf16,
                        mainTextUnits = document.mainTextUnits,
                        manifestRelativePath = document.manifestRelativePath,
                        sourceHash = document.sourceHash
                    )
                },
                sections = manifest.sections.map { section ->
                    ReadingSectionEntity(
                        bookId = bookId,
                        revision = manifest.revision,
                        sectionId = section.sectionId,
                        ordinal = section.ordinal,
                        title = section.title,
                        kind = section.kind.name,
                        rangesJson = rangesJson(section.ranges),
                        mainTextUnits = section.mainTextUnits
                    )
                },
                // 必须显式传入 manifest 的目录目标映射：flattenToc 的同名参数有
                // emptyMap() 默认值，漏传会把每个节点的 sectionId 都写成 null——
                // 目录仍能显示标签与层级，但**任何一项都跳不到对应章节**。
                tocNodes = flattenToc(toc, bookId, manifest.revision, manifest.tocTargetSections),
                activate = { activatedBookId, revision ->
                    bookDao.updateContentState(
                        bookId = activatedBookId,
                        publicationId = manifest.publicationId,
                        activeRevision = revision,
                        contentState = BookEntity.CONTENT_STATE_READY
                    )
                }
            )
            Activation.Activated(manifest.revision, prepared.files)
        } catch (e: Exception) {
            // 失败时数据库仍指向旧 revision；staging 已经提交为 revision 目录，
            // 它是"未被引用"的，交给 collectGarbage 处理，**不在失败路径删旧数据**。
            Activation.Failed("CONTENT_ACTIVATION_FAILED", e.message ?: e::class.java.simpleName)
        }
    }

    /** `rangesJson`：按阅读顺序的源区间，跨文档用多个区间表达（INV03）。 */
    fun rangesJson(ranges: List<com.flowreader.app.domain.model.SourceRange>): String {
        val array = JSONArray()
        ranges.forEach { range ->
            array.put(
                JSONObject()
                    .put("documentKey", range.documentKey)
                    .put("startUtf16", range.startUtf16)
                    .put("endUtf16Exclusive", range.endUtf16Exclusive)
            )
        }
        return array.toString()
    }

    /** 目录树 → `toc_nodes`；`targetJson` 里带 sectionId 与源坐标，UI 不需要自己再猜。 */
    fun flattenToc(
        nodes: List<EpubNavigationParser.Node>,
        bookId: Long,
        revision: String,
        /**
         * 目录目标 → section 映射，**必传**。
         *
         * 刻意**不给默认值**：此前它默认 emptyMap()，调用方漏传时不会报错，
         * 只会静默地把所有目录目标的 sectionId 写成 null，导致整本书的目录都点不动。
         * 让编译器强制调用方思考这个参数，比运行时静默降级安全得多。
         */
        tocTargetSections: Map<String, String>
    ): List<TocNodeEntity> {
        val entities = mutableListOf<TocNodeEntity>()
        fun walk(list: List<EpubNavigationParser.Node>, parentId: String?) {
            list.forEachIndexed { index, node ->
                val sectionId = tocTargetSections[node.nodeId]
                val target = if (node.targetPath == null && sectionId == null) {
                    null
                } else {
                    // 用 domain 的编码器而不是 org.json：后者在 JVM 单测里是空壳桩，
                    // 会让「目录目标是否真的写进去」无法在桌面回归。
                    com.flowreader.app.domain.model.TocTargetJson.encode(
                        documentKey = node.targetPath,
                        fragment = node.targetFragment,
                        sectionId = sectionId
                    )
                }
                entities += TocNodeEntity(
                    bookId = bookId,
                    revision = revision,
                    nodeId = node.nodeId,
                    parentId = parentId,
                    siblingOrder = index,
                    label = node.label,
                    kind = node.kind.name,
                    role = node.role,
                    targetJson = target
                )
                walk(node.children, node.nodeId)
            }
        }
        walk(nodes, null)
        return entities
    }

    // ------------------------------------------------------------------ 阶段 3：legacy 章节投影

    /**
     * 用 manifest 的 sections 生成 `Chapter` 列表。
     *
     * **这是显示层投影，不是第二个事实源**：内容来自规范源文本按 section 范围切片，
     * `volume` 来自目录树的卷分组（F08 的卷字段就在这里保住）。
     * 分片（5.1 的 32,768 单位）**不**产生章节，所以不会出现 `(1)(2)` 伪章节（F13）。
     */
    fun projectChapters(
        manifest: PublicationManifest,
        documents: Map<String, StructuredDocument>,
        toc: List<EpubNavigationParser.Node>,
        bookId: Long
    ): List<com.flowreader.app.domain.model.Chapter> {
        val volumes = volumeLabelsBySection(toc, manifest.tocTargetSections)
        return manifest.sections.map { section ->
            val content = StringBuilder()
            section.ranges.forEach { range ->
                val document = documents[range.documentKey] ?: return@forEach
                val start = range.startUtf16.coerceIn(0, document.text.length)
                val end = range.endUtf16Exclusive.coerceIn(start, document.text.length)
                content.append(document.text, start, end)
            }
            val first = section.ranges.firstOrNull()
            com.flowreader.app.domain.model.Chapter(
                bookId = bookId,
                index = section.ordinal,
                title = section.title,
                content = content.toString(),
                startPosition = first?.startUtf16 ?: 0,
                endPosition = first?.endUtf16Exclusive ?: 0,
                volume = volumes[section.sectionId]
            )
        }
    }

    /**
     * 卷标签：沿目录树把 GROUP（`role=VOLUME`）的可读后代目标映射到 section。
     *
     * 同一 section 出现在多个卷时取**第一个**卷（不连续覆盖的判定留给 S6 目录 UI）。
     */
    fun volumeLabelsBySection(
        toc: List<EpubNavigationParser.Node>,
        tocTargetSections: Map<String, String>
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        fun collectTargets(nodes: List<EpubNavigationParser.Node>, into: MutableSet<String>) {
            nodes.forEach { node ->
                tocTargetSections[node.nodeId]?.let { into += it }
                collectTargets(node.children, into)
            }
        }
        fun walk(nodes: List<EpubNavigationParser.Node>) {
            nodes.forEach { node ->
                if (node.role == EpubNavigationParser.ROLE_VOLUME) {
                    val sectionIds = mutableSetOf<String>()
                    collectTargets(node.children, sectionIds)
                    sectionIds.forEach { sectionId -> result.putIfAbsent(sectionId, node.label) }
                }
                walk(node.children)
            }
        }
        walk(toc)
        return result
    }

    /** 供诊断/统计使用的每 section 字数核对（与 manifest 必须一致）。 */
    fun verifySectionUnits(manifest: PublicationManifest, documents: Map<String, StructuredDocument>): Boolean {
        val total = manifest.sections.sumOf { section ->
            section.ranges.sumOf { range ->
                val document = documents[range.documentKey] ?: return@sumOf 0L
                val start = range.startUtf16.coerceIn(0, document.text.length)
                val end = range.endUtf16Exclusive.coerceIn(start, document.text.length)
                TextUnits.count(document.text.substring(start, end))
            }
        }
        return total == manifest.totalMainTextUnits
    }

    companion object {
        /** `content_migration_jobs` 的 stage 取值。 */
        const val STAGE_PREPARED = "PREPARED"
        const val STAGE_READY = "READY"

        /** 便于测试：把字符串写成 UTF-8 字节。 */
        fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)


    }
}
