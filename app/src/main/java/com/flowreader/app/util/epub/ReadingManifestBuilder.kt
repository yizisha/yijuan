package com.flowreader.app.util.epub

import com.flowreader.app.domain.model.DocumentRole
import com.flowreader.app.domain.model.ManifestResource
import com.flowreader.app.domain.model.PublicationDocument
import com.flowreader.app.domain.model.PublicationManifest
import com.flowreader.app.domain.model.ReadingSection
import com.flowreader.app.domain.model.ResourceTargetKind
import com.flowreader.app.domain.model.SectionKind
import com.flowreader.app.domain.model.SourceRange
import com.flowreader.app.domain.model.SpineOccurrence
import com.flowreader.app.domain.model.StructuredDocument
import com.flowreader.app.domain.model.TextUnits

/**
 * 阅读清单构建（主计划 4.1 第 6 步、4.3 末段、10.2、12.3 第 4/6 条、5.1）。
 *
 * 纯函数：输入已经解析好的结构文档与目录树，输出 [PublicationManifest]。
 * **不做任何 I/O**（资源字节、落盘、数据库都在 `PublicationStore`）。
 *
 * 本类集中了三件旧实现做错或没做的事：
 *
 * 1. **sections 不再等于"spine 文档"**（F13 的成因）。边界取主 spine 上不同的叶级可读目录目标，
 *    按实际源顺序排序；第一目标之前的真实正文是 [SectionKind.PREFACE]；一个 section 可以跨多个
 *    相邻文档，一个文档也可以含多个 section。
 * 2. **主线计量去重**（10.2）：重复 spine 出现只算一份源内容，第二次出现仍写入
 *    [SpineOccurrence]（`occurrenceId` 保留顺序），但 `mainTextUnits` 为 0 并带诊断 ——
 *    不能靠 documentKey 覆盖，否则分母会翻倍或丢序。
 * 3. **分母来自完整索引**（INV11）：`totalMainTextUnits` 由本节算出的 sections 求和，
 *    不依赖"当前已加载的章节集合"。
 *
 * 统计口径**只走** [TextUnits]，本类不出现第二处字数计算。
 */
object ReadingManifestBuilder {

    /** 一次构建的输入。 */
    data class Input(
        val publicationId: String,
        val revision: String,
        val normalizationVersion: Int,
        val parserSchemaVersion: Int,
        val sourceHash: String,
        /** 激活 revision 的目录相对路径。 */
        val manifestRelativePath: String,
        /** OPF 路径，用于生成确定性 `occurrenceId`。 */
        val opfPath: String,
        /** spine 的每一次出现，**顺序原样**；无法解析为内容的出现也要保留。 */
        val spine: List<SpineEntry>,
        /** 每个唯一 spine 文档的容器路径 → media-type（诊断用）。 */
        val mediaTypes: Map<String, String> = emptyMap(),
        /** 已解析的结构文档；缺失的 key 表示该文档没有可读内容。 */
        val documents: Map<String, StructuredDocument>,
        /** 主目录树（`EpubNavigationParser.Result.main`），未重排、原顺序保留。 */
        val toc: List<EpubNavigationParser.Node> = emptyList()
    )

    /** spine 的一次出现。 */
    data class SpineEntry(
        /** 解引用后的规范容器路径；解析失败为 null（仍要保留这次出现）。 */
        val documentKey: String?,
        val linear: Boolean = true,
        val mediaType: String = ""
    )

    /** 构建结果：结构错误不返回"表面成功的空书"（4.1 末段）。 */
    sealed interface Result {
        val manifest: PublicationManifest

        data class Built(override val manifest: PublicationManifest) : Result

        data class Rejected(
            override val manifest: PublicationManifest,
            val code: String,
            val detail: String
        ) : Result
    }

    fun build(input: Input): Result {
        val diagnostics = mutableListOf<PublicationManifest.ManifestDiagnostic>()
        val seenDocuments = LinkedHashMap<String, StructuredDocument>()
        val countedDocumentKeys = HashSet<String>()
        val occurrences = mutableListOf<SpineOccurrence>()
        val documents = mutableListOf<PublicationDocument>()
        val documentOrdinals = LinkedHashMap<String, Int>()

        // ---- 1. 逐次保留 spine 出现，并按首次出现计量内容
        input.spine.forEachIndexed { index, entry ->
            val key = entry.documentKey
            val occurrenceId = occurrenceId(input.opfPath, index)
            val document = key?.let { input.documents[it] }
            if (key != null && document == null) {
                diagnostics += PublicationManifest.ManifestDiagnostic.DocumentDiagnostic(
                    code = "SPINE_DOCUMENT_UNREADABLE",
                    detail = "spine 第 $index 项没有可读内容（条目缺失、空文档或解析失败）: $key",
                    documentKey = key
                )
            }
            val firstTime = key != null && countedDocumentKeys.add(key)
            if (key != null && !firstTime) {
                diagnostics += PublicationManifest.ManifestDiagnostic.DocumentDiagnostic(
                    code = "SPINE_DOCUMENT_REPEATED",
                    detail = "文档在 spine 中重复出现，第二次起只保留引用不重复计量: $key",
                    documentKey = key
                )
            }
            occurrences += SpineOccurrence(
                occurrenceId = occurrenceId,
                ordinal = index,
                documentKey = key,
                linear = entry.linear,
                mediaType = entry.mediaType.ifBlank { input.mediaTypes[key].orEmpty() },
                counted = firstTime
            )
            if (key == null || document == null) return@forEachIndexed

            documentOrdinals.putIfAbsent(key, index)
            if (!seenDocuments.containsKey(key)) {
                seenDocuments[key] = document
                documents += PublicationDocument(
                    documentKey = key,
                    ordinal = index,
                    linear = entry.linear,
                    role = documentRole(document, entry.linear),
                    textLengthUtf16 = document.textLengthUtf16,
                    mainTextUnits = if (entry.linear) TextUnits.countMainUnits(document) else 0L,
                    manifestRelativePath = documentManifestPath(input.manifestRelativePath, key),
                    sourceHash = sourceHashOf(document, key)
                )
            } else if (entry.linear && !documents.last { it.documentKey == key }.linear) {
                // 之前只作为辅助出现，之后进了主线：升级 linear，但不重复计量内容。
                val position = documents.indexOfLast { it.documentKey == key }
                documents[position] = documents[position].copy(linear = true)
            }
        }

        // ---- 2. 逻辑 section 边界：主 spine 上不同的叶级可读目录目标（4.3 末段）
        val mainOrder = mainDocumentOrder(occurrences)
        val boundaries = sectionBoundaries(input, occurrences, mainOrder, diagnostics)
        val sections = buildSections(boundaries, mainOrder, seenDocuments, countedDocumentKeys, diagnostics)

        // ---- 3. 目录目标 → section
        val tocTargetSections = LinkedHashMap<String, String>()
        val sectionsByKey = sections.associateBy { it.sectionId }
        collectTocTargets(input.toc, boundaries, mainOrder, sectionsByKey, tocTargetSections, diagnostics)

        // ---- 4. 资源引用汇总
        val resources = collectResources(seenDocuments)

        val manifest = PublicationManifest(
            publicationId = input.publicationId,
            revision = input.revision,
            normalizationVersion = input.normalizationVersion,
            parserSchemaVersion = input.parserSchemaVersion,
            sourceHash = input.sourceHash,
            manifestRelativePath = input.manifestRelativePath,
            spineOccurrences = occurrences,
            documents = documents,
            sections = sections,
            tocTargetSections = tocTargetSections,
            totalMainTextUnits = sections.sumOf { it.mainTextUnits },
            resources = resources,
            diagnostics = diagnostics
        )

        if (sections.isEmpty()) {
            return Result.Rejected(
                manifest = manifest,
                code = "MANIFEST_NO_READABLE_SECTION",
                detail = "没有可读的逻辑 section：spine 为空或全部文档无正文"
            )
        }
        return Result.Built(manifest)
    }

    // ------------------------------------------------------------------ spine

    /**
     * `occurrenceId` = SHA-256(OPF 路径 + itemref 序号) 前 16 位。
     *
     * 确定性（INV02）、**与 documentKey 无关**（同一文档的两次出现必须不同）、不用随机值（12.3 第 6 条）。
     */
    fun occurrenceId(opfPath: String, index: Int): String {
        val input = "$opfPath\u0000$index"
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun documentRole(document: StructuredDocument, linear: Boolean): DocumentRole {
        if (!linear) return DocumentRole.AUXILIARY
        // 文档全是辅助注释（脚注/尾注）时，它的正文不进主线计量（10.2 第 3 条）。
        val hasMain = document.blocks.any { it.role == com.flowreader.app.domain.model.BlockRole.MAIN }
        return if (hasMain) DocumentRole.MAIN else DocumentRole.AUXILIARY
    }

    private fun documentManifestPath(base: String, documentKey: String): String =
        "${base.trimEnd('/')}/documents/${documentKey.replace('/', '_')}.json"

    private fun sourceHashOf(document: StructuredDocument, documentKey: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(document.text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 主线上可读文档的顺序（去重、保留首次出现位置）。 */
    private fun mainDocumentOrder(occurrences: List<SpineOccurrence>): List<String> {
        val keys = LinkedHashSet<String>()
        occurrences.filter { it.linear }.forEach { occurrence ->
            occurrence.documentKey?.let { keys.add(it) }
        }
        return keys.toList()
    }

    // ------------------------------------------------------------------ section 边界

    /** 一个 section 边界：目录目标或文档起点。 */
    private data class Boundary(
        val documentKey: String,
        val offsetUtf16: Int,
        val title: String,
        val aliases: MutableList<String> = mutableListOf(),
        val kind: SectionKind,
        /** `SHA256(documentKey + sourceAnchorIdentity)` 的输入部分。 */
        val anchorIdentity: String
    ) {
        val sectionId: String get() = "s:" + sha256Hex("$documentKey|$anchorIdentity").take(24)
    }

    private fun sectionBoundaries(
        input: Input,
        occurrences: List<SpineOccurrence>,
        mainOrder: List<String>,
        diagnostics: MutableList<PublicationManifest.ManifestDiagnostic>
    ): List<Boundary> {
        val documentRank = mainOrder.withIndex().associate { (index, key) -> key to index }
        val boundaries = LinkedHashMap<String, Boundary>()

        // 4.3 末段：嵌套目录父目标与子目标在同一边界时不重复生成 section（同一 offset 自然合并）。
        val leafTargets = mutableListOf<Triple<String, String, String>>() // documentKey, anchor, label
        fun walk(nodes: List<EpubNavigationParser.Node>) {
            nodes.forEach { node ->
                val path = node.targetPath
                if (path != null && documentRank.containsKey(path)) {
                    val anchor = node.targetFragment ?: ""
                    leafTargets += Triple(path, anchor, node.label)
                    if (node.children.isNotEmpty()) {
                        val sameTargetChildren = node.children.filter {
                            it.targetPath == path && (it.targetFragment ?: "") == anchor
                        }
                        if (sameTargetChildren.isNotEmpty()) {
                            diagnostics += PublicationManifest.ManifestDiagnostic.DocumentDiagnostic(
                                code = "TOC_PARENT_CHILD_SAME_TARGET",
                                detail = "父分组与子项指向同一目标，不重复生成 section: $path#$anchor",
                                documentKey = path,
                                fragment = anchor
                            )
                        }
                    }
                }
                walk(node.children)
            }
        }
        walk(input.toc)

        // 目录顺序与 spine 不一致属于源数据问题：正文顺序仍按 spine，只记诊断（4.3 末段）。
        val tocOrder = leafTargets.map { documentRank[it.first] ?: Int.MAX_VALUE }
        if (tocOrder.zipWithNext().any { (a, b) -> a > b }) {
            diagnostics += PublicationManifest.ManifestDiagnostic.DocumentDiagnostic(
                code = "TOC_ORDER_DIFFERS_FROM_SPINE",
                detail = "目录顺序与 spine 顺序不一致；正文仍按 spine，不按目录重排"
            )
        }

        leafTargets
            .sortedWith(compareBy({ documentRank[it.first] ?: Int.MAX_VALUE }, { anchorOffset(input, it.first, it.second) }))
            .forEach { (documentKey, anchor, label) ->
                val document = input.documents[documentKey] ?: return@forEach
                // 只取**叶级可读**目标：分组标题（无链接）不产生边界。
                if (anchor.isEmpty() && label.isBlank()) return@forEach
                val offset = anchorOffset(input, documentKey, anchor)
                if (offset < 0) {
                    if (anchor.isNotEmpty()) {
                        diagnostics += PublicationManifest.ManifestDiagnostic.DocumentDiagnostic(
                            code = "ANCHOR_NOT_FOUND",
                            detail = "目录目标不存在，未生成 section: $documentKey#$anchor",
                            documentKey = documentKey,
                            fragment = anchor
                        )
                    }
                    return@forEach
                }
                if (!validBoundary(document.text, offset)) {
                    diagnostics += PublicationManifest.ManifestDiagnostic.DocumentDiagnostic(
                        code = "ANCHOR_INVALID_BOUNDARY",
                        detail = "锚点落在代理对中间，已按最近合法边界处理: $documentKey#$anchor",
                        documentKey = documentKey,
                        fragment = anchor
                    )
                }
                val clamped = clampToBoundary(document.text, offset)
                val identity = if (anchor.isEmpty()) "@$clamped" else anchor
                val key = "$documentKey|null|\u0000$clamped"
                val existing = boundaries[key]
                if (existing != null) {
                    // 同一边界的多个标题：第一个是 canonical，其余是别名（不能互相覆盖）。
                    if (label.isNotBlank() && !existing.aliases.contains(label)) existing.aliases += label
                } else {
                    boundaries[key] = Boundary(
                        documentKey = documentKey,
                        offsetUtf16 = clamped,
                        title = label.ifBlank { "正文" },
                        kind = SectionKind.NORMAL,
                        anchorIdentity = identity
                    )
                }
            }

        // 第一目标之前的真实正文 → 前言 section（4.3 末段）。
        val firstByDocument = boundaries.values
            .groupBy { it.documentKey }
            .mapValues { (_, list) -> list.minOf { it.offsetUtf16 } }
        val result = mutableListOf<Boundary>()
        mainOrder.forEach { documentKey ->
            val document = input.documents[documentKey] ?: return@forEach
            val firstBoundary = firstByDocument[documentKey]
            if (endOfMainContent(document) > 0 && (firstBoundary == null || firstBoundary > 0)) {
                result += Boundary(
                    documentKey = documentKey,
                    offsetUtf16 = 0,
                    title = if (mainOrder.first() == documentKey) "正文起始" else nullTitleFallback(documentKey),
                    kind = SectionKind.PREFACE,
                    anchorIdentity = "@0"
                )
            }
            boundaries.values
                .filter { it.documentKey == documentKey }
                .sortedBy { it.offsetUtf16 }
                .forEach { result += it }
        }
        return result
    }

    private fun nullTitleFallback(documentKey: String): String = documentKey.substringAfterLast('/')

    private fun anchorOffset(input: Input, documentKey: String, anchor: String): Int {
        if (anchor.isEmpty()) return 0
        val document = input.documents[documentKey] ?: return -1
        return document.anchorIndex[anchor]?.offsetUtf16 ?: -1
    }

    /** UTF-16 偏移不得落在代理对中间（INV04）。 */
    private fun validBoundary(text: String, offset: Int): Boolean {
        if (offset <= 0 || offset >= text.length) return true
        val previous = text[offset - 1]
        val current = text[offset]
        return !(Character.isHighSurrogate(previous) && Character.isLowSurrogate(current))
    }

    private fun clampToBoundary(text: String, offset: Int): Int {
        var value = offset.coerceIn(0, text.length)
        if (value in 1 until text.length) {
            val previous = text[value - 1]
            val current = text[value]
            if (Character.isHighSurrogate(previous) && Character.isLowSurrogate(current)) value--
        }
        return value
    }

    /**
     * 主线内容的结束偏移：**尾部空白不算内容，但尾部非空白必须算**。
     *
     * 曾经的实现只回退 `\n`，于是"正文最后是一张图"（U+FFFC + LF）会把图片留在 section 之外 ——
     * 也就是 4.5「不吞图」在**区间层**被破坏（渲染层看不到它就等于丢了）。
     * 这里改为回退所有可折叠空白，图片占位符自然被包含进来。
     */
    private fun endOfMainContent(document: StructuredDocument): Int {
        var end = document.text.length
        while (end > 0) {
            val ch = document.text[end - 1]
            if (ch == '\n' || ch == '\r' || ch == '\t' || ch == ' ' || ch == '\u000C') end-- else break
        }
        return end
    }

    // ------------------------------------------------------------------ section 组装

    private fun buildSections(
        boundaries: List<Boundary>,
        mainOrder: List<String>,
        documents: Map<String, StructuredDocument>,
        countedDocumentKeys: Set<String>,
        diagnostics: MutableList<PublicationManifest.ManifestDiagnostic>
    ): List<ReadingSection> {
        if (mainOrder.isEmpty()) return emptyList()
        val docIndex = mainOrder.withIndex().associate { (index, key) -> key to index }

        // 每个文档的边界（含该文档末尾哨兵，便于算末段范围）。
        val boundariesByDocument = boundaries.groupBy { it.documentKey }

        data class Piece(val boundary: Boundary, val startOffset: Int, val endOffset: Int)

        val pieces = mutableListOf<Piece>()
        mainOrder.forEach { documentKey ->
            val document = documents[documentKey] ?: return@forEach
            val list = boundariesByDocument[documentKey].orEmpty().sortedBy { it.offsetUtf16 }
            val contentEnd = endOfMainContent(document)
            if (list.isEmpty()) {
                if (contentEnd > 0) {
                    pieces += Piece(
                        boundary = Boundary(
                            documentKey = documentKey,
                            offsetUtf16 = 0,
                            title = nullTitleFallback(documentKey),
                            kind = SectionKind.NORMAL,
                            anchorIdentity = "@0"
                        ),
                        startOffset = 0,
                        endOffset = contentEnd
                    )
                }
                return@forEach
            }
            list.forEachIndexed { index, boundary ->
                val next = list.getOrNull(index + 1)?.offsetUtf16 ?: Int.MAX_VALUE
                val end = if (next == Int.MAX_VALUE) contentEnd else next
                if (end > boundary.offsetUtf16) {
                    pieces += Piece(boundary = boundary, startOffset = boundary.offsetUtf16, endOffset = end)
                }
            }
        }

        // 同一 section 跨多个相邻文档：本实现按文档切分，跨文档相邻且同 sectionId 的片段合并。
        val merged = LinkedHashMap<String, MutableList<Piece>>()
        pieces.forEach { piece ->
            merged.getOrPut(piece.boundary.sectionId) { mutableListOf() } += piece
        }

        val sections = mutableListOf<ReadingSection>()
        var ordinal = 0
        val orderedIds = pieces.map { it.boundary.sectionId }.distinct()
        orderedIds.forEach { sectionId ->
            val group = merged.getValue(sectionId)
            val boundary = group.first().boundary
            // 同文档内的相邻片段合并为连续范围；跨文档保留为多个 SourceRange（INV03）。
            val ranges = group
                .sortedWith(compareBy({ docIndex[it.boundary.documentKey] ?: Int.MAX_VALUE }, { it.startOffset }))
                .map { SourceRange(it.boundary.documentKey, it.startOffset, it.endOffset) }
            val units = if (countedDocumentKeys.contains(boundary.documentKey)) {
                ranges.sumOf { range -> unitsInRange(documents, range) }
            } else {
                0L
            }
            sections += ReadingSection(
                sectionId = sectionId,
                ordinal = ordinal++,
                title = boundary.title,
                aliases = boundary.aliases.toList(),
                kind = boundary.kind,
                ranges = ranges,
                mainTextUnits = units,
                isRepeatOccurrence = !countedDocumentKeys.contains(boundary.documentKey)
            )
        }

        if (sections.sumOf { it.mainTextUnits } == 0L) {
            diagnostics += PublicationManifest.ManifestDiagnostic.DocumentDiagnostic(
                code = "MANIFEST_ZERO_TEXT_UNITS",
                detail = "主线文本量为 0：可能是纯图片书或结构标题过滤过度，不能除零（10.2）"
            )
        }
        return sections
    }

    /** 一个源区间内的主线字数：只算 MAIN 角色块落在该区间内的部分。 */
    private fun unitsInRange(
        documents: Map<String, StructuredDocument>,
        range: SourceRange
    ): Long {
        val document = documents[range.documentKey] ?: return 0L
        return mainRangesOf(document).sumOf { blockRange ->
            val start = maxOf(blockRange.startUtf16, range.startUtf16)
            val end = minOf(blockRange.endUtf16Exclusive, range.endUtf16Exclusive)
            if (end <= start) 0L else TextUnits.count(document.text.substring(start, end))
        }
    }

    /**
     * 文档里参与主线计量的源区间集合。
     *
     * 用块自己的范围而不是"整段文本减掉辅助区间"，这样 3.2 第 6/7 条
     * （ruby 注音、脚注引用标记、U+FFFC）天然不参与统计。
     */
    internal fun mainRangesOf(document: StructuredDocument): List<SourceRange> {
        val ranges = mutableListOf<SourceRange>()
        document.blocks.forEach { block ->
            if (block.role == com.flowreader.app.domain.model.BlockRole.MAIN) {
                ranges += block.range.toSourceRange()
            }
        }
        return ranges
    }

    // ------------------------------------------------------------------ 目录目标

    private fun collectTocTargets(
        nodes: List<EpubNavigationParser.Node>,
        boundaries: List<Boundary>,
        mainOrder: List<String>,
        sectionsById: Map<String, ReadingSection>,
        out: MutableMap<String, String>,
        diagnostics: MutableList<PublicationManifest.ManifestDiagnostic>
    ) {
        val documentRank = mainOrder.withIndex().associate { (index, key) -> key to index }
        val byKey = boundaries.associateBy { "${it.documentKey}\u0000${it.offsetUtf16}" }

        fun walk(list: List<EpubNavigationParser.Node>) {
            list.forEach { node ->
                val path = node.targetPath
                if (path != null && documentRank.containsKey(path)) {
                    val anchor = node.targetFragment ?: ""
                    // 无锚点的目录目标指向该文档的第一个边界（4.3："文档无 fragment → 文档开头"）。
                    val candidates = boundaries.filter { it.documentKey == path }
                    val boundary = if (anchor.isEmpty()) {
                        candidates.minByOrNull { it.offsetUtf16 }
                    } else {
                        byKey["$path\u0000${candidates.firstOrNull { it.anchorIdentity == anchor }?.offsetUtf16 ?: -1}"]
                    }
                    val sectionId = boundary?.sectionId
                    if (sectionId != null && sectionsById.containsKey(sectionId)) {
                        out[node.nodeId] = sectionId
                    } else if (anchor.isNotEmpty()) {
                        diagnostics += PublicationManifest.ManifestDiagnostic.DocumentDiagnostic(
                            code = "TOC_TARGET_UNRESOLVED",
                            detail = "目录目标未映射到 section: $path#$anchor",
                            documentKey = path,
                            fragment = anchor
                        )
                    }
                }
                walk(node.children)
            }
        }
        walk(nodes)
    }

    // ------------------------------------------------------------------ 资源

    /** 资源汇总的累加器（两个并行列表用命名类型表达，避免 `Pair` 的 `.first/.second` 歧义）。 */
    private class ResourceAccumulator {
        val documentKeys = mutableListOf<String>()
        val rawReferences = mutableListOf<String>()
    }

    private fun collectResources(documents: Map<String, StructuredDocument>): List<ManifestResource> {
        val grouped = LinkedHashMap<String, ResourceAccumulator>()
        documents.forEach { (documentKey, document) ->
            document.resourceRefs
                .filter { it.target == ResourceTargetKind.INTERNAL && !it.containerPath.isNullOrEmpty() }
                .forEach { reference ->
                    val entry = grouped.getOrPut(reference.containerPath!!) { ResourceAccumulator() }
                    if (!entry.documentKeys.contains(documentKey)) entry.documentKeys += documentKey
                    if (!entry.rawReferences.contains(reference.raw)) entry.rawReferences += reference.raw
                }
        }
        return grouped.map { (path, entry) ->
            ManifestResource(
                containerPath = path,
                documentKeys = entry.documentKeys.toList(),
                rawReferences = entry.rawReferences.toList()
            )
        }
    }

    private fun sha256Hex(value: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
