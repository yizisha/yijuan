package com.flowreader.app.data.repository

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.flowreader.app.data.local.AppDatabase
import com.flowreader.app.data.local.entity.*
import com.flowreader.app.domain.model.*
import com.flowreader.app.domain.repository.RestoreMode
import com.flowreader.app.util.epub.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompleteBackupTest {
    @Test fun seriesAndManualOrderSurviveCompleteBackupAndRestore() = runBlocking {
        val source = home(); val a = source.text("第一卷"); val b = source.text("第二卷")
        val repository = BookRepositoryImpl(source.db.bookDao())
        repository.setBooksSeries(listOf(a, b), "长篇")
        repository.reorderSeries("长篇", listOf(b, a))
        val target = home()
        val preview = target.repository.prepareImportFromFile(source.backup()).getOrThrow()
        target.repository.restorePrepared(preview.token, RestoreMode.REPLACE, false).getOrThrow()
        assertEquals(listOf("第二卷", "第一卷"), target.db.bookDao().getSeriesBooks("长篇").map { it.title })
    }

    @Test fun schema11BackupGetsSeriesDefaultsAndCannotEraseExistingMembership() = runBlocking {
        val source = home(); val pub = UUID.randomUUID().toString(); source.text("旧备份", pub)
        val original = source.backup()
        val entries = ZipFile(original).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() }.toMutableMap() }
        val rows = entries.getValue("tables/books.jsonl").toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.map {
            JSONObject(it).apply { remove("seriesName"); remove("seriesOrder") }.toString()
        }.joinToString("\n", postfix = "\n").toByteArray()
        entries["tables/books.jsonl"] = rows
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8)).put("schema", 11)
        val tables = manifest.getJSONArray("tables")
        for (i in 0 until tables.length()) {
            val row = tables.getJSONObject(i)
            if (row.getString("entry") == "tables/books.jsonl") {
                row.put("size", rows.size)
                row.put("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(rows).joinToString("") { "%02x".format(it) })
            }
        }
        entries["manifest.json"] = manifest.toString().toByteArray()
        val legacy = File(source.root, "schema11.zip")
        java.util.zip.ZipOutputStream(legacy.outputStream()).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        val fresh = home()
        val first = fresh.repository.prepareImportFromFile(legacy).getOrThrow()
        fresh.repository.restorePrepared(first.token, RestoreMode.REPLACE, false).getOrThrow()
        assertNull(fresh.books().single().seriesName)
        val target = home(); val existing = target.text("旧备份", pub)
        BookRepositoryImpl(target.db.bookDao()).setBooksSeries(listOf(existing), "现有系列")
        val preview = target.repository.prepareImportFromFile(legacy).getOrThrow()
        target.repository.restorePrepared(preview.token, RestoreMode.MERGE, false).getOrThrow()
        assertEquals("现有系列", target.books().single().seriesName)
    }

    private val homes = mutableListOf<Home>()
    private fun home(): Home = Home(ApplicationProvider.getApplicationContext()).also { homes += it }
    @After fun close() { homes.forEach { it.close() } }

    internal class Home(base: Context, grantTestTreePermissions: Boolean = false) {
        // Windows 原生 SQLite 不支持这些测试长方法名产生的 >260 字符路径。
        val root = File(System.getProperty("java.io.tmpdir"), "yb-${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        private val preferenceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val context: Context = object : ContextWrapper(base), SettingsDataStoreOwner {
            override val settingsDataStore = PreferenceDataStoreFactory.create(scope = preferenceScope) {
                File(filesDir, "datastore/settings.preferences_pb")
            }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun checkCallingOrSelfUriPermission(uri: android.net.Uri, flags: Int): Int =
                if (grantTestTreePermissions && uri.authority?.startsWith("backup.fixture.") == true) android.content.pm.PackageManager.PERMISSION_GRANTED else super.checkCallingOrSelfUriPermission(uri, flags)
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, File(root, "library.db").absolutePath).allowMainThreadQueries().build()
        val repository = BackupRepositoryImpl(context, db, db.bookDao(), db.chapterDao(), db.bookmarkDao(), db.annotationDao(), db.categoryDao(), db.readingStatsDao())
        fun close() { db.close(); preferenceScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
        fun file(name: String) = File(context.filesDir, name).apply { parentFile!!.mkdirs() }
        suspend fun text(title: String = "短书名", pub: String = UUID.randomUUID().toString(), favorite: Boolean = false): Long {
            val source = file("source-$pub.txt").apply { writeText("这是正文。$title", Charsets.UTF_8) }
            val id = db.bookDao().insertBook(BookEntity(title = title, author = "作者", filePath = source.absolutePath,
                publicationId = pub, format = "TXT", isFavorite = favorite, totalChapters = 1, currentPosition = 5, readingProgress = .2f))
            db.chapterDao().insertChapters(listOf(ChapterEntity(bookId = id, index = 0, title = "第 1 章", content = "这是正文。$title")))
            return id
        }
        suspend fun backup(): File = File(root, "backup.zip").also { repository.exportDataToFile(it).getOrThrow() }
        suspend fun books() = db.bookDao().getAllBooks().first()
    }

    private suspend fun epub(home: Home): Long {
        val pub = UUID.randomUUID().toString()
        val epub = home.file("sample.epub")
        EpubFixtureBuilder()
            .addEntry("mimetype", "application/epub+zip")
            .addEntry("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            .addEntry("OPS/package.opf", """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">test</dc:identifier><dc:title>带图书</dc:title></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="pic" href="image.png" media-type="image/png"/></manifest><spine><itemref idref="chapter"/></spine></package>""")
            .addEntry("OPS/chapter.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1 id="start">第一章</h1><p>这是测试正文，可以准确定位。</p><img src="image.png" alt="测试插图"/><p>插图后的文字。</p></body></html>""")
            .addEntry("OPS/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml#start">第一章</a></li></ol></nav></body></html>""")
            .addEntry("OPS/image.png", byteArrayOf(1, 2, 3, 4, 5))
            .writeTo(epub)
        val store = PublicationStore(home.context)
        val coordinator = PublicationImportCoordinator(store, home.db.contentIndexDao(), home.db.bookDao())
        val prepared = coordinator.prepareFromContainer(pub, "test-revision-1", epub).getOrThrow()
        val category = home.db.categoryDao().insertCategory(CategoryEntity(name = "文学"))
        val cover = home.file("cover.png").apply { writeBytes(byteArrayOf(3, 2, 1)) }
        val id = home.db.bookDao().insertBook(BookEntity(title = "带图书", author = "作者", filePath = epub.absolutePath,
            coverPath = cover.absolutePath, publicationId = pub, categoryId = category, isFavorite = true,
            totalChapters = prepared.manifest.sections.size, currentPosition = 8, readingProgress = .6f, wordCount = 28, tags = "文学,经典"))
        home.db.chapterDao().insertChapters(coordinator.projectChapters(prepared.manifest, prepared.documents, prepared.toc, id).map(ChapterEntity::fromDomain))
        assertTrue(coordinator.activate(id, prepared, prepared.toc) is PublicationImportCoordinator.Activation.Activated)
        val locator = TextLocator(publicationId = pub, revision = prepared.manifest.revision, documentKey = "OPS/chapter.xhtml", offsetUtf16 = 8)
        home.db.readerStateDao().upsertState(ReaderStateEntity(id, TextLocatorCodec.toJson(locator), "EXACT", "{\"position\":8}", TextLocatorCodec.toJson(locator), 23, 100))
        home.db.bookmarkDao().insertBookmark(BookmarkEntity(bookId = id, chapterIndex = 0, position = 8, text = "测试正文", locatorJson = TextLocatorCodec.toJson(locator), locatorQuality = "EXACT"))
        val range = RangeLocator(publicationId = pub, revision = prepared.manifest.revision, ranges = listOf(SourceRange("OPS/chapter.xhtml", 8, 12)))
        home.db.annotationDao().insertAnnotation(AnnotationEntity(bookId = id, chapterIndex = 0, startPosition = 8, endPosition = 12,
            selectedText = "测试正文", note = "阅读想法", rangeLocatorJson = RangeLocatorCodec.toJson(range), locatorQuality = "EXACT"))
        home.db.readingStatsDao().insertStats(ReadingStatsEntity(bookId = id, date = "2026-10-13", readPages = 6, readTimeSeconds = 60, visualReadMillis = 12345, ttsReadMillis = 100, qualifiedTextUnits = 40, settledTurns = 5, statsVersion = 2))
        home.db.readerStateDao().insertDelta(ReadingSessionDeltaEntity("delta-1", id, "session-1", 1, "2026-10-13", "Asia/Shanghai", 12345, 100, 40, 5, 2))
        home.context.dataStore.edit {
            it[intPreferencesKey("font_size")] = 23
            it[stringPreferencesKey("app_visual_style")] = AppVisualStyle.entries.first { it != AppVisualStyle.CLEAN_BLUE }.name
            it[intPreferencesKey("daily_reading_goal_minutes")] = 45
            it[booleanPreferencesKey("speech_enabled")] = true
            it[booleanPreferencesKey("app_lock_enabled")] = true
            it[stringPreferencesKey("app_lock_pin_hash")] = "source-secret"
        }
        return id
    }

    @Test fun fullEpubRestoresFilesImagesLocatorsAndPreferencesOnAnotherInstallationAndReopens() = runBlocking {
        val source = home()
        epub(source)
        val target = home()
        val localSettings = target.context.dataStore
        localSettings.edit { it[stringPreferencesKey("app_lock_pin_hash")] = "target-secret"; it[booleanPreferencesKey("app_lock_enabled")] = true; it[stringPreferencesKey("auto_backup_directory")] = "content://local" }
        val file = source.backup()
        val preview = target.repository.prepareImportFromFile(file).getOrThrow()
        assertEquals(1, preview.books)
        assertTrue(preview.files >= 7)
        assertTrue(preview.hasSettings)
        assertTrue(target.books().isEmpty()) // 预览不改变数据库。
        target.repository.restorePrepared(preview.token, RestoreMode.REPLACE, true).getOrThrow()
        val book = target.books().single()
        assertTrue(book.isFavorite)
        assertEquals(28, book.wordCount)
        assertEquals("文学,经典", book.tags)
        assertTrue(book.filePath.startsWith(target.context.filesDir.absolutePath))
        assertArrayEquals(File(source.books().single().filePath).readBytes(), File(book.filePath).readBytes())
        assertTrue(File(book.coverPath!!).isFile)
        val index = PublicationStore(target.context).readPackageIndex(book.publicationId!!, book.activeRevision!!)!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), File(index.resourcePaths.getValue("OPS/image.png")).readBytes())
        val documentJson = File(index.documentJsonPaths.getValue("OPS/chapter.xhtml"))
        assertEquals("OPS/chapter.xhtml", JSONObject(documentJson.readText()).getString("documentKey"))
        assertTrue(File(documentJson.parentFile, documentJson.nameWithoutExtension + ".txt").readText().contains("这是测试正文"))
        val state = target.db.readerStateDao().getState(book.id)!!
        assertEquals(23L, state.navigationSequence)
        val locator = TextLocatorCodec.fromJson(state.locatorJson!!)!!
        assertEquals(book.publicationId, locator.publicationId)
        assertEquals(8, (locator as TextLocator).offsetUtf16)
        val note = target.db.annotationDao().getAnnotationsListByBookId(book.id).single()
        assertEquals("阅读想法", note.note)
        assertEquals(book.publicationId, RangeLocatorCodec.fromJson(note.rangeLocatorJson!!)!!.publicationId)
        assertEquals(1, target.db.readerStateDao().countDeltas(book.id))
        assertEquals(12345L, target.db.readingStatsDao().getStatsByBookId(book.id).first().single().visualReadMillis)
        assertEquals("文学", target.db.categoryDao().getAllCategories().first().single().name)
        val prefs = localSettings.data.first()
        assertEquals(23, prefs[intPreferencesKey("font_size")])
        assertEquals(45, prefs[intPreferencesKey("daily_reading_goal_minutes")])
        assertEquals("target-secret", prefs[stringPreferencesKey("app_lock_pin_hash")])
        assertEquals("content://local", prefs[stringPreferencesKey("auto_backup_directory")])
        val reopened = Room.databaseBuilder(target.context, AppDatabase::class.java, File(target.root, "library.db").absolutePath).allowMainThreadQueries().build()
        try { assertEquals(book, reopened.bookDao().getBookById(book.id)); assertEquals(state, reopened.readerStateDao().getState(book.id)) } finally { reopened.close() }
    }

    @Test fun mergeRemapsIdsAndCategoriesWithoutOverwritingDifferentLocalBooks() = runBlocking {
        val source = home(); source.text("导入书", favorite = true)
        val category = source.db.categoryDao().insertCategory(CategoryEntity(id = 1, name = "导入分组"))
        source.db.openHelper.writableDatabase.execSQL("UPDATE books SET categoryId=?", arrayOf(category))
        val target = home(); target.text("原书")
        target.db.categoryDao().insertCategory(CategoryEntity(id = 1, name = "本地分组"))
        val preview = target.repository.prepareImportFromFile(source.backup()).getOrThrow()
        assertEquals(0, preview.duplicates)
        target.repository.restorePrepared(preview.token, RestoreMode.MERGE, false).getOrThrow()
        assertEquals(setOf("原书", "导入书"), target.books().map { it.title }.toSet())
        val imported = target.books().first { it.title == "导入书" }
        assertNotEquals(1L, imported.id)
        assertNotEquals(1L, imported.categoryId)
        assertTrue(imported.isFavorite)
        assertTrue(File(imported.filePath).isFile)
    }

    @Test fun repeatedMergeIsIdempotentAndKeepsLocalProgressAndFavoriteUnion() = runBlocking {
        val source = home(); epub(source)
        val file = source.backup()
        val target = home()
        target.repository.importDataFromFile(file).getOrThrow()
        val current = target.books().single()
        val storedFiles = target.context.filesDir.walkTopDown().count { it.isFile }
        target.db.bookDao().updateReadingProgress(current.id, 3, 77, .9f)
        target.db.bookDao().setFavorite(current.id, false)
        val preview = target.repository.prepareImportFromFile(file).getOrThrow()
        assertEquals(1, preview.duplicates)
        target.repository.restorePrepared(preview.token, RestoreMode.MERGE, false).getOrThrow()
        target.repository.importDataFromFile(file).getOrThrow()
        val after = target.books().single()
        assertEquals(.9f, after.readingProgress, .0001f)
        assertEquals(77, after.currentPosition)
        assertTrue(after.isFavorite)
        assertEquals(1, target.db.annotationDao().getAnnotationsListByBookId(after.id).size)
        assertEquals(1, target.db.bookmarkDao().getAllBookmarks().first().size)
        assertEquals(storedFiles, target.context.filesDir.walkTopDown().count { it.isFile })
        assertEquals(1, target.db.readerStateDao().countDeltas(after.id))
        assertEquals(12345L, target.db.readingStatsDao().getStatsByBookId(after.id).first().single().visualReadMillis)
    }

    @Test fun oldJsonHasPreviewRestoresGroupsAndCannotReplaceLibrary() = runBlocking {
        val target = home(); target.text("本地书")
        val file = File(target.root, "legacy.json")
        file.writeText(JSONObject().put("books", JSONArray().put(JSONObject().put("id", 1).put("title", "旧书").put("author", "旧作者").put("filePath", "/missing.txt").put("categoryId", 1)))
            .put("categories", JSONArray().put(JSONObject().put("id", 1).put("name", "旧分组"))).toString())
        val preview = target.repository.prepareImportFromFile(file).getOrThrow()
        assertTrue(preview.legacy)
        assertTrue(preview.warnings.any { it.contains("旧书") })
        assertTrue(target.repository.restorePrepared(preview.token, RestoreMode.REPLACE, false).isFailure)
        target.repository.restorePrepared(preview.token, RestoreMode.MERGE, false).getOrThrow()
        assertEquals(2, target.books().size)
        assertEquals("旧分组", target.db.categoryDao().getAllCategories().first().single().name)
        assertFalse(target.books().first { it.title == "旧书" }.isFavorite)
    }

    @Test fun legacyMissingFavoriteKeepsExistingFavorite() = runBlocking {
        val target = home(); target.text("旧书", favorite = true)
        val existing = target.books().single()
        val file = File(target.root, "legacy.json")
        file.writeText(JSONObject().put("books", JSONArray().put(JSONObject().put("id", 999).put("title", existing.title).put("author", existing.author).put("filePath", existing.filePath))).toString())
        target.repository.importDataFromFile(file).getOrThrow()
        assertTrue(target.books().single().isFavorite)
    }

    private fun mutate(source: File, target: File, change: (String, ByteArray) -> ByteArray?, extra: String? = null): File {
        val builder = EpubFixtureBuilder()
        ZipFile(source).use { zip -> zip.entries().asSequence().forEach { entry -> change(entry.name, zip.getInputStream(entry).readBytes())?.let { builder.addEntry(entry.name, it) } } }
        if (extra != null) builder.addEntry(extra, "unexpected")
        return builder.writeTo(target)
    }

    @Test fun changedChecksumAndMissingFilesFailBeforeAnyWrite() = runBlocking {
        val source = home(); source.text()
        val good = source.backup()
        val target = home(); target.text("本地书")
        val before = target.books()
        val changed = mutate(good, File(source.root, "changed.zip"), { name, data -> if (name == "tables/books.jsonl") "$name corrupted".toByteArray() else data })
        assertTrue(target.repository.prepareImportFromFile(changed).isFailure)
        val missing = mutate(good, File(source.root, "missing.zip"), { name, data -> if (name == "assets/0") null else data })
        assertTrue(target.repository.prepareImportFromFile(missing).isFailure)
        assertEquals(before, target.books())
    }

    @Test fun traversalDuplicateEntriesAndUndeclaredPayloadAreRejected() = runBlocking {
        val source = home(); source.text()
        val good = source.backup()
        val target = home()
        val traversal = mutate(good, File(source.root, "traversal.zip"), { _, data -> data }, "../../escaped.txt")
        assertTrue(target.repository.prepareImportFromFile(traversal).isFailure)
        val unlisted = mutate(good, File(source.root, "extra.zip"), { _, data -> data }, "assets/9999")
        assertTrue(target.repository.prepareImportFromFile(unlisted).isFailure)
        val builder = EpubFixtureBuilder().addEntry("manifest.json", "{}").addEntry("manifest.json", "{}")
        assertTrue(target.repository.prepareImportFromFile(builder.writeTo(File(source.root, "duplicate.zip"))).isFailure)
        assertFalse(File(target.context.cacheDir, "escaped.txt").exists())
        assertTrue(target.books().isEmpty())
    }

    @Test fun validChecksumButInvalidForeignKeyFailsInIsolatedDatabase() = runBlocking {
        val source = home(); epub(source)
        val good = source.backup()
        val target = home(); target.text("原书")
        val payload = ZipFile(good).use { zip -> zip.getInputStream(zip.getEntry("tables/annotations.jsonl")).readBytes() }
        val json = JSONObject(payload.toString(Charsets.UTF_8).trim()).put("bookId", 99999)
        val bad = (json.toString() + "\n").toByteArray()
        val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(bad).joinToString("") { "%02x".format(it) }
        val changed = mutate(good, File(source.root, "bad-fk.zip"), { name, data ->
            when (name) {
                "tables/annotations.jsonl" -> bad
                "manifest.json" -> JSONObject(data.toString(Charsets.UTF_8)).apply {
                    val tables = getJSONArray("tables")
                    for (i in 0 until tables.length()) if (tables.getJSONObject(i).getString("entry") == "tables/annotations.jsonl") tables.getJSONObject(i).put("sha256", checksum).put("size", bad.size)
                }.toString().toByteArray()
                else -> data
            }
        })
        assertTrue(target.repository.prepareImportFromFile(changed).isFailure)
        assertEquals("原书", target.books().single().title)
    }

    @Test fun databaseWriteFailureRollsBackReplacementAndRemovesOnlyAllocatedFiles() = runBlocking {
        val source = home(); epub(source)
        val target = home(); target.text("原书")
        val original = target.books().single()
        target.context.dataStore.edit { it[intPreferencesKey("font_size")] = 17 }
        val preview = target.repository.prepareImportFromFile(source.backup()).getOrThrow()
        target.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON annotations BEGIN SELECT RAISE(ABORT, 'injected disk failure'); END")
        assertTrue(target.repository.restorePrepared(preview.token, RestoreMode.REPLACE, true).isFailure)
        assertEquals(original, target.books().single())
        assertTrue(File(original.filePath).isFile)
        assertEquals(17, target.context.dataStore.data.first()[intPreferencesKey("font_size")])
        assertFalse(File(target.context.filesDir, "restored").walkTopDown().any { it.isFile })
    }

    @Test fun previewIsRevalidatedOnConfirmationAndDiscardRemovesOnlyItsStaging() = runBlocking {
        val source = home(); source.text()
        val target = home(); target.text("原书")
        val preview = target.repository.prepareImportFromFile(source.backup()).getOrThrow()
        File(target.context.cacheDir, "backup-staging/${preview.token}/assets/0").writeText("changed")
        assertTrue(target.repository.restorePrepared(preview.token, RestoreMode.REPLACE, false).isFailure)
        assertEquals("原书", target.books().single().title)
        target.repository.discardPrepared(preview.token)
        assertFalse(File(target.context.cacheDir, "backup-staging/${preview.token}").exists())
        assertTrue(File(target.books().single().filePath).exists())
    }

    @Test fun missingSourceFailsExportAndDoesNotOverwritePriorBackup() = runBlocking {
        val source = home(); source.text()
        val file = source.backup()
        val before = file.readBytes()
        File(source.books().single().filePath).delete()
        assertTrue(source.repository.exportDataToFile(file).isFailure)
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun emptyLibraryIsValidAndSettingsCanBeLeftUnchanged() = runBlocking {
        val source = home()
        source.context.dataStore.edit { it[intPreferencesKey("font_size")] = 25 }
        val target = home(); target.text("原书")
        target.context.dataStore.edit { it[intPreferencesKey("font_size")] = 16 }
        val preview = target.repository.prepareImportFromFile(source.backup()).getOrThrow()
        assertEquals(0, preview.books)
        target.repository.restorePrepared(preview.token, RestoreMode.REPLACE, false).getOrThrow()
        assertTrue(target.books().isEmpty())
        assertEquals(16, target.context.dataStore.data.first()[intPreferencesKey("font_size")])
    }

    @Test fun settingsWhitelistRejectsSecurityKeysAndInvalidTypes() {
        val security = JSONObject().put("app_lock_pin_hash", JSONObject().put("type", "string").put("value", "injected"))
        assertTrue(runCatching { BackupPreferences.validate(security) }.isFailure)
        val type = JSONObject().put("font_size", JSONObject().put("type", "string").put("value", "wrong"))
        assertTrue(runCatching { BackupPreferences.validate(type) }.isFailure)
    }
}
