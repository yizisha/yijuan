package com.flowreader.app.util.epub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.flowreader.app.data.local.dao.BookDao
import com.flowreader.app.data.local.dao.ContentIndexDao
import com.flowreader.app.data.local.entity.PublicationRevisionEntity
import com.flowreader.app.domain.model.TextUnits
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
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
 * S3 / Gate G3 收尾：**导入结果 golden**（主计划 14.1 FX03/FX04、14.2 T001/T032/T033/T035/T037/T048）。
 *
 * 这里跑的是**完整链路**：真实 ZIP 容器 → container.xml → OPF → nav/NCX → 结构文档 → 阅读清单
 * → 内容包落盘 → 目录/章节投影。夹具是自造 EPUB，不含任何版权样书。
 *
 * 同时钉住 4.1 第 7 步：`ContentIndexDao.activateRevision` 必须收到"指针切换"回调，
 * 且 `books.contentState` 只在同一事务里被写成 READY。
 */
@RunWith(RobolectricTestRunner::class)
class PublicationImportGoldenTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: PublicationStore
    private lateinit var contentIndexDao: ContentIndexDao
    private lateinit var bookDao: BookDao
    private lateinit var coordinator: PublicationImportCoordinator

    private val publicationId = "fx-pub-0001"
    private val bookId = 7L

    @Before
    fun setUp() {
        store = PublicationStore(context)
        contentIndexDao = mockk(relaxed = true)
        bookDao = mockk(relaxed = true)
        coordinator = PublicationImportCoordinator(store, contentIndexDao, bookDao)
        cleanup()
    }

    @After
    fun tearDown() = cleanup()

    private fun cleanup() {
        File(context.filesDir, "books/$publicationId").deleteRecursively()
        File(context.cacheDir, "epub-in").deleteRecursively()
    }

    private fun writeFixture(name: String, builder: EpubFixtureBuilder): File {
        val target = File(context.cacheDir, "fixtures/$name")
        target.parentFile?.mkdirs()
        return builder.writeTo(target)
    }

    /** revision 与 `BookParser` 用同一算法，保证两处一致。 */
    private fun revisionOf(file: File): String {
        val versionTag = buildString {
            append(com.flowreader.app.domain.model.StructuredDocument.CURRENT_NORMALIZATION_VERSION)
            append(':')
            append(com.flowreader.app.domain.model.StructuredDocument.CURRENT_SCHEMA_VERSION)
        }
        val payload = java.io.ByteArrayOutputStream()
        payload.write(file.readBytes())
        payload.write(1)
        payload.write(versionTag.toByteArray(Charsets.UTF_8))
        return store.sha256Hex(payload.toByteArray())
    }

    private fun prepare(epub: File): PublicationImportCoordinator.Prepared =
        coordinator.prepareFromContainer(
            publicationId = publicationId,
            revision = revisionOf(epub),
            containerFile = epub
        ).getOrElse { throw AssertionError("准备失败: ${it.message}", it) }

    // ================================================================== FX03：EPUB3

    /**
     * FX03：toc `<span>` 卷头、三层树、landmarks/page-list、**同一 XHTML 三个锚点**、跨 XHTML section。
     */
    private fun fx03(): File {
        val builder = EpubFixtureBuilder()
        builder.addEntry("mimetype", "application/epub+zip")
        builder.addEntry(
            "META-INF/container.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/content.opf",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="pub-id">urn:uuid:fx03</dc:identifier>
                <dc:title>夹具三</dc:title>
                <dc:creator>夹具作者</dc:creator>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="img" href="Images/fig+a.png" media-type="image/png"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>
            """.trimIndent()
        )
        // 目录：卷 span 头 + 三层树 + landmarks/page-list（不得污染主目录）+ 同文档三锚点。
        builder.addEntry(
            "OEBPS/nav.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc"><ol>
                  <li><span>第一卷 山河</span><ol>
                    <li><a href="Text/ch1.xhtml#a1">第一章</a><ol>
                      <li><a href="Text/ch1.xhtml#a2">第一节</a></li>
                    </ol></li>
                    <li><a href="Text/ch1.xhtml#a3">第二章</a></li>
                  </ol></li>
                  <li><a href="Text/ch2.xhtml">第三章</a></li>
                </ol></nav>
                <nav epub:type="landmarks"><ol><li><a epub:type="bodymatter" href="Text/ch1.xhtml">正文</a></li></ol></nav>
                <nav epub:type="page-list"><ol><li><a href="Text/ch1.xhtml#a1">1</a></li></ol></nav>
              </body>
            </html>
            """.trimIndent()
        )
        // 同一文档三个锚点 + 段落/标题/列表/表格/图片（相对路径含 `+`）。
        builder.addEntry(
            "OEBPS/Text/ch1.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>ch1</title></head>
              <body>
                <h1 id="a1">第一章</h1>
                <p>第一章正文甲甲甲</p>
                <h2 id="a2">第一节</h2>
                <p>第一节正文乙乙</p>
                <ul><li>列表一</li><li>列表二</li></ul>
                <table><tr><th>表头</th></tr><tr><td>单元格</td></tr></table>
                <h2 id="a3">第二章</h2>
                <p>第二章正文丙</p>
                <p><img src="../Images/fig+a.png" alt="插图"/></p>
              </body>
            </html>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/Text/ch2.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>ch2</title></head>
              <body><h1 id="b1">第三章</h1><p>第三章正文丁丁丁丁</p></body>
            </html>
            """.trimIndent()
        )
        builder.addEntry("OEBPS/Images/fig+a.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 7, 7))
        return writeFixture("fx03.epub", builder)
    }

    @Test
    fun fx03ImportProducesSectionsAnchorsResourcesAndChapters() {
        val epub = fx03()
        val prepared = prepare(epub)
        val manifest = prepared.manifest

        // ---- 4.3：三个同文档锚点各自成为独立 section（T035），跨文档 section 也在（T036）
        assertEquals(4, manifest.sections.size)
        assertEquals(
            listOf("第一章", "第一节", "第二章", "第三章"),
            manifest.sections.map { it.title }
        )
        // 同文档多个锚点 → 源偏移各不相同，且落在合法边界
        val ch1Offsets = manifest.sections
            .filter { it.ranges.first().documentKey == "OEBPS/Text/ch1.xhtml" }
            .map { it.ranges.first().startUtf16 }
        assertEquals(ch1Offsets.distinct().size, ch1Offsets.size)
        assertTrue(ch1Offsets.zipWithNext().all { (a, b) -> a < b })
        // 目录目标映射：三个主目录项 + 第三章
        assertEquals(4, manifest.tocTargetSections.size)

        // ---- 主线覆盖无缺失无重复（G3）
        val covered = manifest.sections.flatMap { it.ranges }
        covered.zipWithNext().forEach { (a, b) ->
            assertFalse("section 范围不得重叠", a.intersects(b))
        }
        assertEquals(manifest.sections.sumOf { it.mainTextUnits }, manifest.totalMainTextUnits)
        assertTrue(manifest.totalMainTextUnits > 0L)

        // ---- 4.6：资源落盘，文件名是 resourceId 摘要，`+` 不被改写成空格
        val resourceFile = File(prepared.files.directory, "resources")
        val resourceNames = resourceFile.listFiles()?.map { it.name }.orEmpty()
        assertEquals(1, resourceNames.size)
        val resource = manifest.resources.single()
        assertEquals("OEBPS/Images/fig+a.png", resource.containerPath)
        assertTrue(resource.containerPath.contains("+"))
        // 文件名 = SHA256(containerPath + contentHash)（4.6 第 2 条）
        val expectedId = store.resourceIdOf(
            "OEBPS/Images/fig+a.png",
            store.sha256Hex(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 7, 7))
        )
        assertEquals(expectedId, resourceNames.single())
        assertFalse("资源名不能是 basename", resourceNames.single().contains("fig"))

        // ---- 内容包结构（5.1）
        assertNotNull(store.readPackageManifest(publicationId, manifest.revision))
        assertTrue(File(prepared.files.directory, "source/original.bin").isFile)
        assertTrue(File(prepared.files.directory, "documents").listFiles()!!.isNotEmpty())

        // ---- legacy 章节投影：不含物理分片伪章节（F13），卷字段不丢（F08）
        val chapters = coordinator.projectChapters(manifest, prepared.documents, prepared.toc, bookId)
        assertEquals(4, chapters.size)
        assertEquals(listOf(0, 1, 2, 3), chapters.map { it.index })
        assertTrue("章节内容必须来自规范源文本", chapters[0].content.contains("第一章正文甲甲甲"))
        assertTrue(chapters.all { it.content.isNotBlank() })
        // 第一章/第一节/第二章 属于「第一卷 山河」，第三章不在任何卷里
        assertEquals(
            listOf("第一卷 山河", "第一卷 山河", "第一卷 山河", null),
            chapters.map { it.volume }
        )
        // 内容来自同一份源文本：章节投影的字数必须与 manifest 的主线字数一致。
        // 两边用**同一口径**（TextUnits：非空白 code point，排除 U+FFFC 与生成分隔符）核对，
        // 否则会比出"少了换行/多了对象占位符"这种与内容无关的差值。
        val projectedUnits = chapters.sumOf { chapter -> TextUnits.count(chapter.content) }
        assertEquals(manifest.totalMainTextUnits, projectedUnits)
    }

    @Test
    fun fx03ImportIsDeterministicAcrossTwoRuns() {
        val epub = fx03()
        val first = prepare(epub)
        val second = prepare(epub)

        // INV02：revision、sectionId、资源 ID、文档哈希、总字数全一致。
        assertEquals(first.manifest.revision, second.manifest.revision)
        assertEquals(first.manifest.sections.map { it.sectionId }, second.manifest.sections.map { it.sectionId })
        // 落盘资源的文件名就是 resourceId 摘要，两次必须逐字一致（INV02 + 4.6 第 2 条）。
        assertEquals(
            File(first.files.directory, "resources").listFiles()!!.map { it.name }.sorted(),
            File(second.files.directory, "resources").listFiles()!!.map { it.name }.sorted()
        )
        assertEquals(
            first.manifest.documents.map { it.sourceHash },
            second.manifest.documents.map { it.sourceHash }
        )
        assertEquals(first.manifest.totalMainTextUnits, second.manifest.totalMainTextUnits)
        // 第二次导入复用同一个 revision 目录，不覆盖活动文件（5.3 第 3 步）。
        assertTrue(second.files.reusedExisting)
    }

    @Test
    fun fx03ActivationWritesOneTransactionAndSwitchesThePointer() = runTest {
        val epub = fx03()
        val prepared = prepare(epub)

        val revisionSlot = slot<PublicationRevisionEntity>()
        val sectionsSlot = slot<List<com.flowreader.app.data.local.entity.ReadingSectionEntity>>()
        val tocSlot = slot<List<com.flowreader.app.data.local.entity.TocNodeEntity>>()
        coEvery {
            contentIndexDao.activateRevision(
                capture(revisionSlot),
                any(),
                capture(sectionsSlot),
                capture(tocSlot),
                any()
            )
        } coAnswers {
            // 模拟 DAO 在事务内调用 activate 回调。
            val activate = arg<suspend (Long, String) -> Unit>(4)
            activate(bookId, revisionSlot.captured.revision)
        }

        val activation = coordinator.activate(bookId, prepared, prepared.toc)

        assertTrue("激活应当成功: $activation", activation is PublicationImportCoordinator.Activation.Activated)
        assertEquals(PublicationRevisionEntity.STATE_READY, revisionSlot.captured.state)
        assertEquals(prepared.manifest.revision, revisionSlot.captured.revision)
        assertEquals(prepared.manifest.totalMainTextUnits, revisionSlot.captured.totalMainTextUnits)
        assertEquals(prepared.manifest.sections.size, sectionsSlot.captured.size)
        // 目录树：卷 + 三层节点；landmarks/page-list 不在主目录里（T032/T033）
        assertTrue(tocSlot.captured.size >= 5)
        assertTrue(tocSlot.captured.any { it.role == EpubNavigationParser.ROLE_VOLUME })
        // 指针切换与内容状态在回调里完成
        coVerify(exactly = 1) {
            bookDao.updateContentState(
                bookId = bookId,
                publicationId = publicationId,
                activeRevision = prepared.manifest.revision,
                contentState = com.flowreader.app.data.local.entity.BookEntity.CONTENT_STATE_READY
            )
        }
    }

    @Test
    fun activationFailureLeavesTheBookUsableAndDoesNotThrow() = runTest {
        val epub = fx03()
        val prepared = prepare(epub)
        coEvery { contentIndexDao.activateRevision(any(), any(), any(), any(), any()) } throws
            IllegalStateException("disk full")

        val activation = coordinator.activate(bookId, prepared, prepared.toc)
        assertTrue(activation is PublicationImportCoordinator.Activation.Failed)
        assertEquals(
            "CONTENT_ACTIVATION_FAILED",
            (activation as PublicationImportCoordinator.Activation.Failed).code
        )
        // 失败时不得调用指针切换（books.activeRevision 保持指向旧内容）。
        coVerify(exactly = 0) { bookDao.updateContentState(any(), any(), any(), any()) }
        // 已提交的 revision 目录仍然在（不在失败路径删数据，5.3 第 6 条）。
        assertTrue(prepared.files.directory.isDirectory)
    }

    // ================================================================== FX04：EPUB2 NCX

    private fun fx04(): File {
        val builder = EpubFixtureBuilder()
        builder.addEntry("mimetype", "application/epub+zip")
        builder.addEntry(
            "META-INF/container.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/content.opf",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="pub-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="pub-id">urn:uuid:fx04</dc:identifier>
                <dc:title>夹具四</dc:title>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c1" href="Text/one.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="c1"/></spine>
            </package>
            """.trimIndent()
        )
        // NCX：嵌套 navPoint、同文件不同 fragment、Unicode 锚点、重复 label
        builder.addEntry(
            "OEBPS/toc.ncx",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="n1"><navLabel><text>第一章</text></navLabel><content src="Text/one.xhtml#p1"/>
                  <navPoint id="n2"><navLabel><text>同名</text></navLabel><content src="Text/one.xhtml#p2"/></navPoint>
                </navPoint>
                <navPoint id="n3"><navLabel><text>同名</text></navLabel><content src="Text/one.xhtml#%E6%B3%A81"/></navPoint>
              </navMap>
            </ncx>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/Text/one.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <h1 id="p1">第一章</h1><p>正文甲甲</p>
                <h2 id="p2">同名</h2><p>正文乙</p>
                <h2 id="注1">注一节</h2><p>正文丙丙丙</p>
              </body>
            </html>
            """.trimIndent()
        )
        return writeFixture("fx04.epub", builder)
    }

    @Test
    fun fx04NcxNestedAndUnicodeFragmentsBecomeIndependentSections() {
        val prepared = prepare(fx04())
        val manifest = prepared.manifest

        // T034/T035：NCX 嵌套 + Unicode fragment 各自成为 section，标签不串子项。
        assertEquals(3, manifest.sections.size)
        assertEquals(listOf("第一章", "同名", "同名"), manifest.sections.map { it.title })
        // 同名不同位置的 sectionId 必须不同（UI key 不能用 label）
        assertEquals(3, manifest.sections.map { it.sectionId }.distinct().size)
        // 三个目标都在同一份文档里，但源偏移各不相同
        val offsets = manifest.sections.map { it.ranges.first().startUtf16 }
        assertEquals(3, offsets.distinct().size)
        assertTrue(offsets.zipWithNext().all { (a, b) -> a < b })

        // 章节投影与 section 一一对应
        val chapters = coordinator.projectChapters(manifest, prepared.documents, prepared.toc, bookId)
        assertEquals(3, chapters.size)
        assertTrue(chapters[2].content.contains("正文丙丙丙"))
        // NCX 没有卷分组 → 卷字段保持 null，不伪造卷
        assertTrue(chapters.all { it.volume == null })
    }

    @Test
    fun bothFixturesNeverProducePlaceholderImageMarkersInChapters() {
        // F10 的回归防线：新链路**不允许**再出现 `[IMG:绝对路径]`。
        listOf(fx03(), fx04()).forEach { epub ->
            val prepared = prepare(epub)
            val chapters = coordinator.projectChapters(prepared.manifest, prepared.documents, prepared.toc, bookId)
            chapters.forEach { chapter ->
                assertFalse(chapter.content.contains("[IMG:"))
                assertFalse(chapter.content.contains("/data/"))
                assertFalse(chapter.content.contains("filesDir"))
            }
        }
    }
}
