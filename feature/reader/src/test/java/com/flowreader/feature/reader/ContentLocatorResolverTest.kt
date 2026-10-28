package com.flowreader.feature.reader

import com.flowreader.app.domain.model.Affinity
import com.flowreader.app.domain.model.AnchorTarget
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
import com.flowreader.app.domain.model.StructuredDocument
import com.flowreader.app.domain.model.TextContext
import com.flowreader.app.domain.model.TextLocator
import com.flowreader.app.domain.model.fromJson
import com.flowreader.app.domain.model.toJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentLocatorResolverTest {

    private val pubId = "pub-1234-uuid"
    private val rev1 = "rev-hash-v1"
    private val rev2 = "rev-hash-v2"
    private val docKey = "Text/chapter1.xhtml"

    private fun createManifest(
        revision: String = rev1,
        docLength: Int = 5000,
        sections: List<ReadingSection> = emptyList()
    ): PublicationManifest {
        val doc = PublicationDocument(
            documentKey = docKey,
            ordinal = 0,
            linear = true,
            role = DocumentRole.MAIN,
            textLengthUtf16 = docLength,
            mainTextUnits = docLength.toLong(),
            manifestRelativePath = "documents/ch1.json",
            sourceHash = "hash-doc-ch1"
        )
        val defaultSections = if (sections.isEmpty()) {
            listOf(
                ReadingSection(
                    sectionId = "sec-ch1",
                    ordinal = 0,
                    title = "第一章",
                    aliases = emptyList(),
                    kind = SectionKind.NORMAL,
                    ranges = listOf(SourceRange(docKey, 0, docLength)),
                    mainTextUnits = docLength.toLong()
                )
            )
        } else {
            sections
        }
        return PublicationManifest(
            publicationId = pubId,
            revision = revision,
            normalizationVersion = 1,
            parserSchemaVersion = 1,
            sourceHash = "source-hash",
            manifestRelativePath = "manifest.json",
            spineOccurrences = emptyList(),
            documents = listOf(doc),
            sections = defaultSections,
            tocTargetSections = mapOf("node-1" to "sec-ch1"),
            totalMainTextUnits = docLength.toLong(),
            resources = emptyList()
        )
    }

    @Test
    fun tier1_exactSourceTextResolution() {
        val manifest = createManifest(rev1, 5000)
        val locator = TextLocator(
            publicationId = pubId,
            revision = rev1,
            normalizationVersion = 1,
            sectionId = "sec-ch1",
            documentKey = docKey,
            offsetUtf16 = 250
        )

        val result = ContentLocatorResolver.resolve(
            locator = locator,
            activePublicationId = pubId,
            activeRevision = rev1,
            manifest = manifest
        )

        assertEquals(ResolutionQuality.EXACT, result.quality)
        assertEquals(ContentLocatorResolver.TIER_EXACT_SOURCE_TEXT, result.reasonCode)
        assertEquals(250, result.locator?.offsetUtf16)
        assertEquals(docKey, result.locator?.documentKey)
    }

    @Test
    fun tier2_heuristicElementIdMatch() {
        val manifest = createManifest(rev2, 5000)
        val text = "这是一段正文开头。\n这里是目标锚点段落内容。\n正文结束。"
        val anchorOffset = text.indexOf("这里是目标锚点段落内容。")
        val structuredDoc = StructuredDocument(
            documentKey = docKey,
            text = text,
            blocks = listOf(
                ParagraphBlock(
                    blockId = "b-1",
                    range = SourceSpan(docKey, 0, text.length),
                    domPath = "body/p[1]"
                )
            ),
            anchorIndex = mapOf(
                "anchor-target" to AnchorTarget("anchor-target", anchorOffset, "b-1")
            ),
            resourceRefs = emptyList(),
            mainTextUnits = text.length.toLong()
        )

        val oldLocator = TextLocator(
            publicationId = pubId,
            revision = rev1,
            normalizationVersion = 1,
            sectionId = "sec-ch1",
            documentKey = docKey,
            offsetUtf16 = anchorOffset + 50,
            elementId = "anchor-target",
            context = TextContext(before = "开头。\n", after = "这里是")
        )

        val result = ContentLocatorResolver.resolve(
            locator = oldLocator,
            activePublicationId = pubId,
            activeRevision = rev2,
            manifest = manifest,
            documentProvider = { structuredDoc }
        )

        assertEquals(ResolutionQuality.CONTEXT_MATCH, result.quality)
        assertEquals("${ContentLocatorResolver.TIER_HEURISTIC_FALLBACK}_ELEMENT_ID", result.reasonCode)
        assertEquals(anchorOffset, result.locator?.offsetUtf16)
        assertEquals(rev2, result.locator?.revision)
    }

    @Test
    fun tier2_heuristicSlidingWindowMatch() {
        val manifest = createManifest(rev2, 10000)
        val text = "前文开头 " + "填充文字 ".repeat(200) + "关键句子即将出现独一无二标语" + " 后续文字 ".repeat(200)
        val targetOffset = text.indexOf("独一无二标语")

        val oldLocator = TextLocator(
            publicationId = pubId,
            revision = rev1,
            normalizationVersion = 1,
            sectionId = "sec-ch1",
            documentKey = docKey,
            offsetUtf16 = targetOffset - 30,
            context = TextContext(before = "关键句子即将出现", after = "独一无二标语")
        )

        val result = ContentLocatorResolver.resolve(
            locator = oldLocator,
            activePublicationId = pubId,
            activeRevision = rev2,
            manifest = manifest,
            textProvider = { text }
        )

        assertEquals(ResolutionQuality.CONTEXT_MATCH, result.quality)
        assertEquals("${ContentLocatorResolver.TIER_HEURISTIC_FALLBACK}_WINDOW", result.reasonCode)
        assertEquals(targetOffset, result.locator?.offsetUtf16)
    }

    @Test
    fun tier2_ambiguousContextFallsThrough() {
        val manifest = createManifest(rev2, 10000)
        // Same phrase appears twice
        val text = "重复短语内容。" + "填充 ".repeat(50) + "重复短语内容。"
        val oldLocator = TextLocator(
            publicationId = pubId,
            revision = rev1,
            normalizationVersion = 1,
            sectionId = "sec-ch1",
            documentKey = docKey,
            offsetUtf16 = 20,
            context = TextContext(before = "重复", after = "短语")
        )

        val result = ContentLocatorResolver.resolve(
            locator = oldLocator,
            activePublicationId = pubId,
            activeRevision = rev2,
            manifest = manifest,
            textProvider = { text }
        )

        // Ambiguous match must NOT guess; it falls through to SECTION_OFFSET
        assertEquals(ResolutionQuality.APPROXIMATE, result.quality)
        assertEquals(ContentLocatorResolver.TIER_SECTION_OFFSET, result.reasonCode)
    }

    @Test
    fun tier3_sectionOffsetResolution() {
        val manifest = createManifest(rev2, 6000)
        val oldLocator = TextLocator(
            publicationId = pubId,
            revision = rev1,
            normalizationVersion = 1,
            sectionId = "sec-ch1",
            documentKey = docKey,
            offsetUtf16 = 300,
            context = TextContext.EMPTY
        )

        val result = ContentLocatorResolver.resolve(
            locator = oldLocator,
            activePublicationId = pubId,
            activeRevision = rev2,
            manifest = manifest,
            textProvider = { null }
        )

        assertEquals(ResolutionQuality.APPROXIMATE, result.quality)
        assertEquals(ContentLocatorResolver.TIER_SECTION_OFFSET, result.reasonCode)
        assertEquals(300, result.locator?.offsetUtf16)
    }

    @Test
    fun tier4_chapterPageOrdinalResolution() {
        val sections = listOf(
            ReadingSection("sec-0", 0, "第一章", emptyList(), SectionKind.NORMAL, listOf(SourceRange(docKey, 0, 1000)), 1000),
            ReadingSection("sec-1", 1, "第二章", emptyList(), SectionKind.NORMAL, listOf(SourceRange(docKey, 1000, 2000)), 1000)
        )
        val manifest = createManifest(rev2, 2000, sections)
        val oldLocator = TextLocator(
            publicationId = pubId,
            revision = rev1,
            normalizationVersion = 1,
            sectionId = "1",
            documentKey = "old/path.xhtml",
            offsetUtf16 = 50
        )

        val result = ContentLocatorResolver.resolve(
            locator = oldLocator,
            activePublicationId = pubId,
            activeRevision = rev2,
            manifest = manifest
        )

        assertEquals(ResolutionQuality.APPROXIMATE, result.quality)
        assertEquals(ContentLocatorResolver.TIER_CHAPTER_PAGE, result.reasonCode)
        assertEquals(1000, result.locator?.offsetUtf16)
        assertEquals("sec-1", result.locator?.sectionId)
    }

    @Test
    fun tier5_headingFallbackResolution() {
        val manifest = createManifest(rev2, 3000)
        val text = "## 章节标题一\n内容一\n## 章节标题二\n内容二"
        val heading2Offset = text.indexOf("## 章节标题二")
        val structuredDoc = StructuredDocument(
            documentKey = docKey,
            text = text,
            blocks = listOf(
                HeadingBlock("h1", SourceSpan(docKey, 0, 8), "h1", 2),
                ParagraphBlock("p1", SourceSpan(docKey, 9, 13), "p1"),
                HeadingBlock("h2", SourceSpan(docKey, heading2Offset, heading2Offset + 8), "h2", 2),
                ParagraphBlock("p2", SourceSpan(docKey, heading2Offset + 9, text.length), "p2")
            ),
            anchorIndex = emptyMap(),
            resourceRefs = emptyList(),
            mainTextUnits = text.length.toLong()
        )

        val oldLocator = TextLocator(
            publicationId = pubId,
            revision = rev1,
            normalizationVersion = 1,
            sectionId = "unknown-section",
            documentKey = docKey,
            offsetUtf16 = heading2Offset + 15
        )

        val result = ContentLocatorResolver.resolve(
            locator = oldLocator,
            activePublicationId = pubId,
            activeRevision = rev2,
            manifest = manifest,
            documentProvider = { structuredDoc }
        )

        assertEquals(ResolutionQuality.APPROXIMATE, result.quality)
        assertEquals(ContentLocatorResolver.TIER_HEADING, result.reasonCode)
        assertEquals(heading2Offset, result.locator?.offsetUtf16)
        assertEquals("h2", result.locator?.blockId)
    }

    @Test
    fun tier6_revisionRootFallback() {
        val manifest = createManifest(rev2, 5000)
        val oldLocator = TextLocator(
            publicationId = "other-pub-id",
            revision = "other-rev",
            normalizationVersion = 1,
            sectionId = "completely-lost-sec",
            documentKey = "nonexistent.xhtml",
            offsetUtf16 = 99999
        )

        val result = ContentLocatorResolver.resolve(
            locator = oldLocator,
            activePublicationId = pubId,
            activeRevision = rev2,
            manifest = manifest
        )

        assertEquals(ResolutionQuality.UNRESOLVED, result.quality)
        assertEquals(ContentLocatorResolver.TIER_REVISION_ROOT, result.reasonCode)
        assertEquals(0, result.locator?.offsetUtf16)
    }

    @Test
    fun captureContextDoesNotBreakSurrogatePairs() {
        val text = "前文内容😀测试🎉后文内容"
        val offset = text.indexOf("测试")
        val context = ContentLocatorResolver.captureContext(text, offset, maxCodePoints = 4)

        // Ensure "😀" is kept intact as a surrogate pair in before
        assertTrue(context.before.endsWith("😀"))
        // Ensure "🎉" is kept intact in after
        assertTrue(context.after.contains("🎉"))
    }

    @Test
    fun textLocatorJsonRoundTrip() {
        val locator = TextLocator(
            version = 1,
            publicationId = "pub-uuid-1",
            revision = "rev-hash-abc",
            normalizationVersion = 1,
            sectionId = "sec-10",
            documentKey = "Text/ch\"1\".xhtml",
            offsetUtf16 = 1234,
            affinity = Affinity.BEFORE,
            blockId = "block-p1",
            elementId = "anchor-1",
            context = TextContext(before = "前文\"带引号\"\n换行", after = "后文\\反斜杠\t制表符")
        )

        val json = locator.toJson()
        val parsed = TextLocator.fromJson(json)

        assertNotNull(parsed)
        assertEquals(locator.version, parsed?.version)
        assertEquals(locator.publicationId, parsed?.publicationId)
        assertEquals(locator.revision, parsed?.revision)
        assertEquals(locator.sectionId, parsed?.sectionId)
        assertEquals(locator.documentKey, parsed?.documentKey)
        assertEquals(locator.offsetUtf16, parsed?.offsetUtf16)
        assertEquals(locator.affinity, parsed?.affinity)
        assertEquals(locator.blockId, parsed?.blockId)
        assertEquals(locator.elementId, parsed?.elementId)
        assertEquals(locator.context.before, parsed?.context?.before)
        assertEquals(locator.context.after, parsed?.context?.after)
    }

    @Test
    fun resolveLegacyChapterIndex() {
        val sections = listOf(
            ReadingSection("sec-0", 0, "章0", emptyList(), SectionKind.NORMAL, listOf(SourceRange(docKey, 0, 500)), 500),
            ReadingSection("sec-1", 1, "章1", emptyList(), SectionKind.NORMAL, listOf(SourceRange(docKey, 500, 1000)), 500)
        )
        val manifest = createManifest(rev1, 1000, sections)

        val result = ContentLocatorResolver.resolveLegacy(
            bookId = 1L,
            chapterIndex = 1,
            position = 120,
            activePublicationId = pubId,
            activeRevision = rev1,
            manifest = manifest
        )

        assertEquals(ResolutionQuality.APPROXIMATE, result.quality)
        assertEquals(620, result.locator?.offsetUtf16)
        assertEquals("sec-1", result.locator?.sectionId)
    }
}
