package com.flowreader.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class BookFileSources(val original: Uri? = null, val savedCopy: Uri? = null)

/** Device-local sources and grants must never be confused with portable, private reading copies. */
@Singleton
class BookFileLocationStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val preferences = context.getSharedPreferences("book_file_sources", Context.MODE_PRIVATE)

    fun sources(readingPath: String): BookFileSources = BookFileSources(
        preferences.getString("original:$readingPath", null)?.let(Uri::parse),
        preferences.getString("copy:$readingPath", null)?.let(Uri::parse)
    )

    fun recordOriginal(readingPath: String, uri: Uri) {
        // ZIP imports may point at a temporary extracted file in our private directory.
        // That is not the user's original, and must not replace a previously known source.
        if (!isPrivateLocation(uri)) record("original", readingPath, uri)
    }
    fun recordSavedCopy(readingPath: String, uri: Uri) = record("copy", readingPath, uri)

    private fun record(kind: String, readingPath: String, uri: Uri) {
        require(readingPath.isNotBlank() && uri.scheme in listOf("content", "file"))
        // Some share/scanner providers offer only a transient grant. Recording the location must
        // not make an otherwise successful import fail just because persistence is unsupported.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (!preferences.edit().putString("$kind:$readingPath", uri.toString()).commit()) {
            throw IOException("Unable to save book file source")
        }
    }

    fun displayLocation(uri: Uri): String = localPath(uri) ?: sharedAppRelativePath(uri)?.let { "内部存储/$it" } ?: uri.toString()

    /** 聊天应用的 FileProvider 只授予文件本身，不提供可浏览的父目录。 */
    private fun sharedAppRelativePath(uri: Uri): String? {
        if (uri.scheme != "content") return null
        val path = uri.path ?: return null
        if (!path.startsWith("/media/Android/data/")) return null
        val relative = path.removePrefix("/media/")
        val packageName = relative.removePrefix("Android/data/").substringBefore('/')
        if (uri.authority != "$packageName.provider" || relative.split('/').any { it == ".." || it == "." }) return null
        return relative
    }

    fun directoryUnavailableReason(uri: Uri): String? {
        if (isPrivateLocation(uri)) return "阅读副本位于应用私有目录，文件管理器不能进入。"
        val relative = sharedAppRelativePath(uri)
        val path = localPath(uri)
        if (relative != null || path?.contains("/Android/data/") == true) {
            return "该原始文件位于聊天应用的 Android/data 目录，当前共享授权只允许读取文件，不能进入父目录。可保存副本到下载或书籍目录后直接定位。"
        }
        if (parentDocumentUri(uri) == null) return "文件提供方没有提供可定位的父目录。可通过“关联原始文件”重新选择本地文件，或保存副本到可访问目录。"
        return null
    }

    fun canOpenDirectory(uri: Uri): Boolean = directoryUnavailableReason(uri) == null

    fun fileName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: localPath(uri)?.let { File(it).name } ?: uri.lastPathSegment.orEmpty()

    /** Only map the official external-storage provider. Other providers may be virtual/cloud files. */
    internal fun localPath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "")
        if (volume != "primary" || relative.split('/').any { it == ".." }) return null
        return File(Environment.getExternalStorageDirectory(), relative).absolutePath
    }

    internal fun isPrivateLocation(uri: Uri): Boolean = localPath(uri)?.let { path ->
        val root = File(context.applicationInfo.dataDir).canonicalPath
        val target = File(path).canonicalPath
        File(target).toPath().startsWith(File(root).toPath())
    } ?: false

    /** 初始位置必须是可浏览的父目录，FileProvider 的文件 URI 不能作为目录。 */
    fun browserIntent(uri: Uri): Intent {
        require(!isPrivateLocation(uri)) { "Private reading copies are not accessible in file managers" }
        val initial = parentDocumentUri(uri) ?: error("This provider does not expose a parent directory")
        require(directoryUnavailableReason(uri) == null) { "This directory cannot be browsed with the file grant" }
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        }
    }

    private fun parentDocumentUri(uri: Uri): Uri? {
        val document = if (uri.scheme == "file") {
            val path = File(requireNotNull(uri.path)).canonicalFile
            val root = Environment.getExternalStorageDirectory().canonicalFile
            if (path == root || !path.toPath().startsWith(root.toPath())) return null
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:${path.relativeTo(root).invariantSeparatorsPath}"
            )
        } else {
            if (uri.scheme != "content" || uri.authority != "com.android.externalstorage.documents") return null
            uri
        }
        val id = runCatching { DocumentsContract.getDocumentId(document) }.getOrNull() ?: return null
        val colon = id.indexOf(':')
        if (colon < 0) return null
        val relative = id.substring(colon + 1)
        if (relative.split('/').any { it == ".." }) return null
        val parentId = id.substring(0, colon + 1) + relative.substringBeforeLast('/', "")
        return DocumentsContract.buildDocumentUri(document.authority!!, parentId)
    }

    fun directoryIntent(uri: Uri): Intent? {
        if (!canOpenDirectory(uri)) return null
        val parent = parentDocumentUri(uri) ?: return null
        return Intent(Intent.ACTION_VIEW).apply { setDataAndType(parent, DocumentsContract.Document.MIME_TYPE_DIR) }
    }

    fun saveReadingCopy(readingPath: String, destination: Uri) {
        val output = context.contentResolver.openOutputStream(destination, "wt") ?: throw IOException("Unable to open destination")
        output.use { stream -> File(readingPath).inputStream().use { it.copyTo(stream) } }
        recordSavedCopy(readingPath, destination)
    }
}
