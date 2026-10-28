package com.flowreader.app.ui.screens.continue_reading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.flowreader.app.core.designsystem.component.FlowStateHost
import com.flowreader.app.core.designsystem.component.FlowTopBar
import com.flowreader.app.core.designsystem.token.FlowRadius
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.app.core.util.FlowFormatters
import com.flowreader.app.domain.model.Book

/** Reading history, opened from the shelf and ordered by the last reading time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContinueScreen(
    onBookClick: (Long) -> Unit,
    onContinueReading: (Long) -> Unit,
    viewModel: ContinueViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val books by viewModel.books.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        topBar = { FlowTopBar(title = stringResource(R.string.ui_history), onNavigateUp = onBack) }
    ) { paddingValues ->
        FlowStateHost(
            isLoading = isLoading && books.isEmpty(),
            isEmpty = books.isEmpty(),
            error = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            emptyTitle = stringResource(R.string.continue_empty_title),
            emptyMessage = stringResource(R.string.continue_empty_message),
            emptyIcon = Icons.AutoMirrored.Filled.MenuBook
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(FlowSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(FlowSpacing.md)
            ) {
                items(books, key = { it.id }) { book ->
                    ContinueBookCard(
                        book = book,
                        onClick = { onBookClick(book.id) },
                        onContinue = { onContinueReading(book.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueBookCard(book: Book, onClick: () -> Unit, onContinue: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(FlowRadius.lg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FlowSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCover(
                title = book.title,
                author = book.author,
                coverPath = book.coverPath,
                modifier = Modifier.size(64.dp, 92.dp),
                showTitleOnFallback = false
            )

            Spacer(modifier = Modifier.width(FlowSpacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(FlowSpacing.xs))
                Text(
                    text = stringResource(
                        R.string.continue_chapter_of,
                        book.currentChapter + 1,
                        book.totalChapters
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(FlowSpacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { book.readingProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                    )
                    Spacer(modifier = Modifier.width(FlowSpacing.sm))
                    Text(
                        text = FlowFormatters.percent(book.readingProgress),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(FlowSpacing.sm))

            FilledTonalButton(onClick = onContinue) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(FlowSpacing.xs))
                Text(stringResource(R.string.continue_action))
            }
        }
    }
}
