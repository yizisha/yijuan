package com.flowreader.app.ui.screens.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.app.core.util.FlowFormatters
import com.flowreader.app.ui.components.durationText

/**
 * 阅读统计, the book's own statistics screen.
 *
 * The detail page is an overview; the numbers live here so neither screen is a long scroll. All
 * values come from data the detail ViewModel already loads — no extra query, and no second source
 * of truth for reading time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookStatsScreen(
    onBackClick: () -> Unit,
    viewModel: BookDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val book = uiState.book

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.book_detail_reading_stats)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(FlowSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FlowSpacing.md)
        ) {
            Text(
                text = book?.title.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = book?.author.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Progress first: it is the number a reader actually looks for.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(FlowSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm)
                ) {
                    Text(
                        text = stringResource(R.string.book_detail_progress_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = FlowFormatters.percent(book?.readingProgress ?: 0f),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = { (book?.readingProgress ?: 0f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.book_detail_progress_detail,
                            (book?.currentChapter ?: 0) + 1,
                            book?.totalChapters ?: 0
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(FlowSpacing.lg)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        StatBlock(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Timer,
                            value = durationText(uiState.totalReadTime),
                            label = stringResource(R.string.stats_read_time)
                        )
                        StatBlock(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.LocalFireDepartment,
                            value = "${uiState.daysRead}",
                            label = stringResource(R.string.stats_unit_days_read)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(top = FlowSpacing.md)) {
                        StatBlock(
                            modifier = Modifier.weight(1f),
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            value = "${uiState.totalReadPages}",
                            label = stringResource(R.string.stats_read_pages)
                        )
                        StatBlock(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Schedule,
                            value = formatWordCount(book?.wordCount ?: 0),
                            label = stringResource(R.string.book_detail_word_count)
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(FlowSpacing.lg)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        StatBlock(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Bookmark,
                            value = "${uiState.bookmarks.size}",
                            label = stringResource(R.string.reader_toc_tab_bookmarks)
                        )
                        StatBlock(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Highlight,
                            value = "${uiState.annotations.size}",
                            label = stringResource(R.string.reader_toc_tab_notes)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(top = FlowSpacing.md)) {
                        StatBlock(
                            modifier = Modifier.weight(1f),
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            value = "${book?.totalChapters ?: 0}",
                            label = stringResource(R.string.book_detail_chapter_count_label)
                        )
                        StatBlock(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Schedule,
                            value = formatFileSize(book?.fileSize ?: 0L),
                            label = stringResource(R.string.book_detail_file_size)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBlock(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 字数 as readers write it: 万 above ten thousand characters, plain digits below. */
internal fun formatWordCount(count: Int): String = when {
    count <= 0 -> "—"
    count >= 10_000 -> "%.1f 万".format(count / 10_000f)
    else -> "$count"
}

/** Byte size in the units a file manager would show. */
internal fun formatFileSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
