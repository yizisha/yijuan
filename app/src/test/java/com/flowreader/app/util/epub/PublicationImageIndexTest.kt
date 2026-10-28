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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * S5 / 主计划 4.6：结构图片必须**随内容包一起持久化**（偏移 + 资源），并在读回时能解析成本机路径。
 *
 * 为什么这是 R5 的数据层根因：规范源文本里图片只占一个 U+FFFC，如果文档 JSON 不带
 * 「哪个偏移是哪张图」，渲染层再完善也无图可画 —— 旧格式正是这样，所以新导入的 EPUB
 * 插图一直不显示。这里用**真实文件 I/O**（Robolectric 提供 filesDir）验证写入与读回。
 */
@RunWith(RobolectricTestRunner::class)
class PublicationImageIndexTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val publicationId = "pub-img-0001"
    private val revision = "rev-img-1"
    private lateinit var store: PublicationStore

    private val documentKey = "OEBPS/Text/ch1.xhtml"
    private val imageContainerPath = "OEBPS/Text/cover.jpg"

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

    /** 真实落盘一个含图的内容包，返回它的包索引。 */
    private fun stageWithImage(body: String): PublicationStore.PackageIndex {
        val document = document(body)
        val manifest = manifestFor(document)
        val staged = store.stage(
            manifest = manifest,
            documents = mapOf(documentKey to document),
            sourceBytes = "<epub/>".toByteArray(),
            resourceBytes = mapOf(imageContainerPath to byteArrayOf(1, 2, 3, 4))
        ).getOrThrow()
        store.commitFiles(staged).getOrThrow()
        return store.readPackageIndex(publicationId, revision)!!
    }

    @Test
    fun inlineImageIsPersistedWithItsOffsetAndResource() {
        val index = stageWithImage("""<p>前文<img src="cover.jpg" alt="封面插图"/>后文</p>""")

        val documentJsonPath = index.documentJsonPaths[documentKey]
        assertNotNull("文档 JSON 路径必须能从包索引取到", documentJsonPath)

        val images = store.readDocumentImages(documentJsonPath!!)
        assertEquals("行内图片必须被持久化", 1, images.size)
        val image = images.single()
        assertTrue("必须带有 U+FFFC 的偏移", image.offsetUtf16 > 0)
        assertEquals("cover.jpg", image.raw)
        assertEquals("封面插图", image.alt)
        assertTrue(
            "资源必须指向容器路径，实际 ${image.containerPath}",
            image.containerPath?.endsWith("cover.jpg") == true
        )
    }

    @Test
    fun figureWrappedImageIsFoundToo() {
        val index = stageWithImage(
            """<figure><img src="cover.jpg" alt="插图"/><figcaption>图 1</figcaption></figure>"""
        )

        val images = store.readDocumentImages(index.documentJsonPaths[documentKey]!!)

        assertEquals("figure 里包裹的图片也必须被收集（否则图注插图全丢）", 1, images.size)
    }

    @Test
    fun documentTextKeepsTheObjectMarkerAndNeverGainsAnImageMarker() {
        val document = document("""<p>前文<img src="cover.jpg" alt="x"/>后文</p>""")

        assertTrue("规范源文本里图片只占一个 U+FFFC", document.text.contains('\uFFFC'))
        assertFalse(
            "F10 回归防线：章节正文里绝不能出现 [IMG: 标记（这里也不许引入）",
            document.text.contains("[IMG:")
        )
    }

    @Test
    fun chapterIndexResolvesTheImageToARealLocalFile() {
        val index = stageWithImage("""<p>前文<img src="cover.jpg" alt="封面"/>后文</p>""")
        val ranges = index.sectionsByOrdinal.getValue(0)
        val imagesByDocument = mapOf(
            documentKey to store.readDocumentImages(index.documentJsonPaths[documentKey]!!)
                .map { DocumentImage(it.offsetUtf16, it.containerPath, it.alt) }
        )

        val refs = ChapterImageIndex.build(ranges, imagesByDocument) { path ->
            path?.let { index.resourcePaths[it] }
        }

        assertEquals(1, refs.size)
        val resolved = refs.single().absolutePath
        assertNotNull("资源必须解析成本机绝对路径", resolved)
        assertTrue("必须指向真实存在的文件: $resolved", File(resolved!!).isFile)
    }

    @Test
    fun missingPackageIndexIsNotAnError() {
        // 旧书没有内容包：必须返回 null 而不是抛异常（渲染层按"没有图"处理）。
        assertEquals(null, store.readPackageIndex("no-such-book", "no-such-revision"))
        assertEquals(emptyList<PublicationStore.DocumentImageEntry>(), store.readDocumentImages("/no/such/file.json"))
    }
}
