package com.flowreader.feature.reader

import com.flowreader.app.domain.model.Affinity
import com.flowreader.app.domain.model.HeadingBlock
import com.flowreader.app.domain.model.ParagraphBlock
import com.flowreader.app.domain.model.SourceRange
import com.flowreader.app.domain.model.StructuredDocument
import com.flowreader.app.domain.model.TextLocator

/**
 * Greedy text pagination for the `PAGED` page-turn mode.
 *
 * A chapter is split into blocks (title / paragraph / heading) and every block is measured
 * through an injected [measureHeight] callback so the algorithm stays pure Kotlin and JVM
 * testable; the composable side implements the callback with a real `TextMeasurer`.
 *
 * A block that does not fit the remaining page height is split on raw character boundaries —
 * the fragment keeps its absolute chapter offset and [sourceRanges], so highlights and bookmarks
 * computed against one fragment map back to the same raw text regardless of how the chapter was paged.
 */
class ChapterPaginator(
    private val measureHeight: (text: String, offset: Int, indent: Boolean, isTitle: Boolean) -> Int
) {

    /** A renderable chunk of a block. [paragraphStart] is the absolute chapter offset. */
    data class Fragment(
        val text: String,
        val paragraphStart: Int,
        val indent: Boolean,
        val isTitle: Boolean,
        val isHeading: Boolean,
        val sourceRanges: List<SourceRange> = listOf(
            SourceRange(
                documentKey = "text/main",
                startUtf16 = paragraphStart,
                endUtf16Exclusive = paragraphStart + text.length
            )
        ),
        val blockId: String? = null
    )

    /**
     * @param blocks raw chapter blocks with their absolute offsets ([title] is offset 0).
     * @param pageHeightPx usable content height of one page.
     * @param gapPx vertical gap between consecutive fragments inside a page.
     * @param measure 测量覆盖：默认用构造回调。**相邻章预排版**必须传自己的测量 ——
     *   各章的呈现标注（列表前缀等，schema v3）不同，用当前章的标注去量别的章，
     *   缓存出的页与真实页就不一致（0.2.11 图片漏传是同一类错）。
     */
    fun paginate(
        blocks: List<Block>,
        pageHeightPx: Int,
        gapPx: Int,
        measure: ((text: String, offset: Int, indent: Boolean, isTitle: Boolean) -> Int)? = null
    ): List<List<Fragment>> = paginateProgressively(blocks, pageHeightPx, gapPx, { _, _ -> }, measure)

    /** Publish sealed pages while measuring the rest; page coordinates and final output are identical. */
    fun paginateProgressively(
        blocks: List<Block>,
        pageHeightPx: Int,
        gapPx: Int,
        onPageReady: (Int, List<Fragment>) -> Unit,
        measure: ((text: String, offset: Int, indent: Boolean, isTitle: Boolean) -> Int)? = null
    ): List<List<Fragment>> {
        if (blocks.isEmpty() || pageHeightPx <= 0) return emptyList()
        val heightOf = measure ?: measureHeight

        val pages = mutableListOf<List<Fragment>>()
        var current = mutableListOf<Fragment>()
        var remainingHeight = pageHeightPx

        fun flushPage() {
            if (current.isNotEmpty()) {
                val sealed = current.toList()
                pages.add(sealed)
                onPageReady(pages.lastIndex, sealed)
                current = mutableListOf()
            }
            remainingHeight = pageHeightPx
        }

        blocks.forEach { block ->
            var text = block.text
            var offset = block.offset
            var firstFragment = true
            val docKey = block.sourceRanges.firstOrNull()?.documentKey ?: "text/main"

            while (text.isNotEmpty()) {
                val indent = firstFragment && block.paragraph && block.indent
                // Never measure a huge remaining paragraph on every page. Probe only as far
                // as this page can fit, keeping work proportional to visible text.
                val availableNow = remainingHeight - if (current.isEmpty()) 0 else gapPx
                val proposedSplit = if (block.paragraph && text.length > 2048) {
                    if (availableNow > 0) findSplit(text, offset, indent, block.isTitle, availableNow, heightOf) else 0
                } else {
                    null
                }
                val measured = if (proposedSplit != null && proposedSplit < text.length) {
                    pageHeightPx + 1
                } else {
                    heightOf(text, offset, indent, block.isTitle)
                }
                val needed = measured + if (current.isEmpty()) 0 else gapPx

                if (needed <= remainingHeight) {
                    val ranges = listOf(
                        SourceRange(
                            documentKey = docKey,
                            startUtf16 = offset,
                            endUtf16Exclusive = offset + text.length
                        )
                    )
                    current.add(
                        Fragment(
                            text = text,
                            paragraphStart = offset,
                            indent = indent,
                            isTitle = block.isTitle,
                            isHeading = block.isHeading,
                            sourceRanges = ranges,
                            blockId = block.blockId
                        )
                    )
                    remainingHeight -= needed
                    text = ""
                } else if (current.isEmpty()) {
                    // Block is taller than a whole page: split it and let the overflow flow.
                    val split = proposedSplit ?: findSplit(text, offset, indent, block.isTitle, remainingHeight, heightOf)
                    if (split <= 0) {
                        val ranges = listOf(
                            SourceRange(
                                documentKey = docKey,
                                startUtf16 = offset,
                                endUtf16Exclusive = offset + text.length
                            )
                        )
                        current.add(
                            Fragment(
                                text = text,
                                paragraphStart = offset,
                                indent = indent,
                                isTitle = block.isTitle,
                                isHeading = block.isHeading,
                                sourceRanges = ranges,
                                blockId = block.blockId
                            )
                        )
                        text = ""
                    } else {
                        val fragmentText = text.substring(0, split)
                        val ranges = listOf(
                            SourceRange(
                                documentKey = docKey,
                                startUtf16 = offset,
                                endUtf16Exclusive = offset + split
                            )
                        )
                        current.add(
                            Fragment(
                                text = fragmentText,
                                paragraphStart = offset,
                                indent = indent,
                                isTitle = block.isTitle,
                                isHeading = block.isHeading,
                                sourceRanges = ranges,
                                blockId = block.blockId
                            )
                        )
                        offset += split
                        text = text.substring(split)
                        firstFragment = false
                        flushPage()
                    }
                } else {
                    // The page still has room: fill it with as much of this block as fits instead
                    // of flushing.
                    val available = remainingHeight - gapPx
                    val split = proposedSplit ?: if (available > 0) findSplit(text, offset, indent, block.isTitle, available, heightOf) else 0
                    if (split > 0) {
                        val fragmentText = text.substring(0, split)
                        val ranges = listOf(
                            SourceRange(
                                documentKey = docKey,
                                startUtf16 = offset,
                                endUtf16Exclusive = offset + split
                            )
                        )
                        current.add(
                            Fragment(
                                text = fragmentText,
                                paragraphStart = offset,
                                indent = indent,
                                isTitle = block.isTitle,
                                isHeading = block.isHeading,
                                sourceRanges = ranges,
                                blockId = block.blockId
                            )
                        )
                        offset += split
                        text = text.substring(split)
                        firstFragment = false
                    }
                    flushPage()
                }
            }
            firstFragment = false
        }

        if (current.isNotEmpty()) flushPage()
        return pages
    }

    /**
     * Paginates a [StructuredDocument] by converting its structured content blocks
     * into measurer [Block]s while preserving precise [SourceRange]s and [blockId]s.
     */
    fun paginateStructuredDocument(document: StructuredDocument, pageHeightPx: Int, gapPx: Int): List<List<Fragment>> {
        val blocks = mutableListOf<Block>()
        for (cb in document.blocks) {
            val text = document.text.substring(
                cb.range.startUtf16.coerceIn(0, document.text.length),
                cb.range.endUtf16Exclusive.coerceIn(0, document.text.length)
            ).trimEnd('\n')
            if (text.isEmpty()) continue
            val range = listOf(cb.range.toSourceRange())
            when (cb) {
                is HeadingBlock -> {
                    blocks.add(
                        Block(
                            text = text,
                            offset = cb.range.startUtf16,
                            isTitle = cb.level == 1,
                            isHeading = true,
                            paragraph = false,
                            indent = false,
                            sourceRanges = range,
                            blockId = cb.blockId
                        )
                    )
                }
                is ParagraphBlock -> {
                    blocks.add(
                        Block(
                            text = text,
                            offset = cb.range.startUtf16,
                            isTitle = false,
                            isHeading = false,
                            paragraph = true,
                            indent = true,
                            sourceRanges = range,
                            blockId = cb.blockId
                        )
                    )
                }
                else -> {
                    blocks.add(
                        Block(
                            text = text,
                            offset = cb.range.startUtf16,
                            isTitle = false,
                            isHeading = false,
                            paragraph = true,
                            indent = false,
                            sourceRanges = range,
                            blockId = cb.blockId
                        )
                    )
                }
            }
        }
        return paginate(blocks, pageHeightPx, gapPx)
    }

    /**
     * Finds the page index containing the given [locator] (主计划 6.5 第 4 条).
     */
    fun findPageForLocator(pages: List<List<Fragment>>, locator: TextLocator): Int {
        if (pages.isEmpty()) return 0

        // 1. Exact blockId match if locator provides a blockId
        if (locator.blockId != null) {
            for (pageIndex in pages.indices) {
                val page = pages[pageIndex]
                if (page.any { it.blockId == locator.blockId }) {
                    return pageIndex
                }
            }
        }

        // 2. Search by documentKey and offsetUtf16 across pages
        val targetOffset = locator.offsetUtf16
        val affinity = locator.affinity

        for (pageIndex in pages.indices) {
            val page = pages[pageIndex]
            val matchingRanges = page.flatMap { it.sourceRanges }
                .filter { it.documentKey == locator.documentKey }
            if (matchingRanges.isEmpty()) continue

            val minStart = matchingRanges.minOf { it.startUtf16 }
            val maxEnd = matchingRanges.maxOf { it.endUtf16Exclusive }

            if (affinity == Affinity.BEFORE) {
                if (targetOffset in (minStart + 1)..maxEnd) {
                    return pageIndex
                }
            } else {
                if (targetOffset in minStart until maxEnd) {
                    return pageIndex
                }
            }
        }

        // Fallback: If targetOffset is after the last page, return last page; if before first page, return 0
        val allMatching = pages.flatten().flatMap { it.sourceRanges }
            .filter { it.documentKey == locator.documentKey }
        if (allMatching.isNotEmpty()) {
            val firstStart = allMatching.minOf { it.startUtf16 }
            val lastEnd = allMatching.maxOf { it.endUtf16Exclusive }
            if (targetOffset >= lastEnd) return pages.lastIndex
            if (targetOffset <= firstStart) return 0
        }

        return 0
    }

    /**
     * Largest raw split point whose measured height still fits [availableHeight]. Binary search
     * over the raw text length — measurement is monotonic in raw length, and display length
     * (after markdown stripping) is monotonic in raw length too.
     */
    private fun findSplit(
        text: String,
        offset: Int,
        indent: Boolean,
        isTitle: Boolean,
        availableHeight: Int,
        heightOf: (text: String, offset: Int, indent: Boolean, isTitle: Boolean) -> Int
    ): Int {
        var low = 1
        var high = minOf(128, text.length)
        var best = 0
        // Exponential probing brackets the first overflow without measuring most of a long
        // EPUB paragraph. The subsequent binary search still finds the exact largest fit.
        while (heightOf(text.substring(0, high), offset, indent, isTitle) <= availableHeight) {
            best = high
            if (high == text.length) return high
            low = high + 1
            high = minOf(text.length, high * 2)
        }
        high--
        while (low <= high) {
            val mid = (low + high) / 2
            if (heightOf(text.substring(0, mid), offset, indent, isTitle) <= availableHeight) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }
}

/** A raw chapter block: the optional title, a paragraph or a markdown heading. */
data class Block(
    val text: String,
    val offset: Int,
    val isTitle: Boolean = false,
    val isHeading: Boolean = false,
    val paragraph: Boolean = true,
    val indent: Boolean = false,
    val sourceRanges: List<SourceRange> = emptyList(),
    val blockId: String? = null
)
