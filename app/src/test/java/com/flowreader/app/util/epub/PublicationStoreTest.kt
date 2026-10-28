package com.flowreader.app.util.epub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.flowreader.app.domain.model.PublicationManifest
import com.flowreader.app.domain.model.StructuredDocument
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * S3 / 主计划 5.1（内容包与分片）、5.3（staged 协议）、4.6 第 2 条（resourceId）验收
 * （T086、T087、T088 的分片与落盘部分，以及 INV02）。
 *
 * Robolectric 提供真实的 `filesDir`，所以这里跑的是**真实文件 I/O**，
 * 不是 mock 出来的路径字符串。
 */
@RunWith(RobolectricTestRunner::class)
class PublicationStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: PublicationStore

    private val publicationId = "pub-0000-1111"
    private val revision = "rev-1"

    @Before
    fun setUp() {
        store = PublicationStore(context)
        cleanup()
    }

    @After
    fun tearDown() {
        cleanup()
    }

    private fun cleanup() {
        File(context.filesDir, "books/$publicationId").deleteRecursively()
    }

    // ------------------------------------------------------------ 夹具

    private fun document(body: String, key: String = "OEBPS/Text/ch1.xhtml"): StructuredDocument =
        EpubDocumentParser.parse(
            key,
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>
            """.trimIndent()
        )

    private fun manifest(documentKeys: List<String>, sections: Int = documentKeys.size): PublicationManifest {
        val occurrences = documentKeys.mapIndexed { index, key ->
            com.flowreader.app.domain.model.SpineOccurrence(
                occurrenceId = ReadingManifestBuilder.occurrenceId("OEBPS/content.opf", index),
                ordinal = index,
                documentKey = key,
                linear = true,
                mediaType = "application/xhtml+xml",
                counted = true
            )
        }
        val readingSections = documentKeys.take(sections).mapIndexed { index, key ->
            com.flowreader.app.domain.model.ReadingSection(
                sectionId = "s-$index",
                ordinal = index,
                title = "第 ${index + 1} 章",
                aliases = emptyList(),
                kind = com.flowreader.app.domain.model.SectionKind.NORMAL,
                ranges = listOf(com.flowreader.app.domain.model.SourceRange(key, 0, 10)),
                mainTextUnits = 10L
            )
        }
        return PublicationManifest(
            publicationId = publicationId,
            revision = revision,
            normalizationVersion = 1,
            parserSchemaVersion = 1,
            sourceHash = "source-hash",
            manifestRelativePath = "files/books/$publicationId/revisions/$revision",
            spineOccurrences = occurrences,
            documents = documentKeys.mapIndexed { index, key ->
                com.flowreader.app.domain.model.PublicationDocument(
                    documentKey = key,
                    ordinal = index,
                    linear = true,
                    role = com.flowreader.app.domain.model.DocumentRole.MAIN,
                    textLengthUtf16 = 10,
                    mainTextUnits = 10L,
                    manifestRelativePath = "documents/x.json",
                    sourceHash = "doc-hash"
                )
            },
            sections = readingSections,
            tocTargetSections = emptyMap(),
            totalMainTextUnits = readingSections.sumOf { it.mainTextUnits },
            resources = emptyList()
        )
    }

    // ------------------------------------------------------------ 分片（5.1）

    @Test
    fun chunkingPrefersBlockBoundaries() {
        val parsed = document("<p>${"甲".repeat(10)}</p><p>${"乙".repeat(10)}</p>")
        // 目标 12：第一个块只有 10 单位，加上块间 LF 共 11，仍装得下第二个块的一部分。
        val chunks = store.chunkDocument(parsed, targetUnits = 12)
        assertTrue(chunks.size >= 2)
        // 分片必须连续覆盖全文且不重叠。
        assertEquals(0, chunks.first().startUtf16)
        assertEquals(parsed.textLengthUtf16, chunks.last().endUtf16Exclusive)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.endUtf16Exclusive, b.startUtf16) }
        assertTrue(chunks.all { it.lengthUtf16 <= 12 })
    }

    @Test
    fun chunkingNeverSplitsSurrogatePairs() {
        // 全部是 emoji（每个 2 个 UTF-16 单位），目标 5 会落在代理对中间。
        val parsed = document("<p>${"😀".repeat(20)}</p>")
        val chunks = store.chunkDocument(parsed, targetUnits = 5)
        chunks.forEach { chunk ->
            val boundaryOk = isBoundary(parsed.text, chunk.startUtf16) && isBoundary(parsed.text, chunk.endUtf16Exclusive)
            assertTrue("分片切在代理对中间: $chunk", boundaryOk)
        }
        assertTrue(chunks.all { it.lengthUtf16 <= 5 })
    }

    @Test
    fun oversizedSingleBlockKeepsTheSameBlockIdAcrossItsChunks() {
        val parsed = document("<p>${"甲".repeat(100)}</p>")
        val chunks = store.chunkDocument(parsed, targetUnits = 30)
        assertTrue(chunks.size >= 3)
        val blockIds = chunks.mapNotNull { it.blockId }.distinct()
        assertEquals("同一超长块的所有分片必须共享 blockId", 1, blockIds.size)
        assertTrue(chunks.all { it.checksumSha256.isNotEmpty() })
    }

    @Test
    fun emptyDocumentHasNoChunks() {
        val parsed = EpubDocumentParser.parse("k", "<html><body></body></html>")
        assertEquals(0, parsed.textLengthUtf16)
        assertTrue(store.chunkDocument(parsed, 100).isEmpty())
    }

    @Test
    fun chunksCarryDeterministicChecksums() {
        val parsed = document("<p>${"甲".repeat(50)}</p>")
        val first = store.chunkDocument(parsed, 20)
        val second = store.chunkDocument(parsed, 20)
        // INV02：同一输入重复分片必须完全一致（含 checksum）。
        assertEquals(first, second)
    }

    // ------------------------------------------------------------ resourceId（4.6 第 2 条）

    @Test
    fun resourceIdIsSha256OfPathAndContentHash() {
        val contentHash = store.sha256Hex("bytes".toByteArray(StandardCharsets.UTF_8))
        val id = store.resourceIdOf("OEBPS/Images/a.png", contentHash)
        assertEquals(64, id.length)
        // 同路径同内容 → 同 ID；任一变化 → 不同 ID。
        assertEquals(id, store.resourceIdOf("OEBPS/Images/a.png", contentHash))
        assertTrue(id != store.resourceIdOf("OEBPS/Images/b.png", contentHash))
        assertTrue(id != store.resourceIdOf("OEBPS/Images/a.png", store.sha256Hex(byteArrayOf(1))))
        // 大小写敏感：资源键与 ZIP 条目名一致（4.2 第 6 条）。
        assertTrue(id != store.resourceIdOf("oebps/images/a.png", contentHash))
    }

    @Test
    fun documentFileNameIsADigestNotTheRawPath() {
        val name = store.fileNameFor("OEBPS/Text/../../etc/passwd")
        assertEquals(32, name.length)
        assertFalse(name.contains("/"))
        assertFalse(name.contains(".."))
        assertTrue(name.all { it in "0123456789abcdef" })
    }

    // ------------------------------------------------------------ staged 协议（5.3）

    @Test
    fun stageWritesTheContentPackageIncludingAResourceNamedByItsDigest() {
        val parsed = document("<p>甲乙丙<img src=\"../Images/a.png\"/></p>")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val staged = store.stage(
            manifest = manifest(listOf(parsed.documentKey)),
            documents = mapOf(parsed.documentKey to parsed),
            sourceBytes = "original-epub-bytes".toByteArray(StandardCharsets.UTF_8),
            resourceBytes = mapOf("OEBPS/Images/a.png" to png)
        ).getOrThrow()

        val staging = staged.stagingDirectory
        assertTrue(staging.isDirectory)
        assertTrue(File(staging, "manifest.json").isFile)
        assertTrue(File(staging, "source/original.bin").isFile)
        assertTrue(File(staging, "documents").isDirectory)
        assertTrue(File(staging, "resources").isDirectory)

        // 资源文件名 = resourceId 摘要，不含书名/basename/timestamp（4.6 第 2 条）。
        val resource = staged.resources.single()
        assertEquals("OEBPS/Images/a.png", resource.containerPath)
        assertEquals(store.resourceIdOf(resource.containerPath, resource.contentHash), resource.resourceId)
        val resourceFile = File(staging, resource.relativePath)
        assertTrue(resourceFile.isFile)
        assertArrayEquals(png, resourceFile.readBytes())
        assertFalse(resourceFile.name.contains("a.png"))

        // 文档分片带了 checksum，且文本按 UTF-8 落地。
        val document = staged.documents.single()
        assertTrue(document.chunks.isNotEmpty())
        assertTrue(document.textSha256.isNotEmpty())
        val textFile = File(staging, document.textRelativePath)
        assertEquals(parsed.text, textFile.readText(StandardCharsets.UTF_8))

        // manifest.json 是确定性 JSON（键顺序固定 → 同一输入两次 stage 必须逐字节一致）。
        val first = File(staging, "manifest.json").readText(StandardCharsets.UTF_8)
        val again = store.stage(
            manifest = manifest(listOf(parsed.documentKey)),
            documents = mapOf(parsed.documentKey to parsed),
            sourceBytes = "original-epub-bytes".toByteArray(StandardCharsets.UTF_8),
            resourceBytes = mapOf("OEBPS/Images/a.png" to png)
        ).getOrThrow()
        assertEquals(first, File(again.stagingDirectory, "manifest.json").readText(StandardCharsets.UTF_8))
    }

    @Test
    fun commitRenamesStagingIntoTheImmutableRevisionDirectory() {
        val parsed = document("<p>甲</p>")
        val staged = store.stage(
            manifest = manifest(listOf(parsed.documentKey)),
            documents = mapOf(parsed.documentKey to parsed),
            sourceBytes = byteArrayOf(1, 2, 3),
            resourceBytes = emptyMap()
        ).getOrThrow()

        val committed = store.commitFiles(staged).getOrThrow()
        assertFalse("staging 必须已经不在", staged.stagingDirectory.exists())
        assertTrue(committed.directory.isDirectory)
        assertEquals(store.revisionRoot(publicationId, revision).absolutePath, committed.directory.absolutePath)
        assertFalse(committed.reusedExisting)
        assertNotNull(store.readPackageManifest(publicationId, revision))
    }

    @Test
    fun committingTheSameRevisionReusesItInsteadOfOverwritingActiveFiles() {
        val parsed = document("<p>甲</p>")
        fun stageOnce() = store.stage(
            manifest = manifest(listOf(parsed.documentKey)),
            documents = mapOf(parsed.documentKey to parsed),
            sourceBytes = byteArrayOf(9),
            resourceBytes = emptyMap()
        ).getOrThrow()

        val first = store.commitFiles(stageOnce()).getOrThrow()
        assertFalse(first.reusedExisting)
        val marker = File(first.directory, "marker.txt").apply { writeText("keep-me") }

        val second = store.commitFiles(stageOnce()).getOrThrow()
        assertTrue("已存在的 revision 必须复用，不能覆盖活动文件", second.reusedExisting)
        assertEquals("keep-me", marker.readText())
    }

    @Test
    fun discardingStagingNeverTouchesAnExistingRevision() {
        val parsed = document("<p>甲</p>")
        val committed = store.commitFiles(
            store.stage(
                manifest = manifest(listOf(parsed.documentKey)),
                documents = mapOf(parsed.documentKey to parsed),
                sourceBytes = byteArrayOf(9),
                resourceBytes = emptyMap()
            ).getOrThrow()
        ).getOrThrow()

        // 再写一次 staging 然后丢弃（模拟失败路径，5.3 第 6 条）。
        val staged = store.stage(
            manifest = manifest(listOf(parsed.documentKey)),
            documents = mapOf(parsed.documentKey to parsed),
            sourceBytes = byteArrayOf(7),
            resourceBytes = emptyMap()
        ).getOrThrow()
        assertTrue(staged.stagingDirectory.exists())
        store.discardStaging(publicationId, revision)
        assertFalse(staged.stagingDirectory.exists())
        // 已有 revision 与它的内容必须完好。
        assertTrue(committed.directory.isDirectory)
        assertNotNull(store.readPackageManifest(publicationId, revision))
    }

    @Test
    fun garbageCollectionRemovesOnlyUnreferencedStagingDirectories() {
        val parsed = document("<p>甲</p>")
        val committed = store.commitFiles(
            store.stage(
                manifest = manifest(listOf(parsed.documentKey)),
                documents = mapOf(parsed.documentKey to parsed),
                sourceBytes = byteArrayOf(1),
                resourceBytes = emptyMap()
            ).getOrThrow()
        ).getOrThrow()

        // 造两个："一个是被引用 revision 的 staging 名，一个是孤儿。
        File(store.stagingRoot(publicationId, revision), "source").mkdirs()
        File(store.stagingRoot(publicationId, "rev-orphan"), "source").mkdirs()

        val removed = store.collectGarbage(publicationId, keepRevisions = setOf(revision))
        assertEquals(listOf("staging/rev-orphan"), removed)
        assertTrue("被引用的 staging 不能删", store.stagingRoot(publicationId, revision).exists())
        assertFalse(store.stagingRoot(publicationId, "rev-orphan").exists())
        assertTrue("revision 目录永远不动", committed.directory.isDirectory)
    }

    @Test
    fun documentJsonCarriesBlocksAnchorsChunksAndWeightIndex() {
        val parsed = EpubDocumentParser.parse(
            "OEBPS/Text/ch1.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body><h1 id="h1">标题</h1><p>正文</p><aside id="fn"><p>注释</p></aside></body>
            </html>
            """.trimIndent()
        )
        val staged = store.stage(
            manifest = manifest(listOf(parsed.documentKey)),
            documents = mapOf(parsed.documentKey to parsed),
            sourceBytes = byteArrayOf(1),
            resourceBytes = emptyMap()
        ).getOrThrow()

        val json = File(staged.stagingDirectory, staged.documents.single().jsonRelativePath)
            .readText(StandardCharsets.UTF_8)
        val root = org.json.JSONObject(json)
        // 用常量而不是字面量：结构块模型升级（例如 v2 增加 images）不应顺手改测试。
        assertEquals(
            com.flowreader.app.domain.model.StructuredDocument.CURRENT_SCHEMA_VERSION,
            root.getInt("schemaVersion")
        )
        assertEquals(parsed.textLengthUtf16, root.getInt("textLengthUtf16"))
        assertEquals(parsed.mainTextUnits, root.getLong("mainTextUnits"))
        // 锚点带源偏移，且偏移在合法范围内（8.1 + INV04）。
        val anchor = root.getJSONObject("anchors").getJSONObject("h1")
        assertEquals(0, anchor.getInt("offsetUtf16"))
        assertTrue(anchor.getString("blockId").isNotEmpty())
        assertTrue(root.getJSONArray("blocks").length() >= 3)
        assertTrue(root.getJSONArray("chunks").length() >= 1)
        // weightIndex 只含 MAIN 块：标题 + 正文 = 2，注释不进。
        assertEquals(2, root.getJSONArray("weightIndex").length())
    }

    private fun isBoundary(text: String, offset: Int): Boolean {
        if (offset <= 0 || offset >= text.length) return true
        return !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))
    }
}
