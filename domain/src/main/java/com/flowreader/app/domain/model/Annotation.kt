package com.flowreader.app.domain.model

import java.util.Date

data class Annotation(
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    /** **旧**范围字段：闭区间起点（主计划 5.4 保留，不覆写）。 */
    val startPosition: Int,
    /** **旧**范围字段：**开区间**终点（主计划 1.2/F14：与闭区间混用曾导致少一个字符）。 */
    val endPosition: Int,
    val selectedText: String,
    val note: String = "",
    val color: AnnotationColor = AnnotationColor.YELLOW,
    val type: AnnotationType = AnnotationType.HIGHLIGHT,
    val createdTime: Date = Date(),
    val modifiedTime: Date = Date(),

    // ---- v10 内容坐标（主计划 3.1 / 第 9 节）。
    /**
     * 一条或多条有序 [SourceRange] + revision/context。
     *
     * 跨块选择必须落成多条区间；单条区间表达不了中间夹 synthetic/隐藏区域的断点。
     */
    val rangeLocator: RangeLocator? = null,
    val locatorQuality: ResolutionQuality = ResolutionQuality.UNRESOLVED
) {
    /** 旧字段是否与 [rangeLocator] 的覆盖范围一致（迁移校验用）。 */
    fun legacyRangeMatchesLocator(): Boolean {
        val locator = rangeLocator ?: return false
        if (!locator.isSingleDocument) return false
        val first = locator.ranges.first()
        return first.startUtf16 == startPosition && first.endUtf16Exclusive == endPosition
    }
}

enum class AnnotationType {
    HIGHLIGHT,
    NOTE,
    UNDERLINE
}

enum class AnnotationColor(val colorValue: Long) {
    YELLOW(0xFFFFFF00),
    GREEN(0xFF90EE90),
    BLUE(0xFFADD8E6),
    PINK(0xFFFFB6C1),
    ORANGE(0xFFFFA500)
}

data class SearchResult(
    val bookId: Long,
    val chapterIndex: Int,
    val chapterTitle: String,
    val matchedText: String,
    val position: Int,
    val contextBefore: String = "",
    val contextAfter: String = ""
)

data class SearchQuery(
    val bookId: Long,
    val query: String,
    val caseSensitive: Boolean = false
)
