package com.flowreader.app.domain.model

/**
 * 显示边界（主计划 3.3）。
 *
 * 每个显示字符边界对应一个规范源边界，或标记为 synthetic
 * （人工缩进 "　　"、列表圆点、重复装饰标题等）。
 */
data class DisplayBoundary(
    /** 源文本中的 UTF-16 偏移；如果是 synthetic 人工插入字符，则为 null。 */
    val sourceOffsetUtf16: Int?,
    /** 是否为 synthetic 人工字符（如人工前缀缩进）。 */
    val isSynthetic: Boolean = (sourceOffsetUtf16 == null),
    /** 所属结构块 ID（便于反查所属块）。 */
    val blockId: String? = null,
    /** 若该边界处于被隐藏的正文区间（如标题回声隐藏），记录隐藏原因。 */
    val hiddenReason: String? = null
)

/**
 * 查找源文本在显示层投影的结果。
 */
data class DisplayLookupResult(
    val displayOffset: Int,
    val isAdjusted: Boolean = false,
    val reasonCode: String? = null
)

/**
 * 视口显示文本与规范源文本的双向映射（主计划 3.3 / INV05）。
 *
 * 不变量：
 * - [boundaries] 的长度必须严格等于 `displayText.length + 1`；
 * - 同一 source locator 经过 `source -> display -> source` 回环，真实文字完全一致；
 * - synthetic 区域点击/交互归并到最近的真实源边界；
 * - 隐藏的正文标题回声不修改源数据，返回 `EXACT` 与 `HIDDEN_TARGET_ADJUSTED`。
 */
class DisplayMapping(
    val documentKey: String,
    val displayText: String,
    val boundaries: List<DisplayBoundary>
) {
    init {
        require(boundaries.size == displayText.length + 1) {
            "boundaries.size (${boundaries.size}) must be displayText.length + 1 (${displayText.length + 1})"
        }
    }

    /**
     * 将显示偏移 [displayOffset] 转换为源文本 UTF-16 偏移。
     * 若命中 synthetic 字符（如缩进空格），自动归并到最近的真实源边界。
     */
    fun displayToSource(displayOffset: Int): Int {
        val clamped = displayOffset.coerceIn(0, displayText.length)
        val direct = boundaries[clamped]
        if (!direct.isSynthetic && direct.sourceOffsetUtf16 != null) {
            return direct.sourceOffsetUtf16
        }

        // 向后寻找第一个非 synthetic 边界
        for (i in (clamped + 1)..displayText.length) {
            val b = boundaries[i]
            if (!b.isSynthetic && b.sourceOffsetUtf16 != null) {
                return b.sourceOffsetUtf16
            }
        }

        // 若向后无非 synthetic，向前寻找
        for (i in (clamped - 1) downTo 0) {
            val b = boundaries[i]
            if (!b.isSynthetic && b.sourceOffsetUtf16 != null) {
                return b.sourceOffsetUtf16
            }
        }

        return 0
    }

    /**
     * 将显示范围 `[displayStart, displayEndExclusive)` 映射为一组非空 [SourceRange]。
     * 人工字符（synthetic 缩进/圆点）自动排除，不写入批注或书签。
     */
    fun displayRangeToSourceRanges(displayStart: Int, displayEndExclusive: Int): List<SourceRange> {
        val start = displayStart.coerceIn(0, displayText.length)
        val end = displayEndExclusive.coerceIn(start, displayText.length)
        if (start >= end) return emptyList()

        val ranges = mutableListOf<SourceRange>()
        var currentRangeStart: Int? = null
        var lastSourceOffset: Int? = null

        fun flush() {
            val start = currentRangeStart
            val last = lastSourceOffset
            if (start != null && last != null) {
                // 末边界取「最后一个真实字符之后」的位置：`boundaries[length]` 就是它。
                ranges.add(SourceRange(documentKey, start, last + 1))
            }
            currentRangeStart = null
            lastSourceOffset = null
        }

        for (i in start until end) {
            // `boundaries[i]` 是**第 i 个显示字符自身**的边界：字符 i 的起点源偏移。
            // 因此判定 synthetic 与取源值都直接读 `boundaries[i]`。
            val charBoundary = boundaries[i]
            val src = charBoundary.sourceOffsetUtf16
            if (charBoundary.isSynthetic || src == null) {
                // 人工字符（缩进/圆点）不产出源坐标：打断当前连续区间。
                flush()
                continue
            }

            if (currentRangeStart == null) {
                currentRangeStart = src
                lastSourceOffset = src
            } else if (lastSourceOffset != null && src == lastSourceOffset!! + 1) {
                lastSourceOffset = src
            } else {
                flush()
                currentRangeStart = src
                lastSourceOffset = src
            }
        }
        flush()

        return ranges
    }

    /**
     * 将源文本 UTF-16 偏移 [sourceOffsetUtf16] 转换为显示层偏移。
     * 若目标落在隐藏节点（如标题回声），解析到下一可见块并返回 [DisplayLookupResult.isAdjusted] = true。
     */
    fun sourceToDisplay(sourceOffsetUtf16: Int): DisplayLookupResult {
        // 先查直接匹配
        for (i in 0..displayText.length) {
            val b = boundaries[i]
            if (b.sourceOffsetUtf16 == sourceOffsetUtf16) {
                if (b.hiddenReason != null) {
                    // 处于隐藏区间：寻找下一个可见的真实边界
                    for (j in (i + 1)..displayText.length) {
                        val next = boundaries[j]
                        if (!next.isSynthetic && next.hiddenReason == null && next.sourceOffsetUtf16 != null) {
                            return DisplayLookupResult(
                                displayOffset = j,
                                isAdjusted = true,
                                reasonCode = "HIDDEN_TARGET_ADJUSTED"
                            )
                        }
                    }
                }
                return DisplayLookupResult(displayOffset = i, isAdjusted = false)
            }
        }

        // 若源偏移落在某个显示字符内部或稍前：找首个 sourceOffsetUtf16 >= 给定偏移的边界
        for (i in 0..displayText.length) {
            val b = boundaries[i]
            if (!b.isSynthetic && b.sourceOffsetUtf16 != null && b.sourceOffsetUtf16 >= sourceOffsetUtf16) {
                return DisplayLookupResult(displayOffset = i, isAdjusted = true, reasonCode = "APPROXIMATE_BOUNDARY")
            }
        }

        return DisplayLookupResult(displayOffset = displayText.length, isAdjusted = true, reasonCode = "CLAMPED_END")
    }

    /**
     * 将单一 [SourceRange] 映射到显示区间 `Pair(displayStart, displayEnd)`。
     */
    fun sourceRangeToDisplayRange(sourceRange: SourceRange): Pair<Int, Int>? {
        if (sourceRange.documentKey != documentKey) return null
        val startResult = sourceToDisplay(sourceRange.startUtf16)
        val endResult = sourceToDisplay(sourceRange.endUtf16Exclusive)
        if (startResult.displayOffset >= endResult.displayOffset) {
            return Pair(startResult.displayOffset, startResult.displayOffset)
        }
        return Pair(startResult.displayOffset, endResult.displayOffset)
    }

    companion object {
        /**
         * 快速构造纯文本 1:1 映射。
         */
        fun identity(documentKey: String, text: String, startOffset: Int = 0): DisplayMapping {
            val boundaries = ArrayList<DisplayBoundary>(text.length + 1)
            for (i in 0..text.length) {
                boundaries.add(DisplayBoundary(sourceOffsetUtf16 = startOffset + i, isSynthetic = false))
            }
            return DisplayMapping(documentKey, text, boundaries)
        }

        fun builder(documentKey: String): DisplayMappingBuilder = DisplayMappingBuilder(documentKey)
    }
}

/**
 * [DisplayMapping] 构造器。
 */
class DisplayMappingBuilder(private val documentKey: String) {
    private val textBuilder = StringBuilder()
    private val boundaries = mutableListOf<DisplayBoundary>()

    init {
        // 第 0 个边界初始为空，在追加第一段时确定；若整段为空则有起点
    }

    /**
     * 追加一段**有源坐标**的显示文本。
     *
     * 不变量（构造期强制）：`boundaries.size == textBuilder.length + 1`，
     * 且 `boundaries[i]` 描述的是**第 i 个显示字符之前**的那个边界。
     * 因此每次 append 只需要为**本段新增的每个字符**各追加一个边界；
     * 段的起始边界由上一个 append 的末尾边界或 [build] 的兜底边界提供。
     * 每种 append 都遵守这条规则，才能保证任意 append 序列交错后依然严格对齐。
     */
    fun appendSource(text: String, sourceStart: Int, blockId: String? = null): DisplayMappingBuilder {
        if (text.isEmpty()) return this
        ensureStartBoundary()
        // `boundaries.last()` 正是「本段第一个显示字符之前」的那个边界，它必须描述源
        // [sourceStart]。当前一段是 synthetic（人工缩进）时，这个边界是占位的 synthetic 边界，
        // 必须在此改写为真实源值；否则本段每个字符的边界都会右移一位（整段源坐标 +1）。
        // 连续的真实段之间，上一个 append 的收尾边界恰好等于本段起点，无需改动。
        val startBoundary = boundaries.last()
        if (startBoundary.isSynthetic || startBoundary.sourceOffsetUtf16 != sourceStart) {
            boundaries[boundaries.size - 1] = DisplayBoundary(
                sourceOffsetUtf16 = sourceStart,
                isSynthetic = false,
                blockId = blockId
            )
        }
        for (i in text.indices) {
            textBuilder.append(text[i])
            boundaries.add(DisplayBoundary(sourceOffsetUtf16 = sourceStart + i + 1, isSynthetic = false, blockId = blockId))
        }
        return this
    }

    /**
     * 追加一段 synthetic 人工字符（人工缩进、列表圆点、装饰性重复标题）。
     *
     * 与 [appendSource] 遵守同一条不变量：只为本段每个字符各追加一个边界。
     * 旧实现在 `boundaries` 为空时先补一个起点、再逐字符各补一个，产出 `length + 2` 个边界，
     * 违反 `size == length + 1`；且人工缩进排在段首时会让**整段**源偏移静默 +1。
     */
    fun appendSynthetic(syntheticText: String, blockId: String? = null): DisplayMappingBuilder {
        if (syntheticText.isEmpty()) return this
        ensureStartBoundary()
        // `boundaries.last()` 是「本段第一个显示字符之前」的边界。人工字符没有源坐标，
        // 因此这个边界必须改写为 synthetic —— 否则它仍带着上一段的真实源值，
        // 判定第 1 个人工字符时会误认为真实文字，且下一段真实文字的起点会被算成 synthetic。
        boundaries[boundaries.size - 1] = DisplayBoundary(
            sourceOffsetUtf16 = null,
            isSynthetic = true,
            blockId = blockId
        )
        for (i in syntheticText.indices) {
            textBuilder.append(syntheticText[i])
            boundaries.add(DisplayBoundary(sourceOffsetUtf16 = null, isSynthetic = true, blockId = blockId))
        }
        return this
    }

    /**
     * 保证「第 0 个边界」存在。
     *
     * 这个边界本身没有对应的源字符；它的源值取后续第一个 append 的起点。
     * 若它必须先出现（synthetic 段在最前），则记为 synthetic，随后
     * [appendSource] 会覆盖它的源值，使真实文字的第一个字符仍映射到 [sourceStart]。
     */
    private fun ensureStartBoundary() {
        if (boundaries.isEmpty()) {
            boundaries.add(DisplayBoundary(sourceOffsetUtf16 = null, isSynthetic = true))
        }
    }

    /**
     * 标记一段**被隐藏的源区间**（如正文标题回声、已折叠的脚注正文）。
     *
     * 关键：隐藏区域**不产生显示字符**，因此**不得**追加任何边界，否则
     * `boundaries.size == displayText.length + 1` 立即被破坏。旧实现只在 `boundaries` 为空时
     * 插一个边界，既破坏了不变量，又把隐藏区间误标成可见字符。
     *
     * 正确语义由 [DisplayMapping.sourceToDisplay] 在查询时按 `hiddenReason` 处理：
     * 隐藏区间的源偏移被解析到下一个可见边界并返回 `HIDDEN_TARGET_ADJUSTED`，
     * 源数据本身不被改写（主计划 3.3）。
     */
    fun appendHidden(sourceRange: SourceRange, reason: String = "HIDDEN_TARGET_ADJUSTED"): DisplayMappingBuilder {
        require(sourceRange.documentKey == documentKey) {
            "hidden range documentKey (${sourceRange.documentKey}) must match mapping documentKey ($documentKey)"
        }
        if (boundaries.isEmpty()) {
            // 整篇都以隐藏区间开始：起点边界直接指向隐藏区间的起点并携带隐藏原因，
            // 不额外增加边界数量。
            boundaries.add(
                DisplayBoundary(
                    sourceOffsetUtf16 = sourceRange.startUtf16,
                    isSynthetic = false,
                    hiddenReason = reason
                )
            )
        }
        return this
    }

    fun build(): DisplayMapping {
        ensureStartBoundary()
        // 末尾边界：`boundaries.size` 必须等于 `textBuilder.length + 1`。
        // 每个 append 只为它新增的字符补边界，所以此处恰好还差一个收尾边界。
        if (boundaries.size == textBuilder.length) {
            val lastSource = boundaries.lastOrNull()?.sourceOffsetUtf16
            boundaries.add(
                DisplayBoundary(
                    sourceOffsetUtf16 = lastSource?.plus(1) ?: null,
                    isSynthetic = lastSource == null
                )
            )
        }
        return DisplayMapping(documentKey, textBuilder.toString(), boundaries)
    }
}
