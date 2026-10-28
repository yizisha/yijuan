package com.flowreader.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.flowreader.app.data.local.AppDatabase
import com.flowreader.app.data.local.dao.AnnotationDao
import com.flowreader.app.data.local.dao.BookDao
import com.flowreader.app.data.local.dao.BookmarkDao
import com.flowreader.app.data.local.dao.CategoryDao
import com.flowreader.app.data.local.dao.ChapterDao
import com.flowreader.app.data.local.dao.ReadingStatsDao
import com.flowreader.app.data.local.entity.AnnotationEntity
import com.flowreader.app.data.local.entity.BookEntity
import com.flowreader.app.data.local.entity.BookmarkEntity
import com.flowreader.app.data.local.entity.CategoryEntity
import com.flowreader.app.domain.repository.BackupRepository
import com.flowreader.app.domain.repository.ImportResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class BackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val annotationDao: AnnotationDao,
    private val categoryDao: CategoryDao,
    private val readingStatsDao: ReadingStatsDao
) : BackupRepository {
    private val archive = BackupArchive(context, database)
    private val operation = kotlinx.coroutines.sync.Mutex()

    private suspend fun <T> safely(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        operation.lock()
        try {
            Result.success(block())
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Result.failure(error)
        } finally {
            operation.unlock()
        }
    }

    override suspend fun exportData(uri: Uri): Result<Unit> = safely {
        val temporary = java.io.File(context.cacheDir, "manual-backup-${java.util.UUID.randomUUID()}.zip")
        try {
            temporary.outputStream().use { archive.export(it) }
            val expected = BackupArchive.digest(temporary)
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法创建备份文件")
            temporary.inputStream().use { input ->
                output.use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                }
            }
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            val input = context.contentResolver.openInputStream(uri) ?: error("文件已写入，但无法回读校验")
            input.use { stream ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= BackupArchive.MAX_BYTES)
                    hash.update(buffer, 0, count)
                }
            }
            require(hash.digest().joinToString("") { "%02x".format(it) } == expected) { "备份文件回读校验失败" }
        } finally {
            temporary.delete()
        }
    }

    override suspend fun exportDataToFile(file: java.io.File): Result<Unit> = safely {
        val temporary = java.io.File(file.parentFile, "${file.name}.partial-${java.util.UUID.randomUUID()}")
        try {
            temporary.outputStream().use { archive.export(it) }
            // 先完整写好再替换目标；失败时旧文件仍在。
            java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            Unit
        } finally {
            temporary.delete()
        }
    }

    override suspend fun prepareImport(uri: Uri): Result<com.flowreader.app.domain.repository.BackupPreview> = safely {
        val input = context.contentResolver.openInputStream(uri) ?: error("无法打开备份文件")
        input.use { archive.prepare(it, ::prepareLegacy) }
    }

    override suspend fun prepareImportFromFile(file: java.io.File): Result<com.flowreader.app.domain.repository.BackupPreview> = safely {
        file.inputStream().use { archive.prepare(it, ::prepareLegacy) }
    }

    override suspend fun restorePrepared(
        token: String,
        mode: com.flowreader.app.domain.repository.RestoreMode,
        restoreSettings: Boolean
    ): Result<ImportResult> = safely { archive.restore(token, mode, restoreSettings) }

    override suspend fun discardPrepared(token: String) {
        safely { archive.discard(token) }
    }

    override suspend fun importData(uri: Uri): Result<ImportResult> {
        val preview = prepareImport(uri).getOrElse { return Result.failure(it) }
        return restorePrepared(preview.token, com.flowreader.app.domain.repository.RestoreMode.MERGE, false)
            .also { if (it.isFailure) discardPrepared(preview.token) }
    }

    override suspend fun importDataFromFile(file: java.io.File): Result<ImportResult> {
        val preview = prepareImportFromFile(file).getOrElse { return Result.failure(it) }
        return restorePrepared(preview.token, com.flowreader.app.domain.repository.RestoreMode.MERGE, false)
            .also { if (it.isFailure) discardPrepared(preview.token) }
    }

    private suspend fun prepareLegacy(stage: java.io.File, text: String) {
        val json = JSONObject(text)
        require(json.has("books") && json.get("books") is JSONArray) { "不是一卷备份 JSON（书架导出不能作为完整备份恢复）" }
        val tempFile = java.io.File(stage, "legacy.db")
        val temp = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, tempFile.absolutePath).build()
        val warnings = mutableListOf("旧 JSON 只包含记录，不含正文、封面、完整定位或设置；缺失文件需要重新导入。")
        try {
            temp.withTransaction {
                val categories = json.optJSONArray("categories") ?: JSONArray()
                val categoryIds = mutableSetOf<Long>()
                for (i in 0 until categories.length()) {
                    val row = categories.getJSONObject(i)
                    val id = row.getLong("id")
                    temp.categoryDao().insertCategory(CategoryEntity(id, row.getString("name")))
                    categoryIds += id
                }
                val books = json.getJSONArray("books")
                for (i in 0 until books.length()) {
                    val source = books.getJSONObject(i)
                    val book = BookEntity.fromJson(source)
                    temp.bookDao().insertBook(book.copy(categoryId = book.categoryId?.takeIf { it in categoryIds }))
                    if (!java.io.File(book.filePath).isFile) warnings += "正文文件不可访问：${book.title}"
                }
                val bookmarks = json.optJSONArray("bookmarks") ?: JSONArray()
                for (i in 0 until bookmarks.length()) temp.bookmarkDao().insertBookmark(BookmarkEntity.fromJson(bookmarks.getJSONObject(i)))
                val annotations = json.optJSONArray("annotations") ?: JSONArray()
                for (i in 0 until annotations.length()) temp.annotationDao().insertAnnotation(
                    AnnotationEntity.fromJson(annotations.getJSONObject(i))
                )
            }
            archive.legacyManifest(stage, temp, warnings)
        } finally {
            temp.close()
            listOf("", "-wal", "-shm", "-journal").forEach { java.io.File(tempFile.absolutePath + it).delete() }
        }
    }

    override suspend fun exportReadingProgress(bookId: Long): Result<String> = withContext(Dispatchers.IO) {
        try {
            val book = bookDao.getBookById(bookId)
                ?: return@withContext Result.failure(Exception("书籍不存在"))

            val json = JSONObject().apply {
                put("title", book.title)
                put("author", book.author)
                put("currentChapter", book.currentChapter)
                put("currentPosition", book.currentPosition)
                put("readingProgress", book.readingProgress)
                put("lastReadTime", book.lastReadTime)
            }

            Result.success(json.toString(2))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

private fun BookEntity.Companion.fromJson(json: JSONObject): BookEntity = BookEntity(
    id = json.getLong("id"),
    isFavorite = json.optBoolean("isFavorite", false),
    seriesName = json.optString("seriesName").takeIf { it.isNotBlank() && it != "null" },
    seriesOrder = json.optInt("seriesOrder", 0).coerceAtLeast(0),
    title = json.getString("title"),
    author = json.getString("author"),
    filePath = json.optString("filePath", ""),
    coverPath = json.optString("coverPath").takeIf { it.isNotBlank() && it != "null" },
    description = json.optString("description", ""),
    fileSize = json.optLong("fileSize", 0),
    format = json.optString("format", "TXT"),
    totalChapters = json.optInt("totalChapters", 0),
    currentChapter = json.optInt("currentChapter", 0),
    currentPosition = json.optInt("currentPosition", 0),
    readingProgress = json.optDouble("readingProgress", 0.0).toFloat(),
    lastReadTime = if (json.has("lastReadTime") && !json.isNull("lastReadTime")) json.getLong("lastReadTime") else null,
    addedTime = json.optLong("addedTime", System.currentTimeMillis()),
    categoryId = if (json.has("categoryId") && !json.isNull("categoryId")) json.getLong("categoryId") else null
)

private fun BookmarkEntity.Companion.fromJson(json: JSONObject): BookmarkEntity = BookmarkEntity(
    id = json.getLong("id"),
    bookId = json.getLong("bookId"),
    chapterIndex = json.getInt("chapterIndex"),
    position = json.getInt("position"),
    text = json.getString("text"),
    createdTime = json.optLong("createdTime", System.currentTimeMillis())
)

private fun AnnotationEntity.Companion.fromJson(json: JSONObject): AnnotationEntity = AnnotationEntity(
    id = json.getLong("id"),
    bookId = json.getLong("bookId"),
    chapterIndex = json.getInt("chapterIndex"),
    startPosition = json.getInt("startPosition"),
    endPosition = json.getInt("endPosition"),
    selectedText = json.getString("selectedText"),
    note = json.optString("note", ""),
    color = json.optString("color", "YELLOW"),
    type = json.optString("type", "HIGHLIGHT"),
    createdTime = json.optLong("createdTime", System.currentTimeMillis()),
    modifiedTime = json.optLong("modifiedTime", System.currentTimeMillis())
)
