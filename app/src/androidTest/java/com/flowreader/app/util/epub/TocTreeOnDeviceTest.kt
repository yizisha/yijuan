package com.flowreader.app.util.epub

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flowreader.app.data.local.AppDatabase
import com.flowreader.app.data.local.entity.BookEntity
import com.flowreader.app.data.repository.TocRepositoryImpl
import com.flowreader.app.di.DatabaseModule
import com.flowreader.app.domain.model.TocLocation
import com.flowreader.app.domain.model.TocNodeKind
import com.flowreader.app.domain.model.TocOutline
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **真机**真实目录树验收（S8b / 主计划 4.3、7.1）。
 *
 * ## 为什么必须跑在设备上
 *
 * 桌面单测覆盖了 `TocTreeBuilder` 的纯逻辑，但**证明不了这条链路**：
 * - `toc_nodes` 是否真的以 Room v10 的 schema 建表并写入（真机开库才校验 TableInfo）；
 * - `targetJson` 经真实 SQLite 存取后是否仍能被解析（JSON 列往返、NULL 语义）；
 * - `reading_sections` 的 ordinal 映射在真实数据上是否成立。
 *
 * ## 数据安全
 *
 * 全程只操作**本测试自建**的宿主书（`filePath` 带 `device-e2e://` 前缀）与本次写入的
 * revision，`@After` 强制清理；不读、不改任何用户数据行。
 */
@RunWith(AndroidJUnit4::class)
class TocTreeOnDeviceTest {

    private lateinit var database: AppDatabase
    private lateinit var store: PublicationStore
    private lateinit var coordinator: PublicationImportCoordinator
    private lateinit var tocRepository: TocRepositoryImpl

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var hostBookId: Long = 0L
    private var hostPublicationId: String = ""
    private var writtenRevision: String? = null

    @Before
    fun setUp() {
        store = PublicationStore(context)
        database = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).addMigrations(DatabaseModule.MIGRATION_9_10).build()

        tocRepository = TocRepositoryImpl(database.contentIndexDao())
        hostPublicationId = "device-toc-${System.currentTimeMillis()}"
        runBlocking {
            hostBookId = database.bookDao().insertBook(
                BookEntity(
                    title = "目录验收宿主（用完即删）",
                    author = "device-test",
                    filePath = "device-e2e://$hostPublicationId",
                    format = "EPUB"
                )
            )
        }
        assertTrue("未能创建测试宿主书", hostBookId > 0L)
        coordinator = PublicationImportCoordinator(
            store = store,
            contentIndexDao = database.contentIndexDao(),
            bookDao = database.bookDao()
        )
    }

    @After
    fun tearDown() {
        writtenRevision?.let { revision ->
            runBlocking {
                listOf("reading_sections", "publication_documents", "toc_nodes", "publication_revisions")
                    .forEach { table ->
                        database.openHelper.writableDatabase.execSQL(
                            "DELETE FROM $table WHERE bookId = ? AND revision = ?",
                            arrayOf(hostBookId, revision)
                        )
                    }
            }
        }
        runBlocking { runCatching { database.bookDao().deleteBookById(hostBookId) } }
        runCatching { File(context.filesDir, "books/$hostPublicationId").deleteRecursively() }
        runCatching { File(context.cacheDir, "device-toc.epub").delete() }
        database.close()
    }

    /**
     * 导入夹具并返回 (revision, TocTree)。夹具的 nav 刻意包含：
     * - **无链接 `<span>` 卷头**（真实 EPUB 常见，且是卷范围的唯一依据）；
     * - **三层嵌套**（卷 → 章 → 节）；
     * - **同一 XHTML 的多个锚点**作为不同目录项（F05 的修复点）。
     */
    private fun importFixture(): Pair<String, com.flowreader.app.domain.model.TocTree> {
        val epub = File(context.cacheDir, "device-toc.epub")
        writeFixture(epub)
        assertTrue(epub.length() > 0L)

        val revision = store.sha256Hex(
            epub.readBytes() + byteArrayOf(1) +
                "${com.flowreader.app.domain.model.StructuredDocument.CURRENT_NORMALIZATION_VERSION}:".toByteArray() +
                "${com.flowreader.app.domain.model.StructuredDocument.CURRENT_SCHEMA_VERSION}".toByteArray()
        )
        val prepared = coordinator.prepareFromContainer(
            publicationId = hostPublicationId,
            revision = revision,
            containerFile = epub
        ).getOrElse { throw AssertionError("真机 prepare 失败: ${it.message}", it) }

        val activation = runBlocking { coordinator.activate(hostBookId, prepared, prepared.toc) }
        assertTrue("激活失败: $activation", activation is PublicationImportCoordinator.Activation.Activated)
        writtenRevision = revision

        val tree = runBlocking {
            tocRepository.getTocTree(hostBookId, revision, hostPublicationId)
        }
        assertNotNull("真机上必须能读回真实目录树", tree)
        return revision to tree!!
    }

    @Test
    fun realDeviceTocNodesRoundTripIntoTheTrueHierarchy() {
        val (revision, tree) = importFixture()

        // ---- 树形：卷 → 章 → 节，三层必须完整还原。
        assertEquals(2, tree.roots.size)
        val vol1 = tree.roots[0]
        assertEquals("第一卷 山河", vol1.label)
        assertEquals(TocNodeKind.GROUP, vol1.kind)
        assertTrue("role=VOLUME 必须被识别为卷", vol1.isVolume)
        assertFalse("无链接 span 卷头不得有 target", vol1.hasTarget)
        assertEquals("卷下应有 2 章", 2, vol1.children.size)

        val ch1 = vol1.children[0]
        assertEquals("第一章 风起", ch1.label)
        assertEquals("节必须挂在章下（三层结构）", 1, ch1.children.size)
        assertEquals("第一节 夜行", ch1.children[0].label)

        // ---- 同一 XHTML 的不同锚点必须是**不同**目录项（F05）。
        val ch2 = vol1.children[1]
        assertEquals("第二章 远行", ch2.label)
        assertTrue("同文档不同锚点必须各有独立 nodeId", ch1.nodeId != ch2.nodeId)
        assertTrue("同文档不同锚点必须各有独立目标", ch1.targetSectionId != ch2.targetSectionId)

        // ---- 第二个根是从第二个 XHTML 来的章。
        assertEquals("第三章 归途", tree.roots[1].label)
        assertTrue(tree.roots[1].hasTarget)

        // ---- 真实 SQLite 里确实写进去了这么多行。
        val stored = runBlocking { database.contentIndexDao().countTocNodes(hostBookId, revision) }
        assertTrue("toc_nodes 必须有内容（实得 $stored）", stored >= 5)
    }

    @Test
    fun targetSectionIdsResolveToRealChapterOrdinals() {
        // 目录项能否跳到**正确的章**，取决于 targetJson.sectionId 经 reading_sections 映射。
        val (_, tree) = importFixture()

        val readable = tree.allNodes.filter { it.hasTarget }
        assertTrue("夹具应有多条可读目录项", readable.size >= 4)
        readable.forEach { node ->
            val sectionId = node.targetSectionId!!
            val ordinal = tree.sectionOrdinalById[sectionId]
            assertNotNull("目录项 ${node.label} 的 section 必须能映射到阅读序", ordinal)
            assertTrue("阅读序必须非负", ordinal!! >= 0)
        }
        // 同一文档不同锚点映射到**不同** section，才能各自跳对位置。
        val ch1 = tree.allNodes.first { it.label == "第一章 风起" }
        val ch2 = tree.allNodes.first { it.label == "第二章 远行" }
        assertTrue(
            "同一 XHTML 的两个锚点不得映射到同一 section",
            tree.sectionOrdinalById[ch1.targetSectionId] != tree.sectionOrdinalById[ch2.targetSectionId]
        )
    }

    @Test
    fun outlineFoldingAndSearchWorkOnRealTree() {
        val (_, tree) = importFixture()

        // 收起时只看到两个根。
        assertEquals(2, TocOutline.flatten(tree, emptySet()).size)

        // 展开第一卷后能看到它的子节点（章）。
        val vol1 = tree.roots[0]
        val expanded = TocOutline.flatten(tree, setOf(vol1.nodeId))
        assertTrue(expanded.any { it.node.label == "第一章 风起" })
        assertTrue(expanded.any { it.node.label == "第二章 远行" })

        // 搜索「夜行」应命中第三节并露出完整祖先链。
        val searched = TocOutline.flatten(tree, emptySet(), query = "夜行")
        val labels = searched.map { it.node.label }
        assertTrue("命中项必须出现", labels.contains("第一节 夜行"))
        assertTrue("祖先卷必须出现", labels.contains("第一卷 山河"))
        assertTrue("祖先章必须出现", labels.contains("第一章 风起"))

        // 搜索卷标题应展示该卷全部后代。
        val volSearch = TocOutline.flatten(tree, emptySet(), query = "山河")
        assertTrue(volSearch.any { it.node.label == "第一节 夜行" })

        // 覆盖范围：卷的可读节数应为 3（两章 + 一节）。
        val coverage = TocOutline.coverageOf(tree, vol1)
        assertEquals(3, coverage.readableCount)
    }

    @Test
    fun volumeCoverageExcludesStructuralVolumeHeader() {
        val (_, tree) = importFixture()
        val vol1 = tree.roots[0]
        // 卷头本身不可读，因此不计入覆盖；只有卷下的真实正文才计。
        val coverage = TocOutline.coverageOf(tree, vol1)!!
        assertEquals(
            "结构性卷头不得被当成一个可读节",
            vol1.children.sumOf { it.children.size + 1 },
            coverage.readableCount
        )
        val current = TocOutline.currentNode(tree, TocLocation(sectionId = vol1.children[0].targetSectionId))
        assertNotNull("按 section 必须能定位回目录项", current)
        assertEquals("第一章 风起", current!!.label)
    }

    /** 与单测 `FixtureExporter` 同构的夹具（这里内联，避免 androidTest 依赖 test 源集）。 */
    private fun writeFixture(target: File) {
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
                <dc:identifier id="pub-id">urn:uuid:toc-device</dc:identifier>
                <dc:title>目录夹具</dc:title>
                <dc:creator>夹具作者</dc:creator>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/nav.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body><nav epub:type="toc"><ol>
                <li><span>第一卷 山河</span><ol>
                  <li><a href="Text/ch1.xhtml#a1">第一章 风起</a><ol>
                    <li><a href="Text/ch1.xhtml#a2">第一节 夜行</a></li>
                  </ol></li>
                  <li><a href="Text/ch1.xhtml#a3">第二章 远行</a></li>
                </ol></li>
                <li><a href="Text/ch2.xhtml">第三章 归途</a></li>
              </ol></nav></body>
            </html>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/Text/ch1.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
              <h1 id="a1">第一章 风起</h1><p>山风从谷底卷上来。</p>
              <h2 id="a2">第一节 夜行</h2><p>入夜之后路更难走。</p>
              <h2 id="a3">第二章 远行</h2><p>他终究还是走了。</p>
            </body></html>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/Text/ch2.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
              <h1>第三章 归途</h1><p>多年以后他回到这里。</p>
            </body></html>
            """.trimIndent()
        )
        builder.writeTo(target)
    }
}
