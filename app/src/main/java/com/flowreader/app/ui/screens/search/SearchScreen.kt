package com.flowreader.app.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.component.BookCover
import com.flowreader.app.core.designsystem.component.FlowIcons
import com.flowreader.app.core.designsystem.component.FlowTopBar
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.app.core.util.FlowFormatters
import com.flowreader.app.domain.model.GlobalSearchResult
import com.flowreader.app.domain.model.SearchHitProjector
import com.flowreader.app.domain.repository.SearchProgress

/**
 * The independent search destination (v55): search history on idle, then two-section results —
 * books (title/author) and canonical chapter matches, with progress, cancellation and paged loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    initialQuery: String,
    onBackClick: () -> Unit,
    onBookClick: (Long) -> Unit,
    onChapterClick: (Long, Int, Int) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    DisposableEffect(viewModel) { onDispose { viewModel.cancelSearch() } }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            viewModel.updateQuery(initialQuery)
        }
    }

    SearchContent(
        uiState,
        onBackClick,
        onBookClick,
        onChapterClick,
        viewModel::updateQuery,
        viewModel::cancelSearch,
        viewModel::loadMoreChapters,
        viewModel::clearHistory
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SearchContent(
    uiState: SearchUiState,
    onBackClick: () -> Unit,
    onBookClick: (Long) -> Unit,
    onChapterClick: (Long, Int, Int) -> Unit,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
    onLoadMore: () -> Unit,
    onClearHistory: () -> Unit
) {
    Scaffold(
        topBar = {
            FlowTopBar(title = stringResource(R.string.search_title), onNavigateUp = onBackClick)
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = uiState.query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = FlowSpacing.lg, vertical = FlowSpacing.sm)
            )
            if (uiState.isSearching) {
                SearchProgressRow(uiState.progress, onCancel)
            }
            uiState.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = FlowSpacing.lg))
            }
            if (uiState.cancelled) {
                Row(Modifier.fillMaxWidth().padding(horizontal = FlowSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.search_cancelled), modifier = Modifier.weight(1f))
                    TextButton(onClick = { onQueryChange(uiState.query) }) { Text(stringResource(R.string.search_retry)) }
                }
            }

            when {
                uiState.query.isBlank() -> {
                    HistorySection(
                        history = uiState.history,
                        onUseHistory = onQueryChange,
                        onClearHistory = onClearHistory
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(FlowSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FlowSpacing.md)
                ) {
                    item(key = "book_header") {
                        Text(
                            text = stringResource(R.string.search_section_books, uiState.bookResults.size),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    if (uiState.bookResults.isEmpty() && !uiState.isSearching && !uiState.cancelled && uiState.error == null) {
                        item(key = "book_empty") {
                            Text(
                                text = stringResource(R.string.search_no_books),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(uiState.bookResults, key = { "book-${it.id}" }) { book ->
                        BookSearchRow(book = book, onClick = { onBookClick(book.id) })
                    }

                    item(key = "chapter_header") {
                        Text(
                            text = stringResource(R.string.search_section_chapters, uiState.chapterResults.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = FlowSpacing.md)
                        )
                    }

                    items(uiState.chapterResults, key = { "chapter-${it.bookId}-${it.chapterIndex}" }) { result ->
                        ChapterSearchRow(
                            result = result,
                            onClick = {
                                // 带上**已验证**的章内偏移；无可靠坐标时传 -1，由阅读器退回章首
                                // 并显示「定位待恢复」，绝不用 chapterIndex 伪造偏移。
                                onChapterClick(result.bookId, result.chapterIndex, result.firstStartUtf16 ?: -1)
                            }
                        )
                    }

                    if (uiState.chapterResults.isEmpty() && !uiState.isSearching && !uiState.cancelled && uiState.error == null) {
                        item(key = "chapter_empty") { Text(stringResource(R.string.reader_search_no_results)) }
                    }

                    if (uiState.hasMoreChapters) {
                        item(key = "load_more") {
                            OutlinedButton(
                                onClick = onLoadMore,
                                enabled = !uiState.isSearching,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = FlowSpacing.sm)
                            ) {
                                Text(stringResource(R.string.search_load_more))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchProgressRow(progress: SearchProgress?, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = FlowSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (progress != null && progress.totalChapters > 0) {
                LinearProgressIndicator(
                    progress = { progress.completedChapters.toFloat() / progress.totalChapters },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.search_progress, progress.completedChapters, progress.totalChapters),
                    style = MaterialTheme.typography.labelSmall
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.search_preparing), style = MaterialTheme.typography.labelSmall)
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun HistorySection(history: List<String>, onUseHistory: (String) -> Unit, onClearHistory: () -> Unit) {
    Column(modifier = Modifier.padding(FlowSpacing.lg)) {
        if (history.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = FlowSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    FlowIcons.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(FlowSpacing.md))
                Text(
                    text = stringResource(R.string.search_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(FlowSpacing.sm))
                Text(
                    text = stringResource(R.string.search_history_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClearHistory) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(FlowSpacing.xs))
                    Text(stringResource(R.string.search_clear_history))
                }
            }
            Spacer(modifier = Modifier.height(FlowSpacing.sm))
            history.forEach { query ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onUseHistory(query) }
                        .padding(vertical = FlowSpacing.sm)
                ) {
                    Text(
                        text = query,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun BookSearchRow(book: com.flowreader.app.domain.model.Book, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(text = book.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                text = book.author + if (book.readingProgress > 0f) " · " + FlowFormatters.percent(book.readingProgress) else "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            BookCover(
                title = book.title,
                author = book.author,
                coverPath = book.coverPath,
                modifier = Modifier.size(44.dp, 62.dp),
                showTitleOnFallback = false
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun ChapterSearchRow(result: GlobalSearchResult, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = result.bookTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = result.chapterTitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = result.matchedText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                // 无法精确锁定时不误导用户：明确说明会跳到章首附近。
                if (result.confidence != SearchHitProjector.Confidence.EXACT) {
                    Text(
                        text = stringResource(R.string.search_match_approximate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                FlowIcons.Book,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
