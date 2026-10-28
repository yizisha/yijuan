package com.flowreader.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.model.BookFormat
import java.util.Date

@Entity(
    tableName = "books",
    indices = [
        Index(value = ["lastReadTime", "addedTime"]),
        Index(value = ["categoryId"]),
        Index(value = ["title"]),
        Index(value = ["author"]),
        Index(value = ["seriesName"]),
        // v10：迁移里建了唯一索引 `index_books_publicationId`，实体**必须同样声明**。
        //
        // 这是实测抓到的真实崩溃：真机 logcat 报
        // `Migration didn't properly handle: books(BookEntity)`，Expected 与 Found 的差异就只有
        // 这一条索引 —— Room 的 TableInfo 校验把"迁移建了索引但实体没声明"判为 schema 不一致，
        // 于**每次开库即抛 IllegalStateException**（应用一启动就闪退）。
        // 本地单测没抓到它，是因为当时的 v9 建库脚本"只建表、不建索引"，恰好绕过了这条比对。
        Index(value = ["publicationId"], unique = true)
    ]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val isFavorite: Boolean = false,
    val seriesName: String? = null,
    @ColumnInfo(defaultValue = "0")
    val seriesOrder: Int = 0,
    val title: String,
    val author: String,
    val filePath: String,
    val coverPath: String? = null,
    val description: String = "",
    val fileSize: Long = 0,
    /** Character count of the imported text; 0 for rows imported before v0.1.3. */
    val wordCount: Int = 0,
    val format: String = "EPUB",
    val totalChapters: Int = 0,
    val currentChapter: Int = 0,
    @Deprecated("Use ReaderStateDao with TextLocator instead. Preserved for legacy schema compatibility.")
    val currentPosition: Int = 0,
    val readingProgress: Float = 0f,
    val lastReadTime: Long? = null,
    val addedTime: Long = System.currentTimeMillis(),
    val categoryId: Long? = null,
    val tags: String = "",

    // ---- v10 内容引擎（主计划 5.2）。可空以兼容中断恢复：迁移会逐行回填 publicationId，
    // 但迁移未完成的书仍允许为 null。
    /** 首次入库生成的 UUID；移动文件或重新解析都不改变。SQLite id 只是本机关联。 */
    val publicationId: String? = null,
    /** 当前激活的 revision；null 表示仍是 LEGACY 内容。 */
    val activeRevision: String? = null,
    /** LEGACY | STAGED | READY | FAILED。默认值必须与 MIGRATION_9_10 的 DEFAULT 一致。 */
    @ColumnInfo(defaultValue = "LEGACY")
    val contentState: String = CONTENT_STATE_LEGACY
) {
    fun toDomain(): Book = Book(
        id = id,
        isFavorite = isFavorite,
        seriesName = seriesName,
        seriesOrder = seriesOrder,
        title = title,
        author = author,
        filePath = filePath,
        coverPath = coverPath,
        description = description,
        fileSize = fileSize,
        wordCount = wordCount,
        format = try {
            BookFormat.valueOf(format)
        } catch (e: Exception) {
            BookFormat.UNKNOWN
        },
        totalChapters = totalChapters,
        currentChapter = currentChapter,
        currentPosition = currentPosition,
        readingProgress = readingProgress,
        lastReadTime = lastReadTime?.let { Date(it) },
        addedTime = Date(addedTime),
        categoryId = categoryId,
        tags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        // v10：内容身份与激活 revision 必须一起映射，否则每次 load→save 往返都会把
        // publicationId / activeRevision 丢掉（重导入会拿到新 UUID，旧内容包变成孤儿引用）。
        publicationId = publicationId,
        activeRevision = activeRevision,
        contentState = contentState
    )

    companion object {
        const val CONTENT_STATE_LEGACY = "LEGACY"
        const val CONTENT_STATE_STAGED = "STAGED"
        const val CONTENT_STATE_READY = "READY"
        const val CONTENT_STATE_FAILED = "FAILED"

        fun fromDomain(book: Book): BookEntity = BookEntity(
            id = book.id,
            isFavorite = book.isFavorite,
            seriesName = book.seriesName,
            seriesOrder = book.seriesOrder,
            title = book.title,
            author = book.author,
            filePath = book.filePath,
            coverPath = book.coverPath,
            description = book.description,
            fileSize = book.fileSize,
            wordCount = book.wordCount,
            format = book.format.name,
            totalChapters = book.totalChapters,
            currentChapter = book.currentChapter,
            currentPosition = book.currentPosition,
            readingProgress = book.readingProgress,
            lastReadTime = book.lastReadTime?.time,
            addedTime = book.addedTime.time,
            categoryId = book.categoryId,
            tags = book.tags.joinToString(","),
            publicationId = book.publicationId,
            activeRevision = book.activeRevision,
            contentState = book.contentState
        )
    }
}
