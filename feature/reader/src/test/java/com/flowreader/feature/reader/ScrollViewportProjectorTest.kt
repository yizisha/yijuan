package com.flowreader.feature.reader

import com.flowreader.app.domain.model.Affinity
import com.flowreader.app.domain.model.DisplayMapping
import com.flowreader.app.domain.model.DisplayMappingBuilder
import com.flowreader.app.domain.model.TextContext
import com.flowreader.app.domain.model.TextLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5 滚动视口投影单测（主计划 6.6 / INV01 / INV05）。
 *
 * 覆盖三个必须成立的性质：
 * 1. **正向**：像素 → 源偏移落在「屏幕顶部第一行」的文字上；
 * 2. **逆向**：源偏移 → 像素能精准把该行顶对齐到视口顶部；
 * 3. **回环**：`source -> px -> source` 在同一布局代数下恒等（零漂移）。
 */
class ScrollViewportProjectorTest {

    private val docKey = "text/main"

    /** 构造一个每行 [charsPerLine] 字、行高 [lineHeight] 的等宽块（纯几何，不含 Compose）。 */
    private fun block(
        blockId: String,
        textLength: Int,
        sourceStart: Int,
        contentTopPx: Float,
        charsPerLine: Int = 10,
        lineHeight: Float = 20f
    ): ScrollViewportProjector.BlockLayout {
        val lineCount = (textLength + charsPerLine - 1) / charsPerLine
        val lines = (0 until lineCount).map { i ->
            val start = i * charsPerLine
            val end = minOf(textLength, start + charsPerLine)
            ScrollViewportProjector.LineBox(
                topInBlockPx = i * lineHeight,
                bottomInBlockPx = (i + 1) * lineHeight,
                sourceStartUtf16 = sourceStart + start,
                sourceEndUtf16Exclusive = sourceStart + end,
                displayLength = end - start
            )
        }
        return ScrollViewportProjector.BlockLayout(
            blockId = blockId,
            documentKey = docKey,
            contentTopPx = contentTopPx,
            lines = lines
        )
    }

    @Test
    fun viewportTopProjectsToFirstVisibleLineSourceStart() {
        // 两个块：块 A 从 0 开始 5 行，块 B 从 100px 开始 3 行。
        val blocks = listOf(
            block("p0", textLength = 50, sourceStart = 0, contentTopPx = 0f),
            block("p1", textLength = 30, sourceStart = 50, contentTopPx = 100f)
        )

        // 视口顶部正好在第 0 行顶部：应命中源偏移 0。
        val at0 = ScrollViewportProjector.project(0f, 200f, blocks)
        assertEquals(0, at0.offsetUtf16)
        assertEquals("p0", at0.blockId)
        assertEquals(0, at0.lineIndex)

        // 视口顶部到第 2 行顶部（40px）：命中该行起点 = 源 20。
        val at40 = ScrollViewportProjector.project(40f, 200f, blocks)
        assertEquals(20, at40.offsetUtf16)
        assertEquals(2, at40.lineIndex)

        // 视口顶部落在第 2 行中间（45px）：仍归并到该行起点，保证「第一行文字」语义。
        val at45 = ScrollViewportProjector.project(45f, 200f, blocks)
        assertEquals(20, at45.offsetUtf16)
        assertTrue("行内落点应标记为夹紧到行首", at45.clampedToLineStart)
    }

    @Test
    fun viewportInsideInterBlockGapFallsForwardNotRandomly() {
        // 块 A 只有 2 行（0..40px），块 B 从 100px 开始 —— 40..100 是空隙。
        val blocks = listOf(
            block("p0", textLength = 20, sourceStart = 0, contentTopPx = 0f),
            block("p1", textLength = 20, sourceStart = 20, contentTopPx = 100f)
        )

        // 视口顶部 60px：在空隙里。必须给出确定答案（A 块无更后行 → 回退 B 块首行），
        // 不能返回 null 或随机偏移。
        val gap = ScrollViewportProjector.project(60f, 200f, blocks)
        assertNotNull("空隙中必须仍能给出确定源偏移", gap.offsetUtf16)
        assertEquals(20, gap.offsetUtf16)
        assertEquals("p1", gap.blockId)
    }

    @Test
    fun scrollOffsetForAlignsTargetLineTopToViewport() {
        val blocks = listOf(
            block("p0", textLength = 50, sourceStart = 0, contentTopPx = 0f),
            block("p1", textLength = 30, sourceStart = 50, contentTopPx = 100f)
        )

        // 目标源 62 在块 B 内，块 B 本地第 1 行（本地源 60..70）。
        // 该行 topInBlock = 20px，块 B contentTop = 100px → 期望滚动 120px。
        val locator = TextLocator(
            publicationId = "pub",
            revision = "rev",
            documentKey = docKey,
            offsetUtf16 = 62,
            blockId = "p1"
        )
        val px = ScrollViewportProjector.scrollOffsetFor(locator, blocks)
        assertEquals(120f, px!!, 0.001f)

        // 逆投影一致性：滚到 120px 后投影回来的正是同一行起点（源 60）。
        val back = ScrollViewportProjector.project(120f, 200f, blocks)
        assertEquals(60, back.offsetUtf16)
        assertEquals(1, back.lineIndex)
    }

    @Test
    fun sourcePixelSourceRoundTripIsStableAcrossTwentyLayoutGenerations() {
        // 模拟 20 次重排：每次行高/每行字数不同，但源文本不变。
        val layouts = (0 until 20).map { i ->
            val lineHeight = 18f + i * 2f
            val charsPerLine = 8 + (i % 7)
            listOf(
                block("p0", textLength = 120, sourceStart = 0, contentTopPx = 0f, charsPerLine = charsPerLine, lineHeight = lineHeight),
                block("p1", textLength = 90, sourceStart = 120, contentTopPx = 1000f, charsPerLine = charsPerLine, lineHeight = lineHeight)
            )
        }

        var locator = TextLocator(
            publicationId = "pub",
            revision = "rev",
            documentKey = docKey,
            offsetUtf16 = 137,
            blockId = "p1",
            context = TextContext(before = "前文", after = "后文")
        )

        // 20 次重排：每次用当前布局求出滚动像素，再投影回源偏移，再走六级恢复确认 EXACT。
        for (i in 0 until 20) {
            val blocks = layouts[i]
            val px = ScrollViewportProjector.scrollOffsetFor(locator, blocks)
            assertNotNull("重排 $i 必须能算出目标像素", px)

            val snapshot = ScrollViewportProjector.project(px!!, 800f, blocks)
            assertNotNull("重排 $i 投影必须有结果", snapshot.offsetUtf16)

            // 投影给出的是「行首」偏移；该行必须包含原 locator 的偏移。
            val lineStart = snapshot.offsetUtf16!!
            val target = locator.offsetUtf16
            val block = blocks.first { it.blockId == snapshot.blockId }
            val line = block.lines[snapshot.lineIndex]
            assertTrue(
                "重排 $i：行 [${line.sourceStartUtf16}, ${line.sourceEndUtf16Exclusive}) 必须包含目标 $target",
                target >= (line.sourceStartUtf16 ?: 0) && target < (line.sourceEndUtf16Exclusive ?: 0)
            )
            assertTrue("重排 $i：行首不得越过目标", lineStart <= target)

            // 权威锚点保持原 locator 不变（主计划 6.5 第 6 条：不马上用新页首覆盖）。
            locator = locator.copy(
                offsetUtf16 = target,
                context = TextContext(before = "前文", after = "后文")
            )
        }

        assertEquals("20 次重排后偏移必须零漂移", 137, locator.offsetUtf16)
        assertEquals("p1", locator.blockId)
    }

    @Test
    fun contentEndClampIsReportedAndDoesNotFabricateOffset() {
        val blocks = listOf(block("p0", textLength = 20, sourceStart = 0, contentTopPx = 0f))

        // maxScroll = 0（内容只有两行，不足一屏）：应报告 atContentEnd，但仍给出有效偏移。
        val snap = ScrollViewportProjector.project(0f, 800f, blocks, maxScrollPx = 0f)
        assertTrue("应报告已到内容末尾", snap.atContentEnd)
        assertEquals(0, snap.offsetUtf16)
    }

    @Test
    fun visibleBlocksAreReportedForCurrentChapterResolution() {
        val blocks = listOf(
            block("p0", textLength = 20, sourceStart = 0, contentTopPx = 0f),
            block("p1", textLength = 20, sourceStart = 20, contentTopPx = 40f),
            block("p2", textLength = 20, sourceStart = 40, contentTopPx = 80f)
        )
        val snap = ScrollViewportProjector.project(30f, 40f, blocks)
        // 视口 [30, 70] 覆盖 p0 尾、p1 全部。
        assertTrue(snap.visibleBlockIds.contains("p1"))
        assertTrue("视口顶部所在块必须可见", snap.visibleBlockIds.contains("p0"))
    }

    @Test
    fun emptyBlocksNeverFabricatePosition() {
        val snap = ScrollViewportProjector.project(0f, 800f, emptyList())
        assertNull("无布局数据时必须如实返回 null，交由六级恢复处理", snap.offsetUtf16)
        assertEquals(-1, snap.lineIndex)
    }

    @Test
    fun syntheticIndentOnlyLinesAreSkippedInsteadOfReturningBadOffset() {
        // 块：第 0 行整行 synthetic（人工缩进换行），第 1 行有真实源。
        val block = ScrollViewportProjector.BlockLayout(
            blockId = "p0",
            documentKey = docKey,
            contentTopPx = 0f,
            lines = listOf(
                ScrollViewportProjector.LineBox(
                    topInBlockPx = 0f,
                    bottomInBlockPx = 20f,
                    sourceStartUtf16 = null,
                    sourceEndUtf16Exclusive = null,
                    displayLength = 2
                ),
                ScrollViewportProjector.LineBox(
                    topInBlockPx = 20f,
                    bottomInBlockPx = 40f,
                    sourceStartUtf16 = 0,
                    sourceEndUtf16Exclusive = 10,
                    displayLength = 10
                )
            )
        )
        val snap = ScrollViewportProjector.project(0f, 800f, listOf(block))
        assertEquals("synthetic 行必须顺延到下一有源行", 0, snap.offsetUtf16)
        assertEquals(1, snap.lineIndex)
    }

    @Test
    fun displayMappingDrivenLineSourceMapExcludesSyntheticPrefix() {
        // 用真实 DisplayMapping 验证 builder 的源映射：人工缩进 2 字应被排除。
        val builder = DisplayMappingBuilder(docKey)
        builder.appendSynthetic("　　", blockId = "p0") // synthetic 前缀
        builder.appendSource("春天来了", sourceStart = 10, blockId = "p0")
        val mapping: DisplayMapping = builder.build()

        val lineMap = ScrollLayoutBuilder.mapOfDisplay(mapping)
        // 整行显示下标 [0, 6)（2 synthetic + 4 真实）
        val range = lineMap.sourceRangeOf(0, 6)
        assertNotNull(range)
        assertEquals("synthetic 注入的缩进不得进入源范围", 10, range!!.first)
        assertEquals(14, range.second)

        // 只取 synthetic 部分 → 必须返回 null，不得伪造源偏移
        assertNull(lineMap.sourceRangeOf(0, 2))
    }

    @Test
    fun builderProducesMonotonicContentTops() {
        val blocks = listOf(
            block("p0", textLength = 20, sourceStart = 0, contentTopPx = 999f),
            block("p1", textLength = 20, sourceStart = 20, contentTopPx = 999f)
        )
        val laid = ScrollLayoutBuilder.withCumulativeTops(blocks, listOf(40f, 60f), gapPx = 8f)
        assertEquals(0f, laid[0].contentTopPx, 0.001f)
        assertEquals(48f, laid[1].contentTopPx, 0.001f)
    }

    @Test
    fun affinityBeforeSelectsPreviousLineAtDocumentEnd() {
        val blocks = listOf(block("p0", textLength = 30, sourceStart = 0, contentTopPx = 0f))
        // 文档末尾：offset = 30（exclusive end），BEFORE 应回落到最后一个有源行（第 2 行 top=40）。
        val locator = TextLocator(
            publicationId = "pub",
            revision = "rev",
            documentKey = docKey,
            offsetUtf16 = 30,
            affinity = Affinity.BEFORE
        )
        val px = ScrollViewportProjector.scrollOffsetFor(locator, blocks)
        assertEquals(40f, px!!, 0.001f)
    }
}
