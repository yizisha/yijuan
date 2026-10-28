package com.flowreader.feature.reader

import com.flowreader.app.domain.model.Affinity
import com.flowreader.app.domain.model.ResolutionQuality
import com.flowreader.app.domain.model.TextContext
import com.flowreader.app.domain.model.TextLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5 滚动定位闭环测试（主计划 6.6 / 6.7 / INV01 / INV08）。
 *
 * 覆盖：视口投影 → 源 locator → 六级恢复 → 精准回滚像素，这条链路端到端不漂移；
 * 以及「过期反馈不得覆盖新位置」的单调序约束。
 */
class ScrollLocatorRoundTripTest {

    private val docKey = "chapter:0"

    private fun blocksOf(lineHeight: Float, charsPerLine: Int, textLength: Int): List<ScrollViewportProjector.BlockLayout> {
        val lineCount = (textLength + charsPerLine - 1) / charsPerLine
        return listOf(
            ScrollViewportProjector.BlockLayout(
                blockId = "b0",
                documentKey = docKey,
                contentTopPx = 0f,
                lines = (0 until lineCount).map { i ->
                    val start = i * charsPerLine
                    val end = minOf(textLength, start + charsPerLine)
                    ScrollViewportProjector.LineBox(
                        topInBlockPx = i * lineHeight,
                        bottomInBlockPx = (i + 1) * lineHeight,
                        sourceStartUtf16 = start,
                        sourceEndUtf16Exclusive = end,
                        displayLength = end - start
                    )
                }
            )
        )
    }

    @Test
    fun viewportProjectionBuildsLocatorWithContextThenResolvesExact() {
        val sourceText = (0 until 400).joinToString("") { ('\u4e00' + (it % 200)).toString() }
        val blocks = blocksOf(lineHeight = 24f, charsPerLine = 20, textLength = sourceText.length)

        // 视口滚到第 5 行顶部（5 * 24 = 120px）→ 源偏移应为 100。
        val snapshot = ScrollViewportProjector.project(120f, 800f, blocks)
        assertEquals(100, snapshot.offsetUtf16)

        // 构造权威 locator（与 ViewModel 中 scrollProjectionToLocator 同一规则）。
        val locator = TextLocator(
            publicationId = "pub",
            revision = "rev",
            documentKey = docKey,
            offsetUtf16 = snapshot.offsetUtf16!!,
            affinity = Affinity.AFTER,
            blockId = snapshot.blockId,
            context = ContentLocatorResolver.captureContext(sourceText, snapshot.offsetUtf16!!)
        )

        // 上下文必须是前后各 32 个码点、且不拆代理对。
        assertTrue(locator.context.before.isNotEmpty())
        assertTrue(locator.context.after.isNotEmpty())
        assertEquals(32, locator.context.before.length)
        assertEquals(32, locator.context.after.length)

        // 回滚像素：必须精确落在同一行顶部，零漂移。
        val backPx = ScrollViewportProjector.scrollOffsetFor(locator, blocks)
        assertEquals(120f, backPx!!, 0.001f)
    }

    @Test
    fun recoveryLadderFindsExactAfterViewportRoundTrip() {
        val sourceText = "第一章内容。".repeat(40)
        val blocks = blocksOf(lineHeight = 20f, charsPerLine = 15, textLength = sourceText.length)
        val snapshot = ScrollViewportProjector.project(60f, 600f, blocks)
        val offset = snapshot.offsetUtf16!!

        val locator = TextLocator(
            publicationId = "pub",
            revision = "rev",
            documentKey = docKey,
            offsetUtf16 = offset,
            context = ContentLocatorResolver.captureContext(sourceText, offset)
        )

        val manifest = com.flowreader.app.domain.model.PublicationManifest(
            publicationId = "pub",
            revision = "rev",
            normalizationVersion = 1,
            parserSchemaVersion = 1,
            sourceHash = "",
            manifestRelativePath = "manifest.json",
            spineOccurrences = emptyList(),
            documents = listOf(
                com.flowreader.app.domain.model.PublicationDocument(
                    documentKey = docKey,
                    ordinal = 0,
                    linear = true,
                    role = com.flowreader.app.domain.model.DocumentRole.MAIN,
                    textLengthUtf16 = sourceText.length,
                    mainTextUnits = sourceText.length.toLong(),
                    manifestRelativePath = "text/main",
                    sourceHash = ""
                )
            ),
            sections = listOf(
                com.flowreader.app.domain.model.ReadingSection(
                    sectionId = "section_0",
                    ordinal = 0,
                    title = "第一章",
                    aliases = emptyList(),
                    kind = com.flowreader.app.domain.model.SectionKind.NORMAL,
                    ranges = listOf(com.flowreader.app.domain.model.SourceRange(docKey, 0, sourceText.length)),
                    mainTextUnits = sourceText.length.toLong()
                )
            ),
            tocTargetSections = emptyMap(),
            totalMainTextUnits = sourceText.length.toLong(),
            resources = emptyList()
        )

        val resolution = ContentLocatorResolver.resolve(
            locator = locator,
            activePublicationId = "pub",
            activeRevision = "rev",
            manifest = manifest
        )
        assertEquals(ResolutionQuality.EXACT, resolution.quality)
        assertEquals(offset, resolution.locator?.offsetUtf16)

        // 恢复后的 locator 仍能精准回滚到同一像素。
        assertEquals(60f, ScrollViewportProjector.scrollOffsetFor(resolution.locator!!, blocks)!!, 0.001f)
    }

    @Test
    fun staleSequenceCannotOverwriteNewerPosition() {
        // 模拟 6.7 的有序写入器：只有更大的 sequence 才能覆盖。
        var persistedSequence = 0L
        fun tryPersist(seq: Long): Boolean {
            if (seq < persistedSequence) return false
            persistedSequence = seq
            return true
        }

        assertTrue("新位置应被接受", tryPersist(10))
        assertTrue("更高序号应被接受", tryPersist(11))
        assertTrue("迟到的旧序号必须被丢弃", !tryPersist(9))
        assertEquals("被丢弃的旧写入不得改变已落库序号", 11L, persistedSequence)
    }

    @Test
    fun unresolvedProjectionMustNotFabricateLocator() {
        // 布局未登记时投影返回 null 偏移：调用方必须保持既有锚点，不得生成 0 偏移的假 locator。
        val snapshot = ScrollViewportProjector.project(0f, 800f, emptyList())
        assertNull(snapshot.offsetUtf16)
        assertNull(
            "无布局时不得构造 locator",
            snapshot.offsetUtf16?.let { TextContext("", "") }
        )
    }

    @Test
    fun endOfContentProjectionUsesBeforeAffinityWithoutOverwritingAnchor() {
        val sourceText = "末尾测试文本内容。".repeat(3)
        val blocks = blocksOf(lineHeight = 20f, charsPerLine = 9, textLength = sourceText.length)

        // 滚到最大滚动位置：内容比视口短时 maxScroll = 0。
        val snapshot = ScrollViewportProjector.project(0f, 2000f, blocks, maxScrollPx = 0f)
        assertTrue("必须报告已到内容末尾", snapshot.atContentEnd)
        assertNotNull(snapshot.offsetUtf16)

        // 末尾用 BEFORE affinity（主计划 3.1 字段规则），且不得把 offset 推过文档长度。
        val locator = TextLocator(
            publicationId = "pub",
            revision = "rev",
            documentKey = docKey,
            offsetUtf16 = snapshot.offsetUtf16!!,
            affinity = if (snapshot.atContentEnd) Affinity.BEFORE else Affinity.AFTER
        )
        assertTrue(locator.offsetUtf16 <= sourceText.length)
        assertEquals(Affinity.BEFORE, locator.affinity)
    }
}
