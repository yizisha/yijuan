package com.flowreader.feature.reader

import com.flowreader.app.domain.model.SourceRange
import com.flowreader.app.domain.model.TextLocator

/**
 * 滚动视口 ↔ 内容源坐标投影（主计划 6.6 / INV01 / INV05）。
 *
 * 为什么必须有这一层：旧实现的滚动模式把 `ScrollState.value`（**像素**）直接当作阅读位置
 * 上报（F01：`ReaderPositionUnit` 的字符分支仍以像素近似）。像素随字号、行距、机型、
 * 字体、横竖屏而变，于是改一次字号就再也回不到同一句话。
 *
 * 本类只做一件事：把「视口顶部像素」翻译成「规范源文本 UTF-16 边界」，并保证反向翻译
 * （源坐标 → 滚动像素）是同一个几何模型，从而：
 * - 滚动停止时能生成精确 [TextLocator]（不漂移）；
 * - 进入滚动模式时能精准滚动到锚点所在**行**（而不是近似比例）；
 * - `source -> px -> source` 回环在同一布局代数下恒等。
 *
 * 纯 Kotlin，无 Android 依赖，可直接在 JVM 上单测。
 */
object ScrollViewportProjector {

    /**
     * 一个已测量文本块在**滚动内容坐标系**中的几何位置。
     *
     * [contentTopPx] 是该块在滚动内容中的绝对顶部像素（已含标题、段落间距等所有前序偏移）；
     * [lines] 是该块真实 `TextLayoutResult` 逐行给出的行盒。
     */
    data class BlockLayout(
        val blockId: String?,
        val documentKey: String,
        val contentTopPx: Float,
        /** 文本区左上角相对滚动内容原点的水平偏移（居中排版时非 0）。 */
        val contentLeftPx: Float = 0f,
        val lines: List<LineBox>
    ) {
        /** 该块覆盖的源范围（跨行合并后）；空行块返回 null。 */
        val sourceRange: SourceRange?
            get() {
                val valid = lines.filter { it.hasSource }
                    .mapNotNull { line ->
                        val start = line.sourceStartUtf16
                        val end = line.sourceEndUtf16Exclusive
                        if (start != null && end != null) start to end else null
                    }
                if (valid.isEmpty()) return null
                return SourceRange(
                    documentKey = documentKey,
                    startUtf16 = valid.minOf { it.first },
                    endUtf16Exclusive = valid.maxOf { it.second }
                )
            }
    }

    /**
     * 一行文本的几何与源映射。四元组直接对应主计划 6.6 要求的
     * `lineTop / lineBottom / lineStart / lineEnd`。
     */
    data class LineBox(
        /** 行顶相对**该块**顶部的偏移。 */
        val topInBlockPx: Float,
        val bottomInBlockPx: Float,
        /**
         * 该行源起始的 UTF-16 偏移；整行为 synthetic（如人工缩进换行）时为 null。
         */
        val sourceStartUtf16: Int?,
        /** 该行源结束（exclusive）的 UTF-16 偏移。 */
        val sourceEndUtf16Exclusive: Int?,
        /** 该行显示文本长度，用于判定空行。 */
        val displayLength: Int = 0
    ) {
        val hasSource: Boolean
            get() = sourceStartUtf16 != null && sourceEndUtf16Exclusive != null &&
                sourceEndUtf16Exclusive > sourceStartUtf16
    }

    /**
     * 一次视口投影结果：视口顶部对应的源边界及其命中细节。
     */
    data class ViewportSnapshot(
        /** 视口顶部落在哪个块（null 表示视口整体在已知内容之外）。 */
        val blockId: String?,
        val documentKey: String?,
        /** 视口顶部对应的源 UTF-16 偏移；无法确定时为 null。 */
        val offsetUtf16: Int?,
        /** 该偏移位于哪一行（块内行下标）。 */
        val lineIndex: Int,
        /**
         * 视口顶部是否落在该行**起点之前**（即上一行尾部仍可见）。
         * 用于 `Affinity` 判定：正向阅读时用户实际正在读这一行。
         */
        val clampedToLineStart: Boolean,
        /** 视口是否停在内容末尾（允许夹紧到 maxScroll，不覆盖原锚点）。 */
        val atContentEnd: Boolean,
        /** 可见块集合，供页眉/统计确定当前章（主计划 6.6 末条）。 */
        val visibleBlockIds: List<String> = emptyList()
    )

    /**
     * 投影：给定滚动视口的**内容像素偏移** [scrollTopPx] 与视口高度，求视口顶部对应的源偏移。
     *
     * 算法（主计划 6.6 第 2 条）：
     * 1. 二分找到 contentTop 之后、且离视口顶部最近的那个块；
     * 2. 在该块内二分找到包含视口顶部的行；
     * 3. 若视口顶部在该行顶部之上（行部分可见），归并到该行起点——这正是「屏幕顶部的第一行
     *    文字」的语义，也是重排不漂移的关键；
     * 4. 该行无源坐标（例如人工缩进独占行）时顺延到下一有源行，绝不返回 null 假装定位成功。
     *
     * 注意：这里**不做**任何按比例的近似；找不到可靠源边界就返回 null，由调用方走六级恢复。
     */
    fun project(
        scrollTopPx: Float,
        viewportHeightPx: Float,
        blocks: List<BlockLayout>,
        maxScrollPx: Float = Float.POSITIVE_INFINITY
    ): ViewportSnapshot {
        if (blocks.isEmpty()) {
            return ViewportSnapshot(
                blockId = null,
                documentKey = null,
                offsetUtf16 = null,
                lineIndex = -1,
                clampedToLineStart = false,
                atContentEnd = false
            )
        }

        val safeScroll = scrollTopPx.coerceAtLeast(0f)
        val atContentEnd = maxScrollPx.isFinite() && safeScroll >= maxScrollPx - END_EPSILON_PX
        val viewportBottom = safeScroll + viewportHeightPx.coerceAtLeast(0f)

        val visible = blocks.mapNotNull { block ->
            val blockBottom = block.contentTopPx + (block.lines.lastOrNull()?.bottomInBlockPx ?: 0f)
            if (blockBottom < safeScroll || block.contentTopPx > viewportBottom) null else block
        }.mapNotNull { it.blockId }

        // 1. 找到视口顶部所在的块：contentTop <= scrollTop 的最后一个块。
        //    二分保证 O(log n)，目录式大文档下不随块数线性退化。
        var lo = 0
        var hi = blocks.size - 1
        var candidate = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (blocks[mid].contentTopPx <= safeScroll) {
                candidate = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        // 2. 从候选块向后（向文档开头）找「屏幕顶部的第一行」。
        //    命中条件是先行几何条件 `bottom > 视口顶部`，所以块间距、行高变化都不会让它错位。
        for (index in candidate downTo 0) {
            val block = blocks[index]
            val hit = locateLineInBlock(block, safeScroll) ?: continue
            return snapshotOf(block, hit, atContentEnd, visible)
        }

        // 3. 向后没有可用的有源行（例如视口顶部落在块间距里，而前一块的正文已全部滚过）：
        //    向前找**最近**的有源行。这正是「往下滚一点、下一段还没到顶部」的语义，
        //    必须归并到下一段的起始行，而不是把上一段的末尾当成视口顶部。
        for (index in candidate until blocks.size) {
            val block = blocks[index]
            val hit = locateLineInBlock(block, safeScroll) ?: continue
            return snapshotOf(block, hit, atContentEnd, visible)
        }

        // 4. 视口顶部在所有已知内容之前（例如顶部留白）：归并到第一个有源行。
        for (block in blocks) {
            val hit = locateLineInBlock(block, block.contentTopPx, forceFirst = true) ?: continue
            return snapshotOf(block, hit, atContentEnd, visible)
        }

        return ViewportSnapshot(
            blockId = null,
            documentKey = null,
            offsetUtf16 = null,
            lineIndex = -1,
            clampedToLineStart = false,
            atContentEnd = atContentEnd,
            visibleBlockIds = visible
        )
    }

    /**
     * 逆投影：给定源偏移，求把该偏移所在**行**顶对齐到视口顶部所需的滚动像素。
     *
     * 主计划 6.6 第 3 条：允许最多 2 次「测量后校正」，但校正依据必须来自几何数据；
     * 本函数就是那次校正的解析解，不使用固定 sleep。
     *
     * @return 目标滚动像素；[offsetUtf16] 无法在 [blocks] 中定位时返回 null（调用方走六级恢复）。
     */
    fun scrollOffsetFor(locator: TextLocator, blocks: List<BlockLayout>): Float? {
        val target = locator.offsetUtf16

        // 优先按 blockId 精确定位（主计划 3.1：blockId 辅助重排恢复）。
        if (locator.blockId != null) {
            val byBlock = blocks.filter { it.blockId == locator.blockId && it.documentKey == locator.documentKey }
            byBlock.forEach { block ->
                hitLineForOffset(block, target, locator.affinity)?.let { return block.contentTopPx + it }
            }
        }

        // 退化：按文档 + 源偏移在有序块中查找包含该偏移的行。
        for (block in blocks) {
            if (block.documentKey != locator.documentKey) continue
            val range = block.sourceRange ?: continue
            if (target < range.startUtf16 || target > range.endUtf16Exclusive) continue
            hitLineForOffset(block, target, locator.affinity)?.let { return block.contentTopPx + it }
        }

        // 越过末尾：夹紧到最后一个有源行（主计划 6.6 第 5 条）。
        val last = blocks.lastOrNull { it.sourceRange != null && it.documentKey == locator.documentKey }
            ?: blocks.lastOrNull { it.sourceRange != null }
            ?: return null
        if (target >= (last.sourceRange?.endUtf16Exclusive ?: return null)) {
            val lastLine = last.lines.indexOfLast { it.hasSource }
            if (lastLine >= 0) return last.contentTopPx + last.lines[lastLine].topInBlockPx
        }
        return null
    }

    private fun snapshotOf(block: BlockLayout, hit: LineHit, atContentEnd: Boolean, visible: List<String>): ViewportSnapshot =
        ViewportSnapshot(
            blockId = block.blockId,
            documentKey = block.documentKey,
            offsetUtf16 = hit.offsetUtf16,
            lineIndex = hit.lineIndex,
            clampedToLineStart = hit.clampedToLineStart,
            atContentEnd = atContentEnd,
            visibleBlockIds = visible
        )

    /**
     * 块内定位：返回「屏幕顶部第一行」——即第一个 `bottom > 视口顶部` 的**有源**行。
     *
     * 这里刻意不用「二分到 top <= 视口顶部」作为最终答案：视口顶部常常落在行中部、
     * 块间距或 synthetic 行里，直接返回该下标会给出一个偏早的行。二分只用来确定**搜索起点**，
     * 最终答案始终由几何条件（bottom > localTop）读出，因此结果与块间距、行高无关。
     */
    private fun locateLineInBlock(block: BlockLayout, absoluteTopPx: Float, forceFirst: Boolean = false): LineHit? {
        if (block.lines.isEmpty()) return null
        val localTop = absoluteTopPx - block.contentTopPx

        val startIndex = if (forceFirst) {
            0
        } else {
            var lo = 0
            var hi = block.lines.size - 1
            var found = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (block.lines[mid].topInBlockPx <= localTop) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            found
        }

        for (index in startIndex until block.lines.size) {
            val line = block.lines[index]
            val start = line.sourceStartUtf16
            if (start == null || !line.hasSource) continue
            if (line.bottomInBlockPx > localTop || forceFirst) {
                return LineHit(
                    offsetUtf16 = start,
                    lineIndex = index,
                    clampedToLineStart = line.topInBlockPx < localTop
                )
            }
        }

        // 本块之后已无有源行：如实返回 null，由调用方向前一个块继续搜索，
        // 而不是把本块最后一行冒充成视口顶部。
        return null
    }

    /** 行内定位：返回该行相对块顶的像素偏移。 */
    private fun hitLineForOffset(block: BlockLayout, offsetUtf16: Int, affinity: com.flowreader.app.domain.model.Affinity): Float? {
        val lines = block.lines
        if (lines.isEmpty()) return null

        // AFTER（默认）：归属后一块/行的首字符 → 用 sourceEnd > offset 的第一行。
        // BEFORE（文档末尾）：归属前一行 → 用 sourceStart <= offset 的最后一个有源行。
        var chosen = -1
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.hasSource) continue
            val start = line.sourceStartUtf16 ?: continue
            val end = line.sourceEndUtf16Exclusive ?: continue
            if (affinity == com.flowreader.app.domain.model.Affinity.AFTER) {
                if (offsetUtf16 < end) {
                    chosen = i
                    break
                }
            } else {
                if (start <= offsetUtf16) {
                    chosen = i
                } else {
                    break
                }
            }
        }
        if (chosen < 0) {
            chosen = lines.indexOfLast { it.hasSource }
        }
        if (chosen < 0) return null
        return lines[chosen].topInBlockPx
    }

    private data class LineHit(
        val offsetUtf16: Int,
        val lineIndex: Int,
        val clampedToLineStart: Boolean
    )

    /** 判定「已到内容末尾」的像素容差。滚动夹紧通常带来亚像素误差。 */
    private const val END_EPSILON_PX = 0.5f
}
