package com.flowreader.app.util

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import com.flowreader.app.data.repository.CompleteBackupTest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutomaticBackupTest {
    private lateinit var home: CompleteBackupTest.Home
    private lateinit var controller: AutomaticBackup
    private lateinit var provider: LocalDocuments
    private lateinit var tree: Uri
    @Before fun setup() = runBlocking {
        // Robolectric 默认墙钟接近 epoch 0；使用有效日期验证“从未成功”的哨兵与每日间隔。
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofDays(20000))
        val base = ApplicationProvider.getApplicationContext<Context>()
        home = CompleteBackupTest.Home(base, grantTestTreePermissions = true)
        home.text()
        controller = AutomaticBackup(home.context, home.repository)
        val authority = "backup.fixture." + UUID.randomUUID().toString().take(8)
        provider = LocalDocuments(File(home.root, "saf").apply { mkdirs() })
        provider.attachInfo(base, ProviderInfo().apply { this.authority = authority; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val document = androidx.documentfile.provider.DocumentFile.fromTreeUri(home.context, tree)!!
        assertTrue("type=${document.type}, permission=${home.context.checkCallingOrSelfUriPermission(document.uri, 2)}, logs=${org.robolectric.shadows.ShadowLog.getLogs()}", document.isDirectory && document.canWrite())
    }
    @After fun close() { home.close() }

    @Test fun disabledByDefaultAndDirectoryAndKeepSurviveControllerRecreation() = runBlocking {
        assertFalse(controller.state.first().enabled)
        controller.chooseDirectory(tree)
        controller.setKeep(5)
        controller.setEnabled(true)
        val reopened = AutomaticBackup(home.context, home.repository)
        assertEquals(tree.toString(), reopened.state.first().directory)
        assertEquals(5, reopened.state.first().keep)
        assertTrue(reopened.state.first().enabled)
        val scheduler = home.context.getSystemService(JobScheduler::class.java)
        assertEquals(AutomaticBackup.INTERVAL, scheduler.getPendingJob(AutomaticBackup.JOB_ID)!!.intervalMillis)
        reopened.setEnabled(false)
        assertNull(scheduler.getPendingJob(AutomaticBackup.JOB_ID))
    }

    @Test fun backupIsReadBackBeforeSuccessAndRetentionNeverDeletesOtherFiles() = runBlocking {
        controller.chooseDirectory(tree)
        val folder = File(provider.root, AutomaticBackup.FOLDER).apply { mkdirs() }
        File(folder, "手动备份.zip").writeText("keep manual")
        File(folder, "其他文档.txt").writeText("keep other")
        repeat(5) { controller.run().getOrThrow() }
        val automatic = folder.listFiles()!!.filter { it.name.matches(AutomaticBackup.FILE_PATTERN) }
        assertEquals(3, automatic.size)
        assertEquals("keep manual", File(folder, "手动备份.zip").readText())
        assertEquals("keep other", File(folder, "其他文档.txt").readText())
        assertTrue(controller.state.first().lastSuccess > 0)
        assertFalse(controller.state.first().running)
        assertNull(controller.state.first().error)
        assertEquals(1, home.repository.prepareImportFromFile(automatic.first()).getOrThrow().books)
        assertTrue(folder.listFiles()!!.none { it.name.endsWith(".partial") })
    }

    @Test fun failedWriteDoesNotAdvanceSuccessTimeOrPruneExistingBackups() = runBlocking {
        controller.chooseDirectory(tree)
        controller.run().getOrThrow()
        val successful = controller.state.first().lastSuccess
        provider.rejectWrites = true
        assertTrue(controller.run().isFailure)
        assertEquals(successful, controller.state.first().lastSuccess)
        assertNotNull(controller.state.first().error)
        assertFalse(controller.state.first().running)
        val folder = File(provider.root, AutomaticBackup.FOLDER)
        assertEquals(1, folder.listFiles()!!.count { it.name.matches(AutomaticBackup.FILE_PATTERN) })
        assertTrue(folder.listFiles()!!.none { it.name.endsWith(".partial") })
    }

    @Test fun dueOnlyDoesNothingWhenDisabledAndSkipsRecentSuccess() = runBlocking {
        controller.chooseDirectory(tree)
        controller.run(dueOnly = true).getOrThrow()
        assertEquals(0, provider.root.listFiles()!!.size)
        controller.setEnabled(true)
        assertTrue("before=${controller.state.first()}", controller.state.first().enabled)
        controller.run(dueOnly = true).getOrThrow()
        val success = controller.state.first().lastSuccess
        assertTrue("after=${controller.state.first()}, files=${provider.root.listFiles()?.map { it.name }}", success > 0)
        controller.run(dueOnly = true).getOrThrow()
        assertEquals(success, controller.state.first().lastSuccess)
        assertEquals(1, File(provider.root, AutomaticBackup.FOLDER).listFiles()!!.size)
    }

    class LocalDocuments(val root: File) : android.content.ContentProvider() {
        var rejectWrites = false
        override fun onCreate() = true
        // Robolectric 的 ContentResolver 直接调用旧 query 签名，DocumentsProvider 的 final
        // 旧入口会抛异常。这里模拟 SAF 协议，不弱化应用的目录权限检查。
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            if (uri.path?.endsWith("/children") == true) queryChildDocuments(DocumentsContract.getDocumentId(uri), projection, sortOrder)
            else queryDocument(DocumentsContract.getDocumentId(uri), projection)
        override fun getType(uri: Uri): String = if (file(DocumentsContract.getDocumentId(uri)).isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = throw UnsupportedOperationException()
        override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openDocument(DocumentsContract.getDocumentId(uri), mode, null)
        override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle {
            val input = requireNotNull(extras)
            val uri = requireNotNull(input.getParcelable<Uri>("uri"))
            val documentId = DocumentsContract.getDocumentId(uri)
            return android.os.Bundle().apply {
                when (method) {
                    "android:createDocument" -> putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri,
                        createDocument(documentId, input.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)!!, input.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)!!)))
                    "android:renameDocument" -> putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri,
                        renameDocument(documentId, input.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)!!)))
                    "android:deleteDocument" -> deleteDocument(documentId)
                    else -> error("Unsupported test SAF call: $method")
                }
            }
        }
        private fun file(id: String) = if (id == "root") root else File(root, id.removePrefix("root/")).also { require(it.canonicalPath.startsWith(root.canonicalPath + File.separator)) }
        private fun id(file: File) = if (file == root) "root" else "root/" + file.relativeTo(root).invariantSeparatorsPath
        private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        private fun row(cursor: MatrixCursor, file: File) {
            val builder = cursor.newRow()
            cursor.columnNames.forEach { column -> builder.add(when (column) {
                columns[0] -> id(file)
                columns[1] -> file.name
                columns[2] -> if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                columns[3] -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                columns[4] -> file.length()
                columns[5] -> file.lastModified()
                else -> null
            }) }
        }
        fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID)).apply { addRow(arrayOf("root", "root")) }
        fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: columns).apply { val file = file(documentId); if (file.exists()) row(this, file) }
        fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(projection ?: columns).apply { file(parentDocumentId).listFiles()?.forEach { row(this, it) } }
        fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            require(!displayName.contains('/') && !displayName.contains('\\'))
            val target = File(file(parentDocumentId), displayName)
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) check(target.mkdir()) else check(target.createNewFile())
            return id(target)
        }
        fun renameDocument(documentId: String, displayName: String): String {
            val old = file(documentId)
            val target = File(old.parentFile, displayName)
            check(old.renameTo(target))
            return id(target)
        }
        fun deleteDocument(documentId: String) { check(file(documentId).delete()) }
        fun openDocument(documentId: String, mode: String, signal: android.os.CancellationSignal?): ParcelFileDescriptor {
            if (rejectWrites && mode.contains('w')) throw java.io.FileNotFoundException("injected write failure")
            return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
        }
    }
}
