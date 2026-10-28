package com.flowreader.app.ui.screens.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.component.BookCover
import com.flowreader.app.core.designsystem.component.BookShelfSkeleton
import com.flowreader.app.core.designsystem.component.FlowChoiceChip
import com.flowreader.app.core.designsystem.component.FlowIcons
import com.flowreader.app.core.designsystem.component.FlowPreferenceGroup
import com.flowreader.app.core.designsystem.component.FlowSelectionTopBar
import com.flowreader.app.core.designsystem.token.FlowRadius
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.app.core.util.FlowFormatters
import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.model.Category
import com.flowreader.app.domain.model.LibraryCoverSize
import com.flowreader.app.domain.model.LibraryViewMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    initialImportUri: android.net.Uri? = null,
    onBookClick: (Long) -> Unit,
    onContinueReading: (Long) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNotesClick: () -> Unit,
    onReadingListsClick: () -> Unit,
    onOpdsClick: () -> Unit,
    onStatsClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
    onImportSettingsClick: () -> Unit = onSettingsClick,
    onInitialImportConsumed: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    val bookPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri -> viewModel.importBook(uri) }
    }

    var showImportSheet by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }
    var showSeries by remember { mutableStateOf(false) }
    var selectedSeries by remember { mutableStateOf<String?>(null) }
    fun openSeries(name: String?) { selectedSeries = name; showSeries = true }
    if (showSeries) {
        com.flowreader.app.ui.components.BookSeriesSheet(
            initialName = selectedSeries,
            onOpenBook = { showSeries = false; onContinueReading(it) },
            onShowDetail = { showSeries = false; onBookClick(it) },
            onDismiss = { showSeries = false }
        )
    }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var batchAction by remember { mutableStateOf<BatchAction?>(null) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var showAddExistingDialog by remember { mutableStateOf(false) }
    var duplicateGroups by remember { mutableStateOf<List<List<Book>>?>(null) }
    var categoryToEdit by remember { mutableStateOf<Category?>(null) }
    var bookToMove by remember { mutableStateOf<Book?>(null) }

    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val pullToRefreshState = rememberPullToRefreshState()

    /**
     * Shelf tap behaviour: a book that has already been opened jumps straight into the reader when
     * 首次阅读后点击直接阅读 is on (default). Long-press / selection mode are unaffected.
     */
    fun openBook(book: Book) {
        if (uiState.openReadBookDirectly && book.lastReadTime != null) {
            onContinueReading(book.id)
        } else {
            onBookClick(book.id)
        }
    }

    // v52: import failures used to be swallowed by a LaunchedEffect that called clearError()
    // without ever showing anything. The reason now reaches the user.
    val error = uiState.error
    val dismissLabel = stringResource(R.string.action_dismiss)
    LaunchedEffect(error) {
        if (error != null) {
            viewModel.clearError()
            snackbarScope.launch {
                snackbarHostState.showSnackbar(
                    message = error,
                    actionLabel = dismissLabel,
                    duration = SnackbarDuration.Long
                )
            }
        }
    }

    // v56 audit: an "open with FlowReader" share is imported exactly once per intent.
    LaunchedEffect(initialImportUri) {
        val uri = initialImportUri
        if (uri != null) {
            viewModel.importInitialBook(uri)
            onInitialImportConsumed()
        }
    }

    val message = uiState.message
    val messageText = message?.let { libraryMessageText(it) }
    LaunchedEffect(message) {
        if (messageText != null) {
            viewModel.clearMessage()
            snackbarScope.launch { snackbarHostState.showSnackbar(messageText) }
        }
    }

    if (showImportSheet) {
        ShelfImportSheet(
            onDismiss = { showImportSheet = false },
            onFiles = {
                showImportSheet = false
                bookPickerLauncher.launch(IMPORT_MIME_TYPES)
            },
            onArchive = {
                showImportSheet = false
                bookPickerLauncher.launch(ARCHIVE_MIME_TYPES)
            },
            onScanSettings = {
                showImportSheet = false
                onImportSettingsClick()
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            when {
                uiState.selectionMode -> FlowSelectionTopBar(
                    selectedCount = uiState.selectedBookIds.size,
                    onClose = { viewModel.clearSelection() },
                    actions = {
                        IconButton(onClick = { viewModel.selectAllVisible() }) {
                            Icon(Icons.Default.DoneAll, contentDescription = stringResource(R.string.batch_select_all))
                        }
                        IconButton(onClick = { batchAction = BatchAction.MOVE }) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.batch_move))
                        }
                        IconButton(onClick = { batchAction = BatchAction.EDIT }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.batch_edit))
                        }
                        IconButton(onClick = { batchAction = BatchAction.DELETE }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.batch_delete))
                        }
                    }
                )

                else -> TopAppBar(
                    title = { Text(stringResource(R.string.nav_shelf), style = MaterialTheme.typography.headlineSmall) },
                    actions = {
                        IconButton(onClick = onSearchClick) {
                            Icon(FlowIcons.Search, contentDescription = stringResource(R.string.action_search))
                        }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(FlowIcons.MoreVertical, contentDescription = stringResource(R.string.action_more))
                            }
                            LibraryOverflowMenu(
                                expanded = showOverflowMenu,
                                sortOrder = uiState.sortOrder,
                                onDismiss = { showOverflowMenu = false },
                                onSortOrder = { viewModel.updateSortOrder(it) },
                                onFindDuplicates = { duplicateGroups = viewModel.findDuplicates() },
                                onImportArchive = { bookPickerLauncher.launch(ARCHIVE_MIME_TYPES) },
                                onNotes = onNotesClick,
                                onReadingLists = onReadingListsClick,
                                onOpds = onOpdsClick,
                                onSettings = onSettingsClick,
                                onHistory = onHistoryClick,
                                onImport = { showImportSheet = true },
                                onToggleView = { viewModel.setViewMode(uiState.viewMode.toggle()) },
                                viewMode = uiState.viewMode,
                                onGroups = { showGroupPicker = true },
                                onSeries = { openSeries(null) }
                            )
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        // Deliberately not FlowStateHost: its empty state replaces the whole screen, which hid the
        // group chips and left no way back to 全部. Only the cold-start spinner is gated here; the
        // empty shelf/group is rendered *inside* the list so the chips stay reachable.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading && uiState.books.isEmpty() && uiState.categories.isEmpty()) {
                BookShelfSkeleton()
            } else {
                if (uiState.viewMode == LibraryViewMode.GRID) {
                    LibraryGrid(
                        uiState = uiState,
                        gridState = gridState,
                        isRefreshing = isRefreshing,
                        pullToRefreshState = pullToRefreshState,
                        onRefresh = { viewModel.refreshBooks() },
                        onBookClick = { openBook(it) },
                        onOpenSeries = { openSeries(it) },
                        onToggleSelection = { viewModel.toggleSelection(it) },
                        onDeleteBook = { viewModel.deleteBook(it) },
                        onSelectCategory = { viewModel.selectCategory(it) },
                        onSelectReadingFilter = { viewModel.setReadingFilter(it) },
                        onNewCategory = { showNewCategoryDialog = true },
                        onEditCategory = { categoryToEdit = it },
                        onMoveBookCategory = { bookToMove = it },
                        onAddExistingBooks = { showAddExistingDialog = true },
                        onShowDetail = onBookClick,
                        header = {},
                        onOpenGroups = { showGroupPicker = true },
                        onFavorite = { viewModel.setFavorite(it) },
                        onImport = { bookPickerLauncher.launch(IMPORT_MIME_TYPES) }
                    )
                }
                if (uiState.viewMode == LibraryViewMode.LIST) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pullToRefresh(
                                isRefreshing = isRefreshing,
                                state = pullToRefreshState,
                                onRefresh = { viewModel.refreshBooks() }
                            ),
                        contentPadding = PaddingValues(FlowSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!uiState.selectionMode) {
                            item(key = "shelf_filters") {
                                ShelfFilterBar(
                                    uiState.selectedCategory?.name,
                                    uiState.readingFilter,
                                    { showGroupPicker = true },
                                    viewModel::setReadingFilter
                                )
                            }
                        }
                        if (uiState.books.isEmpty()) {
                            item(key = "shelf_empty") {
                                ShelfEmptyState(
                                    readingFilter = uiState.readingFilter,
                                    inGroup = uiState.selectedCategoryId != null,
                                    hiddenByGrouping = uiState.allHiddenByGrouping,
                                    onImport = { bookPickerLauncher.launch(IMPORT_MIME_TYPES) },
                                    // The explanation alone left the shelf looking broken; this puts the
                                    // fix one tap away instead of three screens deep in 设置.
                                    onShowGroupedBooks = { viewModel.setHideGroupedBooksFromAll(false) }
                                )
                            }
                        }

                        val entries = if (uiState.selectionMode) uiState.books.map {
                            com.flowreader.app.domain.model.SeriesShelfEntry("book:${it.id}", it, null, listOf(it))
                        } else uiState.seriesEntries.ifEmpty { com.flowreader.app.domain.model.BookSeries.shelf(uiState.books) }
                        items(entries, key = { it.key }) { entry ->
                            val book = entry.book
                            if (entry.name != null) {
                                SeriesShelfListRow(entry, onClick = { openSeries(entry.name) })
                            } else {
                            BookListItem(
                                book = book,
                                selectionMode = uiState.selectionMode,
                                selected = book.id in uiState.selectedBookIds,
                                coverSize = uiState.coverSize,
                                onClick = {
                                    if (uiState.selectionMode) viewModel.toggleSelection(book.id) else openBook(book)
                                },
                                onLongClick = { viewModel.toggleSelection(book.id) },
                                onMoveCategory = { bookToMove = book },
                                onShowDetail = { onBookClick(book.id) },
                                onFavorite = { viewModel.setFavorite(book) },
                                onDelete = { viewModel.deleteBook(book.id) }
                            )
                            }
                        }
                    }
                }

                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }

    BatchActionDialogs(
        action = batchAction,
        uiState = uiState,
        onDismiss = { batchAction = null },
        viewModel = viewModel
    )

    if (showNewCategoryDialog) {
        NewCategoryDialog(
            onDismiss = { showNewCategoryDialog = false },
            onConfirm = { name ->
                viewModel.addCategory(name)
                showNewCategoryDialog = false
            }
        )
    }

    categoryToEdit?.let { category ->
        EditCategoryDialog(
            category = category,
            onDismiss = { categoryToEdit = null },
            onRename = { newName ->
                viewModel.renameCategory(category, newName)
                categoryToEdit = null
            },
            onDelete = {
                viewModel.deleteCategory(category)
                categoryToEdit = null
            }
        )
    }

    bookToMove?.let { book ->
        MoveBookCategoryDialog(
            book = book,
            categories = uiState.categories,
            onDismiss = { bookToMove = null },
            onSelectCategory = { catId ->
                viewModel.updateBookCategory(book.id, catId)
                bookToMove = null
            }
        )
    }

    if (showGroupPicker) {
        ShelfGroupSheet(
            uiState,
            { showGroupPicker = false },
            viewModel::selectCategory,
            {
                viewModel.setReadingFilter(ReadingFilter.ALL)
                showGroupPicker = false
            },
            {
                showGroupPicker = false
                showNewCategoryDialog = true
            },
            {
                showGroupPicker = false
                categoryToEdit = it
            },
            {
                showGroupPicker = false
                showAddExistingDialog = true
            }
        )
    }

    val openGroup = uiState.selectedCategory
    if (showAddExistingDialog && openGroup != null) {
        AddExistingBooksDialog(
            // Everything that is not already in this group is a candidate.
            books = uiState.allBooks.filter { it.categoryId != openGroup.id },
            groupName = openGroup.name,
            onDismiss = { showAddExistingDialog = false },
            onConfirm = { ids ->
                viewModel.addBooksToCategory(ids, openGroup.id)
                showAddExistingDialog = false
            }
        )
    }

    duplicateGroups?.let { groups ->
        DuplicateBooksDialog(
            groups = groups,
            onDismiss = { duplicateGroups = null },
            onMerge = { confirmed ->
                viewModel.mergeDuplicates(confirmed)
                duplicateGroups = null
            }
        )
    }
}

@Composable
internal fun LibraryHomeOverview(currentBook: Book?, onContinue: (Long) -> Unit, onImport: () -> Unit, onHistory: () -> Unit) {
    FlowPreferenceGroup {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ui_resume),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onHistory) { Text(stringResource(R.string.ui_history)) }
        }
        if (currentBook != null) {
            Row(
                Modifier.fillMaxWidth().clickable { onContinue(currentBook.id) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlowSpacing.md)
            ) {
                BookCover(
                    title = currentBook.title,
                    author = currentBook.author,
                    coverPath = currentBook.coverPath,
                    modifier = Modifier.size(52.dp, 74.dp)
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlowSpacing.xs)) {
                    Text(currentBook.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        currentBook.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    LinearProgressIndicator(progress = { currentBook.readingProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.ui_reading_progress, FlowFormatters.percent(currentBook.readingProgress)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.room_read_now),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        } else {
            Text(
                stringResource(R.string.ui_no_history),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onImport) { Text(stringResource(R.string.library_import)) }
        }
    }
}

/**
 * Shows the books that were imported more than once and lets the user merge them.
 *
 * Merging keeps the copy with the most reading progress, so a finished book never loses its
 * position to an empty duplicate.
 */
@Composable
private fun DuplicateBooksDialog(groups: List<List<Book>>, onDismiss: () -> Unit, onMerge: (List<List<Book>>) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_find_duplicates)) },
        text = {
            if (groups.isEmpty()) {
                Text(stringResource(R.string.library_duplicates_none))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm)
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.library_duplicates_found, groups.sumOf { it.size - 1 }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(groups, key = { group -> group.first().id }) { group ->
                        Column(modifier = Modifier.padding(vertical = FlowSpacing.xs)) {
                            Text(
                                text = group.first().title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.library_duplicates_copies, group.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (groups.isNotEmpty()) {
                TextButton(onClick = { onMerge(groups) }) {
                    Text(stringResource(R.string.library_duplicates_merge))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

private enum class BatchAction { DELETE, MOVE, EDIT, ADD_TO_LIST }

private val IMPORT_MIME_TYPES = arrayOf(
    "application/epub+zip",
    "text/plain",
    "text/markdown",
    "application/pdf",
    "image/jpeg",
    "image/png",
    "image/webp",
    "application/vnd.comicbook+zip",
    "application/x-fictionbook+xml",
    "application/x-mobipocket-ebook",
    "application/zip",
    "application/octet-stream"
)

private val ARCHIVE_MIME_TYPES = arrayOf("application/zip", "application/vnd.comicbook+zip")

@Composable
private fun libraryMessageText(message: LibraryMessage): String = when (message) {
    is LibraryMessage.Deleted -> stringResource(R.string.batch_result_deleted, message.count)
    is LibraryMessage.Moved -> stringResource(R.string.batch_result_moved, message.count)
    is LibraryMessage.Updated -> stringResource(R.string.batch_result_updated, message.count)
    is LibraryMessage.AddedToList -> stringResource(R.string.batch_result_added_to_list, message.listName)
    is LibraryMessage.ArchiveImported -> stringResource(R.string.library_import_archive_result, message.count)
    is LibraryMessage.Scanned -> stringResource(R.string.library_scanned_result, message.count)
    is LibraryMessage.Imported -> stringResource(R.string.library_import_done, message.title)
}

@Composable
private fun sortOrderName(order: SortOrder): String = when (order) {
    SortOrder.ADDED_TIME -> stringResource(R.string.library_sort_added)
    SortOrder.LAST_READ -> stringResource(R.string.library_sort_last_read)
    SortOrder.TITLE -> stringResource(R.string.library_sort_title)
    SortOrder.AUTHOR -> stringResource(R.string.library_sort_author)
    SortOrder.PROGRESS -> stringResource(R.string.library_sort_progress)
    SortOrder.FILE_SIZE -> stringResource(R.string.library_sort_size)
}

@Composable
private fun readingFilterName(filter: ReadingFilter): String = when (filter) {
    ReadingFilter.ALL -> stringResource(R.string.library_filter_all)
    ReadingFilter.UNREAD -> stringResource(R.string.library_filter_unread)
    ReadingFilter.READING -> stringResource(R.string.library_filter_reading)
    ReadingFilter.FINISHED -> stringResource(R.string.library_filter_finished)
    ReadingFilter.FAVORITES -> stringResource(R.string.shelf_favorites)
}

@Composable
private fun ReadingFilterRow(selected: ReadingFilter, onSelect: (ReadingFilter) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm)
    ) {
        items(ReadingFilter.entries, key = { it.name }) { filter ->
            val isSelected = selected == filter
            FlowChoiceChip(
                selected = isSelected,
                onClick = { onSelect(filter) },
                label = { Text(readingFilterName(filter)) }
            )
        }
    }
}

@Composable
private fun LibraryOverflowMenu(
    expanded: Boolean,
    sortOrder: SortOrder,
    onDismiss: () -> Unit,
    onSortOrder: (SortOrder) -> Unit,
    onFindDuplicates: () -> Unit,
    onImportArchive: () -> Unit,
    onNotes: () -> Unit,
    onReadingLists: () -> Unit,
    onOpds: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onImport: () -> Unit,
    onToggleView: () -> Unit,
    viewMode: LibraryViewMode,
    onGroups: () -> Unit,
    onSeries: () -> Unit = {}
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.series_title)) },
            onClick = { onDismiss(); onSeries() }
        )
        DropdownMenuItem(text = { Text(stringResource(R.string.library_import)) }, onClick = {
            onDismiss()
            onImport()
        })
        DropdownMenuItem(text = {
            Text(stringResource(if (viewMode == LibraryViewMode.GRID) R.string.library_view_list else R.string.library_view_grid))
        }, onClick = {
            onDismiss()
            onToggleView()
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_history)) }, onClick = {
            onDismiss()
            onHistory()
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.shelf_groups)) }, onClick = {
            onDismiss()
            onGroups()
        })
        HorizontalDivider()

        Text(
            stringResource(R.string.ui_sort),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(FlowSpacing.lg)
        )
        SortOrder.entries.forEach { order ->
            DropdownMenuItem(
                text = { Text(sortOrderName(order)) },
                onClick = {
                    onSortOrder(order)
                    onDismiss()
                },
                leadingIcon = {
                    if (sortOrder == order) {
                        Icon(Icons.Default.Check, contentDescription = null)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                    }
                }
            )
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.ui_shelf_tools),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(FlowSpacing.lg)
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.library_find_duplicates)) },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
            onClick = {
                onDismiss()
                onFindDuplicates()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.library_import_archive)) },
            leadingIcon = { Icon(Icons.Default.FolderZip, contentDescription = null) },
            onClick = {
                onDismiss()
                onImportArchive()
            }
        )
    }
}

@Composable
private fun BatchActionDialogs(action: BatchAction?, uiState: LibraryUiState, onDismiss: () -> Unit, viewModel: LibraryViewModel) {
    when (action) {
        null -> Unit

        BatchAction.DELETE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.batch_delete_title, uiState.selectedBookIds.size)) },
            text = { Text(stringResource(R.string.batch_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected()
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        )

        BatchAction.MOVE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.batch_move_title)) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.batch_move_none)) },
                        modifier = Modifier.clickable {
                            viewModel.moveSelectedToCategory(null)
                            onDismiss()
                        }
                    )
                    uiState.categories.forEach { category ->
                        ListItem(
                            headlineContent = { Text(category.name) },
                            modifier = Modifier.clickable {
                                viewModel.moveSelectedToCategory(category.id)
                                onDismiss()
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        )

        BatchAction.EDIT -> BatchMetadataDialog(
            onDismiss = onDismiss,
            onConfirm = { author, tags ->
                viewModel.updateSelectedMetadata(author, tags)
                onDismiss()
            }
        )

        BatchAction.ADD_TO_LIST -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.batch_add_to_list)) },
            text = {
                if (uiState.readingLists.isEmpty()) {
                    Text(stringResource(R.string.reading_lists_no_list))
                } else {
                    Column {
                        uiState.readingLists.forEach { list ->
                            ListItem(
                                headlineContent = { Text(list.name) },
                                supportingContent = {
                                    Text(stringResource(R.string.reading_lists_book_count, list.bookCount))
                                },
                                modifier = Modifier.clickable {
                                    viewModel.addSelectedToList(list)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun BatchMetadataDialog(onDismiss: () -> Unit, onConfirm: (String?, String?) -> Unit) {
    var author by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FlowSpacing.md)) {
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.batch_edit_author)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.batch_edit_tags)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(author.ifBlank { null }, tags.ifBlank { null }) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookListItem(
    book: Book,
    selectionMode: Boolean,
    selected: Boolean,
    coverSize: LibraryCoverSize,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoveCategory: () -> Unit,
    onShowDetail: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(FlowRadius.md),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors(containerColor = Color.Transparent)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
                Spacer(modifier = Modifier.width(FlowSpacing.sm))
            }

            BookCover(
                title = book.title,
                author = book.author,
                coverPath = book.coverPath,
                modifier = Modifier.size(coverSize.coverWidthDp.dp, coverSize.coverHeightDp.dp),
                showTitleOnFallback = false
            )

            Spacer(modifier = Modifier.width(FlowSpacing.md))

            Column(modifier = Modifier.weight(1f)) {
                if (book.isFavorite) {
                    Icon(
                        Icons.Default.Star,
                        stringResource(R.string.shelf_favorites),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(FlowSpacing.xs))
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(FlowSpacing.xs))
                Text(
                    text = if (book.totalChapters > 0) {
                        stringResource(
                            R.string.shelf_progress_chapter,
                            FlowFormatters.percent(book.readingProgress),
                            (book.currentChapter + 1).coerceAtMost(book.totalChapters)
                        )
                    } else {
                        FlowFormatters.percent(book.readingProgress)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!selectionMode) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = FlowIcons.MoreVertical,
                            contentDescription = stringResource(R.string.library_book_actions, book.title)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        // Kept reachable because tapping the card may jump straight into the
                        // reader (首次阅读后点击直接阅读); the detail page holds the TOC and notes.
                        DropdownMenuItem(
                            text = { Text(stringResource(if (book.isFavorite) R.string.shelf_unfavorite else R.string.shelf_favorite)) },
                            leadingIcon = { Icon(if (book.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder, null) },
                            onClick = {
                                showMenu = false
                                onFavorite()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.book_action_detail)) },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                            },
                            onClick = {
                                showMenu = false
                                onShowDetail()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.book_action_move_category)) },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null)
                            },
                            onClick = {
                                showMenu = false
                                onMoveCategory()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            },
                            onClick = {
                                showMenu = false
                                showDeleteDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = { Text(stringResource(R.string.library_delete_message, book.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryGrid(
    uiState: LibraryUiState,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    isRefreshing: Boolean,
    pullToRefreshState: androidx.compose.material3.pulltorefresh.PullToRefreshState,
    onRefresh: () -> Unit,
    onBookClick: (Book) -> Unit,
    onOpenSeries: (String) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onDeleteBook: (Long) -> Unit,
    onSelectCategory: (Long?) -> Unit,
    onSelectReadingFilter: (ReadingFilter) -> Unit,
    onNewCategory: () -> Unit,
    onEditCategory: (Category) -> Unit,
    onMoveBookCategory: (Book) -> Unit,
    onAddExistingBooks: () -> Unit,
    onShowDetail: (Long) -> Unit,
    onImport: () -> Unit,
    onOpenGroups: () -> Unit,
    onFavorite: (Book) -> Unit,
    header: @Composable () -> Unit
) {
    LazyVerticalGrid(
        // Fixed columns from 设置 → 书架: the user controls how big each book tile is, and therefore
        // how many fit on one screen. Adaptive was the old behaviour and ignored the setting.
        columns = GridCells.Fixed(uiState.gridColumns.coerceIn(2, 5)),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .pullToRefresh(
                isRefreshing = isRefreshing,
                state = pullToRefreshState,
                onRefresh = onRefresh
            ),
        contentPadding = PaddingValues(FlowSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(if (uiState.gridColumns == 3) 24.dp else FlowSpacing.md),
        verticalArrangement = Arrangement.spacedBy(FlowSpacing.md)
    ) {
        if (!uiState.selectionMode) {
            item(key = "shelf_filters", span = { GridItemSpan(maxLineSpan) }) {
                ShelfFilterBar(uiState.selectedCategory?.name, uiState.readingFilter, onOpenGroups, onSelectReadingFilter)
            }
        }
        if (uiState.books.isEmpty()) {
            item(key = "grid_shelf_empty", span = { GridItemSpan(maxLineSpan) }) {
                ShelfEmptyState(
                    readingFilter = uiState.readingFilter,
                    inGroup = uiState.selectedCategoryId != null,
                    hiddenByGrouping = uiState.allHiddenByGrouping,
                    onImport = onImport
                )
            }
        }

        val entries = if (uiState.selectionMode) uiState.books.map {
            com.flowreader.app.domain.model.SeriesShelfEntry("book:${it.id}", it, null, listOf(it))
        } else uiState.seriesEntries.ifEmpty { com.flowreader.app.domain.model.BookSeries.shelf(uiState.books) }
        gridItems(entries, key = { it.key }) { entry ->
            val book = entry.book
            if (entry.name != null) {
                SeriesShelfGridCard(entry, onClick = { entry.name?.let(onOpenSeries) })
            } else {
            BookGridCard(
                book = book,
                selectionMode = uiState.selectionMode,
                selected = book.id in uiState.selectedBookIds,
                onClick = {
                    if (uiState.selectionMode) onToggleSelection(book.id) else onBookClick(book)
                },
                onLongClick = { onToggleSelection(book.id) },
                onMoveCategory = { onMoveBookCategory(book) },
                onShowDetail = { onShowDetail(book.id) },
                onFavorite = { onFavorite(book) },
                onDelete = { onDeleteBook(book.id) }
            )
            }
        }
    }
}

@Composable
private fun SeriesShelfGridCard(entry: com.flowreader.app.domain.model.SeriesShelfEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box {
            BookCover(title = entry.book.title, author = entry.book.author, coverPath = entry.book.coverPath,
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f), shape = MaterialTheme.shapes.small)
            Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
                Text(stringResource(R.string.series_badge), Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 96.dp || LocalDensity.current.fontScale > 1.2f
            val style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall
            val slot = with(LocalDensity.current) { rememberTextMeasurer().measure("Ag书\nAg书", style = style).size.height.toDp() }
            Column(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 4.dp)) {
                Text(entry.name.orEmpty(), Modifier.fillMaxWidth().height(slot), style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.series_volume_count, entry.volumes.size), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(FlowIcons.Next, stringResource(R.string.series_open), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SeriesShelfListRow(entry: com.flowreader.app.domain.model.SeriesShelfEntry, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BookCover(title = entry.book.title, author = entry.book.author, coverPath = entry.book.coverPath, modifier = Modifier.size(52.dp, 72.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.series_volume_count, entry.volumes.size), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(FlowIcons.Next, stringResource(R.string.series_open))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridCard(
    book: Book,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoveCategory: () -> Unit,
    onShowDetail: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box {
            BookCover(
                title = book.title,
                author = book.author,
                coverPath = book.coverPath,
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                shape = MaterialTheme.shapes.small
            )
            if (selectionMode) {
                Checkbox(selected, { onClick() }, Modifier.align(Alignment.TopEnd))
            } else if (book.isFavorite) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .9f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                ) {
                    Icon(
                        Icons.Default.Star,
                        stringResource(R.string.shelf_favorites),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(4.dp).size(16.dp)
                    )
                }
            }
        }
        val actions: @Composable () -> Unit = {
            if (!selectionMode) {
                Box {
                    IconButton(onClick = {
                        showMenu = true
                    }, modifier = Modifier.width(24.dp).height(48.dp)) {
                        Icon(FlowIcons.MoreVertical, stringResource(R.string.library_book_actions, book.title), Modifier.size(18.dp))
                    }
                    DropdownMenu(showMenu, { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (book.isFavorite) R.string.shelf_unfavorite else R.string.shelf_favorite)) },
                            leadingIcon = { Icon(if (book.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder, null) },
                            onClick = {
                                showMenu = false
                                onFavorite()
                            }
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.book_action_detail)) }, onClick = {
                            showMenu = false
                            onShowDetail()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.book_action_move_category)) }, onClick = {
                            showMenu = false
                            onMoveCategory()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                            showMenu = false
                            showDeleteDialog = true
                        })
                    }
                }
            }
        }
        val progressLabel = if (book.readingProgress >= ReadingFilter.FINISHED_THRESHOLD) {
            stringResource(
                R.string.library_filter_finished
            )
        } else {
            FlowFormatters.percent(book.readingProgress)
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compactDetails = maxWidth < 96.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
            val titleStyle = if (compactDetails) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall
            // Mixed CJK/Latin glyph metrics can make minLines alone differ from an actual two-line
            // title at large font scales. Measure one shared two-line slot in the active font.
            val titleSlotHeight = with(LocalDensity.current) {
                rememberTextMeasurer().measure("Ag书\nAg书", style = titleStyle).size.height.toDp()
            }
            Column(Modifier.fillMaxWidth().padding(top = if (compactDetails) 0.dp else 4.dp)) {
                Text(
                    book.title,
                    Modifier.fillMaxWidth().height(titleSlotHeight),
                    style = titleStyle,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        progressLabel,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    actions()
                }
            }
        }
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = { Text(stringResource(R.string.library_delete_message, book.title)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun AddExistingBooksButton(onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(FlowSpacing.sm))
        Text(stringResource(R.string.library_add_existing))
    }
}

/**
 * The empty shelf / empty group message.
 *
 * Rendered as a list item rather than by `FlowStateHost` so the group chips above it stay on screen
 * — the whole reason a user could get stuck inside an empty group with no way back to 全部.
 */
@Composable
private fun ShelfEmptyState(
    readingFilter: ReadingFilter = ReadingFilter.ALL,
    inGroup: Boolean,
    hiddenByGrouping: Boolean,
    onImport: () -> Unit,
    onShowGroupedBooks: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FlowSpacing.xxl, horizontal = FlowSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(FlowSpacing.lg))
        Text(
            text = when {
                readingFilter == ReadingFilter.FAVORITES -> stringResource(R.string.shelf_favorites_empty)
                readingFilter == ReadingFilter.READING -> stringResource(R.string.library_filter_reading)
                readingFilter == ReadingFilter.FINISHED -> stringResource(R.string.library_filter_finished)
                hiddenByGrouping -> stringResource(R.string.library_all_hidden_title)
                inGroup -> stringResource(R.string.library_group_empty_title)
                else -> stringResource(R.string.library_empty_title)
            },
            style = MaterialTheme.typography.titleLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(FlowSpacing.sm))
        Text(
            text = when {
                readingFilter == ReadingFilter.FAVORITES -> stringResource(R.string.shelf_favorites_hint)
                readingFilter != ReadingFilter.ALL -> stringResource(R.string.shelf_status_empty)
                hiddenByGrouping -> stringResource(R.string.library_all_hidden_message)
                inGroup -> stringResource(R.string.library_group_empty_message)
                else -> stringResource(R.string.library_empty_message)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (hiddenByGrouping) {
            Spacer(modifier = Modifier.height(FlowSpacing.lg))
            Button(onClick = onShowGroupedBooks) {
                Text(stringResource(R.string.library_all_hidden_action))
            }
        } else {
            Spacer(modifier = Modifier.height(FlowSpacing.lg))
            TextButton(onClick = onImport) {
                Text(stringResource(R.string.library_empty_action))
            }
        }
    }
}

/**
 * Picks from the books that are already in the library and files them into the open group.
 *
 * Without this, a group created after the books were imported could only be populated one book at a
 * time through the per-book menu.
 */
@Composable
private fun AddExistingBooksDialog(books: List<Book>, groupName: String, onDismiss: () -> Unit, onConfirm: (List<Long>) -> Unit) {
    var selected by remember { mutableStateOf(emptySet<Long>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_add_existing_title, groupName)) },
        text = {
            if (books.isEmpty()) {
                Text(stringResource(R.string.library_add_existing_empty))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(FlowSpacing.xs)
                ) {
                    items(books, key = { it.id }) { book ->
                        ListItem(
                            headlineContent = {
                                Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(
                                    book.author.ifBlank { stringResource(R.string.unknown_author) },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = {
                                Checkbox(
                                    checked = book.id in selected,
                                    onCheckedChange = {
                                        selected = if (book.id in selected) {
                                            selected - book.id
                                        } else {
                                            selected + book.id
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                selected = if (book.id in selected) {
                                    selected - book.id
                                } else {
                                    selected + book.id
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty()
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun CategoryFilterRow(
    categories: List<Category>,
    selectedCategoryId: Long?,
    onSelectCategory: (Long?) -> Unit,
    onNewCategory: () -> Unit,
    onEditCategory: (Category) -> Unit,
    modifier: Modifier = Modifier
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = Color.Transparent,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedContainerColor = Color.Transparent,
        selectedLabelColor = MaterialTheme.colorScheme.onSurface
    )
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm)
    ) {
        item(key = "all_categories") {
            val isSelected = selectedCategoryId == null
            FlowChoiceChip(
                selected = isSelected,
                onClick = { onSelectCategory(null) },
                label = { Text(stringResource(R.string.library_filter_all)) }
            )
        }
        items(categories, key = { it.id }) { category ->
            val isSelected = selectedCategoryId == category.id
            FlowChoiceChip(
                selected = isSelected,
                onClick = { onSelectCategory(category.id) },
                label = { Text(category.name) },
                trailingIcon = if (isSelected) {
                    {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.category_edit_title),
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { onEditCategory(category) }
                        )
                    }
                } else {
                    null
                }
            )
        }
        item(key = "add_category") {
            AssistChip(
                onClick = onNewCategory,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = { Text(stringResource(R.string.category_new)) }
            )
        }
    }
}

@Composable
private fun NewCategoryDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.category_new_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.category_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim())
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun EditCategoryDialog(category: Category, onDismiss: () -> Unit, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(category.name) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.category_delete)) },
            text = { Text(stringResource(R.string.category_delete_confirm, category.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.category_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FlowSpacing.md)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.category_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(FlowSpacing.xs))
                        Text(
                            stringResource(R.string.category_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            onRename(name.trim())
                        }
                    },
                    enabled = name.isNotBlank() && name.trim() != category.name
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun MoveBookCategoryDialog(book: Book, categories: List<Category>, onDismiss: () -> Unit, onSelectCategory: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_move_title)) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.batch_move_none)) },
                    trailingContent = if (book.categoryId == null) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    modifier = Modifier.clickable {
                        onSelectCategory(null)
                        onDismiss()
                    }
                )
                categories.forEach { category ->
                    ListItem(
                        headlineContent = { Text(category.name) },
                        trailingContent = if (book.categoryId == category.id) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        modifier = Modifier.clickable {
                            onSelectCategory(category.id)
                            onDismiss()
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

private fun LibraryViewMode.toggle(): LibraryViewMode = if (this == LibraryViewMode.GRID) LibraryViewMode.LIST else LibraryViewMode.GRID

@Composable
internal fun ShelfFilterBar(groupName: String?, selected: ReadingFilter, onGroups: () -> Unit, onSelect: (ReadingFilter) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        // Five related filters share the same row; narrow screens and large text can scroll.
        val tabWidth = ((maxWidth - 16.dp) / 5.4f).coerceAtLeast(56.dp * fontScale)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ShelfTab(
                groupName ?: stringResource(R.string.library_filter_all),
                selected == ReadingFilter.ALL,
                onGroups,
                Modifier.width(tabWidth * 1.2f),
                true
            )
            listOf(ReadingFilter.UNREAD, ReadingFilter.READING, ReadingFilter.FINISHED, ReadingFilter.FAVORITES).forEach { filter ->
                val width = if (filter == ReadingFilter.READING) tabWidth * 1.2f else tabWidth
                ShelfTab(readingFilterName(filter), selected == filter, { onSelect(filter) }, Modifier.width(width))
            }
        }
    }
}

@Composable
private fun ShelfTab(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier, dropdown: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.heightIn(min = 48.dp).semantics { selected = active }.clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = if (active) colors.primary else Color.Transparent,
            contentColor = if (active) colors.onPrimary else colors.onSurfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    label,
                    if (dropdown) Modifier.weight(1f, fill = false) else Modifier,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (dropdown) Icon(FlowIcons.Down, null, Modifier.size(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShelfGroupSheet(
    state: LibraryUiState,
    onDismiss: () -> Unit,
    onSelect: (Long?) -> Unit,
    onShowAll: () -> Unit,
    onNew: () -> Unit,
    onEdit: (Category) -> Unit,
    onAddExisting: () -> Unit = {}
) {
    ModalBottomSheet(onDismissRequest = onDismiss, shape = MaterialTheme.shapes.extraLarge) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Text(stringResource(R.string.shelf_groups), style = MaterialTheme.typography.titleLarge) }
            item {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                        onSelect(null)
                        onDismiss()
                    }.padding(12.dp)
                ) {
                    Text(stringResource(R.string.library_filter_all), Modifier.weight(1f))
                    if (state.selectedCategoryId == null) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            items(state.categories, key = { it.id }) { category ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                        onSelect(category.id)
                        onDismiss()
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(category.name, Modifier.weight(1f).padding(12.dp), maxLines = 2)
                    if (state.selectedCategoryId == category.id) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { onEdit(category) }) { Icon(Icons.Default.Edit, stringResource(R.string.category_edit_title)) }
                }
            }
            if (state.selectedCategoryId != null) {
                item {
                    TextButton(
                        onClick = onAddExisting,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.library_add_existing)) }
                }
            }
            item { HorizontalDivider() }
            item {
                TextButton(
                    onClick = onShowAll,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.shelf_show_group_all)) }
            }
            item { TextButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.category_new)) } }
        }
    }
}

/** Import methods use existing local pickers and the configured folder scanner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShelfImportSheet(onDismiss: () -> Unit, onFiles: () -> Unit, onArchive: () -> Unit, onScanSettings: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.library_import),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            com.flowreader.app.ui.screens.settings.SettingsItem(
                icon = FlowIcons.File,
                badge = 5,
                title = stringResource(R.string.library_import_files),
                onClick = onFiles,
                trailing = { Icon(FlowIcons.Next, null, Modifier.size(16.dp)) }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            com.flowreader.app.ui.screens.settings.SettingsItem(
                icon = FlowIcons.Folder,
                badge = 3,
                title = stringResource(R.string.settings_scan_folder),
                subtitle = stringResource(R.string.settings_scan_folder_desc),
                onClick = onScanSettings,
                trailing = { Icon(FlowIcons.Next, null, Modifier.size(16.dp)) }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            com.flowreader.app.ui.screens.settings.SettingsItem(
                icon = FlowIcons.Import,
                badge = 4,
                title = stringResource(R.string.library_import_archive),
                onClick = onArchive,
                trailing = { Icon(FlowIcons.Next, null, Modifier.size(16.dp)) }
            )
        }
    }
}
