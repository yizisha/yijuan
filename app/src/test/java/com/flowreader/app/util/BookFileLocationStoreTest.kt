package com.flowreader.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookFileLocationStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = BookFileLocationStore(context)
    private val readingPath = File(context.filesDir, "books/山与生活.txt").path
    private val original = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/山与生活.txt")

    // Android Uri.fromFile running on a Windows host adds a slash before the drive letter.
    // Supply the path a real Android file URI returns, using the host File for IO assertions.
    private fun fileUri(file: File): Uri = io.mockk.mockk<Uri> {
        io.mockk.every { scheme } returns "file"
        io.mockk.every { path } returns file.path
    }

    @Before fun clearSources() {
        context.getSharedPreferences("book_file_sources", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun sourcePersistsAndSavedCopyNeverReplacesOriginalOrReadingBytes() {
        val file = File(readingPath).apply {
            parentFile!!.mkdirs()
            writeText("reading bytes")
        }
        store.recordOriginal(readingPath, original)
        val saved = Uri.parse("content://local-provider/document/copy")
        store.recordSavedCopy(readingPath, saved)
        val reopened = BookFileLocationStore(context).sources(readingPath)
        assertEquals(original, reopened.original)
        assertEquals(saved, reopened.savedCopy)
        assertEquals("reading bytes", file.readText())
        assertEquals(BookFileSources(), store.sources("different-reading-file"))
    }

    @Test fun oldBooksHaveNoInventedSourceAndPrivateExtractionCannotOverwriteOriginal() {
        assertNull(store.sources(readingPath).original)
        store.recordOriginal(readingPath, original)
        store.recordOriginal(readingPath, fileUri(File(context.cacheDir, "extracted.txt")))
        assertEquals(original, store.sources(readingPath).original)
    }

    @Test fun externalDocumentMapsOnlyPrimaryStorageAndParentFolder() {
        assertEquals(File(Environment.getExternalStorageDirectory(), "Download/山与生活.txt").path, store.displayLocation(original))
        val direct = store.directoryIntent(original)!!
        assertEquals(Intent.ACTION_VIEW, direct.action)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, direct.type)
        assertEquals("primary:Download", DocumentsContract.getDocumentId(direct.data!!))
        assertEquals(0, direct.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val sd = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "ABCD:书籍/test.txt")
        assertEquals(sd.toString(), store.displayLocation(sd))
    }

    @Test fun browserUsesDocumentUriAsInitialLocationWithoutExposingPrivatePaths() {
        val intent = store.browserIntent(original)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE))
        assertEquals("primary:Download", DocumentsContract.getDocumentId(intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)!!))
        val fileUri = fileUri(File(Environment.getExternalStorageDirectory(), "Download/book.txt"))
        val initial = store.browserIntent(fileUri).getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)!!
        assertEquals("content", initial.scheme)
        assertEquals("primary:Download", DocumentsContract.getDocumentId(initial))
        assertNull(store.directoryIntent(fileUri(File(readingPath))))
        assertTrue(runCatching { store.browserIntent(fileUri(File(readingPath))) }.isFailure)
    }

    @Test fun virtualProvidersAndMalformedPathsRemainUris() {
        val virtual = Uri.parse("content://cloud.example/document/book")
        assertEquals(virtual.toString(), store.displayLocation(virtual))
        assertNull(store.directoryIntent(virtual))
        val traversal = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:../secret")
        assertEquals(traversal.toString(), store.displayLocation(traversal))
        assertNull(store.directoryIntent(traversal))
        assertTrue(runCatching { store.browserIntent(Uri.parse("https://example.com/book")) }.isFailure)
    }

    @Test fun sharedAndroidDataFileIsDecodedAndNeverPretendsToExposeAFolder() {
        val shared = Uri.parse("content://nu.gpu.nagram.provider/media/Android/data/nu.gpu.nagram/files/Telegram/Telegram%20Files/%E4%B9%A6%E7%B1%8D.txt")
        assertEquals("内部存储/Android/data/nu.gpu.nagram/files/Telegram/Telegram Files/书籍.txt", store.displayLocation(shared))
        assertFalse(store.canOpenDirectory(shared))
        assertTrue(store.directoryUnavailableReason(shared)!!.contains("Android/data"))
        assertNull(store.directoryIntent(shared))
        assertTrue(runCatching { store.browserIntent(shared) }.isFailure)
    }

    @Test fun savingCopyCopiesBytesAndRecordsDestinationOnlyAfterSuccess() {
        val source = File(readingPath).apply {
            parentFile!!.mkdirs()
            writeText("原始阅读内容")
        }
        store.recordOriginal(readingPath, original)
        val target = Uri.parse("content://local-provider/document/export")
        val output = ByteArrayOutputStream()
        Shadows.shadowOf(context.contentResolver).registerOutputStream(target, output)
        store.saveReadingCopy(readingPath, target)
        assertEquals("原始阅读内容", output.toString("UTF-8"))
        assertEquals("原始阅读内容", source.readText())
        assertEquals(original, store.sources(readingPath).original)
        assertEquals(target, store.sources(readingPath).savedCopy)
        val failedTarget = Uri.parse("content://local-provider/document/failed")
        Shadows.shadowOf(context.contentResolver).registerOutputStream(failedTarget, ByteArrayOutputStream())
        assertTrue(runCatching { store.saveReadingCopy("missing-file", failedTarget) }.isFailure)
        assertEquals(target, store.sources(readingPath).savedCopy)
    }

    @Test fun importerRecordsTheOriginalOnlyAfterTheBookHasBeenSaved() = kotlinx.coroutines.runBlocking {
        val books = io.mockk.mockk<com.flowreader.app.domain.repository.BookRepository>()
        val chapters = io.mockk.mockk<com.flowreader.app.domain.repository.ChapterRepository>(relaxed = true)
        val coordinator = io.mockk.mockk<com.flowreader.app.util.epub.PublicationImportCoordinator>(relaxed = true)
        io.mockk.coEvery { books.getBookByPath(readingPath) } returns null
        val settings = io.mockk.mockk<com.flowreader.app.domain.repository.SettingsRepository>()
        io.mockk.every { settings.appSettings } returns kotlinx.coroutines.flow.flowOf(com.flowreader.app.domain.model.AppSettings())
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(original) {
            java.io.ByteArrayInputStream("第1章 山与生活\n山间有薄雾升起。".toByteArray())
        }
        val parser = BookParser(context, settings, coordinator)
        io.mockk.coEvery { books.insertBook(any()) } returns 13L
        val outcome = BookImporter(parser, books, chapters, coordinator, store).importSingle(original).getOrThrow()
        assertEquals(13L, outcome.bookId)
        assertEquals(original, store.sources(readingPath).original)
        io.mockk.coVerify { books.insertBook(match { it.filePath == readingPath }) }
    }

    @Test fun unsupportedSourcePersistenceCannotTurnASuccessfulImportIntoAFailure() = kotlinx.coroutines.runBlocking {
        val books = io.mockk.mockk<com.flowreader.app.domain.repository.BookRepository>()
        val chapters = io.mockk.mockk<com.flowreader.app.domain.repository.ChapterRepository>(relaxed = true)
        val coordinator = io.mockk.mockk<com.flowreader.app.util.epub.PublicationImportCoordinator>(relaxed = true)
        val unavailable = io.mockk.mockk<BookFileLocationStore>()
        io.mockk.coEvery { books.getBookByPath(readingPath) } returns null
        val settings = io.mockk.mockk<com.flowreader.app.domain.repository.SettingsRepository>()
        io.mockk.every { settings.appSettings } returns kotlinx.coroutines.flow.flowOf(com.flowreader.app.domain.model.AppSettings())
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(original) {
            java.io.ByteArrayInputStream("第1章 山与生活\n山间有薄雾升起。".toByteArray())
        }
        val parser = BookParser(context, settings, coordinator)
        io.mockk.coEvery { books.insertBook(any()) } returns 13L
        io.mockk.every { unavailable.recordOriginal(any(), any()) } throws java.io.IOException("source index unavailable")
        assertTrue(BookImporter(parser, books, chapters, coordinator, unavailable).importSingle(original).isSuccess)
    }
}
