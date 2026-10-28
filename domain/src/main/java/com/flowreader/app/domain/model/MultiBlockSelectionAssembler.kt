package com.flowreader.app.domain.model

/**
 * 跨块选择 → 多区间批注的纯装配层（主计划第 9 节 / 3.3 / INV03）。
 *
 * ## 为什么必须有这一层
 *
 * 旧实现的选择回调用 `(text, startPosition, endPosition)` 三个值表达，
 * 且 `startPosition..endPosition` 只能是一个**连续**的 `IntRange`。
 * 一旦选择跨过段落边界或跨过被排除的人工字符（synthetic 缩进、隐藏区域），
 * 这个模型只能给出首尾两点，于是：
 *
 * - 中间**从未被选中**的内容会被一并纳入范围（数据错误，不是显示瑕疵）；
 * - 或者反过来，为了让范围连续而丢掉断点，导致高亮/导出把同一段文字输出两次。
 *
 * 本层把「若干块各自被选中的显示区间」装配为**一组有序、已合并的 [SourceRange]**，
 * 并由这些范围反推文本，保证：
 *
 * 1. synthetic / 隐藏区域**绝不**进入源范围（它们不是正文）；
 * 2. 相接或重叠的范围合并，同一段文字不会出现两次；
 * 3. 文本严格等于「首个范围起点 → 末个范围终点」的源文本，与范围自洽（F14 的教训：
 *    位置与文本必须来自同一份计算，不能各算各的）。
 */
object MultiBlockSelectionAssembler {

    /**
     * 一个块中被选中的显示区间。
     *
     * @param mapping 该块的显示 ↔ 源双向映射（含 synthetic 标记）。
     * @param displayStart 选中起点（块内显示下标，含）。
     * @param displayEndExclusive 选中终点（块内显示下标，不含）。
     */
    data class SelectedSegment(
        val mapping: DisplayMapping,
        val displayStart: Int,
        val displayEndExclusive: Int
    )

    /**
     * 装配结果。
     *
     * [ranges] 按文档分组、组内按起点升序且同一文档内相接/重叠已合并。
     * [text] 与 [ranges] 严格自洽：等于首范围起点到末范围终点之间的源文本。
     */
    data class Assembled(
        val ranges: List<SourceRange>,
        val text: String,
        val quality: ResolutionQuality
    ) {
        /** 首个真实源范围起点；「选中文字建书签」用它（主计划第 9 节）。 */
        val firstRangeStartUtf16: Int get() = ranges.first().startUtf16
    }

    /**
     * 装配多个块的选择。
     *
     * @param segments 按**阅读顺序**给出的选中区间；顺序错乱会被规整，但调用方不应依赖。
     * @param rawTextProvider 取某个规范文档的完整源文本；返回 null 表示该文档不可用。
     * @return 全部区间都无效时返回 null（调用方必须当作「无可持久化内容」，
     *   不得退化成 0 偏移的假范围）。文本无法取得时为 null，因为「有范围没文本」的批注
     *   在 UI 上无法回显、在导出时会变成空白。
     */
    fun assemble(segments: List<SelectedSegment>, rawTextProvider: (documentKey: String) -> String?): Assembled? {
        if (segments.isEmpty()) return null

        // 逐块投影：displayRangeToSourceRanges 会跳过 synthetic 与隐藏字符，
        // 这正是「人工缩进不进入批注范围」的落点。
        val projected = segments.flatMap { segment ->
            segment.mapping.displayRangeToSourceRanges(segment.displayStart, segment.displayEndExclusive)
        }
        if (projected.isEmpty()) return null

        // 段落的 DisplayMapping 以「段内偏移」为基准，不含段间分隔符，因此跨段选择
        // 会得到 `[2,10)` 与 `[12,19)` 这样「中间只差一个段落分隔」的两段。
        // 源文本上这其实是**连续**的一段（用户确实从上一段拖到了下一段），
        // 因此在同一文档内把**仅隔空白/换行**的相邻区间合并，恢复源坐标的真实语义。
        val mergedWithGaps = mergeWhitespaceGaps(projected, rawTextProvider)
        val merged = mergeRanges(mergedWithGaps)
        val text = buildText(merged, rawTextProvider) ?: return null
        // 投影来自 DisplayMapping 的源边界，属于精确映射；调用方可在恢复失败后降级。
        return Assembled(ranges = merged, text = text, quality = ResolutionQuality.EXACT)
    }

    /**
     * 合并同一文档内**仅被空白字符分隔**的相邻区间。
     *
     * 场景：阅读器按 `"\n\n"` 切分段落，每段的 `DisplayMapping` 只描述段内偏移，
     * 于是「从第 1 段拖到第 2 段」会产出 `[.., 段1末)` 与 `[段2首, ..)` 两条，
     * 中间恰好是段落分隔符。源文本上这是连续的一段，必须合并，
     * 否则批注文本会丢掉段落断行、且范围与文本不再自洽。
     *
     * **保守边界**：只有当两区间之间的源文本**全部是空白**时才合并。
     * 间隔里只要有一个可见字符，就说明用户跳过了真实内容，必须保持分离。
     */
    private fun mergeWhitespaceGaps(ranges: List<SourceRange>, rawTextProvider: (documentKey: String) -> String?): List<SourceRange> {
        if (ranges.size < 2) return ranges
        val sorted = ranges.sortedWith(
            compareBy({ it.documentKey }, { it.startUtf16 }, { it.endUtf16Exclusive })
        )
        val cache = mutableMapOf<String, String>()
        val result = mutableListOf<SourceRange>()
        for (range in sorted) {
            val lastIndex = result.lastIndex
            val last = if (lastIndex >= 0) result[lastIndex] else null
            if (last == null || last.documentKey != range.documentKey || range.startUtf16 < last.endUtf16Exclusive) {
                // 不同文档、或重叠/相接：交给后续 mergeRanges 统一处理。
                result.add(range)
                continue
            }
            val text = cache.getOrPut(range.documentKey) {
                rawTextProvider(range.documentKey) ?: return@getOrPut ""
            }
            val gapEnd = range.startUtf16.coerceAtMost(text.length)
            val gapStart = last.endUtf16Exclusive.coerceAtMost(gapEnd)
            val gap = if (text.isEmpty() || gapStart >= gapEnd) "" else text.substring(gapStart, gapEnd)
            if (gap.isEmpty() || gap.all { it.isWhitespace() }) {
                result[lastIndex] = last.copy(endUtf16Exclusive = range.endUtf16Exclusive)
            } else {
                result.add(range)
            }
        }
        return result
    }

    /**
     * 按文档分组、组内按起点排序并合并相接/重叠区间。
     *
     * 合并规则必须是「相接或重叠」：`[0,5)` 与 `[5,9)` 表示一段连续文字，必须合一，
     * 否则下游会把同一段内容当成两段分别处理（导出重复、统计翻倍）。
     * 中间有空洞的区间**保持分离**，因为空洞里的文字从未被选中。
     */
    internal fun mergeRanges(ranges: List<SourceRange>): List<SourceRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedWith(
            compareBy({ it.documentKey }, { it.startUtf16 }, { it.endUtf16Exclusive })
        )
        val merged = mutableListOf<SourceRange>()
        for (range in sorted) {
            val lastIndex = merged.lastIndex
            val last = if (lastIndex >= 0) merged[lastIndex] else null
            if (last == null || last.documentKey != range.documentKey || range.startUtf16 > last.endUtf16Exclusive) {
                merged.add(range)
            } else {
                merged[lastIndex] = last.copy(
                    endUtf16Exclusive = maxOf(last.endUtf16Exclusive, range.endUtf16Exclusive)
                )
            }
        }
        return merged
    }

    /**
     * 由已合并的范围反推文本。
     *
     * **关键不变量**：`text.length == ranges.sumOf { it.lengthUtf16 }`。
     *
     * 为什么不能用 `joinToString("\n")`：那会凭空插入分隔符，使文本比范围多出
     * N-1 个字符。批注的「位置」与「文本」一旦不一致，下游（导出、高亮回显、
     * 修订后按文本校验）就会各算各的——这正是 F14 记录过的缺陷类型。
     *
     * 因此本方法**只取各范围自身的文本并直接拼接**，范围之间真实存在的间隙文字
     * （例如段落之间的换行）如果不属于任何范围，就不出现在文本里；
     * 若它属于选择，调用方应当把它并入范围（相邻范围合并逻辑负责连续情形）。
     */
    private fun buildText(ranges: List<SourceRange>, rawTextProvider: (documentKey: String) -> String?): String? {
        val cache = mutableMapOf<String, String>()
        val pieces = mutableListOf<String>()
        for (range in ranges) {
            val text = cache.getOrPut(range.documentKey) {
                rawTextProvider(range.documentKey) ?: return null
            }
            if (range.startUtf16 < 0 || range.endUtf16Exclusive > text.length) return null
            pieces.add(text.substring(range.startUtf16, range.endUtf16Exclusive))
        }
        return pieces.joinToString("")
    }
}
