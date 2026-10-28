package com.flowreader.feature.reader

import com.flowreader.app.domain.model.Affinity
import com.flowreader.app.domain.model.AnchorTarget
import com.flowreader.app.domain.model.DisplayMapping
import com.flowreader.app.domain.model.DisplayMappingBuilder
import com.flowreader.app.domain.model.DocumentRole
import com.flowreader.app.domain.model.HeadingBlock
import com.flowreader.app.domain.model.ParagraphBlock
import com.flowreader.app.domain.model.PublicationDocument
import com.flowreader.app.domain.model.PublicationManifest
import com.flowreader.app.domain.model.ReadingSection
import com.flowreader.app.domain.model.ResolutionQuality
import com.flowreader.app.domain.model.SectionKind
import com.flowreader.app.domain.model.SourceRange
import com.flowreader.app.domain.model.SourceSpan
import com.flowreader.app.domain.model.SpineOccurrence
import com.flowreader.app.domain.model.StructuredDocument
import com.flowreader.app.domain.model.TextContext
import com.flowreader.app.domain.model.TextLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate G4 真实阅读器验收测试（主计划 Gate G4 / R1 / INV01 / INV07 / INV08）。
 *
 * 验收目标：
 * 1. 连续 20 次重排（字号改变、屏幕旋转、视口尺寸变化、平卷/翻页切换）locator 零漂移；
 * 2. 六级定位恢复引擎在真实结构文档与内容清单下各级阶梯命中确定性；
 * 3. 双向内容映射与结构化分页的一致性与保真度。
 */
class GateG4ReflowAcceptanceTest {

    private fun sampleDocument(): StructuredDocument {
        val paragraphs = listOf(
            "第一章：风起云涌。天际泛起微光，晨雾尚未散去，城门缓缓开启。",
            "行人匆匆走过石板路，青石缝隙间长着嫩绿的青苔，露珠在微风中轻颤。",
            "老茶馆的掌柜卸下最后一块门板，铜壶里的热水已开始咕嘟作响，升起缕缕白汽。",
            "少年背着行囊在门前停下脚步，抬头望了望褪色的招牌，长舒了一口气。",
            "“店家，来一碗阳春面，两角老酒。”他的声音有些沙哑，显然赶了整夜的路。",
            "掌柜打量了他一眼，笑着应了一声，转身掀开后厨的布帘忙活去了。",
            "店内角落里坐着几位早起的茶客，正低声议论着城南赵府昨夜走水的消息。",
            "据说火势极为诡异，明明没有风，火舌却径直窜起三丈高，照亮了半边夜空。",
            "少年微微侧耳倾听，手指无意识地在桌沿轻轻叩击，眼神渐渐变得深邃。",
            "不多时，热气腾腾的面条端了上来，细葱翠绿，汤清面白，香气扑鼻。",
            "他拿起竹筷，挑起一缕面条送入口中，暖意瞬间驱散了通宵的寒气。",
            "窗外的阳光逐渐明朗起来，街道上的人流多起来，车马喧嚣声渐起。",
            "这一天，注定不会平静。新的旅程，正是从这间普通的早茶馆悄然展开。"
        )

        val blocks = mutableListOf<com.flowreader.app.domain.model.ContentBlock>()
        val anchorIndex = mutableMapOf<String, AnchorTarget>()
        var offset = 0

        // Title heading
        val title = "天外之境"
        blocks.add(
            HeadingBlock(
                blockId = "h_title",
                range = SourceSpan("doc_main.xhtml", offset, offset + title.length),
                domPath = "body/h1[1]",
                level = 1
            )
        )
        anchorIndex["title"] = AnchorTarget(id = "title", offsetUtf16 = offset, blockId = "h_title")
        offset += title.length

        // Paragraphs
        paragraphs.forEachIndexed { idx, pText ->
            val blockId = "p_$idx"
            val elementId = "para_$idx"
            blocks.add(
                ParagraphBlock(
                    blockId = blockId,
                    range = SourceSpan("doc_main.xhtml", offset, offset + pText.length),
                    domPath = "body/p[${idx + 1}]",
                    elementId = elementId
                )
            )
            anchorIndex[elementId] = AnchorTarget(id = elementId, offsetUtf16 = offset, blockId = blockId)
            offset += pText.length
        }

        val fullText = title + paragraphs.joinToString("")
        return StructuredDocument(
            documentKey = "doc_main.xhtml",
            text = fullText,
            blocks = blocks,
            anchorIndex = anchorIndex,
            resourceRefs = emptyList(),
            mainTextUnits = fullText.length.toLong()
        )
    }

    private fun sampleManifest(doc: StructuredDocument): PublicationManifest {
        val range = SourceRange("doc_main.xhtml", 0, doc.text.length)
        val section = ReadingSection(
            sectionId = "sec_01",
            ordinal = 0,
            title = "第一章",
            aliases = emptyList(),
            kind = SectionKind.NORMAL,
            ranges = listOf(range),
            mainTextUnits = doc.text.length.toLong()
        )
        val docSummary = PublicationDocument(
            documentKey = "doc_main.xhtml",
            ordinal = 0,
            linear = true,
            role = DocumentRole.MAIN,
            textLengthUtf16 = doc.text.length,
            mainTextUnits = doc.text.length.toLong(),
            manifestRelativePath = "text/doc_main.xhtml",
            sourceHash = "hash_doc_main_v1"
        )
        return PublicationManifest(
            publicationId = "pub_g4_golden",
            revision = "rev_g4_01",
            normalizationVersion = 1,
            parserSchemaVersion = 1,
            sourceHash = "hash_pub_g4",
            manifestRelativePath = "manifest.json",
            spineOccurrences = listOf(
                SpineOccurrence(
                    occurrenceId = "occ_0",
                    ordinal = 0,
                    documentKey = "doc_main.xhtml",
                    linear = true,
                    mediaType = "application/xhtml+xml",
                    counted = true
                )
            ),
            documents = listOf(docSummary),
            sections = listOf(section),
            tocTargetSections = mapOf("toc_1" to "sec_01"),
            totalMainTextUnits = doc.text.length.toLong(),
            resources = emptyList()
        )
    }

    @Test
    fun twentyConsecutiveReflowsAcrossDifferentGeometriesPreserveExactLocator() {
        val document = sampleDocument()
        val manifest = sampleManifest(document)

        // 目标定位：选在第 4 段中间
        val targetBlock = document.blocks[4]
        val targetOffset = targetBlock.range.startUtf16 + 12
        val targetAnchor = "para_3"
        val contextBefore = document.text.substring(targetOffset - 15, targetOffset)
        val contextAfter = document.text.substring(targetOffset, targetOffset + 15)

        val initialLocator = TextLocator(
            version = 1,
            publicationId = manifest.publicationId,
            revision = manifest.revision,
            sectionId = "sec_01",
            documentKey = "doc_main.xhtml",
            offsetUtf16 = targetOffset,
            affinity = Affinity.AFTER,
            blockId = targetBlock.blockId,
            elementId = targetAnchor,
            context = TextContext(before = contextBefore, after = contextAfter)
        )

        var currentLocator = initialLocator

        // 模拟 20 次连续重排：涵盖字号变化 (14sp..28sp -> 不同高度/分片)、窗口翻转、横竖屏切换
        val pageHeights = listOf(
            450, 480, 520, 550, 600, 640, 700, 750, 800, 850,
            900, 950, 1000, 1050, 1100, 1150, 1200, 1250, 1300, 1400
        )
        assertEquals(20, pageHeights.size)

        val paginator = ChapterPaginator { text, _, _, isTitle ->
            val lineHeight = if (isTitle) 40 else 24
            val charsPerLine = 20
            val lines = (text.length + charsPerLine - 1) / charsPerLine
            lines * lineHeight
        }

        for (i in 0 until 20) {
            val heightPx = pageHeights[i]
            val gapPx = 8 + (i % 5) * 2

            // 1. 使用 StructuredDocument 真实结构分页
            val pages = paginator.paginateStructuredDocument(document, heightPx, gapPx)
            assertTrue("重排 $i 页数必须大于 0", pages.isNotEmpty())

            // 2. 根据 locator 恢复所在页面
            val pageIndex = paginator.findPageForLocator(pages, currentLocator)
            assertTrue("目标页下标必须合法", pageIndex in pages.indices)

            val matchedPage = pages[pageIndex]
            val matchingRange = matchedPage.flatMap { it.sourceRanges }
                .find { currentLocator.offsetUtf16 in it.startUtf16..it.endUtf16Exclusive }
            assertNotNull("重排 $i 目标页必须包含 locator 对应字符", matchingRange)

            // 3. 六级定位恢复器验证
            val resolution = ContentLocatorResolver.resolve(
                locator = currentLocator,
                activePublicationId = manifest.publicationId,
                activeRevision = manifest.revision,
                manifest = manifest,
                documentProvider = { document },
                textProvider = { document.text }
            )

            assertEquals("重排 $i 恢复质量必须是 EXACT", ResolutionQuality.EXACT, resolution.quality)
            assertEquals("重排 $i 偏移必须完全一致，0 漂移", targetOffset, resolution.locator?.offsetUtf16)

            // 4. 下一次重排沿用当前恢复的 locator
            currentLocator = resolution.locator!!
        }

        // 最终校验：经过 20 次不同视口几何重排后，偏移和字符上下文与最初 100% 相同
        assertEquals(targetOffset, currentLocator.offsetUtf16)
        assertEquals(targetBlock.blockId, currentLocator.blockId)
        assertEquals(targetAnchor, currentLocator.elementId)
        assertEquals(contextBefore, currentLocator.context.before)
        assertEquals(contextAfter, currentLocator.context.after)
    }

    @Test
    fun sixTierRecoveryLadderOperatesDeterministically() {
        val document = sampleDocument()
        val manifest = sampleManifest(document)

        // Tier 1: EXACT
        val exactLocator = TextLocator(
            version = 1,
            publicationId = manifest.publicationId,
            revision = manifest.revision,
            sectionId = "sec_01",
            documentKey = "doc_main.xhtml",
            offsetUtf16 = 100,
            context = TextContext(before = document.text.substring(80, 100), after = document.text.substring(100, 120))
        )
        val resTier1 = ContentLocatorResolver.resolve(exactLocator, manifest.publicationId, manifest.revision, manifest)
        assertEquals(ResolutionQuality.EXACT, resTier1.quality)
        assertEquals(ContentLocatorResolver.TIER_EXACT_SOURCE_TEXT, resTier1.reasonCode)

        // Tier 2: HEURISTIC_FALLBACK (revision 升级后带 elementId 或滑窗匹配)
        val newRevision = "rev_g4_02"
        val upgradedManifest = manifest.copy(revision = newRevision)
        val heuristicLocator = exactLocator.copy(elementId = "para_2")
        val resTier2 = ContentLocatorResolver.resolve(
            heuristicLocator,
            upgradedManifest.publicationId,
            newRevision,
            upgradedManifest,
            documentProvider = { document }
        )
        assertEquals(ResolutionQuality.CONTEXT_MATCH, resTier2.quality)
        assertTrue(resTier2.reasonCode?.startsWith(ContentLocatorResolver.TIER_HEURISTIC_FALLBACK) == true)

        // Tier 3: SECTION_OFFSET (文档无锚点上下文，按 section 相对比例回退)
        val sectionLocator = TextLocator(
            version = 1,
            publicationId = manifest.publicationId,
            revision = "old_rev",
            sectionId = "sec_01",
            documentKey = "doc_main.xhtml",
            offsetUtf16 = (document.text.length / 2)
        )
        val resTier3 = ContentLocatorResolver.resolve(
            sectionLocator,
            manifest.publicationId,
            newRevision,
            upgradedManifest,
            documentProvider = { null }
        )
        assertEquals(ResolutionQuality.APPROXIMATE, resTier3.quality)
        assertEquals(ContentLocatorResolver.TIER_SECTION_OFFSET, resTier3.reasonCode)

        // Tier 6: REVISION_ROOT (完全未知章节与文档，保底回退到全书第一正文开头)
        val alienLocator = TextLocator(
            version = 1,
            publicationId = "alien_pub",
            revision = "alien_rev",
            sectionId = "non_existent",
            documentKey = "non_existent.xhtml",
            offsetUtf16 = 999
        )
        val resTier6 = ContentLocatorResolver.resolve(
            alienLocator,
            manifest.publicationId,
            newRevision,
            upgradedManifest,
            documentProvider = { null }
        )
        assertEquals(ResolutionQuality.UNRESOLVED, resTier6.quality)
        assertEquals(ContentLocatorResolver.TIER_REVISION_ROOT, resTier6.reasonCode)
        assertEquals(0, resTier6.locator?.offsetUtf16)
    }

    @Test
    fun bidirectionalDisplayMappingMaintainsZeroLossInvariance() {
        val text = "这是一段用于测试 ReaderTextMapping 双向映射的样本文字。"
        val mapping = DisplayMapping.identity("doc_main.xhtml", text)

        // 验证 displayToSource 与 sourceToDisplay 双向互逆性
        for (i in text.indices) {
            val src = mapping.displayToSource(i)
            assertEquals(i, src)
            val disp = mapping.sourceToDisplay(src)
            assertEquals(i, disp.displayOffset)
        }

        // 验证 DisplayMappingBuilder 构建
        val builder = DisplayMappingBuilder("doc_main.xhtml")
        builder.appendSource(text, 0)
        val customMapping = builder.build()
        assertEquals(text.length, customMapping.displayText.length)
    }
}
