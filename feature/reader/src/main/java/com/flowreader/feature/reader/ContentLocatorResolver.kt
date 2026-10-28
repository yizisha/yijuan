package com.flowreader.feature.reader

import com.flowreader.app.domain.model.Affinity
import com.flowreader.app.domain.model.HeadingBlock
import com.flowreader.app.domain.model.LocatorResolution
import com.flowreader.app.domain.model.PublicationManifest
import com.flowreader.app.domain.model.ResolutionQuality
import com.flowreader.app.domain.model.StructuredDocument
import com.flowreader.app.domain.model.TextContext
import com.flowreader.app.domain.model.TextLocator

/**
 * 六级内容定位恢复器（主计划 3.4 / INV01 / R1）。
 *
 * 彻底停用裸 Int 坐标后，书籍重开、设置改变或 EPUB 修订升级时，
 * 均通过本解析器按六级回退阶梯恢复位置，不可随机跳过：
 *
 * 1. [TIER_EXACT_SOURCE_TEXT]（EXACT）：同 publicationId + 同 revision + 文档与偏移有效；
 * 2. [TIER_HEURISTIC_FALLBACK]（CONTEXT_MATCH）：elementId 锚点命中、同文档 4096 滑窗唯一命中、全文档滑窗唯一命中、指纹重命名文档命中；
 * 3. [TIER_SECTION_OFFSET]（APPROXIMATE）：同 section 字符百分比偏移；
 * 4. [TIER_CHAPTER_PAGE]（APPROXIMATE）：章节序号/页码兼容回退到 section 起点；
 * 5. [TIER_HEADING]（APPROXIMATE）：就近回退到上一级标题锚点；
 * 6. [TIER_REVISION_ROOT]（UNRESOLVED）：全书第一正文起点保底，保留原 locator。
 */
object ContentLocatorResolver {

    const val TIER_EXACT_SOURCE_TEXT = "EXACT_SOURCE_TEXT"
    const val TIER_HEURISTIC_FALLBACK = "HEURISTIC_FALLBACK"
    const val TIER_SECTION_OFFSET = "SECTION_OFFSET"
    const val TIER_CHAPTER_PAGE = "CHAPTER_PAGE"
    const val TIER_HEADING = "HEADING"
    const val TIER_REVISION_ROOT = "REVISION_ROOT"

    const val WINDOW_RADIUS_UTF16 = 4096

    /**
     * 解析 [locator] 到活动 publication revision。
     */
    fun resolve(
        locator: TextLocator,
        activePublicationId: String,
        activeRevision: String,
        manifest: PublicationManifest,
        documentProvider: (documentKey: String) -> StructuredDocument? = { null },
        textProvider: (documentKey: String) -> String? = { documentProvider(it)?.text }
    ): LocatorResolution {
        // ----------------------------------------------------------- 第 1 级：EXACT_SOURCE_TEXT
        if (locator.publicationId == activePublicationId && locator.revision == activeRevision) {
            val pubDoc = manifest.documents.firstOrNull { it.documentKey == locator.documentKey }
            if (pubDoc != null && locator.offsetUtf16 in 0..pubDoc.textLengthUtf16) {
                return LocatorResolution(
                    locator = locator,
                    quality = ResolutionQuality.EXACT,
                    reasonCode = TIER_EXACT_SOURCE_TEXT
                )
            }
        }

        // 查找目标文档（或通过 sourceHash 指纹找到被重命名的文档）
        val targetDocKey = findDocumentKey(locator.documentKey, manifest)
        val docText = targetDocKey?.let { textProvider(it) }
        val docStructured = targetDocKey?.let { documentProvider(it) }

        // ----------------------------------------------------------- 第 2 级：HEURISTIC_FALLBACK
        if (targetDocKey != null) {
            // 2A: elementId 锚点命中
            if (locator.elementId != null && docStructured != null) {
                val anchor = docStructured.anchorIndex[locator.elementId]
                if (anchor != null) {
                    val verified = verifyContext(docText, anchor.offsetUtf16, locator.context)
                    if (verified) {
                        return LocatorResolution(
                            locator = locator.copy(
                                publicationId = activePublicationId,
                                revision = activeRevision,
                                documentKey = targetDocKey,
                                offsetUtf16 = anchor.offsetUtf16,
                                blockId = anchor.blockId
                            ),
                            quality = ResolutionQuality.CONTEXT_MATCH,
                            reasonCode = "${TIER_HEURISTIC_FALLBACK}_ELEMENT_ID"
                        )
                    }
                }
            }

            // 2B: 4096 字符滑窗上下文搜索
            if (docText != null && (locator.context.before.isNotEmpty() || locator.context.after.isNotEmpty())) {
                val windowStart = (locator.offsetUtf16 - WINDOW_RADIUS_UTF16).coerceAtLeast(0)
                val windowEnd = (locator.offsetUtf16 + WINDOW_RADIUS_UTF16).coerceAtMost(docText.length)
                val windowMatch = findUniqueContextMatch(docText, windowStart, windowEnd, locator.context)
                if (windowMatch != null) {
                    return LocatorResolution(
                        locator = locator.copy(
                            publicationId = activePublicationId,
                            revision = activeRevision,
                            documentKey = targetDocKey,
                            offsetUtf16 = windowMatch
                        ),
                        quality = ResolutionQuality.CONTEXT_MATCH,
                        reasonCode = "${TIER_HEURISTIC_FALLBACK}_WINDOW"
                    )
                }

                // 2C: 扩大到全文档上下文搜索（仅在唯一时接受；若多候选则为歧义 LOCATOR_AMBIGUOUS）
                val docMatch = findUniqueContextMatch(docText, 0, docText.length, locator.context)
                if (docMatch != null) {
                    return LocatorResolution(
                        locator = locator.copy(
                            publicationId = activePublicationId,
                            revision = activeRevision,
                            documentKey = targetDocKey,
                            offsetUtf16 = docMatch
                        ),
                        quality = ResolutionQuality.CONTEXT_MATCH,
                        reasonCode = "${TIER_HEURISTIC_FALLBACK}_DOCUMENT"
                    )
                }
            }
        }

        // ----------------------------------------------------------- 第 3 级：SECTION_OFFSET
        if (locator.sectionId != null) {
            val section = manifest.sections.firstOrNull { it.sectionId == locator.sectionId }
            if (section != null && section.ranges.isNotEmpty()) {
                val firstRange = section.ranges.first()
                val targetDoc = manifest.documents.firstOrNull { it.documentKey == firstRange.documentKey }
                val docLength = targetDoc?.textLengthUtf16 ?: firstRange.lengthUtf16
                val clampedOffset = if (docLength > 0) {
                    (firstRange.startUtf16 + (locator.offsetUtf16 % docLength.coerceAtLeast(1)))
                        .coerceIn(firstRange.startUtf16, firstRange.endUtf16Exclusive)
                } else {
                    firstRange.startUtf16
                }
                return LocatorResolution(
                    locator = locator.copy(
                        publicationId = activePublicationId,
                        revision = activeRevision,
                        documentKey = firstRange.documentKey,
                        sectionId = section.sectionId,
                        offsetUtf16 = clampedOffset
                    ),
                    quality = ResolutionQuality.APPROXIMATE,
                    reasonCode = TIER_SECTION_OFFSET
                )
            }
        }

        // ----------------------------------------------------------- 第 4 级：CHAPTER_PAGE
        val ordinalCandidate = locator.sectionId?.toIntOrNull()
        val sectionByOrdinal = if (ordinalCandidate != null) {
            manifest.sections.firstOrNull { it.ordinal == ordinalCandidate }
        } else {
            null
        }
        if (sectionByOrdinal != null && sectionByOrdinal.ranges.isNotEmpty()) {
            val firstRange = sectionByOrdinal.ranges.first()
            return LocatorResolution(
                locator = locator.copy(
                    publicationId = activePublicationId,
                    revision = activeRevision,
                    documentKey = firstRange.documentKey,
                    sectionId = sectionByOrdinal.sectionId,
                    offsetUtf16 = firstRange.startUtf16
                ),
                quality = ResolutionQuality.APPROXIMATE,
                reasonCode = TIER_CHAPTER_PAGE
            )
        }

        // ----------------------------------------------------------- 第 5 级：HEADING
        if (targetDocKey != null && docStructured != null) {
            val heading = docStructured.blocks.filterIsInstance<HeadingBlock>()
                .lastOrNull { it.range.startUtf16 <= locator.offsetUtf16 }
            if (heading != null) {
                return LocatorResolution(
                    locator = locator.copy(
                        publicationId = activePublicationId,
                        revision = activeRevision,
                        documentKey = targetDocKey,
                        offsetUtf16 = heading.range.startUtf16,
                        blockId = heading.blockId
                    ),
                    quality = ResolutionQuality.APPROXIMATE,
                    reasonCode = TIER_HEADING
                )
            }
        }

        // ----------------------------------------------------------- 第 6 级：REVISION_ROOT
        val rootSection = manifest.sections.firstOrNull()
        val rootDocKey = rootSection?.firstRange?.documentKey
            ?: manifest.mainDocumentKeys.firstOrNull()
            ?: manifest.documents.firstOrNull()?.documentKey
            ?: "text/main"
        val rootOffset = rootSection?.firstRange?.startUtf16 ?: 0

        return LocatorResolution(
            locator = locator.copy(
                publicationId = activePublicationId,
                revision = activeRevision,
                documentKey = rootDocKey,
                sectionId = rootSection?.sectionId,
                offsetUtf16 = rootOffset
            ),
            quality = ResolutionQuality.UNRESOLVED,
            reasonCode = TIER_REVISION_ROOT
        )
    }

    /**
     * 将旧版阅读位置 (chapterIndex, rawPosition) 转换为 [LocatorResolution]。
     */
    fun resolveLegacy(
        bookId: Long,
        chapterIndex: Int,
        position: Int,
        activePublicationId: String,
        activeRevision: String,
        manifest: PublicationManifest
    ): LocatorResolution {
        val section = manifest.sections.getOrNull(chapterIndex)
            ?: manifest.sections.firstOrNull { it.ordinal == chapterIndex }
            ?: manifest.sections.firstOrNull()

        if (section != null && section.ranges.isNotEmpty()) {
            val range = section.ranges.first()
            val targetOffset = (range.startUtf16 + position.coerceAtLeast(0)).coerceIn(
                range.startUtf16,
                range.endUtf16Exclusive
            )
            return LocatorResolution(
                locator = TextLocator(
                    publicationId = activePublicationId,
                    revision = activeRevision,
                    normalizationVersion = manifest.normalizationVersion,
                    sectionId = section.sectionId,
                    documentKey = range.documentKey,
                    offsetUtf16 = targetOffset,
                    affinity = Affinity.AFTER
                ),
                quality = ResolutionQuality.APPROXIMATE,
                reasonCode = TIER_CHAPTER_PAGE
            )
        }

        val rootDocKey = manifest.mainDocumentKeys.firstOrNull() ?: "text/main"
        return LocatorResolution(
            locator = TextLocator(
                publicationId = activePublicationId,
                revision = activeRevision,
                normalizationVersion = manifest.normalizationVersion,
                documentKey = rootDocKey,
                offsetUtf16 = 0,
                affinity = Affinity.AFTER
            ),
            quality = ResolutionQuality.UNRESOLVED,
            reasonCode = TIER_REVISION_ROOT
        )
    }

    /**
     * 从源文本生成上下文，前后各最多 [maxCodePoints] 个 Unicode 码点，不拆开代理对。
     */
    fun captureContext(text: String, offsetUtf16: Int, maxCodePoints: Int = TextContext.MAX_CODE_POINTS): TextContext {
        if (text.isEmpty()) return TextContext.EMPTY
        val clampedOffset = offsetUtf16.coerceIn(0, text.length)

        // 前文：向前取最多 maxCodePoints 个码点
        var beforeStart = clampedOffset
        var beforeCount = 0
        while (beforeStart > 0 && beforeCount < maxCodePoints) {
            beforeStart = text.offsetByCodePoints(beforeStart, -1)
            beforeCount++
        }
        val before = text.substring(beforeStart, clampedOffset)

        // 后文：向后取最多 maxCodePoints 个码点
        var afterEnd = clampedOffset
        var afterCount = 0
        while (afterEnd < text.length && afterCount < maxCodePoints) {
            afterEnd = text.offsetByCodePoints(afterEnd, 1)
            afterCount++
        }
        val after = text.substring(clampedOffset, afterEnd)

        return TextContext(before = before, after = after)
    }

    private fun findDocumentKey(documentKey: String, manifest: PublicationManifest): String? {
        if (manifest.documents.any { it.documentKey == documentKey }) return documentKey
        // 查找 sourceHash 指纹匹配（重命名候选）
        val matchingHash = manifest.documents.firstOrNull { it.documentKey.endsWith(documentKey.substringAfterLast('/')) }
        return matchingHash?.documentKey
    }

    private fun verifyContext(text: String?, offsetUtf16: Int, context: TextContext): Boolean {
        if (text == null) return false
        if (context.before.isEmpty() && context.after.isEmpty()) return true
        val beforeMatches = context.before.isEmpty() ||
            (
                offsetUtf16 >= context.before.length &&
                    text.regionMatches(offsetUtf16 - context.before.length, context.before, 0, context.before.length)
                )
        val afterMatches = context.after.isEmpty() ||
            (
                offsetUtf16 + context.after.length <= text.length &&
                    text.regionMatches(offsetUtf16, context.after, 0, context.after.length)
                )
        return beforeMatches && afterMatches
    }

    /**
     * 在 `searchStart..searchEnd` 范围内寻找唯一的上下文匹配点。
     * 若找到严格 1 个，返回对应 offset；若 0 个或多个（歧义），返回 null。
     */
    private fun findUniqueContextMatch(text: String, searchStart: Int, searchEnd: Int, context: TextContext): Int? {
        val query = context.before + context.after
        if (query.isEmpty()) return null

        var matches = 0
        var foundOffset = -1

        var idx = searchStart
        while (idx <= searchEnd - query.length) {
            val matchPos = text.indexOf(query, idx)
            if (matchPos == -1 || matchPos > searchEnd - query.length) break

            matches++
            foundOffset = matchPos + context.before.length
            if (matches > 1) {
                // 歧义：多于一个匹配，不猜
                return null
            }
            idx = matchPos + 1
        }

        return if (matches == 1) foundOffset else null
    }
}
