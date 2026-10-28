package com.flowreader.app.domain.model

import java.util.Date

data class Book(
    val id: Long = 0,
    val title: String,
    val author: String,
    val filePath: String,
    val coverPath: String? = null,
    val description: String = "",
    val fileSize: Long = 0,
    /** Character count of the imported text, measured once at import time. */
    val wordCount: Int = 0,
    val format: BookFormat = BookFormat.EPUB,
    val totalChapters: Int = 0,
    val currentChapter: Int = 0,
    @Deprecated("Use ReaderStateDao with TextLocator instead. Preserved for legacy schema compatibility.")
    val currentPosition: Int = 0,
    val readingProgress: Float = 0f,
    val lastReadTime: Date? = null,
    val addedTime: Date = Date(),
    val categoryId: Long? = null,
    val tags: List<String> = emptyList(),
    /**
     * 首次入库生成的 UUID（主计划 3.1）：移动文件或重新解析都不改变，SQLite `id` 只是本机关联。
     * null 表示该行仍是迁移未完成的 legacy 书。
     */
    val publicationId: String? = null,
    /** 当前激活的内容 revision；null 表示内容仍是 LEGACY。 */
    val activeRevision: String? = null,
    /**
     * 内容状态：`LEGACY` / `STAGED` / `READY` / `FAILED`（主计划 5.2）。
     *
     * UI 用它显示"正在准备内容 / 内容已就绪"，所以必须随 domain 往返；
     * 只在实体里保存会让每次 load→save 都把 READY 退回 LEGACY（实测抓到的缺陷）。
     */
    val contentState: String = CONTENT_STATE_LEGACY,
    val isFavorite: Boolean = false,
    val seriesName: String? = null,
    val seriesOrder: Int = 0
) {
    companion object {
        const val CONTENT_STATE_LEGACY = "LEGACY"
        const val CONTENT_STATE_STAGED = "STAGED"
        const val CONTENT_STATE_READY = "READY"
        const val CONTENT_STATE_FAILED = "FAILED"
    }
}

/**
 * [FB2] and [MOBI] are read-only imports added in v53: both are converted to chapters at import
 * time like EPUB, and neither involves DRM — an encrypted MOBI is rejected, not decrypted.
 * [COMIC] covers single image files and image-only ZIP/CBZ packs, added in v54.
 */
enum class BookFormat {
    EPUB,
    TXT,
    PDF,
    MARKDOWN,
    FB2,
    MOBI,
    COMIC,
    UNKNOWN
}

data class Chapter(
    val id: Long = 0,
    val bookId: Long,
    val index: Int,
    val title: String,
    val content: String = "",
    val startPosition: Int = 0,
    val endPosition: Int = 0,
    /**
     * Volume ("分卷") this chapter belongs to, or null when the book has none. Kept as its own
     * field rather than folded into [title] so the table of contents can group chapters and the
     * reader never shows a volume heading as if it were a readable chapter.
     */
    val volume: String? = null
)

data class Category(
    val id: Long = 0,
    val name: String,
    val bookCount: Int = 0
)
