package com.flowreader.app.ui.screens.library

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.model.BookFormat
import com.flowreader.app.domain.model.Category
import com.flowreader.app.domain.model.LibraryCoverSize
import com.flowreader.app.domain.model.LibraryViewMode
import com.flowreader.app.domain.model.ReadingList
import com.flowreader.app.domain.repository.BookRepository
import com.flowreader.app.domain.repository.CategoryRepository
import com.flowreader.app.domain.repository.ChapterRepository
import com.flowreader.app.domain.repository.ReadingListRepository
import com.flowreader.app.domain.repository.SettingsRepository
import com.flowreader.app.util.BookImporter
import com.flowreader.app.util.BookParser
import com.flowreader.app.util.FolderScanner
import com.flowreader.app.util.ZipImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class SortOrder {
    ADDED_TIME,
    LAST_READ,
    TITLE,
    AUTHOR,

    /** Reading progress, most advanced first — useful to find what to finish. */
    PROGRESS,

    /** Largest file first, for spotting the big imports. */
    FILE_SIZE
}

/** Reading-state filter for the shelf (v0.0.7). */
enum class ReadingFilter {
    ALL,
    UNREAD,
    READING,
    FINISHED,
    FAVORITES;

    /** A book counts as finished at 99% or more, matching the reader's own progress readout. */
    fun matches(book: Book): Boolean = when (this) {
        ALL -> true
        UNREAD -> book.lastReadTime == null
        READING -> book.lastReadTime != null && book.readingProgress < FINISHED_THRESHOLD
        FINISHED -> book.readingProgress >= FINISHED_THRESHOLD
        FAVORITES -> book.isFavorite
    }

    companion object {
        const val FINISHED_THRESHOLD = 0.99f
    }
}

/**
 * Localizable feedback. The ViewModel reports *what* happened; `LibraryScreen` decides how to say
 * it, so batch results survive the v53 in-app language switch instead of being frozen Chinese.
 *
 * `@Immutable` because a sealed interface leaves the compiler no choice but to defer to runtime,
 * which left `LibraryUiState.message` as the one field blocking the whole state class from being
 * stable. Every implementation below holds only an Int or a String.
 */
@Immutable
sealed interface LibraryMessage {
    data class Deleted(val count: Int) : LibraryMessage
    data class Moved(val count: Int) : LibraryMessage
    data class Updated(val count: Int) : LibraryMessage
    data class AddedToList(val listName: String) : LibraryMessage
    data class ArchiveImported(val count: Int) : LibraryMessage

    /** Books picked up by the folder scanner since the last scan. */
    data class Scanned(val count: Int) : LibraryMessage

    /**
     * 单本导入成功。
     *
     * 存在理由：导入期间书架**没有任何可见反馈**（LibraryScreen 只在书架为空时显示加载态），
     * 大书导入耗时较长时用户会以为「点了没反应」。成功提示同时也是「书去哪儿了」的回答。
     */
    data class Imported(val title: String) : LibraryMessage
}

data class LibraryUiState(
    val books: List<Book> = emptyList(),
    val seriesEntries: List<com.flowreader.app.domain.model.SeriesShelfEntry> = emptyList(),
    /** Every book in the database, regardless of the active group — the "add existing" picker. */
    val allBooks: List<Book> = emptyList(),
    val recentlyRead: List<Book> = emptyList(),
    val categories: List<Category> = emptyList(),
    val readingLists: List<ReadingList> = emptyList(),
    val selectedCategoryId: Long? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val message: LibraryMessage? = null,
    val sortOrder: SortOrder = SortOrder.ADDED_TIME,
    val readingFilter: ReadingFilter = ReadingFilter.ALL,
    val selectedBookIds: Set<Long> = emptySet(),
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    /** Books per row in the grid view, and the cover size used by the list view. */
    val gridColumns: Int = 3,
    val coverSize: LibraryCoverSize = LibraryCoverSize.MEDIUM,
    /** Tapping an already-started book jumps straight into the reader (setting, default on). */
    val openReadBookDirectly: Boolean = true,
    val hideGroupedBooksFromAll: Boolean = false
) {
    val selectionMode: Boolean get() = selectedBookIds.isNotEmpty()

    /** The group currently being viewed, or null for 全部. */
    val selectedCategory: Category? get() = categories.firstOrNull { it.id == selectedCategoryId }

    /**
     * True when 全部 is empty only because every book is filed in a group — the shelf is not
     * actually empty, so it must not claim to be.
     */
    val allHiddenByGrouping: Boolean
        get() = readingFilter != ReadingFilter.FAVORITES && hideGroupedBooksFromAll && selectedCategoryId == null &&
            books.isEmpty() && allBooks.isNotEmpty()
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val chapterRepository: ChapterRepository,
    private val categoryRepository: CategoryRepository,
    private val readingListRepository: ReadingListRepository,
    private val bookParser: BookParser,
    private val zipImporter: ZipImporter,
    private val bookImporter: BookImporter,
    private val folderScanner: FolderScanner,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.getLibraryViewMode().collect { mode ->
                _uiState.update { it.copy(viewMode = mode) }
            }
        }
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.ADDED_TIME)
    private val _selectedCategoryId = MutableStateFlow<Long?>(null)
    private val _readingFilter = MutableStateFlow(ReadingFilter.ALL)
    private val _shelfPreferences = MutableStateFlow(ShelfPreferences())

    init {
        loadShelfPreferences()
        loadCategories()
        loadReadingLists()
        observeBooks()
        autoScanFolderIfStale()
    }

    /**
     * Scans the configured folder once per [AUTO_SCAN_INTERVAL_MS] at most.
     *
     * The scan itself is idempotent and runs on the IO dispatcher, so an unchanged folder costs one
     * directory walk and imports nothing.
     */
    private fun autoScanFolderIfStale() {
        viewModelScope.launch {
            val settings = runCatching { settingsRepository.appSettings.first() }.getOrNull() ?: return@launch
            val folder = settings.scanFolderUri ?: return@launch
            val now = System.currentTimeMillis()
            if (now - settings.lastScanTime < AUTO_SCAN_INTERVAL_MS) return@launch
            // Let the first frame and the shelf query finish before competing for disk and CPU.
            delay(AUTO_SCAN_START_DELAY_MS)
            val result = runCatching { folderScanner.scan(Uri.parse(folder)) }.getOrNull() ?: return@launch
            settingsRepository.updateLastScanTime(now)
            if (result.imported > 0) {
                _uiState.update { it.copy(message = LibraryMessage.Scanned(result.imported)) }
            }
        }
    }

    /**
     * Turns 「加入分组后不在全部显示」 off from the shelf itself.
     *
     * The empty-state explanation pointed at 设置 → 书架, which is three taps away and reads as a
     * broken shelf; this is the one-tap fix the screen should have offered.
     */
    fun setHideGroupedBooksFromAll(hide: Boolean) {
        viewModelScope.launch {
            val current = _shelfPreferences.value
            settingsRepository.updateShelfPreferences(
                hideGroupedBooksFromAll = hide,
                gridColumns = current.gridColumns,
                coverSize = current.coverSize
            )
        }
    }

    private fun loadShelfPreferences() {
        viewModelScope.launch {
            settingsRepository.appSettings.collect { settings ->
                _shelfPreferences.value = ShelfPreferences(
                    hideGroupedBooksFromAll = settings.hideGroupedBooksFromAll,
                    gridColumns = settings.libraryGridColumns,
                    coverSize = settings.libraryCoverSize,
                    openReadBookDirectly = settings.openReadBookDirectly
                )
            }
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            categoryRepository.getAllCategories().collect { categories ->
                _uiState.update { it.copy(categories = categories) }
                // A category deleted elsewhere (restore, another screen) must not stay selected.
                if (_selectedCategoryId.value != null && categories.none { it.id == _selectedCategoryId.value }) {
                    _selectedCategoryId.value = null
                }
            }
        }
    }

    private fun loadReadingLists() {
        viewModelScope.launch {
            readingListRepository.getAllLists().collect { lists ->
                _uiState.update { it.copy(readingLists = lists) }
            }
        }
    }

    /**
     * The single shelf pipeline.
     *
     * Before this, two independent Room flows (`getAllBooks` + `getRecentlyReadBooks`) plus two
     * `MutableStateFlow`s were combined, and a second query fed the 继续阅读 strip. Deriving the
     * recent list from the same emission removes the chance of the two lists disagreeing, and the
     * category filter is applied in this one place instead of being re-derived per screen.
     */
    @OptIn(FlowPreview::class)
    private fun observeBooks() {
        viewModelScope.launch {
            combine(
                bookRepository.getAllBooks(),
                _sortOrder,
                _selectedCategoryId,
                _readingFilter,
                _shelfPreferences
            ) { allBooks, sortOrder, categoryId, readingFilter, prefs ->
                ShelfQuery(allBooks, sortOrder, categoryId, readingFilter, prefs)
            }
                // Filtering + sorting a large shelf is O(n log n) and used to run on the main
                // thread inside the collector. The heavy part happens in map(), which flowOn moves
                // to a worker; the collector only publishes the finished lists.
                .map { query -> query.compute() }
                .flowOn(Dispatchers.Default)
                .collect { computed ->
                    val visibleIds = computed.books.map { it.id }.toSet()
                    _uiState.update {
                        it.copy(
                            books = computed.books,
                            seriesEntries = computed.seriesEntries,
                            allBooks = computed.allBooks,
                            recentlyRead = computed.recent,
                            selectedCategoryId = computed.categoryId,
                            sortOrder = computed.sortOrder,
                            readingFilter = computed.readingFilter,
                            gridColumns = computed.preferences.gridColumns,
                            coverSize = computed.preferences.coverSize,
                            openReadBookDirectly = computed.preferences.openReadBookDirectly,
                            hideGroupedBooksFromAll = computed.preferences.hideGroupedBooksFromAll,
                            isLoading = false,
                            // Books can disappear under an active selection (delete, filter change);
                            // keeping their ids would let a later batch action target nothing.
                            selectedBookIds = it.selectedBookIds intersect visibleIds
                        )
                    }
                }
        }
    }

    /** One emission of the shelf pipeline: the books plus every input that shapes the list. */
    internal data class ShelfQuery(
        val allBooks: List<Book>,
        val sortOrder: SortOrder,
        val categoryId: Long?,
        val readingFilter: ReadingFilter,
        val preferences: ShelfPreferences
    ) {
        /** Pure: filtering, sorting and the recent strip, with no state touched. */
        fun compute(): ComputedShelf {
            val byCategory = when {
                readingFilter == ReadingFilter.FAVORITES -> allBooks
                categoryId != null -> allBooks.filter { it.categoryId == categoryId }
                // 全部书籍 with grouping-hides-grouped on: a grouped book lives in its group only,
                // which is what makes groups feel like real shelves.
                preferences.hideGroupedBooksFromAll -> allBooks.filter { it.categoryId == null }
                else -> allBooks
            }
            val filtered = byCategory.filter { readingFilter.matches(it) }
            val sorted = when (sortOrder) {
                SortOrder.ADDED_TIME -> filtered.sortedByDescending { it.addedTime }
                SortOrder.LAST_READ -> filtered.sortedByDescending { it.lastReadTime ?: it.addedTime }
                SortOrder.TITLE -> filtered.sortedBy { it.title }
                SortOrder.AUTHOR -> filtered.sortedBy { it.author }
                SortOrder.PROGRESS -> filtered.sortedByDescending { it.readingProgress }
                SortOrder.FILE_SIZE -> filtered.sortedByDescending { it.fileSize }
            }
            val recent = allBooks
                .filter { it.lastReadTime != null }
                .sortedByDescending { it.lastReadTime }
                .take(RECENT_LIMIT)
            return ComputedShelf(
                books = sorted,
                allBooks = allBooks,
                recent = recent,
                categoryId = categoryId,
                sortOrder = sortOrder,
                readingFilter = readingFilter,
                preferences = preferences
            )
        }
    }

    internal data class ComputedShelf(
        val books: List<Book>,
        val allBooks: List<Book>,
        val recent: List<Book>,
        val categoryId: Long?,
        val sortOrder: SortOrder,
        val readingFilter: ReadingFilter,
        val preferences: ShelfPreferences,
        val seriesEntries: List<com.flowreader.app.domain.model.SeriesShelfEntry> = com.flowreader.app.domain.model.BookSeries.shelf(books)
    )

    internal data class ShelfPreferences(
        val hideGroupedBooksFromAll: Boolean = false,
        val gridColumns: Int = 3,
        val coverSize: LibraryCoverSize = LibraryCoverSize.MEDIUM,
        val openReadBookDirectly: Boolean = true
    )

    fun setViewMode(mode: LibraryViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
        viewModelScope.launch {
            settingsRepository.setLibraryViewMode(mode)
        }
    }

    fun selectCategory(categoryId: Long?) {
        if (_selectedCategoryId.value == categoryId) return
        _selectedCategoryId.value = categoryId
        _uiState.update { it.copy(selectedCategoryId = categoryId, selectedBookIds = emptySet()) }
    }

    private val favoriteWrites = mutableSetOf<Long>()

    fun setFavorite(book: Book) {
        if (!favoriteWrites.add(book.id)) return
        viewModelScope.launch {
            try {
                bookRepository.setFavorite(book.id, !book.isFavorite)
            } catch (error: Exception) {
                _uiState.update { it.copy(error = error.message ?: "收藏保存失败") }
            } finally {
                favoriteWrites.remove(book.id)
            }
        }
    }

    fun addCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { categoryRepository.insertCategory(Category(name = trimmed)) }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: "创建分组失败") }
                }
        }
    }

    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            if (_selectedCategoryId.value == category.id) {
                _selectedCategoryId.value = null
            }
            runCatching { categoryRepository.deleteCategory(category) }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: "删除分组失败") }
                }
        }
    }

    fun renameCategory(category: Category, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { categoryRepository.updateCategory(category.copy(name = trimmed)) }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message ?: "重命名分组失败") }
                }
        }
    }

    /** Moves a single book into a group (or out of every group when [categoryId] is null). */
    fun updateBookCategory(bookId: Long, categoryId: Long?) {
        viewModelScope.launch {
            runCatching { bookRepository.moveBooksToCategory(listOf(bookId), categoryId) }
                .onSuccess { _uiState.update { it.copy(message = LibraryMessage.Moved(1)) } }
                .onFailure { error -> _uiState.update { it.copy(error = error.message ?: "移入分组失败") } }
        }
    }

    /**
     * Files already-imported books into a group, which is the only way to populate a group that was
     * created after the books were. Uses the same batch statement as the selection toolbar.
     */
    fun addBooksToCategory(bookIds: List<Long>, categoryId: Long?) {
        val valid = bookIds.filter { it > 0L }.distinct()
        if (valid.isEmpty()) return
        viewModelScope.launch {
            runCatching { bookRepository.moveBooksToCategory(valid, categoryId) }
                .onSuccess { _uiState.update { it.copy(message = LibraryMessage.Moved(valid.size)) } }
                .onFailure { error -> _uiState.update { it.copy(error = error.message ?: "移入分组失败") } }
        }
    }

    fun updateSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setReadingFilter(filter: ReadingFilter) {
        if (_readingFilter.value == filter) return
        _readingFilter.value = filter
        _uiState.update { it.copy(readingFilter = filter, selectedBookIds = emptySet()) }
    }

    /**
     * Finds books that are almost certainly the same file imported twice: same title and byte size,
     * or the same internal path. Duplicates predate the idempotent import added in v0.0.6.
     */
    fun findDuplicates(): List<List<Book>> = _uiState.value.allBooks
        .groupBy { book -> "${book.title.trim().lowercase()}|${book.fileSize}|${book.filePath}" }
        .values
        .filter { it.size > 1 }
        .sortedByDescending { it.size }

    /**
     * Keeps the copy with the most progress (ties broken by the most recent read) and deletes the
     * rest, so nothing a reader has actually done is thrown away.
     */
    fun mergeDuplicates(groups: List<List<Book>>) {
        val losers = groups.mapNotNull { group ->
            group.maxWithOrNull(
                compareBy<Book> { it.readingProgress }
                    .thenBy { it.lastReadTime?.time ?: 0L }
                    .thenBy { it.id }
            )?.let { keep -> group.filter { it.id != keep.id }.map { it.id } }
        }.flatten()
        if (losers.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                groups.forEach { group ->
                    val keep = group.maxWithOrNull(
                        compareBy<Book> { it.readingProgress }.thenBy { it.lastReadTime?.time ?: 0L }.thenBy { it.id }
                    )
                    if (keep != null && group.any { it.isFavorite } && !keep.isFavorite) bookRepository.setFavorite(keep.id, true)
                    if (keep != null && keep.seriesName == null) {
                        group.firstOrNull { it.seriesName != null }?.seriesName?.let { bookRepository.setBooksSeries(listOf(keep.id), it) }
                    }
                }
                bookRepository.deleteBooksByIds(losers)
            }
                .onSuccess { _uiState.update { it.copy(message = LibraryMessage.Deleted(losers.size)) } }
                .onFailure { error -> _uiState.update { it.copy(error = error.message ?: "合并重复书籍失败") } }
        }
    }

    /**
     * Single entry point for the picker. A `.fb2.zip` is a book, a plain `.zip` is an archive of
     * books — format detection runs first so the two do not collide.
     *
     * Imports land in the group that is currently selected, which is what "import from inside a
     * group" has to mean; the shelf is filtered to that group, so a book that went to 全部 would
     * appear to have vanished.
     */
    private val receivedImportUris = mutableSetOf<Uri>()

    /** Returning to the shelf must not replay the Activity's original shared-file intent. */
    fun importInitialBook(uri: Uri) {
        if (receivedImportUris.add(uri)) importBook(uri)
    }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val categoryId = _selectedCategoryId.value
            val name = bookParser.displayName(uri)
            if (BookParser.detectFormatStatic(name) == BookFormat.UNKNOWN && isZipArchiveName(name)) {
                if (zipImporter.isComicArchive(uri)) {
                    importSingle(uri, categoryId).fold(
                        onSuccess = { _uiState.update { state -> state.copy(isLoading = false) } },
                        onFailure = { error -> _uiState.update { state -> state.copy(isLoading = false, error = error.message ?: "导入失败") } }
                    )
                } else {
                    importArchiveInternal(uri, categoryId)
                }
                return@launch
            }
            val result = importSingle(uri, categoryId)
            _uiState.update { state ->
                result.fold(
                    // 导入成功必须给出可见反馈：否则大书导入耗时较长时用户会以为「点了没反应」。
                    onSuccess = { state.copy(isLoading = false, message = LibraryMessage.Imported(name)) },
                    onFailure = { state.copy(isLoading = false, error = it.message ?: "导入失败") }
                )
            }
        }
    }

    private fun isZipArchiveName(name: String): Boolean =
        name.endsWith(".zip", ignoreCase = true) || name.endsWith(".cbz", ignoreCase = true)

    private suspend fun importArchiveInternal(uri: Uri, categoryId: Long?) {
        zipImporter.extract(uri)
            .onSuccess { files ->
                var imported = 0
                val failures = mutableListOf<String>()
                files.forEach { file ->
                    importSingle(Uri.fromFile(file), categoryId)
                        .onSuccess { imported++ }
                        .onFailure { failures.add("${file.name}: ${it.message ?: "解析失败"}") }
                    file.delete()
                }
                files.firstOrNull()?.parentFile?.deleteRecursively()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        message = LibraryMessage.ArchiveImported(imported),
                        // Report the first few failures rather than silently importing 3 of 30.
                        error = failures.take(3).joinToString("\n").takeIf { text -> text.isNotEmpty() }
                    )
                }
            }
            .onFailure { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message ?: "压缩包导入失败") }
            }
    }

    /**
     * Parses and stores one book, idempotently, via the shared [BookImporter].
     *
     * The whole body runs off the main thread inside the importer: `BookParser.copyFileToInternal`
     * is a plain blocking file copy, and calling it from the default dispatcher froze the shelf for
     * the duration of a large import.
     */
    private suspend fun importSingle(uri: Uri, categoryId: Long? = null): Result<Long> =
        bookImporter.importSingle(uri, categoryId).map { it.bookId }

    fun deleteBook(bookId: Long) {
        viewModelScope.launch {
            bookRepository.deleteBookById(bookId)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun refreshBooks() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Room re-emits on every write, so a refresh is only a visual acknowledgement; the
                // pipeline above is always live.
                _uiState.update { it.copy(isRefreshing = true) }
            } finally {
                _isRefreshing.value = false
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    // ---- Multi-select (v53) -------------------------------------------------

    fun toggleSelection(bookId: Long) {
        _uiState.update { state ->
            val next = if (bookId in state.selectedBookIds) {
                state.selectedBookIds - bookId
            } else {
                state.selectedBookIds + bookId
            }
            state.copy(selectedBookIds = next)
        }
    }

    fun selectAllVisible() {
        _uiState.update { state -> state.copy(selectedBookIds = state.books.map { it.id }.toSet()) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedBookIds = emptySet()) }
    }

    fun deleteSelected() {
        val ids = _uiState.value.selectedBookIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { bookRepository.deleteBooksByIds(ids) }
                .onSuccess {
                    _uiState.update { it.copy(selectedBookIds = emptySet(), message = LibraryMessage.Deleted(ids.size)) }
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message ?: "批量删除失败") } }
        }
    }

    fun moveSelectedToCategory(categoryId: Long?) {
        val ids = _uiState.value.selectedBookIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { bookRepository.moveBooksToCategory(ids, categoryId) }
                .onSuccess {
                    _uiState.update { it.copy(selectedBookIds = emptySet(), message = LibraryMessage.Moved(ids.size)) }
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message ?: "批量移动失败") } }
        }
    }

    fun updateSelectedMetadata(author: String?, tagsText: String?) {
        val ids = _uiState.value.selectedBookIds.toList()
        if (ids.isEmpty()) return
        val tags = tagsText?.takeIf { it.isNotBlank() }
            ?.split(",", "，", " ")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
        viewModelScope.launch {
            runCatching { bookRepository.updateBooksMetadata(ids, author?.takeIf { it.isNotBlank() }, tags) }
                .onSuccess {
                    _uiState.update { it.copy(selectedBookIds = emptySet(), message = LibraryMessage.Updated(ids.size)) }
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message ?: "批量编辑失败") } }
        }
    }

    fun addSelectedToList(list: ReadingList) {
        val ids = _uiState.value.selectedBookIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { readingListRepository.addBooks(list.id, ids) }
                .onSuccess {
                    _uiState.update {
                        it.copy(selectedBookIds = emptySet(), message = LibraryMessage.AddedToList(list.name))
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(error = error.message ?: "加入书单失败") } }
        }
    }

    companion object {
        private const val RECENT_LIMIT = 20
        private const val AUTO_SCAN_INTERVAL_MS = 30 * 60 * 1000L
        private const val AUTO_SCAN_START_DELAY_MS = 1_500L
    }
}
