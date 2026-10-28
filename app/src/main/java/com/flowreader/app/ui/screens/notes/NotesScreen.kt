package com.flowreader.app.ui.screens.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.component.BookCover
import com.flowreader.app.core.designsystem.component.FlowChoiceChip
import com.flowreader.app.core.designsystem.component.FlowIcons
import com.flowreader.app.core.designsystem.component.FlowScaffold
import com.flowreader.app.core.designsystem.component.FlowStateHost
import com.flowreader.app.core.designsystem.component.FlowTabBar
import com.flowreader.app.core.designsystem.component.FlowTopBar
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.app.domain.repository.AnnotationExportFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Every highlight and note in the library, in one place.
 *
 * Before v53 an annotation only existed inside the book that produced it, so "what did I write
 * about focus last year" meant opening books one by one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(onBackClick: () -> Unit, onOpenPassage: (Long, Int) -> Unit, viewModel: NotesViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var searchOpen by remember { mutableStateOf(false) }
    val visibleNotes = uiState.notes.filter { it.isHighlightOnly == (tab == 1) }
    var editingNote by remember { mutableStateOf<com.flowreader.app.domain.model.Annotation?>(null) }
    var showExportMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    FlowScaffold(
        topBar = {
            FlowTopBar(
                title = stringResource(R.string.notes_title),
                primaryPage = true,
                actions = {
                    IconButton(
                        onClick = { searchOpen = !searchOpen }
                    ) { Icon(FlowIcons.Search, stringResource(R.string.action_search), Modifier.size(20.dp)) }
                    IconButton(onClick = { showExportMenu = true }) {
                        Icon(FlowIcons.Share, contentDescription = stringResource(R.string.notes_export))
                    }
                    DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                        AnnotationExportFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(format.name) },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.exportVisible(format, highlightsOnly = tab == 1)
                                }
                            )
                        }
                    }
                }
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FlowTabBar(listOf(stringResource(R.string.ui_nav_notes), stringResource(R.string.notes_tab_highlights)), tab, { tab = it })
            if (searchOpen || uiState.query.isNotBlank()) {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = { viewModel.updateQuery(it) },
                    label = { Text(stringResource(R.string.notes_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FlowSpacing.lg, vertical = FlowSpacing.sm)
                )
            }

            if (uiState.books.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm),
                    contentPadding = PaddingValues(horizontal = FlowSpacing.lg)
                ) {
                    item(key = "all") {
                        FlowChoiceChip(
                            selected = uiState.selectedBookId == null,
                            onClick = { viewModel.selectBook(null) },
                            label = { Text(stringResource(R.string.notes_filter_all)) }
                        )
                    }
                    items(uiState.books, key = { it.id }) { book ->
                        FlowChoiceChip(
                            selected = uiState.selectedBookId == book.id,
                            onClick = { viewModel.selectBook(book.id) },
                            label = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
            }

            FlowStateHost(
                isLoading = uiState.isLoading,
                isEmpty = visibleNotes.isEmpty(),
                error = null,
                modifier = Modifier.fillMaxSize(),
                emptyTitle = stringResource(R.string.notes_empty_title),
                emptyMessage = stringResource(R.string.notes_empty_message),
                emptyIcon = Icons.AutoMirrored.Filled.MenuBook
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(FlowSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FlowSpacing.md)
                ) {
                    items(visibleNotes, key = { it.annotation.id }) { item ->
                        NoteCard(
                            item = item,
                            onOpen = { onOpenPassage(item.annotation.bookId, item.annotation.chapterIndex) },
                            onEdit = { editingNote = item.annotation },
                            onDelete = { viewModel.deleteNote(item.annotation.id) }
                        )
                    }
                }
            }
        }
    }

    editingNote?.let { annotation ->
        var draft by remember(annotation.id) { mutableStateOf(annotation.note) }
        AlertDialog(
            onDismissRequest = {
                editingNote = null
            },
            modifier = Modifier.padding(horizontal = 16.dp),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            title = {
                Text(stringResource(R.string.reader_selection_note))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(annotation.selectedText, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    OutlinedTextField(draft, {
                        draft = it
                    }, modifier = Modifier.fillMaxWidth(), label = {
                        Text(
                            stringResource(R.string.reader_note_label)
                        )
                    }, minLines = 3, maxLines = 6)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateNote(annotation, draft)
                    editingNote = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editingNote = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    val exportText = uiState.exportText
    if (exportText != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearExport() },
            title = { Text(stringResource(R.string.notes_export_result, uiState.exportedCount)) },
            text = {
                Text(
                    text = exportText.take(EXPORT_PREVIEW_CHARS),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(exportText))
                        viewModel.clearExport()
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearExport() }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
}

private const val EXPORT_PREVIEW_CHARS = 4000

@Composable
internal fun NoteCard(item: NoteItem, onOpen: () -> Unit, onDelete: () -> Unit, onEdit: () -> Unit = {}) {
    var menuOpen by remember { mutableStateOf(false) }
    val date =
        remember(item.annotation.createdTime) { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(item.annotation.createdTime) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.padding(top = 4.dp).width(3.dp).height(18.dp)
                    .background(Color(item.annotation.color.colorValue), RoundedCornerShape(2.dp))
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    item.annotation.selectedText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
                )
                if (item.annotation.note.isNotBlank()) {
                    Text(
                        item.annotation.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(FlowIcons.MoreVertical, stringResource(R.string.action_more), Modifier.size(18.dp))
                }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_selection_note)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.notes_open_book)) },
                        onClick = {
                            menuOpen = false
                            onOpen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 15.dp, top = 6.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BookCover(
                item.bookTitle,
                author = item.author,
                coverPath = item.coverPath,
                modifier = Modifier.width(16.dp).height(22.dp),
                showTitleOnFallback = false
            )
            Text(
                stringResource(R.string.notes_source, item.bookTitle, item.annotation.chapterIndex + 1),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    }
}
