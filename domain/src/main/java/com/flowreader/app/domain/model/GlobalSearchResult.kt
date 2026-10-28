package com.flowreader.app.domain.model

/**
 * 搜索结果（主计划第 9 节）。
 *
 * ## 相比旧模型新增了什么
 *
 * 旧模型只有 `bookId / chapterIndex / chapterTitle / matchedText`：**没有内容坐标**。
 * 于是点击搜索结果只能跳到章首，且 `matchedText` 是 FTS `snippet()` 的原文（带 `<<` `>>`），
 * 既不能用来高亮，也不能用来定位。
 *
 * 新模型补齐了定位所需的一切：
 * - [documentKey] / [revision] / [publicationId]：与 [TextLocator] 同一套坐标语义；
 * - [ranges]：**已验证**的正文 UTF-16 范围（来自 [SearchHitProjector]），不是分词位置；
 * - [confidence]：精确或近似，UI 必须据此区分，不得把近似说成精确。
 *
 * [matchedText] 保留为**去掉标记后的可显示文本**，不再是原始 snippet。
 */
data class GlobalSearchResult(
    val bookId: Long,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val matchedText: String,
    /** 规范文档 key；本仓库兼容约定为 `chapter:<读序>`。 */
    val documentKey: String = "",
    /** 该书活动 revision；用于判断搜索结果是否已过期。 */
    val revision: String = "",
    val publicationId: String = "",
    /** 已验证的正文 UTF-16 范围；无法验证时为空列表（UI 显示「定位待恢复」）。 */
    val ranges: List<SourceRange> = emptyList(),
    val confidence: SearchHitProjector.Confidence = SearchHitProjector.Confidence.APPROXIMATE
) {
    /** 是否具备可精确跳转的内容坐标。 */
    val isLocatable: Boolean get() = ranges.isNotEmpty()

    /** 首个命中范围的起点；跳转用它。 */
    val firstStartUtf16: Int? get() = ranges.firstOrNull()?.startUtf16

    /**
     * 转成可持久化的 [TextLocator]。
     *
     * 无可靠坐标时返回 null——调用方**必须**退回章首并显示「定位待恢复」，
     * 不得用 chapterIndex 伪造一个字符偏移。
     */
    fun toTextLocator(context: TextContext = TextContext.EMPTY): TextLocator? {
        val offset = firstStartUtf16 ?: return null
        if (publicationId.isBlank() || documentKey.isBlank()) return null
        return TextLocator(
            publicationId = publicationId,
            revision = revision,
            sectionId = "section_$chapterIndex",
            documentKey = documentKey,
            offsetUtf16 = offset,
            affinity = Affinity.AFTER,
            context = context
        )
    }

    /** 转成可持久化的多区间坐标（用于「把搜索命中变成批注/书签」）。 */
    fun toRangeLocator(): RangeLocator? {
        if (ranges.isEmpty() || publicationId.isBlank()) return null
        return RangeLocator(
            publicationId = publicationId,
            revision = revision,
            ranges = ranges,
            quality = if (confidence == SearchHitProjector.Confidence.EXACT) {
                ResolutionQuality.EXACT
            } else {
                ResolutionQuality.APPROXIMATE
            }
        )
    }
}
