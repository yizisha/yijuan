package com.flowreader.app.ui.screens.reader.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult
import com.flowreader.app.domain.model.DisplayMapping
import com.flowreader.app.domain.model.ResolutionQuality
import com.flowreader.app.domain.model.TextLocator
import com.flowreader.feature.reader.ScrollLayoutBuilder
import com.flowreader.feature.reader.ScrollViewportProjector

/**
 * 一次滚动视口投影的结果（S5 / 主计划 6.6）。
 *
 * 这是「UI 层向 ViewModel 汇报可见位置」的唯一载体：单位是**规范源文本 UTF-16 偏移**，
 * 不再出现像素。旧实现直接把 `ScrollState.value` 当位置上报（F01），字号一变就失去意义。
 */
data class ScrollViewportProjection(
    /** 视口顶部的源偏移；布局不足以判定时为 null。 */
    val offsetUtf16: Int?,
    val blockId: String?,
    val lineIndex: Int,
    /** 视口是否停在内容末尾（夹紧到 maxScroll）。 */
    val atContentEnd: Boolean,
    /** 可见块集合，供页眉/当前章判定（主计划 6.6 末条）。 */
    val visibleBlockIds: List<String>,
    /** 本次投影所属的章节序号，供调用方丢弃过期反馈（INV08）。 */
    val chapterIndex: Int,
    /** 质量标记：视口投影成功为 EXACT，未就绪为 UNRESOLVED。 */
    val quality: ResolutionQuality
) {
    val isUsable: Boolean get() = offsetUtf16 != null && quality != ResolutionQuality.UNRESOLVED

    companion object {
        /** 布局尚未就绪时的显式「未定位」，**不是** 0。 */
        val UNRESOLVED = ScrollViewportProjection(
            offsetUtf16 = null,
            blockId = null,
            lineIndex = -1,
            atContentEnd = false,
            visibleBlockIds = emptyList(),
            chapterIndex = -1,
            quality = ResolutionQuality.UNRESOLVED
        )

        fun of(
            snapshot: ScrollViewportProjector.ViewportSnapshot,
            chapterIndex: Int
        ): ScrollViewportProjection = ScrollViewportProjection(
            offsetUtf16 = snapshot.offsetUtf16,
            blockId = snapshot.blockId,
            lineIndex = snapshot.lineIndex,
            atContentEnd = snapshot.atContentEnd,
            visibleBlockIds = snapshot.visibleBlockIds,
            chapterIndex = chapterIndex,
            quality = if (snapshot.offsetUtf16 != null) {
                ResolutionQuality.EXACT
            } else {
                ResolutionQuality.UNRESOLVED
            }
        )
    }
}

/**
 * 滚动正文的**布局注册表**：按垂直顺序收集每个已测量文本块的几何。
 *
 * 存在理由：`ScrollViewportProjector` 需要「块顶像素 + 逐行行盒 + 行源范围」才能把像素
 * 投影成源坐标。Compose 里这些信息分散在各段落的 `onTextLayout` / `onGloballyPositioned`
 * 回调中，本类负责把它们按稳定顺序汇总，并在块增删时保持一致（不残留已移除的块）。
 */
class ScrollViewportRegistry {

    // Snapshot observation lets a source jump wait for real paragraph geometry, without a timer.
    private val entries = androidx.compose.runtime.mutableStateMapOf<String, Entry>()

    private data class Entry(
        val contentTopPx: Float,
        val contentLeftPx: Float,
        val layout: TextLayoutResult,
        val mapping: DisplayMapping?,
        val documentKey: String,
        /** 该块的布局坐标，用于跨块选择时把窗口坐标换算到块内。 */
        val coordinates: LayoutCoordinates
    )

    /**
     * 跨块命中的结果：某个块内的某个**显示偏移**。
     *
     * 这是跨块选择的输入单位：`blockId` 决定它在阅读顺序里的位置，
     * `displayOffset` 决定选择在该块内落在哪一字符。
     */
    data class BlockHit(val blockId: String, val displayOffset: Int, val documentKey: String)

    /** 段落测量完成或位置变化时登记。以 [blockId] 去重，重测覆盖旧值。 */
    fun register(
        blockId: String,
        contentTopPx: Float,
        contentLeftPx: Float,
        layout: TextLayoutResult,
        mapping: DisplayMapping?,
        documentKey: String,
        coordinates: LayoutCoordinates
    ) {
        entries[blockId] = Entry(contentTopPx, contentLeftPx, layout, mapping, documentKey, coordinates)
    }

    /**
     * 把**窗口坐标**的指针位置换算成「哪个块 + 块内哪个显示偏移」。
     *
     * 用途：跨块选择时用户的手指可能拖到别的段落上，而每段的 `pointerInput` 只收到自己
     * 区域内的事件。因此在容器层统一用窗口坐标做命中测试，再交给
     * [CrossBlockSelectionModel] 扩展选择。
     *
     * 命中判定用块的实际布局边界（含间距），因此拖到段间空隙时仍归属最邻近的块——
     * 不会出现「拖到空隙就把选择丢在空中」的断档。
     */
    fun hitTest(windowPosition: Offset): BlockHit? {
        if (entries.isEmpty()) return null
        var best: Entry? = null
        var bestId: String? = null
        var bestDistance = Float.MAX_VALUE

        for ((blockId, entry) in entries) {
            if (!entry.coordinates.isAttached) continue
            val topLeft = entry.coordinates.localToWindow(Offset.Zero)
            val size = entry.coordinates.size
            val local = Offset(
                x = windowPosition.x - topLeft.x,
                y = windowPosition.y - topLeft.y
            )
            // 先判直接命中；未命中则记录到该块的垂直距离，取最近者。
            val insideY = local.y >= 0f && local.y <= size.height
            if (insideY && local.x >= -HIT_SLOP_PX && local.x <= size.width + HIT_SLOP_PX) {
                val offset = entry.layout.getOffsetForPosition(local)
                    .coerceIn(0, entry.layout.layoutInput.text.length)
                return BlockHit(blockId, offset, entry.documentKey)
            }
            val distance = when {
                local.y < 0f -> -local.y
                local.y > size.height -> local.y - size.height
                else -> 0f
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = entry
                bestId = blockId
            }
        }

        val fallback = best ?: return null
        val fallbackId = bestId ?: return null
        // 垂直方向落在块外：夹紧到该块最近的一端，保证拖动不会产生无效偏移。
        val topLeft = fallback.coordinates.localToWindow(Offset.Zero)
        val localY = (windowPosition.y - topLeft.y)
            .coerceIn(0f, fallback.coordinates.size.height.toFloat())
        val localX = (windowPosition.x - topLeft.x)
            .coerceIn(0f, fallback.coordinates.size.width.toFloat())
        val offset = fallback.layout.getOffsetForPosition(Offset(localX, localY))
            .coerceIn(0, fallback.layout.layoutInput.text.length)
        return BlockHit(fallbackId, offset, fallback.documentKey)
    }

    /** 某个块的显示映射（跨块装配需要按块取 DisplayMapping）。 */
    fun mappingFor(blockId: String): DisplayMapping? = entries[blockId]?.mapping

    /** 某个块的显示文本长度（选择模型登记用）。 */
    fun displayLengthFor(blockId: String): Int = entries[blockId]?.mapping?.displayText?.length ?: 0


    private companion object {
        /** 水平方向的命中容差：文字块左右留白处也应算命中，否则行尾拖动易断。 */
        const val HIT_SLOP_PX = 24f
    }

    /** 段落离开组合时移除，避免旧几何被当成当前布局。 */
    fun unregister(blockId: String) {
        entries.remove(blockId)
    }

    /** 清空（切换章节时使用）。 */
    fun clear() = entries.clear()

    /**
     * 按块顶像素升序产出 [ScrollViewportProjector.BlockLayout]。
     *
     * 排序而不是依赖登记顺序：Compose 的测量顺序不保证与阅读顺序一致，
     * 而投影算法要求块严格单调排列（否则二分与「第一可见行」都会错）。
     */
    fun snapshot(scrollTopPx: Float = 0f, viewportTopPx: Float = 0f): List<ScrollViewportProjector.BlockLayout> =
        entries.values
            .sortedBy { it.contentTopPx }
            .map { entry -> entry.toBlockLayout(scrollTopPx - viewportTopPx) }

    private fun Entry.toBlockLayout(contentOriginShiftPx: Float): ScrollViewportProjector.BlockLayout {
        val documentKey = this.documentKey
        val sourceMap = if (mapping != null) {
            ScrollLayoutBuilder.mapOfDisplay(mapping)
        } else {
            ScrollLayoutBuilder.LineSourceMap { _, _ -> null }
        }
        return ScrollLayoutBuilder.build(
            blockId = null,
            documentKey = documentKey,
            contentTopPx = contentTopPx + contentOriginShiftPx,
            contentLeftPx = contentLeftPx,
            lineCount = layout.lineCount,
            lineTop = { line -> layout.getLineTop(line) },
            lineBottom = { line -> layout.getLineBottom(line) },
            lineStart = { line -> layout.getLineStart(line) },
            lineEnd = { line -> layout.getLineEnd(line, visibleEnd = true) },
            sourceMap = sourceMap
        )
    }
}

/**
 * 由滚动投影结果构造权威 [TextLocator]。
 *
 * 上下文按 [ContentLocatorResolver] 同一规则截取（前后各最多 32 个码点、不拆代理对），
 * 这样重开书或换 revision 后六级恢复才有一致依据。
 */
fun scrollProjectionToLocator(
    projection: ScrollViewportProjection,
    publicationId: String,
    revision: String,
    sectionId: String?,
    documentKey: String,
    sourceText: String
): TextLocator? {
    val offset = projection.offsetUtf16 ?: return null
    val clamped = offset.coerceIn(0, sourceText.length)
    return TextLocator(
        version = TextLocator.CURRENT_VERSION,
        publicationId = publicationId,
        revision = revision,
        normalizationVersion = 1,
        sectionId = sectionId,
        documentKey = documentKey,
        offsetUtf16 = clamped,
        affinity = if (projection.atContentEnd) {
            com.flowreader.app.domain.model.Affinity.BEFORE
        } else {
            com.flowreader.app.domain.model.Affinity.AFTER
        },
        blockId = projection.blockId,
        context = com.flowreader.feature.reader.ContentLocatorResolver.captureContext(sourceText, clamped)
    )
}
