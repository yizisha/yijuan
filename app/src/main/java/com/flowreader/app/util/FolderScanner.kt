package com.flowreader.app.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports new books from a folder the user granted via `ACTION_OPEN_DOCUMENT_TREE`.
 *
 * The grant is persisted, so the scan survives restarts without asking again. Everything goes
 * through [BookImporter], which is idempotent — re-scanning an unchanged folder imports nothing and
 * only reports skipped files, so the automatic scan on launch is cheap.
 *
 * Bounded on purpose: a depth cap and a file cap keep a pathological tree (a mounted archive, a
 * recursive link farm) from turning a launch into a hang.
 */
@Singleton
class FolderScanner @Inject constructor(
    @ApplicationContext context: Context,
    private val importer: BookImporter
) {
    private val appContext = context.applicationContext

    data class ScanResult(val imported: Int, val skipped: Int, val failed: Int)

    fun supported(fileName: String): Boolean =
        SUPPORTED_EXTENSIONS.any { fileName.endsWith(it, ignoreCase = true) }

    suspend fun scan(treeUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: return@withContext ScanResult(0, 0, 0)

        var imported = 0
        var skipped = 0
        var failed = 0
        var visited = 0

        val queue = ArrayDeque<Pair<DocumentFile, Int>>()
        queue.addLast(root to 0)

        while (queue.isNotEmpty() && visited < MAX_FILES) {
            val (directory, depth) = queue.removeFirst()
            val children = runCatching { directory.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (visited >= MAX_FILES) break
                visited++
                when {
                    child.isDirectory && depth < MAX_DEPTH -> queue.addLast(child to depth + 1)
                    child.isFile && supported(child.name.orEmpty()) -> {
                        val uri = child.uri
                        importer.importSingle(uri)
                            .onSuccess { outcome -> if (outcome.isNew) imported++ else skipped++ }
                            .onFailure { failed++ }
                    }

                    else -> Unit
                }
            }
        }
        ScanResult(imported = imported, skipped = skipped, failed = failed)
    }

    private companion object {
        const val MAX_DEPTH = 6
        const val MAX_FILES = 2_000
        val SUPPORTED_EXTENSIONS = listOf(
            ".epub", ".txt", ".md", ".pdf", ".fb2", ".mobi", ".azw3", ".azw",
            ".cbz", ".zip", ".jpg", ".jpeg", ".png", ".webp"
        )
    }
}
