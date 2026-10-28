package com.flowreader.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowreader.app.R
import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.model.BookSeries
import com.flowreader.app.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@HiltViewModel
class BookSeriesViewModel @Inject constructor(private val repository: BookRepository) : ViewModel() {
    val books = repository.getAllBooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private fun save(onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try { action(); onSuccess() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.localizedMessage ?: "系列保存失败，请重试" }
            finally { busy = false }
        }
    }
    fun assign(ids: List<Long>, name: String?, done: () -> Unit = {}) = save(done) { repository.setBooksSeries(ids, name) }
    fun reorder(name: String, ids: List<Long>) = save { repository.reorderSeries(name, ids) }
    fun rename(name: String, newName: String, done: () -> Unit) = save(done) { repository.renameSeries(name, newName) }
}

/** Shared by the shelf and book details; series membership never changes a shelf group. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookSeriesSheet(
    initialName: String? = null,
    initialBookId: Long? = null,
    onOpenBook: (Long) -> Unit,
    onDismiss: () -> Unit,
    onShowDetail: (Long) -> Unit = onOpenBook,
    viewModel: BookSeriesViewModel = hiltViewModel()
) {
    val books by viewModel.books.collectAsState()
    var activeName by remember(initialName, initialBookId) { mutableStateOf(initialName) }
    var draft by remember(initialName, initialBookId) { mutableStateOf(initialName.orEmpty()) }
    var showPicker by remember { mutableStateOf(false) }
    val names = remember(books) { books.mapNotNull { it.seriesName }.distinct().sorted() }
    val volumes = remember(books, activeName) { BookSeries.ordered(books.filter { it.seriesName == activeName && activeName != null }) }
    val validName = runCatching { BookSeries.requireName(draft) }.getOrNull()
    val busy = viewModel.busy
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.88f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.series_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.series_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(draft, { draft = it }, label = { Text(stringResource(R.string.series_name)) }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeName == null) {
                        Button(enabled = validName != null && !busy, onClick = {
                            if (initialBookId != null) viewModel.assign(listOf(initialBookId), validName) { activeName = validName; draft = validName.orEmpty() }
                            else showPicker = true
                        }) { Text(stringResource(if (initialBookId == null) R.string.series_create else R.string.series_assign)) }
                    } else {
                        OutlinedButton(enabled = validName != null && validName != activeName && !busy, onClick = {
                            val old = activeName ?: return@OutlinedButton
                            val name = validName ?: return@OutlinedButton
                            viewModel.rename(old, name) { activeName = name; draft = name }
                        }) { Text(stringResource(R.string.series_rename)) }
                        Button(enabled = !busy, onClick = { showPicker = true }) { Text(stringResource(R.string.series_add_books)) }
                    }
                }
                viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (activeName == null) {
                items(names, key = { it }) { name ->
                    ListItem(headlineContent = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(stringResource(R.string.series_volume_count, books.count { it.seriesName == name })) },
                        trailingContent = { TextButton(enabled = !busy, onClick = {
                            if (initialBookId != null) viewModel.assign(listOf(initialBookId), name) { activeName = name; draft = name }
                            else { activeName = name; draft = name }
                        }) { Text(stringResource(if (initialBookId == null) R.string.series_open else R.string.series_assign)) } })
                }
                if (names.isEmpty()) item { Text(stringResource(R.string.series_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item { Text(stringResource(R.string.series_volume_count, volumes.size), style = MaterialTheme.typography.titleSmall) }
                items(volumes, key = { it.id }) { book ->
                    val index = volumes.indexOfFirst { it.id == book.id }
                    Column {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", Modifier.width(28.dp), color = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).clickable(enabled = !busy) { onOpenBook(book.id) }.padding(vertical = 12.dp)) {
                                Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(enabled = index > 0 && !busy, onClick = {
                                val ids = volumes.map { it.id }.toMutableList(); java.util.Collections.swap(ids, index, index - 1)
                                viewModel.reorder(activeName!!, ids)
                            }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.series_move_up, book.title)) }
                            IconButton(enabled = index < volumes.lastIndex && !busy, onClick = {
                                val ids = volumes.map { it.id }.toMutableList(); java.util.Collections.swap(ids, index, index + 1)
                                viewModel.reorder(activeName!!, ids)
                            }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.series_move_down, book.title)) }
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(enabled = !busy, onClick = { onShowDetail(book.id) }) { Text(stringResource(R.string.book_action_detail)) }
                            TextButton(enabled = !busy, onClick = { viewModel.assign(listOf(book.id), null) }) { Text(stringResource(R.string.series_remove)) }
                            TextButton(enabled = !busy, onClick = { onOpenBook(book.id) }) { Text(stringResource(R.string.book_detail_start_reading)) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (volumes.isEmpty()) item { Text(stringResource(R.string.series_no_volumes)) }
            }
        }
    }
    if (showPicker) {
        val candidates = books.filter { activeName == null || it.seriesName != activeName }
        var selected by remember(showPicker) { mutableStateOf(emptySet<Long>()) }
        AlertDialog(onDismissRequest = { if (!busy) showPicker = false }, title = { Text(stringResource(R.string.series_add_books)) },
            text = {
                Column {
                    Text(stringResource(R.string.series_move_hint), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(candidates, key = { it.id }) { book ->
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                selected = if (book.id in selected) selected - book.id else selected + book.id
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(book.id in selected, null)
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    book.seriesName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                    viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }, confirmButton = { TextButton(enabled = selected.isNotEmpty() && validName != null && !busy, onClick = {
                val name = activeName ?: validName ?: return@TextButton
                viewModel.assign(candidates.filter { it.id in selected }.map { it.id }, name) { activeName = name; draft = name; showPicker = false }
            }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) } })
    }
}
