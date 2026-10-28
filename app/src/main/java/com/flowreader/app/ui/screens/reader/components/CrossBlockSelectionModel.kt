package com.flowreader.app.ui.screens.reader.components

/**
 * 跨块选择的**纯逻辑核心**（主计划第 9 节 / 3.3）。
 *
 * ## 它解决什么
 *
 * 旧实现把选中状态放进 `ReaderParagraph` 内部（`remember(paragraphStart)`），
 * 于是选择被**物理限制在单个段落内**：用户无论怎么拖动，都无法选到下一段的第一句话。
 * 这直接导致批注永远只能是单块、单区间，「跨块批注」在真实交互上不成立。
 *
 * 本类把选择状态提升为「锚点 + 焦点」两个**跨块坐标**（blockId + 块内显示偏移），
 * 再按阅读顺序投影出「每一块应显示哪一段被选中」。三个关键性质：
 *
 * 1. **顺序由注册顺序决定**：`ReaderContent` 按 `paragraphs.forEach` 顺序注册，
 *    因此中间块即使没有被显式点到，也会被正确包含（这正是跨块选择的语义）；
 * 2. **方向无关**：用户可以从下往上拖，锚点/焦点交换后结果对称；
 * 3. **不产生越界区间**：每块的区间都会被夹紧到该块实际显示长度内。
 *
 * 纯 Kotlin，无 Compose 依赖，可直接 JVM 单测。
 */
class CrossBlockSelectionModel {

    /** 选择坐标系里的一个点：某块的某个块内显示偏移。 */
    data class Point(val blockId: String, val displayOffset: Int)

    private val order = mutableListOf<String>()
    private val displayLengths = mutableMapOf<String, Int>()

    private var anchor: Point? = null
    private var focus: Point? = null

    val isEmpty: Boolean get() = anchor == null

    /** 当前锚点，供 UI 显示「选择起点」提示。 */
    val anchorPoint: Point? get() = anchor

    /** 当前焦点。 */
    val focusPoint: Point? get() = focus

    /**
     * 登记（或更新）一个块的显示长度。**必须按阅读顺序调用**。
     *
     * 重复登记同一 blockId 只更新长度、不改变它在顺序里的位置——
     * 否则重组（如高亮变化触发重绘）会把已选中的中间块顺序打乱。
     */
    fun registerBlock(blockId: String, displayLength: Int) {
        if (blockId !in displayLengths) {
            order.add(blockId)
        }
        displayLengths[blockId] = displayLength.coerceAtLeast(0)
    }

    /** 段落离开组合时移除。若它正是锚点/焦点所在块，选择整体清空（避免悬空坐标）。 */
    fun unregisterBlock(blockId: String) {
        order.remove(blockId)
        displayLengths.remove(blockId)
        if (anchor?.blockId == blockId || focus?.blockId == blockId) clear()
    }

    /** 开始一次选择（长按落点）。 */
    fun beginAt(point: Point) {
        if (point.blockId !in displayLengths) return
        anchor = clamp(point)
        focus = anchor
    }

    /** 扩展到新位置（拖动手柄）。未开始选择时忽略。 */
    fun extendTo(point: Point) {
        if (anchor == null) return
        if (point.blockId !in displayLengths) return
        focus = clamp(point)
    }

    /** Move the visible start/end handle, preserving the opposite bound in either direction. */
    fun moveBoundary(start: Boolean, point: Point) {
        val currentAnchor = anchor ?: return
        val currentFocus = focus ?: return
        if (point.blockId !in displayLengths) return
        val anchorIndex = order.indexOf(currentAnchor.blockId)
        val focusIndex = order.indexOf(currentFocus.blockId)
        val forward = anchorIndex < focusIndex ||
            (anchorIndex == focusIndex && currentAnchor.displayOffset <= currentFocus.displayOffset)
        if (start == forward) anchor = clamp(point) else focus = clamp(point)
    }

    /** 结束选择（保留结果，等待用户点「高亮/书签」）。 */
    fun clear() {
        anchor = null
        focus = null
    }

    /**
     * 某一块应显示的选中区间（块内显示偏移，`[start, endExclusive)`）。
     * 该块未被选中时返回 null。
     */
    fun rangeFor(blockId: String): IntRange? {
        val start = anchor ?: return null
        val end = focus ?: start
        val startIndex = order.indexOf(start.blockId).takeIf { it >= 0 } ?: return null
        val endIndex = order.indexOf(end.blockId).takeIf { it >= 0 } ?: return null
        val blockIndex = order.indexOf(blockId).takeIf { it >= 0 } ?: return null

        // 把选择看成扁平文档上的一个区间：先按阅读顺序定出首尾两块，方向无关。
        val firstIndex = minOf(startIndex, endIndex)
        val lastIndex = maxOf(startIndex, endIndex)
        if (blockIndex < firstIndex || blockIndex > lastIndex) return null

        val length = displayLengths[blockId] ?: return null

        // 区间「起点侧」与「终点侧」的偏移。反向拖动时锚点在后、焦点在前，
        // 因此必须按索引方向选取，不能写死 anchor/focus。
        val headOffset = if (startIndex <= endIndex) start.displayOffset else end.displayOffset
        val tailOffset = if (startIndex <= endIndex) end.displayOffset else start.displayOffset

        if (firstIndex == lastIndex) {
            val from = minOf(headOffset, tailOffset).coerceIn(0, length)
            val to = maxOf(headOffset, tailOffset).coerceIn(0, length)
            return if (to <= from) null else from until to
        }

        return when (blockIndex) {
            // 首块：从区间起点选到块尾。
            firstIndex -> {
                val from = headOffset.coerceIn(0, length)
                if (length <= from) null else from until length
            }
            // 末块：从块首选到区间终点。
            lastIndex -> {
                val to = tailOffset.coerceIn(0, length)
                if (to <= 0) null else 0 until to
            }
            // 中间块：整块被选中（用户没有逐块点选，但它们确实落在区间内）。
            else -> if (length <= 0) null else 0 until length
        }
    }

    /** 按阅读顺序给出所有被选中的块及其区间。装配层直接消费它。 */
    fun selectedBlocks(): List<Pair<String, IntRange>> = order.mapNotNull { blockId -> rangeFor(blockId)?.let { blockId to it } }

    private fun clamp(point: Point): Point {
        val length = displayLengths[point.blockId] ?: 0
        return point.copy(displayOffset = point.displayOffset.coerceIn(0, length))
    }
}
