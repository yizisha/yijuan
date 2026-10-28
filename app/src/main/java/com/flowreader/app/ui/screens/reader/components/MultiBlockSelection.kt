package com.flowreader.app.ui.screens.reader.components

import com.flowreader.app.domain.model.RangeLocator
import com.flowreader.app.domain.model.ResolutionQuality
import com.flowreader.app.domain.model.SourceRange

/**
 * 一次跨块选择经装配后的结果（主计划第 9 节）。
 *
 * 它是 UI 层交给 ViewModel 的最终产物：**已经投影成规范源范围**，而不是显示偏移。
 * 携带 [ranges] 与 [text] 两样东西且二者自洽——这是 F14 的教训：
 * 位置与文本必须来自同一份计算，不能各算各的（曾出现闭/开区间混用导致少一个字符）。
 */
data class MultiBlockSelection(
    /** 有序、已合并的源范围；同一文档内相接/重叠已合一。 */
    val ranges: List<SourceRange>,
    /** 与 [ranges] 严格对应的原文（跨文档之间以换行分隔）。 */
    val text: String,
    /** 装配质量。目前投影来自 DisplayMapping 精确边界，故为 EXACT。 */
    val quality: ResolutionQuality = ResolutionQuality.EXACT,
    /** 选择所属章节序号，供调用方丢弃过期提交（INV08）。 */
    val chapterIndex: Int
) {
    /** 首个真实源范围起点；「选中文字建书签」用它（主计划第 9 节）。 */
    val firstRangeStartUtf16: Int get() = ranges.first().startUtf16

    /** 是否跨越多个段落/文档（用于 UI 提示与诊断）。 */
    val isCrossBlock: Boolean get() = ranges.size > 1

    /**
     * 转成可持久化的 [RangeLocator]。
     *
     * [publicationId]/[revision] 由调用方注入：UI 层不知道也不应猜这两个值。
     */
    fun toRangeLocator(publicationId: String, revision: String): RangeLocator = RangeLocator(
        publicationId = publicationId,
        revision = revision,
        ranges = ranges,
        quality = quality
    )
}
