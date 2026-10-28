package com.flowreader.app.domain.repository

import android.net.Uri

/**
 * 备份和恢复仓库接口
 * 负责书籍、书签、阅读进度等数据的导出和导入
 */
interface BackupRepository {
    /** 校验并缓存待恢复内容；不写入当前书库。 */
    suspend fun prepareImport(uri: Uri): Result<BackupPreview>
    suspend fun prepareImportFromFile(file: java.io.File): Result<BackupPreview>
    suspend fun restorePrepared(token: String, mode: RestoreMode, restoreSettings: Boolean): Result<ImportResult>
    suspend fun discardPrepared(token: String)

    /**
     * 导出数据到指定 URI
     * @param uri 目标文件 URI
     * @return 导出结果
     */
    suspend fun exportData(uri: Uri): Result<Unit>

    /**
     * 从指定 URI 导入数据
     * @param uri 源文件 URI
     * @return 导入结果，包含导入的书籍和书签数量
     */
    suspend fun importData(uri: Uri): Result<ImportResult>

    /** LAN transfer variants (v55): same payload, straight to a file instead of a SAF URI. */
    suspend fun exportDataToFile(file: java.io.File): Result<Unit>
    suspend fun importDataFromFile(file: java.io.File): Result<ImportResult>

    /**
     * 导出单本书的阅读进度
     * @param bookId 书籍 ID
     * @return JSON 格式的阅读进度数据
     */
    suspend fun exportReadingProgress(bookId: Long): Result<String>
}

data class ImportResult(
    val booksImported: Int,
    val bookmarksImported: Int,
    val annotationsImported: Int = 0,
    val booksMerged: Int = 0,
    val warnings: List<String> = emptyList()
)

enum class RestoreMode { MERGE, REPLACE }

data class BackupPreview(
    val token: String,
    val createdAt: Long,
    val books: Int,
    val bookmarks: Int,
    val annotations: Int,
    val categories: Int,
    val files: Int,
    val duplicates: Int,
    val hasSettings: Boolean,
    val legacy: Boolean,
    val warnings: List<String> = emptyList()
)
