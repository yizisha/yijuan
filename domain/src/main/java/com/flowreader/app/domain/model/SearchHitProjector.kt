package com.flowreader.app.domain.model

/**
 * 搜索命中 → 规范源 UTF-16 范围的**投影与验证**（主计划第 9 节）。
 *
 * ## 为什么必须有这一层
 *
 * FTS5 的 `snippet()` 返回的是**带 `<<` `>>` 标记的片段文本**，不是位置。
 * 它内部的分词位置（token offset）既不是 UTF-16 偏移，也不对应任何稳定坐标：
 * 主计划明确禁止「把 FTS 高亮索引/分词位置直接冒充 UTF-16」。
 *
 * 本层做两件事，缺一不可：
 * 1. **投影**：从片段里取出被标记的命中词，回到**规范正文**里定位其真实 UTF-16 位置；
 * 2. **消歧**：同一个词在正文里可能重复多次。片段自带**命中处的上下文**，
 *    用它挑出正确的那一次出现——只取「第一次出现」会把用户带到错误的段落。
 *
 * 无法可靠定位时**如实返回 null**，由 UI 显示「定位待恢复」，
 * 绝不构造一个 0 偏移的假结果（那会让用户跳到一个从未匹配的位置）。
 */
object SearchHitProjector {

    private const val MARK_START = "<<"
    private const val MARK_END = ">>"

    /**
     * 一次投影的结果。
     *
     * [ranges] 是该命中在**正文**中的一段或多段 UTF-16 范围；
     * [confidence] 说明这次定位有多可靠（见 [Confidence]）。
     */
    data class ProjectedHit(
        val ranges: List<SourceRange>,
        val matchedText: String,
        val confidence: Confidence
    ) {
        init {
            require(ranges.isNotEmpty()) { "ProjectedHit must carry at least one range" }
        }

        /** 首个命中范围的起点；跳转与书签用它。 */
        val firstStartUtf16: Int get() = ranges.first().startUtf16
    }

    /**
     * 定位可信度。UI 必须据此区分「精确跳转」与「近似跳转」。
     *
     * 之所以分级而不是布尔值：正文里同一个词重复时，仅凭关键词无法确定
     * 用户点的到底是哪一次出现。给出首个候选是**产品上可接受**的近似，
     * 但必须如实标注，不能声称精确。
     */
    enum class Confidence {
        /** 片段上下文唯一命中：位置可精确确定。 */
        EXACT,

        /** 关键词命中多处、上下文不足以消歧：给出首个候选的近似定位。 */
        APPROXIMATE
    }

    /**
     * 从 FTS 片段中提取被标记的命中词。
     *
     * 只取标记**之间**的文字，因此片段里的省略号与上下文文字都不会被误当成关键词。
     */
    fun extractMarkedTerms(snippet: String): List<String> {
        if (snippet.isEmpty()) return emptyList()
        val terms = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val start = snippet.indexOf(MARK_START, cursor)
            if (start < 0) break
            val end = snippet.indexOf(MARK_END, start + MARK_START.length)
            if (end < 0) break
            val term = snippet.substring(start + MARK_START.length, end)
            if (term.isNotEmpty()) terms.add(term)
            cursor = end + MARK_END.length
        }
        return terms
    }

    /**
     * 取出第一处命中标记**之前**的上下文（用于消歧）。
     *
     * `snippet()` 会用省略号截断，因此只保留省略号**之后**的部分——
     * 省略号前面的内容不属于命中处的紧邻上下文。
     */
    fun contextBefore(snippet: String, ellipsis: String = "..."): String {
        val start = snippet.indexOf(MARK_START)
        if (start < 0) return ""
        val before = snippet.substring(0, start)
        val lastEllipsis = before.lastIndexOf(ellipsis)
        return if (lastEllipsis >= 0) before.substring(lastEllipsis + ellipsis.length) else before
    }

    /** 取出第一处命中标记**之后**的上下文；同样只保留省略号之前的部分。 */
    fun contextAfter(snippet: String, ellipsis: String = "..."): String {
        val end = snippet.indexOf(MARK_END)
        if (end < 0) return ""
        val after = snippet.substring(end + MARK_END.length)
        val firstEllipsis = after.indexOf(ellipsis)
        return if (firstEllipsis >= 0) after.substring(0, firstEllipsis) else after
    }

    /** 去掉片段里的高亮标记与省略号，得到可显示的纯文本。 */
    fun stripMarkers(snippet: String, ellipsis: String = "..."): String =
        snippet.replace(MARK_START, "").replace(MARK_END, "").replace(ellipsis, "")

    /**
     * 把一次搜索命中投影为正文范围。
     *
     * @param content 该结果所属章节的**规范正文**（与建立 FTS 索引时写入的内容同一份）。
     * @param documentKey 规范文档 key（本仓库兼容约定为 `chapter:<读序>`）。
     * @param query 用户原始查询词；片段无标记时作为回退关键词。
     * @param snippet FTS 返回的片段（含 `<<` `>>`），可为 null。
     */
    fun project(content: String, documentKey: String, query: String, snippet: String?): ProjectedHit? {
        if (content.isEmpty() || documentKey.isBlank()) return null

        // 优先用片段里真实被标记的词：FTS 可能做前缀扩展（查询 "abc" 命中 "abcdef"），
        // 因此标记词比查询词更可靠。
        val markedTerms = snippet?.let { extractMarkedTerms(it) }.orEmpty()
        val terms = markedTerms.ifEmpty {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) emptyList() else listOf(trimmed)
        }
        if (terms.isEmpty()) return null

        // 用片段的前后上下文来消歧：只有上下文能唯一确定是哪一次出现。
        val before = snippet?.let { contextBefore(it) }.orEmpty()
        val after = snippet?.let { contextAfter(it) }.orEmpty()

        val candidates = content.findAllOccurrences(terms.first())
        if (candidates.isEmpty()) return null

        val chosen: Int
        val confidence: Confidence
        if (candidates.size == 1) {
            chosen = candidates.first()
            confidence = Confidence.EXACT
        } else {
            // 多个候选：用紧邻上下文筛出唯一者。
            val matched = candidates.filter { index ->
                val okBefore = before.isEmpty() || content.endsWithBefore(index, before)
                val okAfter = after.isEmpty() || content.startsWithAt(index + terms.first().length, after)
                okBefore && okAfter
            }
            if (matched.size == 1) {
                chosen = matched.first()
                confidence = Confidence.EXACT
            } else {
                // 上下文不足以消歧：取首个候选，但**如实标注为近似**。
                chosen = candidates.first()
                confidence = Confidence.APPROXIMATE
            }
        }

        val firstRange = SourceRange(documentKey, chosen, chosen + terms.first().length)
        // 多词查询：其余词从首词之后顺序定位（同一片段内的连续命中）。
        val ranges = mutableListOf(firstRange)
        var cursor = firstRange.endUtf16Exclusive
        for (term in terms.drop(1)) {
            val index = content.indexOf(term, cursor, ignoreCase = true)
            if (index < 0) break
            ranges.add(SourceRange(documentKey, index, index + term.length))
            cursor = index + term.length
        }

        // 必须先合并再生成文本：否则 matchedText 会反映合并前的分段（用空格连接），
        // 与返回的 ranges 不再自洽——下游按 ranges 取原文就会与 matchedText 不一致。
        val mergedRanges = mergeAdjacent(ranges)
        val matchedText = mergedRanges.joinToString("") { range ->
            content.substring(range.startUtf16, range.endUtf16Exclusive)
        }
        return ProjectedHit(ranges = mergedRanges, matchedText = matchedText, confidence = confidence)
    }

    /** 找出 [term] 在正文中所有出现位置（大小写不敏感）。 */
    private fun String.findAllOccurrences(term: String): List<Int> {
        if (term.isEmpty()) return emptyList()
        val result = mutableListOf<Int>()
        var index = indexOf(term, 0, ignoreCase = true)
        while (index >= 0) {
            result.add(index)
            index = indexOf(term, index + 1, ignoreCase = true)
        }
        return result
    }

    /** [index] 之前是否恰好以 [context] 结尾。 */
    private fun String.endsWithBefore(index: Int, context: String): Boolean {
        val start = index - context.length
        return start >= 0 && regionMatches(start, context, 0, context.length, ignoreCase = true)
    }

    /** [index] 处是否恰好以 [context] 开始。 */
    private fun String.startsWithAt(index: Int, context: String): Boolean {
        return index + context.length <= length &&
            regionMatches(index, context, 0, context.length, ignoreCase = true)
    }

    /** 合并相接/重叠范围，避免同一段文字被重复报告。 */
    private fun mergeAdjacent(ranges: List<SourceRange>): List<SourceRange> {
        val sorted = ranges.sortedBy { it.startUtf16 }
        val merged = mutableListOf<SourceRange>()
        for (range in sorted) {
            val lastIndex = merged.lastIndex
            val last = if (lastIndex >= 0) merged[lastIndex] else null
            if (last == null || range.startUtf16 > last.endUtf16Exclusive) {
                merged.add(range)
            } else {
                merged[lastIndex] = last.copy(
                    endUtf16Exclusive = maxOf(last.endUtf16Exclusive, range.endUtf16Exclusive)
                )
            }
        }
        return merged
    }
}
