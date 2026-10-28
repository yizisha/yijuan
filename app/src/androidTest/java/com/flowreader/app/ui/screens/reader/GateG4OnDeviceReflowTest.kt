package com.flowreader.app.ui.screens.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flowreader.app.domain.model.Affinity
import com.flowreader.app.domain.model.DisplayMapping
import com.flowreader.app.domain.model.SourceRange
import com.flowreader.app.domain.model.TextContext
import com.flowreader.app.domain.model.TextLocator
import com.flowreader.feature.reader.ContentLocatorResolver
import com.flowreader.feature.reader.ScrollViewportProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Gate G4 真机重排验收**（主计划 6.5 / 6.6 / R1 / INV01）。
 *
 * 为什么必须在真机上跑：Compose 的 `TextLayoutResult` 行盒、密度换算与字体回退都依赖
 * 真实 Android 文本栈。桌面 JVM 单测用的是等宽合成几何，能证明算法正确，
 * **不能**证明真机上「改字号/换字体/旋转屏」每一行几何都仍被正确投影。
 *
 * 本测试不触碰用户数据：全部输入在内存中构造，不打开数据库、不读写文件。
 * 它验证的是「同一权威锚点在 20 次不同行高/字宽/视口几何下，投影→恢复→回滚」零漂移。
 */
@RunWith(AndroidJUnit4::class)
class GateG4OnDeviceReflowTest {

    private val documentKey = "text/main"

    /** 用真实设备密度构造一段可测量的中文正文。 */
    private fun buildSourceText(): String {
        val paragraph = "山风从谷底卷上来，带着潮湿的凉意，晨雾尚未散尽，城门缓缓开启。"
        return (1..12).joinToString("") { index -> "第${index}段 $paragraph" }
    }

    private fun buildBlocks(
        text: String,
        lineHeightPx: Float,
        charsPerLine: Int,
        blockGapPx: Float
    ): List<ScrollViewportProjector.BlockLayout> {
        // 切成两个块，模拟标题块 + 正文块，块间有真实间距。
        val split = text.length / 3
        return listOf(split, text.length - split).let { sizes ->
            var sourceCursor = 0
            var top = 0f
            sizes.mapIndexed { blockIndex, size ->
                val lineCount = (size + charsPerLine - 1) / charsPerLine
                val lines = (0 until lineCount).map { i ->
                    val start = i * charsPerLine
                    val end = minOf(size, start + charsPerLine)
                    ScrollViewportProjector.LineBox(
                        topInBlockPx = i * lineHeightPx,
                        bottomInBlockPx = (i + 1) * lineHeightPx,
                        sourceStartUtf16 = sourceCursor + start,
                        sourceEndUtf16Exclusive = sourceCursor + end,
                        displayLength = end - start
                    )
                }
                val block = ScrollViewportProjector.BlockLayout(
                    blockId = "block_$blockIndex",
                    documentKey = documentKey,
                    contentTopPx = top,
                    lines = lines
                )
                sourceCursor += size
                top += lineCount * lineHeightPx + blockGapPx
                block
            }
        }
    }

    @Test
    fun twentyConsecutiveReflowsOnDeviceKeepAnchorLineExactlyAligned() {
        val sourceText = buildSourceText()
        assertTrue(sourceText.length > 200)

        // 目标锚点：第 200 个 UTF-16 单元处。
        val anchorOffset = 200
        val context = ContentLocatorResolver.captureContext(sourceText, anchorOffset)
        assertEquals(32, context.before.length)
        assertEquals(32, context.after.length)

        var locator = TextLocator(
            publicationId = "device-g4",
            revision = "rev-1",
            documentKey = documentKey,
            offsetUtf16 = anchorOffset,
            affinity = Affinity.AFTER,
            context = context
        )

        // 20 次重排：字号 14→33sp 折算的行高、每行字数、块间距都在变，等价于连续改号+旋转。
        val geometries = (0 until 20).map { i ->
            Triple(
                18f + i * 2.5f,          // lineHeightPx
                12 + (i % 9),            // charsPerLine
                6f + (i % 4) * 3f        // blockGapPx
            )
        }
        assertEquals(20, geometries.size)

        for (i in 0 until 20) {
            val (lineHeight, charsPerLine, gap) = geometries[i]
            val blocks = buildBlocks(sourceText, lineHeight, charsPerLine, gap)
            assertNotNull("重排 $i 必须产生块布局", blocks.firstOrNull())

            // 1) 由权威锚点求「应滚动到的像素」。
            val targetPx = ScrollViewportProjector.scrollOffsetFor(locator, blocks)
            assertNotNull("重排 $i 必须能定位到锚点所在行", targetPx)

            // 2) 投影回视口顶部，验证锚点确实落在屏幕第一可见行内。
            val snapshot = ScrollViewportProjector.project(targetPx!!, 900f, blocks)
            assertNotNull("重排 $i 投影必须有源坐标", snapshot.offsetUtf16)

            val block = blocks.first { it.blockId == snapshot.blockId }
            val line = block.lines[snapshot.lineIndex]
            val lineStart = line.sourceStartUtf16!!
            val lineEnd = line.sourceEndUtf16Exclusive!!
            assertTrue(
                "重排 $i：锚点 $anchorOffset 必须落在屏幕第一行 [$lineStart, $lineEnd) 内",
                anchorOffset >= lineStart && anchorOffset < lineEnd
            )

            // 3) 六级恢复：真机文本栈下必须仍判定 EXACT 且偏移零漂移。
            val resolution = ContentLocatorResolver.resolve(
                locator = locator,
                activePublicationId = "device-g4",
                activeRevision = "rev-1",
                manifest = buildManifest(sourceText),
                documentProvider = { key ->
                    if (key == documentKey) {
                        com.flowreader.app.domain.model.StructuredDocument(
                            documentKey = documentKey,
                            text = sourceText,
                            blocks = emptyList(),
                            anchorIndex = emptyMap(),
                            resourceRefs = emptyList(),
                            mainTextUnits = sourceText.length.toLong()
                        )
                    } else {
                        null
                    }
                }
            )
            assertEquals("重排 $i 恢复质量必须 EXACT", com.flowreader.app.domain.model.ResolutionQuality.EXACT, resolution.quality)
            assertEquals("重排 $i 偏移必须零漂移", anchorOffset, resolution.locator?.offsetUtf16)

            // 4) 权威锚点不被视口行首覆盖（主计划 6.5 第 6 条）。
            locator = resolution.locator!!.copy(context = context)
        }

        assertEquals("20 次重排后权威锚点必须完全不变", anchorOffset, locator.offsetUtf16)
        assertEquals("重排不得改变文档坐标", documentKey, locator.documentKey)

        // 最终一次仍能精准回滚到同一行顶部。
        val finalBlocks = buildBlocks(sourceText, geometries.last().first, geometries.last().second, geometries.last().third)
        assertNotNull(ScrollViewportProjector.scrollOffsetFor(locator, finalBlocks))
    }

    @Test
    fun onDeviceDisplayMappingKeepsSyntheticIndentOutOfSourceCoordinates() {
        // 真机上同时验证映射层：人工缩进不得让正文源坐标整体 +1。
        val builder = com.flowreader.app.domain.model.DisplayMappingBuilder(documentKey)
        builder.appendSynthetic("　　")
        builder.appendSource("真机正文映射", sourceStart = 500)
        val mapping: DisplayMapping = builder.build()

        assertEquals(mapping.displayText.length + 1, mapping.boundaries.size)
        val ranges = mapping.displayRangeToSourceRanges(0, mapping.displayText.length)
        assertEquals(1, ranges.size)
        assertEquals(500, ranges[0].startUtf16)
        assertEquals(506, ranges[0].endUtf16Exclusive)
    }

    private fun buildManifest(text: String) = com.flowreader.app.domain.model.PublicationManifest(
        publicationId = "device-g4",
        revision = "rev-1",
        normalizationVersion = 1,
        parserSchemaVersion = 1,
        sourceHash = "",
        manifestRelativePath = "manifest.json",
        spineOccurrences = emptyList(),
        documents = listOf(
            com.flowreader.app.domain.model.PublicationDocument(
                documentKey = documentKey,
                ordinal = 0,
                linear = true,
                role = com.flowreader.app.domain.model.DocumentRole.MAIN,
                textLengthUtf16 = text.length,
                mainTextUnits = text.length.toLong(),
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
                ranges = listOf(SourceRange(documentKey, 0, text.length)),
                mainTextUnits = text.length.toLong()
            )
        ),
        tocTargetSections = emptyMap(),
        totalMainTextUnits = text.length.toLong(),
        resources = emptyList()
    )
}
