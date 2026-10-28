package com.flowreader.app.util.epub

import com.flowreader.app.domain.model.DocumentRole
import com.flowreader.app.domain.model.PublicationManifest
import com.flowreader.app.domain.model.SectionKind
import com.flowreader.app.domain.model.StructuredDocument
import com.flowreader.app.domain.model.TextUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 / 主计划 4.1 第 6 步、4.3 末段、10.2、12.3 第 4/6 条验收
 * （T036、T037、T039、T041、T042、T069、T071、INV02、INV11）。
 *
 * 夹具全部为自造 XHTML，正文文字量按 [TextUnits] 口径人工核对过，不含任何版权样书。
 */
class ReadingManifestBuilderTest {

    private val opfPath = "OEBPS/content.opf"
    private val publicationId = "11111111-2222-3333-4444-555555555555"
    private val revision = "rev-abc"

    private fun document(key: String, body: String): StructuredDocument =
        EpubDocumentParser.parse(
            key,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>$body</body>
            </html>
            """.trimIndent()
        )

    private fun entry(href: String, label: String, children: List<EpubNavigationParser.Node> = emptyList()) =
        EpubNavigationParser.Node(
            nodeId = "n-" + href + "-" + label,
            label = label,
            kind = if (children.isEmpty()) EpubNavigationParser.Node.Kind.ENTRY else EpubNavigationParser.Node.Kind.GROUP,
            isVolume = children.isNotEmpty(),
            role = if (children.isEmpty()) null else EpubNavigationParser.ROLE_VOLUME,
            targetPath = href,
            targetFragment = null,
            children = children
        )

    private fun anchoredEntry(href: String, fragment: String, label: String) =
        EpubNavigationParser.Node(
            nodeId = "n-$href#$fragment-$label",
            label = label,
            kind = EpubNavigationParser.Node.Kind.ENTRY,
            targetPath = href,
            targetFragment = fragment
        )

    private fun build(
        spine: List<ReadingManifestBuilder.SpineEntry>,
        documents: Map<String, StructuredDocument>,
        toc: List<EpubNavigationParser.Node> = emptyList()
    ): ReadingManifestBuilder.Result = ReadingManifestBuilder.build(
        ReadingManifestBuilder.Input(
            publicationId = publicationId,
            revision = revision,
            normalizationVersion = 1,
            parserSchemaVersion = 1,
            sourceHash = "deadbeef",
            manifestRelativePath = "files/books/$publicationId/revisions/$revision",
            opfPath = opfPath,
            spine = spine,
            documents = documents,
            toc = toc
        )
    )

    private fun built(result: ReadingManifestBuilder.Result): PublicationManifest {
        assertTrue("期望构建成功，实际: $result", result is ReadingManifestBuilder.Result.Built)
        return result.manifest
    }

    private fun codes(manifest: PublicationManifest): List<String> = manifest.diagnostics.map { it.code }

    /** 一段恰好 [count] 个有效字的正文（用 CJK 字符，1 字 = 1 code point）。 */
    private fun filler(count: Int): String = "<p>" + "甲".repeat(count) + "</p>"

    // ------------------------------------------------------------ 基本结构

    @Test
    fun spineBecomesOneSectionPerDocumentWhenTocIsAbsent() {
        val manifest = built(
            build(
                spine = listOf(
                    ReadingManifestBuilder.SpineEntry("OEBPS/Text/ch1.xhtml"),
                    ReadingManifestBuilder.SpineEntry("OEBPS/Text/ch2.xhtml")
                ),
                documents = mapOf(
                    "OEBPS/Text/ch1.xhtml" to document("OEBPS/Text/ch1.xhtml", filler(100)),
                    "OEBPS/Text/ch2.xhtml" to document("OEBPS/Text/ch2.xhtml", filler(900))
                )
            )
        )
        assertEquals(2, manifest.sections.size)
        assertEquals(listOf(0, 1), manifest.sections.map { it.ordinal })
        assertEquals(listOf(100L, 900L), manifest.sections.map { it.mainTextUnits })
        // 10.2 的 100/900 golden：分母来自完整索引。
        assertEquals(1000L, manifest.totalMainTextUnits)
        assertEquals(listOf("OEBPS/Text/ch1.xhtml", "OEBPS/Text/ch2.xhtml"), manifest.mainDocumentKeys)
    }

    @Test
    fun oneDocumentWithTwoAnchorsBecomesTwoSections() {
        // 同一 XHTML 两个目录目标 → 两个 section（4.3：同一文档可含多个 section）。
        val key = "OEBPS/Text/ch1.xhtml"
        val manifest = built(
            build(
                spine = listOf(ReadingManifestBuilder.SpineEntry(key)),
                documents = mapOf(
                    key to document(
                        key,
                        "<h1 id=\"a\">第一章</h1>" + filler(100) + "<h1 id=\"b\">第二章</h1>" + filler(900)
                    )
                ),
                toc = listOf(anchoredEntry(key, "a", "第一章"), anchoredEntry(key, "b", "第二章"))
            )
        )
        assertEquals(2, manifest.sections.size)
        assertEquals(listOf("第一章", "第二章"), manifest.sections.map { it.title })
        assertEquals(listOf(SectionKind.NORMAL, SectionKind.NORMAL), manifest.sections.map { it.kind })
        assertEquals(2, manifest.tocTargetSections.size)
        assertEquals(
            manifest.sections[0].sectionId,
            manifest.tocTargetSections.values.toList()[0]
        )
        // 两个 section 的范围必须相接且不重叠。
        val first = manifest.sections[0].ranges.single()
        val second = manifest.sections[1].ranges.single()
        assertEquals(first.endUtf16Exclusive, second.startUtf16)
        assertFalse(first.intersects(second))
    }

    @Test
    fun contentBeforeTheFirstTargetBecomesAPrefaceSection() {
        val key = "OEBPS/Text/ch1.xhtml"
        val manifest = built(
            build(
                spine = listOf(ReadingManifestBuilder.SpineEntry(key)),
                documents = mapOf(
                    key to document(
                        key,
                        filler(50) + "<h1 id=\"a\">第一章</h1>" + filler(100)
                    )
                ),
                toc = listOf(anchoredEntry(key, "a", "第一章"))
            )
        )
        assertEquals(2, manifest.sections.size)
        assertEquals(SectionKind.PREFACE, manifest.sections[0].kind)
        assertEquals("正文起始", manifest.sections[0].title)
        assertEquals(SectionKind.NORMAL, manifest.sections[1].kind)
        // 前言区间的有效字 = 50（标题在第二个 section 里）。
        assertEquals(50L, manifest.sections[0].mainTextUnits)
    }

    @Test
    fun plainTocEntryWithoutFragmentMapsToTheDocumentStart() {
        val key = "OEBPS/Text/ch1.xhtml"
        val manifest = built(
            build(
                spine = listOf(ReadingManifestBuilder.SpineEntry(key)),
                documents = mapOf(key to document(key, filler(10))),
                toc = listOf(entry(key, "第一章"))
            )
        )
        assertEquals(1, manifest.sections.size)
        assertEquals("第一章", manifest.sections[0].title)
        // 无 fragment → 指向该文档第一个边界（此处即文档起点）。
        assertEquals(manifest.sections[0].sectionId, manifest.tocTargetSections.values.single())
    }

    // ------------------------------------------------------------ linear 与辅助文档

    @Test
    fun nonLinearDocumentsAreAuxiliaryAndDoNotEnterTheMainOrder() {
        val manifest = built(
            build(
                spine = listOf(
                    ReadingManifestBuilder.SpineEntry("OEBPS/Text/cover.xhtml", linear = false),
                    ReadingManifestBuilder.SpineEntry("OEBPS/Text/ch1.xhtml")
                ),
                documents = mapOf(
                    "OEBPS/Text/cover.xhtml" to document("OEBPS/Text/cover.xhtml", filler(500)),
                    "OEBPS/Text/ch1.xhtml" to document("OEBPS/Text/ch1.xhtml", filler(100))
                )
            )
        )
        // 4.3：主阅读顺序只按 spine 的 linear yes/省略项构建。
        assertEquals(listOf("OEBPS/Text/ch1.xhtml"), manifest.mainDocumentKeys)
        assertEquals(1, manifest.sections.size)
        assertEquals(100L, manifest.totalMainTextUnits)
        assertEquals(
            DocumentRole.AUXILIARY,
            manifest.documents.first { it.documentKey == "OEBPS/Text/cover.xhtml" }.role
        )
        assertEquals(0L, manifest.documents.first { it.documentKey == "OEBPS/Text/cover.xhtml" }.mainTextUnits)
        // 辅助文档仍然被记录下来，不能丢（旧书封面等入口）。
        assertEquals(2, manifest.documents.size)
    }

    // ------------------------------------------------------------ 重复 spine 出现

    @Test
    fun repeatedSpineOccurrenceIsRecordedButCountedOnce() {
        val key = "OEBPS/Text/ch1.xhtml"
        val manifest = built(
            build(
                spine = listOf(
                    ReadingManifestBuilder.SpineEntry(key),
                    ReadingManifestBuilder.SpineEntry("OEBPS/Text/ch2.xhtml"),
                    ReadingManifestBuilder.SpineEntry(key)
                ),
                documents = mapOf(
                    key to document(key, filler(100)),
                    "OEBPS/Text/ch2.xhtml" to document("OEBPS/Text/ch2.xhtml", filler(900))
                )
            )
        )
        // 每一次出现都保留，顺序原样（10.2 第 2 条：不能靠 documentKey 覆盖）。
        assertEquals(3, manifest.spineOccurrences.size)
        assertEquals(listOf(0, 1, 2), manifest.spineOccurrences.map { it.ordinal })
        assertEquals(
            listOf(key, "OEBPS/Text/ch2.xhtml", key),
            manifest.spineOccurrences.map { it.documentKey }
        )
        // occurrenceId 与 documentKey 无关：同一文档的两次出现必须不同。
        val first = manifest.spineOccurrences[0].occurrenceId
        val second = manifest.spineOccurrences[2].occurrenceId
        assertTrue(first != second)
        // 只有第一次计入。
        assertEquals(listOf(true, true, false), manifest.spineOccurrences.map { it.counted })
        assertTrue(codes(manifest).contains("SPINE_DOCUMENT_REPEATED"))

        // 分母不翻倍：仍然 1000，而不是 1100。
        assertEquals(1000L, manifest.totalMainTextUnits)
        // 文档只出现一次。
        assertEquals(2, manifest.documents.size)
        // section 也不重复：reading_sections 主键是 (bookId, revision, sectionId)。
        assertEquals(2, manifest.sections.size)
        assertEquals(2, manifest.sections.map { it.sectionId }.distinct().size)
    }

    @Test
    fun unreadableSpineEntryIsDiagnosedAndKeptInOccurrences() {
        val manifest = built(
            build(
                spine = listOf(
                    ReadingManifestBuilder.SpineEntry("OEBPS/Text/missing.xhtml"),
                    ReadingManifestBuilder.SpineEntry("OEBPS/Text/ch1.xhtml")
                ),
                documents = mapOf("OEBPS/Text/ch1.xhtml" to document("OEBPS/Text/ch1.xhtml", filler(10)))
            )
        )
        // 4.1 末段：任何步骤失败要返回具体 stage/document，不返回表面成功的空书。
        assertTrue(codes(manifest).contains("SPINE_DOCUMENT_UNREADABLE"))
        assertEquals(2, manifest.spineOccurrences.size)
        // 出现记录保留 documentKey（诊断要点名是哪个文档读不出来），只是没有对应文档内容。
        assertEquals("OEBPS/Text/missing.xhtml", manifest.spineOccurrences[0].documentKey)
        assertEquals(1, manifest.documents.size)
        assertEquals(1, manifest.sections.size)
    }

    // ------------------------------------------------------------ 统计口径

    @Test
    fun sectionUnitsExcludeAuxiliaryBlocks() {
        val key = "OEBPS/Text/ch1.xhtml"
        val manifest = built(
            build(
                spine = listOf(ReadingManifestBuilder.SpineEntry(key)),
                documents = mapOf(
                    key to document(
                        key,
                        "<p>甲乙丙丁戊</p><aside id=\"fn1\"><p>脚注文字甲</p></aside>"
                    )
                )
            )
        )
        // 主线 5 字；脚注 5 字不计（3.2 第 7 条 / 10.2 主线计量规则）。
        assertEquals(5L, manifest.totalMainTextUnits)
    }

    @Test
    fun imageOnlyBookReportsZeroUnitsAndDiagnosesInsteadOfDividingByZero() {
        val key = "OEBPS/Text/pics.xhtml"
        val manifest = built(
            build(
                spine = listOf(ReadingManifestBuilder.SpineEntry(key)),
                documents = mapOf(
                    key to document(key, "<div><img src=\"../Images/a.png\" alt=\"图\"/></div>")
                )
            )
        )
        // 10.2：纯图书不换算成"几百字"，也不能除零。
        assertEquals(0L, manifest.totalMainTextUnits)
        assertTrue(codes(manifest).contains("MANIFEST_ZERO_TEXT_UNITS"))
    }

    @Test
    fun totalIsTheSumOfSectionsAndNeverDependsOnLoadedChapters() {
        val key1 = "OEBPS/Text/ch1.xhtml"
        val key2 = "OEBPS/Text/ch2.xhtml"
        val manifest = built(
            build(
                spine = listOf(
                    ReadingManifestBuilder.SpineEntry(key1),
                    ReadingManifestBuilder.SpineEntry(key2)
                ),
                documents = mapOf(
                    key1 to document(key1, filler(100)),
                    key2 to document(key2, filler(900))
                ),
                toc = listOf(
                    anchoredEntry(key1, "h1", "第一章"),
                    anchoredEntry(key2, "h2", "第二章")
                )
            )
        )
        // 文档里没有 h1/h2 锚点：目录目标无法映射，正文仍按 spine 成 section。
        assertEquals(manifest.sections.sumOf { it.mainTextUnits }, manifest.totalMainTextUnits)
        assertEquals(1000L, manifest.totalMainTextUnits)
        assertEquals(1000L, TextUnits.count("甲".repeat(1000)))
    }

    // ------------------------------------------------------------ 目录诊断

    @Test
    fun tocOrderDifferentFromSpineIsDiagnosedButBodyOrderFollowsSpine() {
        val key1 = "OEBPS/Text/ch1.xhtml"
        val key2 = "OEBPS/Text/ch2.xhtml"
        val manifest = built(
            build(
                spine = listOf(
                    ReadingManifestBuilder.SpineEntry(key1),
                    ReadingManifestBuilder.SpineEntry(key2)
                ),
                documents = mapOf(
                    key1 to document(key1, filler(10)),
                    key2 to document(key2, filler(20))
                ),
                // 目录故意倒序
                toc = listOf(entry(key2, "第二章"), entry(key1, "第一章"))
            )
        )
        assertTrue(codes(manifest).contains("TOC_ORDER_DIFFERS_FROM_SPINE"))
        // 正文顺序仍按 spine（INV06：目录不能重排正文）。
        assertEquals(listOf(key1, key2), manifest.sections.map { it.ranges.first().documentKey })
    }

    @Test
    fun missingAnchorIsDiagnosedAndProducesNoBoundary() {
        val key = "OEBPS/Text/ch1.xhtml"
        val manifest = built(
            build(
                spine = listOf(ReadingManifestBuilder.SpineEntry(key)),
                documents = mapOf(key to document(key, filler(10))),
                toc = listOf(anchoredEntry(key, "nope", "不存在的目标"))
            )
        )
        // 8.1：target 不存在要明确报错，不能随机跳。
        assertTrue(codes(manifest).contains("ANCHOR_NOT_FOUND"))
        assertEquals(1, manifest.sections.size)
        assertTrue(manifest.tocTargetSections.isEmpty())
    }

    @Test
    fun volumeGroupIsNotASectionBoundaryButKeepsItsChildrenTargets() {
        val key1 = "OEBPS/Text/ch1.xhtml"
        val key2 = "OEBPS/Text/ch2.xhtml"
        val manifest = built(
            build(
                spine = listOf(
                    ReadingManifestBuilder.SpineEntry(key1),
                    ReadingManifestBuilder.SpineEntry(key2)
                ),
                documents = mapOf(
                    key1 to document(key1, filler(10)),
                    key2 to document(key2, filler(20))
                ),
                toc = listOf(
                    EpubNavigationParser.Node(
                        nodeId = "vol1",
                        label = "第一卷",
                        kind = EpubNavigationParser.Node.Kind.GROUP,
                        isVolume = true,
                        role = EpubNavigationParser.ROLE_VOLUME,
                        targetPath = null,
                        children = listOf(entry(key1, "第一章"), entry(key2, "第二章"))
                    )
                )
            )
        )
        // 卷是 GROUP 且无 target → 不产生 section；两个子项目标各自映射到自己的 section。
        assertEquals(2, manifest.sections.size)
        assertEquals(2, manifest.tocTargetSections.size)
        assertFalse(manifest.tocTargetSections.containsKey("vol1"))
    }

    // ------------------------------------------------------------ 资源与确定性

    @Test
    fun internalResourceReferencesAreAggregatedOnce() {
        val key1 = "OEBPS/Text/ch1.xhtml"
        val key2 = "OEBPS/Text/ch2.xhtml"
        val manifest = built(
            build(
                spine = listOf(
                    ReadingManifestBuilder.SpineEntry(key1),
                    ReadingManifestBuilder.SpineEntry(key2)
                ),
                documents = mapOf(
                    key1 to document(key1, "<p>甲<img src=\"../Images/a.png\"/></p>"),
                    key2 to document(key2, "<p>乙<img src=\"../Images/a.png\"/></p>")
                )
            )
        )
        val resource = manifest.resources.single()
        assertEquals("OEBPS/Images/a.png", resource.containerPath)
        assertEquals(listOf(key1, key2), resource.documentKeys)
        assertEquals(listOf("../Images/a.png"), resource.rawReferences)
    }

    @Test
    fun buildingTwiceYieldsIdenticalSectionIdsAndOccurrenceIds() {
        val key1 = "OEBPS/Text/ch1.xhtml"
        val key2 = "OEBPS/Text/ch2.xhtml"
        fun once(): PublicationManifest = built(
            build(
                spine = listOf(
                    ReadingManifestBuilder.SpineEntry(key1),
                    ReadingManifestBuilder.SpineEntry(key2)
                ),
                documents = mapOf(
                    key1 to document(key1, "<h1 id=\"a\">一</h1>" + filler(10)),
                    key2 to document(key2, filler(20))
                ),
                toc = listOf(anchoredEntry(key1, "a", "一"))
            )
        )
        val first = once()
        val second = once()
        // INV02：同一输入重复构建必须逐字一致。
        assertEquals(first.sections.map { it.sectionId }, second.sections.map { it.sectionId })
        assertEquals(first.spineOccurrences.map { it.occurrenceId }, second.spineOccurrences.map { it.occurrenceId })
        assertEquals(first.documents.map { it.sourceHash }, second.documents.map { it.sourceHash })
        assertEquals(first.tocTargetSections, second.tocTargetSections)
        assertEquals(first.totalMainTextUnits, second.totalMainTextUnits)
    }

    @Test
    fun emptySpineIsRejectedInsteadOfReturningAnEmptySuccess() {
        val result = build(spine = emptyList(), documents = emptyMap())
        assertTrue(result is ReadingManifestBuilder.Result.Rejected)
        val rejected = result as ReadingManifestBuilder.Result.Rejected
        assertEquals("MANIFEST_NO_READABLE_SECTION", rejected.code)
        assertTrue(rejected.manifest.sections.isEmpty())
    }

    @Test
    fun auxiliaryOnlyDocumentIsNotCountedTowardsTheMainOrder() {
        val key = "OEBPS/Text/notes.xhtml"
        val manifest = built(
            build(
                spine = listOf(
                    ReadingManifestBuilder.SpineEntry(key),
                    ReadingManifestBuilder.SpineEntry("OEBPS/Text/ch1.xhtml")
                ),
                documents = mapOf(
                    // 整个文档只有脚注内容 → 没有 MAIN 块。
                    key to document(key, "<aside id=\"fn\"><p>只有注释</p></aside>"),
                    "OEBPS/Text/ch1.xhtml" to document("OEBPS/Text/ch1.xhtml", filler(10))
                )
            )
        )
        assertEquals(DocumentRole.AUXILIARY, manifest.documents.first { it.documentKey == key }.role)
        assertEquals(10L, manifest.totalMainTextUnits)

        val section = manifest.sections.first { it.ranges.first().documentKey == key }
        assertEquals(0L, section.mainTextUnits)
        assertNotNull(manifest.sections.firstOrNull { it.ranges.first().documentKey == "OEBPS/Text/ch1.xhtml" })
    }
}
