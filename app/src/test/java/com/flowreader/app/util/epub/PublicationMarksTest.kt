package com.flowreader.app.util.epub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.flowreader.app.domain.model.DocumentRole
import com.flowreader.app.domain.model.PublicationDocument
import com.flowreader.app.domain.model.PublicationManifest
import com.flowreader.app.domain.model.ReadingSection
import com.flowreader.app.domain.model.SectionKind
import com.flowreader.app.domain.model.SourceRange
import com.flowreader.app.domain.model.SpineOccurrence
import com.flowreader.app.domain.model.StructuredDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * schema v3：列表/表格/注音标注必须**随内容包一起持久化**并在读回时字段完整；
 * 旧格式（schema v2 及更早，没有这三个数组）读回一律空列表、**不抛异常**。
 *
 * 与 [PublicationImageIndexTest] 同一做法：真实文件 I/O（Robolectric filesDir），
 * 不 mock 存储层。图片与标注走**同一次** JSON 读取（readDocumentMarks）。
 */
@RunWith(RobolectricTestRunner::class)
class PublicationMarksTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val publicationId = "pub-marks-0001"
    private val revision = "rev-marks-1"
    private lateinit var store: PublicationStore

    private val documentKey = "OEBPS/Text/ch1.xhtml"

    @Before
    fun setUp() {
        store = PublicationStore(context)
        cleanup()
    }

    @After
    fun tearDown() = cleanup()

    private fun cleanup() {
        File(context.filesDir, "books/$publicationId").deleteRecursively()
    }

    private fun document(body: String): StructuredDocument = EpubDocumentParser.parse(
        documentKey,
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>"""
    )

    private fun manifestFor(document: StructuredDocument): PublicationManifest = PublicationManifest(
        publicationId = publicationId,
        revision = revision,
        normalizationVersion = 1,
        parserSchemaVersion = 1,
        sourceHash = "source-hash",
        manifestRelativePath = "files/books/$publicationId/revisions/$revision",
        spineOccurrences = listOf(
            SpineOccurrence(
                occurrenceId = ReadingManifestBuilder.occurrenceId("OEBPS/content.opf", 0),
                ordinal = 0,
                documentKey = documentKey,
                linear = true,
                mediaType = "application/xhtml+xml",
                counted = true
            )
        ),
        documents = listOf(
            PublicationDocument(
                documentKey = documentKey,
                ordinal = 0,
                linear = true,
                role = DocumentRole.MAIN,
                textLengthUtf16 = document.textLengthUtf16,
                mainTextUnits = document.mainTextUnits,
                manifestRelativePath = "documents/x.json",
                sourceHash = "doc-hash"
            )
        ),
        sections = listOf(
            ReadingSection(
                sectionId = "s-0",
                ordinal = 0,
                title = "第 1 章",
                aliases = emptyList(),
                kind = SectionKind.NORMAL,
                ranges = listOf(SourceRange(documentKey, 0, document.textLengthUtf16)),
                mainTextUnits = document.mainTextUnits
            )
        ),
        tocTargetSections = emptyMap(),
        totalMainTextUnits = document.mainTextUnits,
        resources = emptyList()
    )

    private fun stageWith(body: String): PublicationStore.PackageIndex {
        val document = document(body)
        val staged = store.stage(
            manifest = manifestFor(document),
            documents = mapOf(documentKey to document),
            sourceBytes = "<epub/>".toByteArray(),
            resourceBytes = emptyMap()
        ).getOrThrow()
        store.commitFiles(staged).getOrThrow()
        return store.readPackageIndex(publicationId, revision)!!
    }

    @Test
    fun listTableAndRubyMarksArePersistedWithFullFields() {
        val body = "<ol start=\"3\"><li>甲</li><li value=\"9\">乙</li></ol>" +
            "<table><tr><th>表头</th></tr><tr><td>单元格</td></tr></table>" +
            "<p><ruby>漢<rt>かん</rt></ruby>字</p>"
        val index = stageWith(body)
        val jsonPath = index.documentJsonPaths[documentKey]
        assertNotNull(jsonPath)
        val textLength = org.json.JSONObject(File(jsonPath).readText()).getInt("textLengthUtf16")

        val marks = store.readDocumentMarks(jsonPath!!)

        // ---- lists：每个可见项一条，携带列表级信息（ordered/start/value/level）。
        assertEquals(2, marks.lists.size)
        val first = marks.lists[0]
        assertTrue(first.ordered)
        assertEquals(3, first.listStart)
        assertEquals(1, first.level)
        org.junit.Assert.assertNull(first.itemValue)
        val second = marks.lists[1]
        assertEquals(9, second.itemValue)
        assertTrue("标注偏移必须落在文档文本范围内", first.offsetUtf16 in 0 until textLength)
        assertTrue(marks.lists.zipWithNext().all { (a, b) -> a.offsetUtf16 < b.offsetUtf16 })

        // ---- tables：表头/单元格范围完整，且落在正文范围内。
        assertEquals(1, marks.tables.size)
        val table = marks.tables.single()
        assertEquals(2, table.rows.size)
        assertEquals(1, table.rows[0].size)
        assertTrue(table.rows[0].single().isHeader)
        assertTrue(table.rows[0].single().endUtf16Exclusive > table.rows[0].single().startUtf16)
        assertTrue(table.rows[0].single().endUtf16Exclusive <= textLength)
        assertTrue(table.rows[1].single().isHeader.not())

        // ---- ruby：基字范围在正文内（基字进主文本，3.2 第 6 条），注音文本只在标注里。
        assertEquals(1, marks.ruby.size)
        val ruby = marks.ruby.single()
        assertEquals("かん", ruby.annotations.single())
        assertTrue(ruby.baseEndUtf16Exclusive > ruby.baseStartUtf16)
        assertTrue(ruby.baseEndUtf16Exclusive <= textLength)
    }

    @Test
    fun jsonRoundTripKeepsListStartNullForUnorderedLists() {
        val index = stageWith("<ul><li>甲</li></ul>")
        val marks = store.readDocumentMarks(index.documentJsonPaths[documentKey]!!)

        assertEquals(1, marks.lists.size)
        assertTrue(marks.lists.single().ordered.not())
        assertEquals(null, marks.lists.single().listStart)
    }

    @Test
    fun legacyDocumentJsonWithoutMarkArraysReadsBackEmptyWithoutThrowing() {
        // schema v2 及更早的文档 JSON 没有 lists/tables/ruby —— 读回必须为空且不抛。
        val index = stageWith("<p>正文</p>")
        val jsonPath = index.documentJsonPaths[documentKey]!!
        val legacyJson = org.json.JSONObject(File(jsonPath).readText())
        legacyJson.remove("lists")
        legacyJson.remove("tables")
        legacyJson.remove("ruby")
        val legacy = legacyJson.toString()
        val legacyPath = File(jsonPath).parentFile!!.resolve("legacy.json")
        legacyPath.writeText(legacy)

        val marks = store.readDocumentMarks(legacyPath.absolutePath)

        assertEquals(emptyList<PublicationStore.DocumentListEntry>(), marks.lists)
        assertEquals(emptyList<PublicationStore.DocumentTableEntry>(), marks.tables)
        assertEquals(emptyList<PublicationStore.DocumentRubyEntry>(), marks.ruby)
        // 同一次读取仍然返回图片（合并读取，不重复打开文件）。
        assertTrue(marks.images.isEmpty())
    }

    @Test
    fun corruptedJsonReadsBackEmptyWithoutThrowing() {
        assertEquals(
            PublicationStore.DocumentMarks(),
            store.readDocumentMarks("/no/such/file.json")
        )
        val broken = File(context.cacheDir, "broken-doc.json")
        broken.writeText("{ not json ")
        assertEquals(PublicationStore.DocumentMarks(), store.readDocumentMarks(broken.absolutePath))
    }

    @Test
    fun schemaVersionIsV3SoReimportGeneratesANewRevision() {
        // 升级 schema 的全部意义：BookParser 把版本号算进 revision → 重新导入同一本书
        // 会生成新 revision（旧目录保留），既有书由此拿到标注。
        val body = "<p>正文</p>"
        val document = document(body)
        val json = store.stage(
            manifest = manifestFor(document),
            documents = mapOf(documentKey to document),
            sourceBytes = "<epub/>".toByteArray(),
            resourceBytes = emptyMap()
        ).getOrThrow().let { staged ->
            File(staged.stagingDirectory, staged.documents.single().jsonRelativePath).readText()
        }
        assertEquals(3, org.json.JSONObject(json).getInt("schemaVersion"))
        assertEquals(3, StructuredDocument.CURRENT_SCHEMA_VERSION)
    }

    @Test
    fun resolverReturnsMarksForTheSectionWithChapterCoordinates() = kotlinx.coroutines.test.runTest {
        val body = "<ol start=\"2\"><li>甲</li></ol><p><ruby>漢<rt>かん</rt></ruby></p>"
        val index = stageWith(body)
        val resolver = ChapterImageResolver(store)

        val marks = resolver.marksForSection(publicationId, revision, sectionOrdinal = 0)

        assertEquals(1, marks.lists.size)
        assertEquals(2, marks.lists.single().number)
        assertEquals(1, marks.ruby.size)
        assertTrue(marks.ruby.single().baseEndUtf16Exclusive <= body.length + 100)
        // 无标注章节返回空，不抛。
        assertEquals(ChapterMarks.EMPTY, resolver.marksForSection("no-such", revision, 0))
    }
}
