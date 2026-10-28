package com.flowreader.app.util.epub

import android.content.Context
import com.flowreader.app.domain.model.ContentBlock
import com.flowreader.app.domain.model.FigureBlock
import com.flowreader.app.domain.model.ImageBlock
import com.flowreader.app.domain.model.ListBlock
import com.flowreader.app.domain.model.PublicationManifest
import com.flowreader.app.domain.model.QuoteBlock
import com.flowreader.app.domain.model.StructuredDocument
import com.flowreader.app.domain.model.TextUnits
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 内容包落盘（主计划 5.1、5.3、4.6、12.3 第 4 条）。
 *
 * 目标内部路径：`files/books/<publicationId>/revisions/<revision>/`
 * （`source/` 原始输入副本、`documents/` 规范内容块、`resources/` 可渲染资源、`manifest.json` 索引摘要）。
 * **正文不放 cache 目录**（5.1：不能被系统随时清理）。
 *
 * 三条硬约束：
 *
 * 1. **`resourceId = SHA256(containerPath + contentHash)`，文件名用摘要**，
 *    不用书名、timestamp 或 basename；DOM / locator 内不存设备绝对路径（4.6 第 2 条）。
 * 2. **文档分片按目标 32,768 UTF-16 文本单位**，优先块边界，**不切代理对**（5.1）。
 *    分片只是 I/O 与布局单位，**不是章节**。
 * 3. **staged → 原子重命名 → 单事务激活**（5.3）：文件系统与 Room 不能共享事务，
 *    所以文件必须先落地；失败时数据库仍指向旧 revision，且**不删旧数据**。
 *
 * 本类做文件 I/O，因此属于 Android 侧；所有判定逻辑（分片、摘要、JSON）都是纯函数，可在 JVM 上测试。
 */
class PublicationStore(private val context: Context) {

    /** 文档分片目标长度（UTF-16 单位）。分片不是章节，见 5.1。 */
    val chunkTargetUnits: Int = DEFAULT_CHUNK_TARGET_UNITS

    // ------------------------------------------------------------------ 路径

    fun booksRoot(): File = File(context.filesDir, "books")

    fun revisionRoot(publicationId: String, revision: String): File =
        File(booksRoot(), "$publicationId/revisions/$revision")

    fun stagingRoot(publicationId: String, revision: String): File =
        File(booksRoot(), "$publicationId/staging/$revision")

    /**
     * containerPath -> 可渲染资源的**绝对路径**。
     *
     * 这是 S5「图片呈现」正确修法的**基础件，当前尚无调用方**：
     * 内容引擎把图片存成结构对象（ImageBlock + 资源引用），规范源文本里只留一个 U+FFFC；
     * 而 ReaderContent / PagedReader 仍按 `[IMG:路径]` 标记渲染。
     *
     * **不要**用「把结构图片投影成 `[IMG:]` 标记写回 chapters.content」来搭桥：
     * `PublicationImportGoldenTest`（F10 回归防线）明确要求章节正文**不得**出现 `[IMG:`。
     * 正确做法是**渲染层直接消费结构图片**：由 ViewModel 从内容包取出每张图的源偏移，
     * 用本方法解析成本机文件路径，按偏移交给读者在对应段落位置渲染。
     *
     * 绝对路径只在内存中使用，绝不写进 locator / 备份等跨机字段（4.6 第 2 条）。
     * manifest.json 缺失或损坏时返回空 map：调用方必须显示占位，**不得静默丢图**（INV10）。
     */
    fun resourceIndex(publicationId: String, revision: String): Map<String, String> {
        val root = revisionRoot(publicationId, revision)
        val manifestFile = File(root, "manifest.json")
        if (!manifestFile.isFile) return emptyMap()
        return runCatching {
            resourcesFrom(JSONObject(manifestFile.readText(StandardCharsets.UTF_8)), root)
        }.getOrDefault(emptyMap())
    }

    private fun resourcesFrom(json: JSONObject, root: File): Map<String, String> {
        val array = json.optJSONArray("resources") ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val containerPath = entry.optString("containerPath").takeIf { it.isNotEmpty() } ?: continue
            val relativePath = entry.optString("relativePath").takeIf { it.isNotEmpty() } ?: continue
            val file = File(root, relativePath)
            if (file.isFile) map[containerPath] = file.absolutePath
        }
        return map
    }

    /**
     * 一个 revision 的包索引：section 范围 + 文档 JSON 路径 + 资源绝对路径。
     *
     * 阅读器要按「当前章 → section → 文档 → 结构图片」这条链取图，全部信息都在这一个
     * `manifest.json` 里；一次读取 + 调用方缓存即可，**不需要**新增数据库表
     * （5.3：资源索引留在内容包中）。
     *
     * `manifest.json` 缺失或损坏返回 null：调用方按「这本书没有内容包」处理（旧书），
     * 不得因此崩溃或空指针。
     */
    data class PackageIndex(
        val sectionsByOrdinal: Map<Int, List<SectionRange>>,
        val documentJsonPaths: Map<String, String>,
        val resourcePaths: Map<String, String>
    )

    fun readPackageIndex(publicationId: String, revision: String): PackageIndex? {
        val root = revisionRoot(publicationId, revision)
        val manifestFile = File(root, "manifest.json")
        if (!manifestFile.isFile) return null
        return runCatching {
            val json = JSONObject(manifestFile.readText(StandardCharsets.UTF_8))

            val sectionsByOrdinal = LinkedHashMap<Int, List<SectionRange>>()
            json.optJSONArray("sections")?.let { sections ->
                for (i in 0 until sections.length()) {
                    val section = sections.optJSONObject(i) ?: continue
                    val ordinal = section.optInt("ordinal", -1)
                    if (ordinal < 0) continue
                    val ranges = mutableListOf<SectionRange>()
                    section.optJSONArray("ranges")?.let { array ->
                        for (j in 0 until array.length()) {
                            val range = array.optJSONObject(j) ?: continue
                            val key = range.optString("documentKey").takeIf { it.isNotEmpty() } ?: continue
                            ranges += SectionRange(
                                documentKey = key,
                                startUtf16 = range.optInt("startUtf16", 0),
                                endUtf16Exclusive = range.optInt("endUtf16Exclusive", 0)
                            )
                        }
                    }
                    sectionsByOrdinal[ordinal] = ranges
                }
            }

            val documentJsonPaths = LinkedHashMap<String, String>()
            json.optJSONArray("documents")?.let { documents ->
                for (i in 0 until documents.length()) {
                    val document = documents.optJSONObject(i) ?: continue
                    val key = document.optString("documentKey").takeIf { it.isNotEmpty() } ?: continue
                    val relative = document.optString("jsonRelativePath").takeIf { it.isNotEmpty() } ?: continue
                    documentJsonPaths[key] = File(root, relative).absolutePath
                }
            }

            PackageIndex(
                sectionsByOrdinal = sectionsByOrdinal,
                documentJsonPaths = documentJsonPaths,
                resourcePaths = resourcesFrom(json, root)
            )
        }.getOrNull()
    }

    /** `documents/<hash>.json` 里的一条结构图片记录。 */
    data class DocumentImageEntry(
        val offsetUtf16: Int,
        val containerPath: String?,
        val raw: String,
        val alt: String?,
        val blockId: String
    )

    /** `documents/<hash>.json` 里的一条列表项呈现标注（schema v3）。 */
    data class DocumentListEntry(
        val listBlockId: String,
        val blockId: String,
        val domPath: String,
        val offsetUtf16: Int,
        val ordered: Boolean,
        val listStart: Int?,
        val level: Int,
        val itemValue: Int?,
        val role: String
    )

    /** `documents/<hash>.json` 里的一条表格结构标注（schema v3）。 */
    data class DocumentTableEntry(
        val blockId: String,
        val domPath: String,
        val startUtf16: Int,
        val endUtf16Exclusive: Int,
        val rows: List<List<DocumentTableCellEntry>>,
        val role: String
    )

    data class DocumentTableCellEntry(
        val blockId: String,
        val startUtf16: Int,
        val endUtf16Exclusive: Int,
        val isHeader: Boolean,
        val rowSpan: Int,
        val colSpan: Int
    )

    /** `documents/<hash>.json` 里的一条注音显示标注（schema v3）。注音不进主文本。 */
    data class DocumentRubyEntry(
        val blockId: String,
        val domPath: String,
        val baseStartUtf16: Int,
        val baseEndUtf16Exclusive: Int,
        val annotations: List<String>,
        val role: String
    )

    /** 一次文档 JSON 读取拿到的全部呈现数据：避免图片与标注重复读同一个文件。 */
    data class DocumentMarks(
        val images: List<DocumentImageEntry> = emptyList(),
        val lists: List<DocumentListEntry> = emptyList(),
        val tables: List<DocumentTableEntry> = emptyList(),
        val ruby: List<DocumentRubyEntry> = emptyList()
    )

    /**
     * 读某个文档 JSON 的全部呈现数据（偏移**相对该文档**）。
     *
     * 文件缺失/损坏/旧格式（schema v2 及更早，没有这三个数组）一律返回空列表：**不抛异常**。
     * 图片与 lists/tables/ruby **一次读取**同时返回 —— 调用方（[ChapterImageResolver]）
     * 按文档缓存整份 [DocumentMarks]，换章时同一文档只读一次。
     */
    fun readDocumentMarks(documentJsonPath: String): DocumentMarks {
        val file = File(documentJsonPath)
        if (!file.isFile) return DocumentMarks()
        return runCatching {
            val json = JSONObject(file.readText(StandardCharsets.UTF_8))
            DocumentMarks(
                images = readImages(json),
                lists = readLists(json),
                tables = readTables(json),
                ruby = readRuby(json)
            )
        }.getOrDefault(DocumentMarks())
    }

    private fun readImages(json: JSONObject): List<DocumentImageEntry> {
        val array = json.optJSONArray("images") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val offset = entry.optInt("offsetUtf16", -1)
                if (offset < 0) continue
                add(
                    DocumentImageEntry(
                        offsetUtf16 = offset,
                        containerPath = entry.optString("containerPath").takeIf { it.isNotEmpty() },
                        raw = entry.optString("raw"),
                        alt = entry.optString("alt").takeIf { it.isNotEmpty() },
                        blockId = entry.optString("blockId")
                    )
                )
            }
        }
    }

    private fun readLists(json: JSONObject): List<DocumentListEntry> {
        val array = json.optJSONArray("lists") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val offset = entry.optInt("offsetUtf16", -1)
                if (offset < 0) continue
                add(
                    DocumentListEntry(
                        listBlockId = entry.optString("listBlockId"),
                        blockId = entry.optString("blockId"),
                        domPath = entry.optString("domPath"),
                        offsetUtf16 = offset,
                        ordered = entry.optBoolean("ordered", false),
                        listStart = entry.optInt("listStart", -1).takeIf { it >= 0 },
                        level = entry.optInt("level", 1),
                        itemValue = entry.optInt("itemValue", -1).takeIf { it >= 0 },
                        role = entry.optString("role", "MAIN")
                    )
                )
            }
        }
    }

    private fun readTables(json: JSONObject): List<DocumentTableEntry> {
        val array = json.optJSONArray("tables") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val rows = mutableListOf<List<DocumentTableCellEntry>>()
                entry.optJSONArray("rows")?.forEachObject { row ->
                    val cells = mutableListOf<DocumentTableCellEntry>()
                    row.optJSONArray("cells")?.forEachObject { cell ->
                        cells += DocumentTableCellEntry(
                            blockId = cell.optString("blockId"),
                            startUtf16 = cell.optInt("startUtf16", -1),
                            endUtf16Exclusive = cell.optInt("endUtf16Exclusive", -1),
                            isHeader = cell.optBoolean("isHeader", false),
                            rowSpan = cell.optInt("rowSpan", 1),
                            colSpan = cell.optInt("colSpan", 1)
                        )
                    }
                    if (cells.isNotEmpty()) rows += cells
                }
                add(
                    DocumentTableEntry(
                        blockId = entry.optString("blockId"),
                        domPath = entry.optString("domPath"),
                        startUtf16 = entry.optInt("startUtf16", 0),
                        endUtf16Exclusive = entry.optInt("endUtf16Exclusive", 0),
                        rows = rows,
                        role = entry.optString("role", "MAIN")
                    )
                )
            }
        }
    }

    private fun readRuby(json: JSONObject): List<DocumentRubyEntry> {
        val array = json.optJSONArray("ruby") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val annotations = mutableListOf<String>()
                entry.optJSONArray("annotations")?.let { values ->
                    for (j in 0 until values.length()) {
                        val text = values.optString(j)
                        if (text.isNotEmpty()) annotations += text
                    }
                }
                if (annotations.isEmpty()) continue
                add(
                    DocumentRubyEntry(
                        blockId = entry.optString("blockId"),
                        domPath = entry.optString("domPath"),
                        baseStartUtf16 = entry.optInt("baseStartUtf16", -1),
                        baseEndUtf16Exclusive = entry.optInt("baseEndUtf16Exclusive", -1),
                        annotations = annotations,
                        role = entry.optString("role", "MAIN")
                    )
                )
            }
        }
    }

    /**
     * 读某个文档 JSON 的 `images` 数组（偏移**相对该文档**）。
     *
     * 文件缺失/损坏/旧格式（没有 `images`）一律返回空列表：**不抛异常**。
     * 空列表的含义是「这个文档没有可渲染的结构图」，由 [ChapterImageIndex] 与渲染层决定如何占位。
     */
    fun readDocumentImages(documentJsonPath: String): List<DocumentImageEntry> =
        readDocumentMarks(documentJsonPath).images

    // ------------------------------------------------------------------ 阶段 1：写 staging

    /**
     * 把内容包写进 staging 目录。
     *
     * 返回 [StagedPackage]：描述已落地的文件与分片索引。**此时数据库还没有任何指向它的引用**，
     * 因此中途失败只需丢弃 staging（[discardStaging]），不会污染活动内容（5.3 第 6 条）。
     */
    fun stage(
        manifest: PublicationManifest,
        documents: Map<String, StructuredDocument>,
        sourceBytes: ByteArray,
        resourceBytes: Map<String, ByteArray>
    ): Result<StagedPackage> = runCatching {
        val staging = stagingRoot(manifest.publicationId, manifest.revision)
        if (staging.exists()) staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("无法创建 staging 目录: ${staging.absolutePath}")

        // source/：保持一份可验证的原始副本，便于重新解析（5.1）。
        val sourceFile = File(staging, "source/original.bin")
        sourceFile.parentFile?.mkdirs()
        sourceFile.writeBytes(sourceBytes)

        // documents/：规范源文本 + 结构分片索引。
        val documentsDir = File(staging, "documents").apply { mkdirs() }
        val documentSummaries = mutableListOf<StagedDocument>()
        documents.toSortedMap().forEach { (documentKey, document) ->
            val textFile = File(documentsDir, "${fileNameFor(documentKey)}.txt")
            textFile.writeText(document.text, StandardCharsets.UTF_8)
            val chunks = chunkDocument(document, chunkTargetUnits)
            val documentJson = buildDocumentJson(documentKey, document, chunks, manifest)
            val jsonFile = File(documentsDir, "${fileNameFor(documentKey)}.json")
            jsonFile.writeText(documentJson, StandardCharsets.UTF_8)
            documentSummaries += StagedDocument(
                documentKey = documentKey,
                jsonRelativePath = "documents/${jsonFile.name}",
                textRelativePath = "documents/${textFile.name}",
                textSha256 = sha256Hex(document.text.toByteArray(StandardCharsets.UTF_8)),
                chunks = chunks
            )
        }

        // resources/：文件名用 resourceId 摘要，不用书名/basename（4.6 第 2 条）。
        val resourcesDir = File(staging, "resources").apply { mkdirs() }
        val resources = mutableListOf<StagedResource>()
        resourceBytes.toSortedMap().forEach { (containerPath, bytes) ->
            val contentHash = sha256Hex(bytes)
            val resourceId = resourceIdOf(containerPath, contentHash)
            val file = File(resourcesDir, resourceId)
            file.writeBytes(bytes)
            resources += StagedResource(
                resourceId = resourceId,
                containerPath = containerPath,
                mediaType = mediaTypeOf(containerPath),
                byteSize = bytes.size.toLong(),
                contentHash = contentHash,
                relativePath = "resources/$resourceId"
            )
        }

        // manifest.json：全部索引摘要与校验和。
        val packageJson = buildPackageJson(manifest, documentSummaries, resources, sourceBytes)
        File(staging, "manifest.json").writeText(packageJson, StandardCharsets.UTF_8)

        StagedPackage(
            publicationId = manifest.publicationId,
            revision = manifest.revision,
            stagingDirectory = staging,
            sourceSha256 = sha256Hex(sourceBytes),
            documents = documentSummaries,
            resources = resources,
            manifestSha256 = sha256Hex(packageJson.toByteArray(StandardCharsets.UTF_8))
        )
    }

    /**
     * 原子提交：staging 目录重命名为不可变 revision 目录（5.3 第 3 步）。
     *
     * 已存在同一 revision 时**不覆盖活动文件**，直接复用（调用方应先核对 checksum）。
     */
    fun commitFiles(staged: StagedPackage): Result<RevisionFiles> = runCatching {
        val target = revisionRoot(staged.publicationId, staged.revision)
        if (target.exists()) {
            staged.stagingDirectory.deleteRecursively()
            return@runCatching RevisionFiles(
                publicationId = staged.publicationId,
                revision = staged.revision,
                directory = target,
                reusedExisting = true
            )
        }
        target.parentFile?.mkdirs()
        if (!staged.stagingDirectory.renameTo(target)) {
            // renameTo 跨卷会失败；回退为"复制后删除 staging"，仍然只在文件侧，不触碰数据库。
            staged.stagingDirectory.copyRecursively(target, overwrite = false)
            staged.stagingDirectory.deleteRecursively()
        }
        RevisionFiles(staged.publicationId, staged.revision, target, reusedExisting = false)
    }

    /** 失败路径：丢弃 staging。**不删除任何已有 revision 目录**（5.3 第 6 条）。 */
    fun discardStaging(publicationId: String, revision: String) {
        stagingRoot(publicationId, revision).deleteRecursively()
    }

    /**
     * 垃圾回收：只删除**未被引用**的 staging 目录（5.3 第 8 条）。
     *
     * `keepRevisions` 是仍然可用的 revision 列表；它们的目录一律不动，原书与迁移前数据更不动。
     */
    fun collectGarbage(publicationId: String, keepRevisions: Set<String>): List<String> {
        val removed = mutableListOf<String>()
        val stagingDir = File(booksRoot(), "$publicationId/staging")
        stagingDir.listFiles()?.forEach { candidate ->
            if (candidate.name !in keepRevisions && candidate.isDirectory) {
                if (candidate.deleteRecursively()) removed += "staging/${candidate.name}"
            }
        }
        return removed
    }

    /** 读回一个 revision 的 manifest.json（诊断与三方状态协调用，5.3 第 7 条）。 */
    fun readPackageManifest(publicationId: String, revision: String): String? {
        val file = File(revisionRoot(publicationId, revision), "manifest.json")
        return if (file.isFile) file.readText(StandardCharsets.UTF_8) else null
    }

    // ------------------------------------------------------------------ 纯函数：分片

    /**
     * 把一个文档切成不超过 [targetUnits] UTF-16 单位的分片（5.1）。
     *
     * 规则：
     * 1. 优先在**结构块边界**切；
     * 2. 单块超长时在块内切，但**不切代理对**（INV04），并保留同一 `blockId` 与起止偏移；
     * 3. 分片是 I/O 与布局单位，**不是章节**（所以不生成 `(1)(2)` 目录项）。
     */
    fun chunkDocument(document: StructuredDocument, targetUnits: Int = DEFAULT_CHUNK_TARGET_UNITS): List<DocumentChunk> {
        require(targetUnits > 0) { "targetUnits 必须为正: $targetUnits" }
        if (document.text.isEmpty()) return emptyList()

        val chunks = mutableListOf<DocumentChunk>()
        var chunkStart = 0
        var currentBlockId: String? = null

        document.blocks.forEach { block ->
            val blockStart = block.range.startUtf16
            val blockEnd = minOf(block.range.endUtf16Exclusive, document.text.length)
            if (blockEnd <= blockStart) return@forEach

            // 块与当前分片差距过大时先收口，保证分片尽量落在块边界。
            if (chunkStart < blockStart && blockStart - chunkStart >= targetUnits) {
                chunks += makeChunk(document, chunkStart, blockStart, currentBlockId, chunks.size)
                chunkStart = blockStart
                currentBlockId = null
            }

            var start = blockStart
            while (start < blockEnd) {
                val remaining = blockEnd - start
                val room = targetUnits - (start - chunkStart)
                if (room <= 0) {
                    chunks += makeChunk(document, chunkStart, start, currentBlockId, chunks.size)
                    chunkStart = start
                    currentBlockId = null
                    continue
                }
                if (remaining <= room) {
                    if (currentBlockId == null) currentBlockId = block.blockId
                    start = blockEnd
                } else {
                    val hardEnd = safeBoundary(document.text, start + room)
                    if (currentBlockId == null) currentBlockId = block.blockId
                    chunks += makeChunk(document, chunkStart, hardEnd, currentBlockId, chunks.size)
                    chunkStart = hardEnd
                    currentBlockId = block.blockId
                    start = hardEnd
                }
            }
        }

        if (chunkStart < document.text.length) {
            chunks += makeChunk(document, chunkStart, document.text.length, currentBlockId, chunks.size)
        }
        // 空文本或纯空白文档：仍然要有分片，否则 reader 无从定位。
        if (chunks.isEmpty()) chunks += makeChunk(document, 0, document.text.length, null, 0)
        return chunks
    }

    private fun makeChunk(
        document: StructuredDocument,
        start: Int,
        endExclusive: Int,
        blockId: String?,
        ordinal: Int
    ): DocumentChunk {
        val slice = document.text.substring(start, endExclusive)
        return DocumentChunk(
            ordinal = ordinal,
            documentKey = document.documentKey,
            startUtf16 = start,
            endUtf16Exclusive = endExclusive,
            blockId = blockId,
            checksumSha256 = sha256Hex(slice.toByteArray(StandardCharsets.UTF_8))
        )
    }

    /** 不切代理对：偏移落在低位代理前就后退一位（INV04）。 */
    fun safeBoundary(text: String, offset: Int): Int {
        var value = offset.coerceIn(0, text.length)
        if (value in 1 until text.length) {
            val previous = text[value - 1]
            val current = text[value]
            if (Character.isHighSurrogate(previous) && Character.isLowSurrogate(current)) value--
        }
        return value
    }

    // ------------------------------------------------------------------ 纯函数：摘要与 ID

    /**
     * `resourceId = SHA256(containerPath + contentHash)`（4.6 第 2 条）。
     *
     * 输入固定 UTF-8、分隔符用 NUL（不会出现在合法容器路径里），保证确定性（INV02）。
     */
    fun resourceIdOf(containerPath: String, contentHash: String): String =
        sha256Hex("$containerPath\u0000$contentHash".toByteArray(StandardCharsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 文档文件名：用 documentKey 的确定性摘要，避免把 `../` 之类路径带进文件名。 */
    fun fileNameFor(documentKey: String): String = sha256Hex(documentKey.toByteArray(StandardCharsets.UTF_8)).take(32)

    private fun mediaTypeOf(containerPath: String): String = when (containerPath.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "css" -> "text/css"
        "xhtml" -> "application/xhtml+xml"
        else -> "application/octet-stream"
    }

    // ------------------------------------------------------------------ JSON（确定性键顺序）

    private fun buildDocumentJson(
        documentKey: String,
        document: StructuredDocument,
        chunks: List<DocumentChunk>,
        manifest: PublicationManifest
    ): String {
        val blocks = JSONArray()
        document.blocks.forEach { block ->
            blocks.put(
                JSONObject()
                    .put("blockId", block.blockId)
                    .put("domPath", block.domPath)
                    .put("role", block.role.name)
                    .put("startUtf16", block.range.startUtf16)
                    .put("endUtf16Exclusive", block.range.endUtf16Exclusive)
                    .put("type", block::class.simpleName)
            )
        }
        val anchors = JSONObject()
        document.anchorIndex.toSortedMap().forEach { (id, target) ->
            anchors.put(id, JSONObject().put("offsetUtf16", target.offsetUtf16).put("blockId", target.blockId))
        }
        val weightIndex = JSONArray()
        document.blocks
            .filter { it.role == com.flowreader.app.domain.model.BlockRole.MAIN }
            .forEach { block ->
                weightIndex.put(
                    JSONObject()
                        .put("blockId", block.blockId)
                        .put("startUtf16", block.range.startUtf16)
                        .put("endUtf16Exclusive", block.range.endUtf16Exclusive)
                        .put(
                            "textUnits",
                            TextUnits.count(
                                document.text.substring(
                                    block.range.startUtf16,
                                    minOf(block.range.endUtf16Exclusive, document.text.length)
                                )
                            )
                        )
                )
            }
        val chunkArray = JSONArray()
        chunks.forEach { chunk ->
            chunkArray.put(
                JSONObject()
                    .put("ordinal", chunk.ordinal)
                    .put("startUtf16", chunk.startUtf16)
                    .put("endUtf16Exclusive", chunk.endUtf16Exclusive)
                    .put("blockId", chunk.blockId ?: JSONObject.NULL)
                    .put("checksumSha256", chunk.checksumSha256)
            )
        }

        // 结构图片的**偏移 → 资源**映射（4.6）。规范源文本里图片只占一个 U+FFFC，
        // 没有这张表，阅读器知道"这里有个对象"却不知道它是哪张图 ——
        // 这正是「新导入 EPUB 的插图不显示」在数据层的根因。
        val imageArray = JSONArray()
        // 优先用文档自己的图片清单（含段落内部的行内图）；blocks 只在文档由别处构造时兜底。
        document.images.ifEmpty { collectImages(document.blocks) }.forEach { image ->
            imageArray.put(
                JSONObject()
                    .put("blockId", image.blockId)
                    .put("domPath", image.domPath)
                    .put("offsetUtf16", image.objectOffsetUtf16 ?: image.range.startUtf16)
                    .put("containerPath", image.reference.containerPath ?: JSONObject.NULL)
                    .put("raw", image.reference.raw)
                    .put("alt", image.alt ?: JSONObject.NULL)
                    .put("role", image.role.name)
            )
        }

        // schema v3 的三类呈现标注：与 images 同一条链路（唯一创建点收集 → 落盘 → 读回）。
        // 规范正文里没有这三类语义（列表无标记、表格只有 TAB、注音不进正文），
        // 不持久化它们，渲染层就永远无米下锅。
        val listArray = JSONArray()
        document.lists.forEach { mark ->
            listArray.put(
                JSONObject()
                    .put("listBlockId", mark.listBlockId)
                    .put("blockId", mark.blockId)
                    .put("domPath", mark.domPath)
                    .put("offsetUtf16", mark.offsetUtf16)
                    .put("ordered", mark.ordered)
                    .put("listStart", mark.listStart ?: JSONObject.NULL)
                    .put("level", mark.level)
                    .put("itemValue", mark.itemValue ?: JSONObject.NULL)
                    .put("role", mark.role.name)
            )
        }
        val tableArray = JSONArray()
        document.tables.forEach { mark ->
            val rows = JSONArray()
            mark.rows.forEach { row ->
                val cells = JSONArray()
                row.cells.forEach { cell ->
                    cells.put(
                        JSONObject()
                            .put("blockId", cell.blockId)
                            .put("startUtf16", cell.startUtf16)
                            .put("endUtf16Exclusive", cell.endUtf16Exclusive)
                            .put("isHeader", cell.isHeader)
                            .put("rowSpan", cell.rowSpan)
                            .put("colSpan", cell.colSpan)
                    )
                }
                rows.put(JSONObject().put("cells", cells))
            }
            tableArray.put(
                JSONObject()
                    .put("blockId", mark.blockId)
                    .put("domPath", mark.domPath)
                    .put("startUtf16", mark.startUtf16)
                    .put("endUtf16Exclusive", mark.endUtf16Exclusive)
                    .put("rows", rows)
                    .put("role", mark.role.name)
            )
        }
        val rubyArray = JSONArray()
        document.ruby.forEach { mark ->
            val annotations = JSONArray()
            mark.annotations.forEach { annotations.put(it) }
            rubyArray.put(
                JSONObject()
                    .put("blockId", mark.blockId)
                    .put("domPath", mark.domPath)
                    .put("baseStartUtf16", mark.baseStartUtf16)
                    .put("baseEndUtf16Exclusive", mark.baseEndUtf16Exclusive)
                    .put("annotations", annotations)
                    .put("role", mark.role.name)
            )
        }

        return JSONObject()
            .put("schemaVersion", StructuredDocument.CURRENT_SCHEMA_VERSION)
            .put("normalizationVersion", document.normalizationVersion)
            .put("parserSchemaVersion", manifest.parserSchemaVersion)
            .put("revision", manifest.revision)
            .put("documentKey", documentKey)
            .put("textLengthUtf16", document.textLengthUtf16)
            .put("mainTextUnits", TextUnits.countMainUnits(document))
            .put("chunks", chunkArray)
            .put("blocks", blocks)
            .put("anchors", anchors)
            .put("weightIndex", weightIndex)
            .put("images", imageArray)
            .put("lists", listArray)
            .put("tables", tableArray)
            .put("ruby", rubyArray)
            .toString(2)
    }

    /**
     * 递归收集结构图片：图片可以直接是块，也可以藏在 figure、引用块或列表项里。
     * 不递归就会漏掉「图注包裹的插图」与「列表里的图标」。
     */
    private fun collectImages(blocks: List<ContentBlock>): List<ImageBlock> {
        val found = mutableListOf<ImageBlock>()
        fun walk(block: ContentBlock) {
            when (block) {
                is ImageBlock -> found += block
                is FigureBlock -> block.image?.let { found += it }
                is QuoteBlock -> block.children.forEach { walk(it) }
                is ListBlock -> block.items.forEach { item -> item.children.forEach { walk(it) } }
                else -> Unit
            }
        }
        blocks.forEach { walk(it) }
        return found
    }

    private fun buildPackageJson(
        manifest: PublicationManifest,
        documents: List<StagedDocument>,
        resources: List<StagedResource>,
        sourceBytes: ByteArray
    ): String {
        val occurrences = JSONArray()
        manifest.spineOccurrences.forEach { occurrence ->
            occurrences.put(
                JSONObject()
                    .put("occurrenceId", occurrence.occurrenceId)
                    .put("ordinal", occurrence.ordinal)
                    .put("documentKey", occurrence.documentKey ?: JSONObject.NULL)
                    .put("linear", occurrence.linear)
                    .put("mediaType", occurrence.mediaType)
                    .put("counted", occurrence.counted)
            )
        }
        val documentArray = JSONArray()
        documents.forEach { document ->
            documentArray.put(
                JSONObject()
                    .put("documentKey", document.documentKey)
                    .put("jsonRelativePath", document.jsonRelativePath)
                    .put("textRelativePath", document.textRelativePath)
                    .put("textSha256", document.textSha256)
                    .put("chunkCount", document.chunks.size)
            )
        }
        val resourceArray = JSONArray()
        resources.forEach { resource ->
            resourceArray.put(
                JSONObject()
                    .put("resourceId", resource.resourceId)
                    .put("containerPath", resource.containerPath)
                    .put("mediaType", resource.mediaType)
                    .put("byteSize", resource.byteSize)
                    .put("contentHash", resource.contentHash)
                    .put("relativePath", resource.relativePath)
            )
        }
        val sectionArray = JSONArray()
        manifest.sections.forEach { section ->
            val ranges = JSONArray()
            section.ranges.forEach { range ->
                ranges.put(
                    JSONObject()
                        .put("documentKey", range.documentKey)
                        .put("startUtf16", range.startUtf16)
                        .put("endUtf16Exclusive", range.endUtf16Exclusive)
                )
            }
            sectionArray.put(
                JSONObject()
                    .put("sectionId", section.sectionId)
                    .put("ordinal", section.ordinal)
                    .put("title", section.title)
                    .put("kind", section.kind.name)
                    .put("mainTextUnits", section.mainTextUnits)
                    .put("ranges", ranges)
            )
        }

        return JSONObject()
            .put("schemaVersion", manifest.parserSchemaVersion)
            .put("parserSchemaVersion", manifest.parserSchemaVersion)
            .put("normalizationVersion", manifest.normalizationVersion)
            .put("publicationId", manifest.publicationId)
            .put("revision", manifest.revision)
            .put("sourceHash", manifest.sourceHash)
            .put("sourceSha256", sha256Hex(sourceBytes))
            .put("totalMainTextUnits", manifest.totalMainTextUnits)
            .put("spineOccurrences", occurrences)
            .put("documents", documentArray)
            .put("sections", sectionArray)
            .put("resources", resourceArray)
            .toString(2)
    }

    // ------------------------------------------------------------------ 结果类型

    /** 已写入 staging 的一个文档。 */
    data class StagedDocument(
        val documentKey: String,
        val jsonRelativePath: String,
        val textRelativePath: String,
        val textSha256: String,
        val chunks: List<DocumentChunk>
    )

    /** 已写入 staging 的一个资源。 */
    data class StagedResource(
        val resourceId: String,
        val containerPath: String,
        val mediaType: String,
        val byteSize: Long,
        val contentHash: String,
        val relativePath: String
    )

    /** staging 目录的完整描述。 */
    data class StagedPackage(
        val publicationId: String,
        val revision: String,
        val stagingDirectory: File,
        val sourceSha256: String,
        val documents: List<StagedDocument>,
        val resources: List<StagedResource>,
        val manifestSha256: String
    ) {
        /** 该包内所有文件相对 staging 根的路径，供校验和写入索引。 */
        fun relativePaths(): List<String> = buildList {
            add("manifest.json")
            add("source/original.bin")
            documents.forEach { add(it.jsonRelativePath); add(it.textRelativePath) }
            resources.forEach { add(it.relativePath) }
        }
    }

    /** 已提交的不可变 revision 目录。 */
    data class RevisionFiles(
        val publicationId: String,
        val revision: String,
        val directory: File,
        val reusedExisting: Boolean
    )

    companion object {
        /** 文档分片目标：32,768 UTF-16 文本单位（5.1）。 */
        const val DEFAULT_CHUNK_TARGET_UNITS = 32_768
    }
}

/**
 * 一个文档分片。**是 I/O 与布局单位，不是章节**（5.1）。
 *
 * 只带源坐标与 checksum；分片文本本身在 `documents/<hash>.txt` 里，
 * 避免同一份文本在 manifest 里存两遍。
 */
data class DocumentChunk(
    val ordinal: Int,
    val documentKey: String,
    val startUtf16: Int,
    val endUtf16Exclusive: Int,
    /** 单个超长块被切分时，所有分片共享同一 `blockId` 与各自起止（5.1）。 */
    val blockId: String?,
    val checksumSha256: String
) {
    val lengthUtf16: Int get() = endUtf16Exclusive - startUtf16
}

/** 跳过数组里不是对象的元素，逐个交给 [block]（读回代码的容错辅助）。 */
private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) {
        val obj = optJSONObject(i) ?: continue
        block(obj)
    }
}
