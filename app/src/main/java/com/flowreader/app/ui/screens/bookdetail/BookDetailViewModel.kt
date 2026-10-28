package com.flowreader.app.ui.screens.bookdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowreader.app.domain.model.Annotation
import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.model.Bookmark
import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.repository.AnnotationExportFormat
import com.flowreader.app.domain.repository.AnnotationRepository
import com.flowreader.app.domain.repository.BookRepository
import com.flowreader.app.domain.repository.BookmarkRepository
import com.flowreader.app.domain.repository.ChapterRepository
import com.flowreader.app.domain.repository.ReadingStatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class BookDetailUiState(
    val book: Book? = null,
    val chapters: List<Chapter> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val annotations: List<Annotation> = emptyList(),
    val totalReadTime: Long = 0,
    val totalReadPages: Int = 0,
    /** Distinct days this book was read on. */
    val daysRead: Int = 0,
    val isLoading: Boolean = true,
    val isAnnotating: Boolean = false,
    val annotationExportText: String? = null,
    val isReparsing: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    private val chapterRepository: ChapterRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val annotationRepository: AnnotationRepository,
    private val readingStatsRepository: ReadingStatsRepository,
    private val bookParser: com.flowreader.app.util.BookParser,
    private val coverImporter: com.flowreader.app.util.CoverImporter
) : ViewModel() {

    private val bookId: Long = savedStateHandle.get<Long>("bookId") ?: 0L

    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    init {
        loadBookDetails()
        viewModelScope.launch {
            bookRepository.getAllBooks().collect { books ->
                books.firstOrNull { it.id == bookId }?.let { stored ->
                    _uiState.update { it.copy(book = it.book?.copy(isFavorite = stored.isFavorite)) }
                }
            }
        }
    }

    private var favoriteWritePending = false

    fun toggleFavorite() {
        val book = _uiState.value.book ?: return
        if (favoriteWritePending) return
        favoriteWritePending = true
        viewModelScope.launch {
            runCatching { bookRepository.setFavorite(book.id, !book.isFavorite) }
                .onSuccess { _uiState.update { it.copy(book = it.book?.copy(isFavorite = !book.isFavorite)) } }
                .onFailure { error -> _uiState.update { it.copy(message = error.message ?: "收藏保存失败") } }
            favoriteWritePending = false
        }
    }

    private fun loadBookDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                if (bookId <= 0L) {
                    _uiState.update { it.copy(isLoading = false, error = "无效的书籍 ID") }
                    return@launch
                }

                val bookDeferred = async { bookRepository.getBookById(bookId) }
                val chaptersDeferred = async { chapterRepository.getChapterMetadataList(bookId) }
                val bookmarksDeferred = async { bookmarkRepository.getBookmarksListByBookId(bookId) }
                val annotationsDeferred = async { annotationRepository.getAnnotationsListByBookId(bookId) }
                val statsDeferred = async {
                    try {
                        readingStatsRepository.getStatsByBookId(bookId).first()
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                val book = bookDeferred.await()
                if (book != null) {
                    val chapters = chaptersDeferred.await()
                    val bookmarks = bookmarksDeferred.await()
                    val annotations = annotationsDeferred.await()
                    val stats = statsDeferred.await()
                    val totalReadTime = stats.sumOf { it.readTimeSeconds }
                    val totalReadPages = stats.sumOf { it.readPages }
                    // Days on which this book was actually read, for the 掌阅-style detail page.
                    val daysRead = stats.map { it.date }.distinct().size
                    // Books imported before 字数 existed carry 0. Measure it once from the stored
                    // text (SQLite does the SUM) and keep it, so an old book still shows its size.
                    var resolvedBook = if (book.wordCount > 0) {
                        book
                    } else {
                        val measured = runCatching {
                            chapterRepository.getTotalContentLength(book.id).toInt()
                        }.getOrDefault(0)
                        if (measured > 0) {
                            book.copy(wordCount = measured).also { bookRepository.updateBook(it) }
                        } else {
                            book
                        }
                    }
                    val actualSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        java.io.File(book.filePath).takeIf { it.isFile }?.length()
                    }
                    if (actualSize != null && actualSize > 0 && actualSize != resolvedBook.fileSize) {
                        resolvedBook = resolvedBook.copy(fileSize = actualSize)
                        bookRepository.updateBook(resolvedBook)
                    }
                    _uiState.update {
                        it.copy(
                            book = resolvedBook,
                            chapters = chapters,
                            bookmarks = bookmarks,
                            annotations = annotations,
                            totalReadTime = totalReadTime,
                            totalReadPages = totalReadPages,
                            daysRead = daysRead,
                            isLoading = false
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "未找到书籍") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "加载书籍失败: ${e.localizedMessage ?: "未知错误"}") }
            }
        }
    }

    fun refreshSeries() {
        viewModelScope.launch {
            val current = bookRepository.getBookById(bookId) ?: return@launch
            _uiState.update { it.copy(book = it.book?.copy(seriesName = current.seriesName, seriesOrder = current.seriesOrder)) }
        }
    }

    fun deleteBookmark(bookmarkId: Long) {
        viewModelScope.launch {
            if (bookmarkId <= 0L) return@launch
            try {
                bookmarkRepository.deleteBookmarkById(bookmarkId)
                _uiState.update { it.copy(bookmarks = it.bookmarks.filter { b -> b.id != bookmarkId }) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "删除书签失败: ${e.localizedMessage ?: "未知错误"}") }
            }
        }
    }

    fun deleteAnnotation(annotationId: Long) {
        viewModelScope.launch {
            annotationRepository.deleteAnnotationById(annotationId)
            _uiState.update { it.copy(annotations = it.annotations.filter { a -> a.id != annotationId }) }
        }
    }

    fun updateTags(tagsText: String) {
        viewModelScope.launch {
            val book = _uiState.value.book ?: return@launch
            val tags = tagsText.split(",", "，", " ").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val updated = book.copy(tags = tags)
            bookRepository.updateBook(updated)
            _uiState.update { it.copy(book = updated) }
        }
    }

    /** Title and author are user-editable; blank values are ignored rather than written. */
    fun updateMetadata(title: String, author: String) {
        val book = _uiState.value.book ?: return
        val trimmedTitle = title.trim().ifBlank { book.title }
        val trimmedAuthor = author.trim().ifBlank { book.author }
        if (trimmedTitle == book.title && trimmedAuthor == book.author) return
        viewModelScope.launch {
            val updated = book.copy(title = trimmedTitle, author = trimmedAuthor)
            bookRepository.updateBook(updated)
            _uiState.update { it.copy(book = updated, message = "已保存") }
        }
    }

    /** Replaces the cover with a user-picked image. */
    fun updateCover(uri: android.net.Uri) {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                coverImporter.import(uri)
            }
            result.fold(
                onSuccess = { path ->
                    coverImporter.clear(book.coverPath)
                    val updated = book.copy(coverPath = path)
                    bookRepository.updateBook(updated)
                    _uiState.update { it.copy(book = updated, message = "封面已更新") }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = "封面导入失败：${error.message}") }
                }
            )
        }
    }

    /**
     * Re-runs the TXT parser (with the current 净化/分章规则) against the stored file and replaces
     * the chapter rows. Reading position resets to the start because chapter boundaries change.
     */
    fun reparseChapters() {
        val book = _uiState.value.book ?: return
        if (book.filePath.isBlank()) {
            _uiState.update { it.copy(error = "找不到原始文件") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isReparsing = true, error = null) }
            val result = runCatching {
                val parsed = bookParser.parseBook(android.net.Uri.fromFile(java.io.File(book.filePath))).getOrThrow()
                chapterRepository.deleteChaptersByBookId(book.id)
                chapterRepository.insertChapters(parsed.chapters.map { it.copy(bookId = book.id) })
                bookRepository.updateBook(
                    book.copy(
                        totalChapters = parsed.chapters.size,
                        currentChapter = 0,
                        currentPosition = 0,
                        readingProgress = 0f
                    )
                )
                parsed.chapters.size
            }
            result.fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(isReparsing = false, message = "已重新分章：$count 章") }
                    loadBookDetails()
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isReparsing = false, error = "重新解析失败：${error.message}") }
                }
            )
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun exportAnnotations(format: AnnotationExportFormat) {
        viewModelScope.launch {
            val exported = annotationRepository.exportAnnotations(bookId, format)
            _uiState.update { it.copy(annotationExportText = exported.ifBlank { "暂无可导出的标注" }) }
        }
    }

    fun clearAnnotationExport() {
        _uiState.update { it.copy(annotationExportText = null) }
    }
}
