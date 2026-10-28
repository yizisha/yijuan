package com.flowreader.app.util.epub

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flowreader.app.data.local.AppDatabase
import com.flowreader.app.data.local.dao.BookDao
import com.flowreader.app.data.local.entity.BookEntity
import com.flowreader.app.di.DatabaseModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **真机**端到端导入验证（S3 Gate G3 / S4 前置）。
 *
 * 为什么必须跑在设备上：Robolectric 用的是自己那套 SQLite 与 schema 校验，
 * "迁移建了索引但实体没声明"这类缺陷在桌面上**全绿**，到真机开库时才抛
 * `Migration didn't properly handle`（应用一启动即闪退）。所以开库与写入必须在真机跑一遍。
 *
 * 本测试对**用户自己的数据库**只做加法：
 * - 只往 `reader_states` 写一条（`upsertState`，可覆盖，不影响书籍数据）；
 * - 不新建 books/chapters 行，不删任何行；
 * - 结束时清掉自己写的那条状态与 publicationId 记录，并删掉自己造的 revision 目录。
 */
@RunWith(AndroidJUnit4::class)
class PublicationImportOnDeviceTest {

    private lateinit var database: AppDatabase
    private lateinit var store: PublicationStore
    private lateinit var coordinator: PublicationImportCoordinator

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * **测试自己的宿主书行**（`filePath` 带 `device-e2e://` 前缀，绝不可能与真实书冲突）。
     *
     * 关于安全性的教训必须写下来：本测试最初拿"用户库里第一本书"当外键宿主，
     * 结果一次清理不彻底就把用户的一行书改成了 `activeRevision=<已删除的 revision>`。
     * 即使清理逻辑看起来正确，**也不要让测试有机会碰用户数据** ——
     * 改为自建一行、用完删除，用户数据在结构上就不可能被影响。
     */
    private var hostBookId: Long = 0L
    private var hostPublicationId: String = ""

    /** 本次测试真正写入的 revision（只有它及其索引行会被清理）。 */
    private var writtenRevision: String? = null

    @Before
    fun setUp() {
        store = PublicationStore(context)
        database = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).addMigrations(
            DatabaseModule.MIGRATION_9_10
        ).build()

        hostPublicationId = "device-e2e-${System.currentTimeMillis()}"
        runBlocking {
            // 触发真实开库（真机 schema 校验就发生在这里），并插入本测试专用的书行。
            hostBookId = database.bookDao().insertBook(
                BookEntity(
                    title = "端到端测试宿主（用完即删）",
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

    /**
     * 清理只针对**自己造的东西**：本次写入的 revision 索引行、revision 目录、宿主书行。
     * 用户原有的书、章节、书签、批注一律不动。
     */
    @After
    fun tearDown() {
        val revision = writtenRevision
        if (revision != null) {
            runBlocking {
                listOf(
                    "reading_sections", "publication_documents", "toc_nodes", "publication_revisions"
                ).forEach { table ->
                    database.openHelper.writableDatabase.execSQL(
                        "DELETE FROM $table WHERE bookId = ? AND revision = ?",
                        arrayOf(hostBookId, revision)
                    )
                }
            }
        }
        runBlocking { runCatching { database.bookDao().deleteBookById(hostBookId) } }
        runCatching { File(context.filesDir, "books/$hostPublicationId").deleteRecursively() }
        runCatching { File(context.cacheDir, "device-e2e.epub").delete() }
        database.close()
    }

    @Test
    fun realDeviceImportWritesContentPackageAndActivatesRevision() {
        // 用与单测同一份构建器产出夹具：真机验的就是同一份字节。
        val epub = File(context.cacheDir, "device-e2e.epub")
        writeFx03(epub)
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

        // ---- 清单层：同 XHTML 三锚点 → 4 个 section；主线文本量 > 0
        assertEquals(4, prepared.manifest.sections.size)
        assertTrue(prepared.manifest.totalMainTextUnits > 0L)
        // 包内引用会被完整登记：正文里的图片 + 正文里的同包链接（导航文档本身不计入正文引用）。
        assertTrue(
            "至少应登记图片资源，实际: ${prepared.manifest.resources.map { it.containerPath }}",
            prepared.manifest.resources.any { it.containerPath == "OEBPS/Images/fig+a.png" }
        )

        // ---- 文件侧：分片、资源摘要命名、manifest 都在真实 filesDir 下落盘
        val revisionDir = prepared.files.directory
        assertTrue("revision 目录必须在 files/books 下", revisionDir.isDirectory)
        assertTrue(File(revisionDir, "manifest.json").isFile)
        assertTrue(File(revisionDir, "source/original.bin").length() > 0L)
        val resourceFiles = File(revisionDir, "resources").listFiles().orEmpty()
        assertTrue("资源必须落盘", resourceFiles.isNotEmpty())
        // 落盘的 manifest.json 才是交付物：逐个资源核对 contentHash 与实际字节、以及 resourceId。
        val packageJson = org.json.JSONObject(File(revisionDir, "manifest.json").readText())
        val resourceJson = packageJson.getJSONArray("resources")
        assertTrue(resourceJson.length() > 0)
        var matched = 0
        for (index in 0 until resourceJson.length()) {
            val entry = resourceJson.getJSONObject(index)
            val resourceId = entry.getString("resourceId")
            val containerPath = entry.getString("containerPath")
            val contentHash = entry.getString("contentHash")
            val file = File(revisionDir, entry.getString("relativePath"))
            assertTrue("资源文件必须存在: $containerPath", file.isFile)
            assertEquals("文件名必须是 resourceId 摘要", resourceId, file.name)
            assertEquals("resourceId = SHA256(containerPath + contentHash)", store.resourceIdOf(containerPath, contentHash), resourceId)
            assertEquals("contentHash 必须与实际字节一致", contentHash, store.sha256Hex(file.readBytes()))
            if (containerPath == "OEBPS/Images/fig+a.png") matched++
        }
        assertEquals("图片资源必须在清单里", 1, matched)

        // ---- 数据库侧：单事务激活（真机 SQLite 上真的写进去）
        val activation = runBlocking { coordinator.activate(hostBookId, prepared, prepared.toc) }
        assertTrue("激活失败: $activation", activation is PublicationImportCoordinator.Activation.Activated)
        writtenRevision = revision

        runBlocking {
            assertEquals(1, database.contentIndexDao().countRevisions(hostBookId))
            assertEquals(2, database.contentIndexDao().countDocuments(hostBookId, revision))
            assertEquals(4, database.contentIndexDao().countSections(hostBookId, revision))
            assertTrue(database.contentIndexDao().countTocNodes(hostBookId, revision) >= 5)
            // 分母来自完整索引（INV11）：sections 之和 == revision 的总量
            assertEquals(
                prepared.manifest.totalMainTextUnits,
                database.contentIndexDao().sumSectionUnits(hostBookId, revision)
            )
            // 指针切换：books.activeRevision / contentState 已就绪
            val book = database.bookDao().getBookById(hostBookId)
            assertNotNull(book)
            assertEquals(revision, book!!.activeRevision)
            assertEquals(hostPublicationId, book.publicationId)
            assertEquals(BookEntity.CONTENT_STATE_READY, book.contentState)
        }

        // ---- legacy 章节投影：卷字段在线，且不含物理分片伪章节
        val chapters = coordinator.projectChapters(
            prepared.manifest,
            prepared.documents,
            prepared.toc,
            hostBookId
        )
        assertEquals(4, chapters.size)
        assertEquals(listOf(0, 1, 2, 3), chapters.map { it.index })
        assertEquals(3, chapters.count { it.volume == "第一卷 山河" })
        chapters.forEach { chapter ->
            assertTrue(chapter.content.isNotBlank())
            assertTrue("章节不得再出现 [IMG: 标记", !chapter.content.contains("[IMG:"))
        }
        epub.delete()
    }

    /** 与单测 `FixtureExporter` 同一构造（这里内联，避免 androidTest 依赖 test 源集）。 */
    private fun writeFx03(target: File) {
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
                <dc:identifier id="pub-id">urn:uuid:fx03-device</dc:identifier>
                <dc:title>夹具三 山河初见</dc:title>
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
              <h1 id="a1">第一章 风起</h1><p>山风从谷底卷上来，<strong>带着潮湿的凉意</strong>。</p>
              <h2 id="a2">第一节 夜行</h2><p>入夜之后路更难走，好在<em>月色还算明亮</em>。</p>
              <ul><li>带上水囊</li><li>检查绳索</li></ul>
              <table><tr><th>时辰</th><th>行程</th></tr><tr><td>子时</td><td>过溪</td></tr></table>
              <h2 id="a3">第二章 远行</h2><p>他终究还是走了，留下一盏未灭的灯。</p>
              <figure><img src="../Images/fig+a.png" alt="山谷夜景"/><figcaption>图一 山谷夜景</figcaption></figure>
            </body></html>
            """.trimIndent()
        )
        builder.addEntry(
            "OEBPS/Text/ch2.xhtml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
              <h1 id="b1">第三章 归途</h1>
              <p>多年以后他回到这里，<a href="ch1.xhtml#a2">那一夜的路</a>已经长满了草。</p>
            </body></html>
            """.trimIndent()
        )
        builder.addEntry("OEBPS/Images/fig+a.png", MINIMAL_PNG)
        builder.writeTo(target)
    }

    private companion object {
        val MINIMAL_PNG: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
            0x42, 0x60, 0x82.toByte()
        )
    }
}
