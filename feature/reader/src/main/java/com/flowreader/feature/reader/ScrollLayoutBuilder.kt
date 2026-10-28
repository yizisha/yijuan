package com.flowreader.feature.reader

import com.flowreader.app.domain.model.DisplayMapping

/**
 * 从 Compose 测量结果构建 [ScrollViewportProjector.BlockLayout]（主计划 6.6）。
 *
 * 这一层刻意把 Compose 的 `TextLayoutResult` 调用**收敛到少量纯函数**：
 * `TextLayoutResult` 无法在 JVM 单测里构造，所以「读取行盒」「显示偏移 → 源偏移」这两步
 * 通过 [LineSourceMap] 抽象出来，测试用等价的手工数据即可覆盖全部算法分支，
 * 而 Composable 侧只负责把真实 `TextLayoutResult` 喂进来。
 *
 * 单位约定：
 * - 行盒由 [lineTop] / [lineBottom] 提供，单位是**像素**（与 `ScrollState.value` 同单位）；
 * - 源偏移一律是规范源文本 UTF-16 边界，由 [DisplayMapping] 换算，**绝不用像素近似**。
 */
object ScrollLayoutBuilder {

    /**
     * 抽象「一行显示文本 → 其源范围」的映射来源。
     *
     * 真实实现基于 [DisplayMapping]；测试可给出等价的手工映射。
     */
    fun interface LineSourceMap {
        /**
         * @param displayStart 该行显示文本在**本块显示文本**中的起始下标。
         * @param displayEndExclusive 该行显示文本在**本块显示文本**中的结束下标（exclusive）。
         * @return 该行对应的源范围；整行 synthetic（如人工缩进）时返回 null。
         */
        fun sourceRangeOf(displayStart: Int, displayEndExclusive: Int): Pair<Int, Int>?
    }

    /** 用 [DisplayMapping] 构造 [LineSourceMap]：人工缩进等 synthetic 字符会被正确排除。 */
    fun mapOfDisplay(mapping: DisplayMapping): LineSourceMap = LineSourceMap { start, end ->
        val ranges = mapping.displayRangeToSourceRanges(start, end)
        if (ranges.isEmpty()) {
            null
        } else {
            ranges.first().startUtf16 to ranges.last().endUtf16Exclusive
        }
    }

    /**
     * 构建一个块的布局几何。
     *
     * @param blockId 结构块稳定身份。
     * @param documentKey 该块所属规范文档 key。
     * @param contentTopPx 该块在滚动内容坐标系中的绝对顶部像素。
     * @param lineCount 真实测量出的行数（`TextLayoutResult.lineCount`）。
     * @param lineTop 行顶相对块顶的像素偏移提供者。
     * @param lineBottom 行底相对块顶的像素偏移提供者。
     * @param lineStart 行首显示偏移提供者。
     * @param lineEnd 行尾显示偏移提供者（exclusive）。
     */
    fun build(
        blockId: String?,
        documentKey: String,
        contentTopPx: Float,
        lineCount: Int,
        lineTop: (Int) -> Float,
        lineBottom: (Int) -> Float,
        lineStart: (Int) -> Int,
        lineEnd: (Int) -> Int,
        sourceMap: LineSourceMap,
        contentLeftPx: Float = 0f
    ): ScrollViewportProjector.BlockLayout {
        val lines = ArrayList<ScrollViewportProjector.LineBox>(lineCount.coerceAtLeast(0))
        for (i in 0 until lineCount) {
            val start = lineStart(i)
            val end = lineEnd(i)
            val source = if (end > start) sourceMap.sourceRangeOf(start, end) else null
            lines.add(
                ScrollViewportProjector.LineBox(
                    topInBlockPx = lineTop(i),
                    bottomInBlockPx = lineBottom(i),
                    sourceStartUtf16 = source?.first,
                    sourceEndUtf16Exclusive = source?.second,
                    displayLength = (end - start).coerceAtLeast(0)
                )
            )
        }
        return ScrollViewportProjector.BlockLayout(
            blockId = blockId,
            documentKey = documentKey,
            contentTopPx = contentTopPx,
            contentLeftPx = contentLeftPx,
            lines = lines
        )
    }

    /**
     * 依据块在滚动内容中的垂直顺序，为每个块推导绝对 [BlockLayout.contentTopPx]。
     *
     * [measuredHeights] 与 [blocks] 一一对应，来自真实测量；块间距由 [gapPx] 提供。
     * 这样即使某些块尚未测量（高度为 0），也保持整体单调，不会互相错位。
     */
    fun withCumulativeTops(
        blocks: List<ScrollViewportProjector.BlockLayout>,
        measuredHeights: List<Float>,
        gapPx: Float = 0f
    ): List<ScrollViewportProjector.BlockLayout> {
        var cursor = 0f
        return blocks.mapIndexed { index, block ->
            val top = cursor
            val height = measuredHeights.getOrElse(index) {
                block.lines.lastOrNull()?.bottomInBlockPx ?: 0f
            }
            cursor += height + gapPx
            block.copy(contentTopPx = top)
        }
    }
}
