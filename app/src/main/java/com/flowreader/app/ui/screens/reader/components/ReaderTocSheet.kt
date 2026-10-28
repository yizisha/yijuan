package com.flowreader.app.ui.screens.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.component.FlowIcons
import com.flowreader.app.core.designsystem.component.FlowTabBar
import com.flowreader.app.core.designsystem.reader.ReaderPalette
import com.flowreader.app.core.designsystem.reader.text
import com.flowreader.app.core.designsystem.theme.flowColorScheme
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.app.domain.model.Annotation
import com.flowreader.app.domain.model.Bookmark
import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.model.TocCoverage
import com.flowreader.app.domain.model.TocLocation
import com.flowreader.app.domain.model.TocMatchKind
import com.flowreader.app.domain.model.TocNodeKind
import com.flowreader.app.domain.model.TocOutline
import com.flowreader.app.domain.model.TocRow
import com.flowreader.app.domain.model.TocTree

/**
 * 全屏目录（主计划 7.1 / 7.2）。
 *
 * ## 与旧版的区别
 *
 * 旧版接收扁平 `List<Chapter>`，在内部用正则猜卷、卷行**完全不可点击**（既不能展开也不能收起），
 * 也没有「全部展开/收起」、没有覆盖范围、没有死分组提示。
 *
 * 现在：
 * - 只消费 [TocTree] + [TocLocation] + 展开状态，**不含任何结构猜测**；
 * - 卷行主体与箭头**都**只做展开/收起（触控目标 ≥48dp）；
 * - 有独立目标的卷另有「读卷首」按钮，不会同一点击既展开又跳转；
 * - 无子节点又无目标的卷显示「暂无可读章节」且不显示误导性箭头；
 * - 连续覆盖显示「第 X—Y 节」，非连续显示「共 N 节 · 非连续」，不假装覆盖中间章节。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderTocSheet(
    bookTitle: String,
    tocTree: TocTree,
    currentLocation: TocLocation,
    expandedNodeIds: Set<String>,
    onExpandedChange: (Set<String>) -> Unit,
    bookmarks: List<Bookmark>,
    annotations: List<Annotation>,
    chapters: List<Chapter>,
    palette: ReaderPalette,
    onNodeSelect: (String?) -> Unit,
    onBookmarkSelect: (Bookmark) -> Unit,
    onAnnotationSelect: (Annotation) -> Unit,
    onDismiss: () -> Unit,
    initialTab: Int = 0,
    onBookmarkDelete: ((Bookmark) -> Unit)? = null,
    onAnnotationDelete: ((Annotation) -> Unit)? = null,
    onAnnotationNoteUpdate: ((Long, String) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var editingNote by remember { mutableStateOf<Annotation?>(null) }
    var pendingDelete by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val rows = remember(tocTree, expandedNodeIds, currentLocation, query) {
        TocOutline.flatten(tocTree, expandedNodeIds, currentLocation, query)
    }
    val isSearching = query.isNotBlank()

    val listState = rememberLazyListState()
    // 首次打开/定位变化时把当前行滚进视野。搜索时不滚动：结果本身就是过滤后的。
    LaunchedEffect(tab, currentLocation, expandedNodeIds) {
        if (tab == 0 && !isSearching) {
            val index = rows.indexOfFirst { it.isCurrent }
            if (index >= 0) listState.scrollToItem(index)
        }
    }

    MaterialTheme(colorScheme = flowColorScheme(palette.isDark)) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = MaterialTheme.shapes.extraLarge) {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.94f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(FlowIcons.Back, stringResource(R.string.action_close), Modifier.size(20.dp)) }
                    FlowTabBar(
                        listOf(R.string.reader_toc_tab_toc, R.string.reader_toc_tab_bookmarks, R.string.reader_toc_tab_notes)
                            .map { stringResource(it) },
                        tab,
                        { tab = it },
                        Modifier.weight(1f)
                    )
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(FlowIcons.Search, stringResource(R.string.action_search), Modifier.size(20.dp))
                    }
                }
                Text(
                    bookTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                when (tab) {
                    0 -> TocTab(
                        rows = rows,
                        showSearch = showSearch,
                        query = query,
                        onQueryChange = { query = it },
                        isSearching = isSearching,
                        palette = palette,
                        listState = listState,
                        onToggle = { nodeId ->
                            onExpandedChange(TocOutline.toggle(expandedNodeIds, nodeId))
                        },
                        onExpandAll = { onExpandedChange(tocTree.expandableIds.toSet()) },
                        onCollapseAll = { onExpandedChange(emptySet()) },
                        onSelect = { nodeId ->
                            onNodeSelect(nodeId)
                            onDismiss()
                        }
                    )

                    2 -> NotesTab(
                        annotations = annotations,
                        chapters = chapters,
                        palette = palette,
                        emptyMessage = stringResource(R.string.reader_toc_notes_empty),
                        titleOf = { annotation -> chapters.getOrNull(annotation.chapterIndex)?.title.orEmpty() },
                        bodyOf = { annotation ->
                            listOf(
                                annotation.selectedText,
                                annotation.note
                            ).filter { it.isNotBlank() }.joinToString("\n")
                        },
                        keyOf = { annotation -> "a_${annotation.id}" },
                        onDelete = onAnnotationDelete?.let { callback ->
                            { annotation: Annotation ->
                                pendingDelete = annotation.selectedText to {
                                    callback(annotation)
                                }
                            }
                        },
                        onEdit = onAnnotationNoteUpdate?.let { { annotation: Annotation -> editingNote = annotation } },
                        onClick = { annotation ->
                            onAnnotationSelect(annotation)
                            onDismiss()
                        }
                    )

                    else -> NotesTab(
                        annotations = bookmarks,
                        chapters = chapters,
                        palette = palette,
                        emptyMessage = stringResource(R.string.reader_toc_bookmarks_empty),
                        titleOf = { bookmark -> chapters.getOrNull(bookmark.chapterIndex)?.title.orEmpty() },
                        bodyOf = { bookmark -> bookmark.text },
                        keyOf = { bookmark -> "b_${bookmark.id}" },
                        onDelete = onBookmarkDelete?.let { callback -> { bookmark: Bookmark -> pendingDelete = bookmark.text to { callback(bookmark) } } },
                        onClick = { bookmark ->
                            onBookmarkSelect(bookmark)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
    MaterialTheme(colorScheme = flowColorScheme(palette.isDark)) {
        editingNote?.let { annotation ->
            var draft by remember(annotation.id) { mutableStateOf(annotation.note) }
            AlertDialog(
                onDismissRequest = { editingNote = null },
                modifier = Modifier.padding(horizontal = 16.dp),
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                title = { Text(stringResource(R.string.reader_selection_note)) },
                text = {
                    Column {
                        Text(annotation.selectedText, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        OutlinedTextField(draft, {
                            draft = it
                        }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, label = {
                            Text(
                                stringResource(R.string.reader_note_label)
                            )
                        })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onAnnotationNoteUpdate?.invoke(annotation.id, draft)
                        editingNote = null
                    }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = { TextButton(onClick = { editingNote = null }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }
        pendingDelete?.let { (text, action) ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(R.string.action_delete)) },
                text = { Text(text, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDelete = null
                        action()
                    }) { Text(stringResource(R.string.action_delete)) }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }
    }
}

@Composable
private fun TocHeader(bookTitle: String, tocTree: TocTree, palette: ReaderPalette, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FlowSpacing.lg, vertical = FlowSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bookTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.reader_toc_summary,
                    tocTree.allNodes.count { it.kind == TocNodeKind.ENTRY && it.hasTarget }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.action_close),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun TocTab(
    rows: List<TocRow>,
    showSearch: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    isSearching: Boolean,
    palette: ReaderPalette,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggle: (String) -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onSelect: (String?) -> Unit
) {
    if (showSearch || isSearching) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            leadingIcon = { Icon(FlowIcons.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.reader_toc_search_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FlowSpacing.lg, vertical = FlowSpacing.sm)
        )
    }
    // 全部展开/全部收起：计划 7.2 第 9 条。搜索进行中隐藏，避免与命中过滤冲突。
    if (showSearch && !isSearching) {
        Row(
            modifier = Modifier.padding(horizontal = FlowSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm)
        ) {
            TextButton(onClick = onExpandAll) {
                Text(stringResource(R.string.reader_toc_expand_all))
            }
            TextButton(onClick = onCollapseAll) {
                Text(stringResource(R.string.reader_toc_collapse_all))
            }
        }
    }

    if (rows.isEmpty()) {
        // 计划 7.2 第 8 条：搜索无结果给空态。
        TocEmpty(stringResource(R.string.reader_toc_search_empty), palette)
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = FlowSpacing.xxl)
    ) {
        items(rows, key = { row -> row.node.nodeId }) { row ->
            TocRowItem(row = row, palette = palette, onToggle = onToggle, onSelect = onSelect)
        }
    }
}

@Composable
private fun TocRowItem(row: TocRow, palette: ReaderPalette, onToggle: (String) -> Unit, onSelect: (String?) -> Unit) {
    val node = row.node
    val isGroup = node.kind == TocNodeKind.GROUP
    // 缩进封顶：更深层级靠语义/面包屑表达，不能把文字挤成一列（7.2 第 10 条）。
    val indent = FlowSpacing.md * row.indentDepth

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 整行 ≥48dp 触控目标；无障碍读出卷标题/展开状态/数量/当前状态（7.2 第 15 条）。
            .semantics {
                contentDescription = tocRowDescription(row)
            }
            .clickable {
                // 卷行主体与箭头**都**只做展开/收起；普通条目点击才跳转（7.2 第 1、3 条）。
                if (isGroup) onToggle(node.nodeId) else onSelect(node.nodeId)
            }
            .background(
                if (row.isCurrent) {
                    MaterialTheme.colorScheme.primaryContainer.copy(
                        alpha = .65f
                    )
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .padding(start = FlowSpacing.lg + indent, end = FlowSpacing.lg, top = 0.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (row.showExpandArrow) {
            IconButton(
                onClick = { onToggle(node.nodeId) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (row.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (row.isExpanded) R.string.reader_toc_collapse else R.string.reader_toc_expand
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // 无子节点时留同宽占位，保证同级文字对齐。
            if (row.isCurrent) {
                Box(
                    Modifier.width(3.dp).height(20.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
            }
            Spacer(modifier = Modifier.width(if (row.isCurrent) 9.dp else 0.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.label,
                style = if (isGroup) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                fontWeight = when {
                    row.isCurrent -> FontWeight.Medium
                    isGroup -> FontWeight.Bold
                    else -> FontWeight.Normal
                },
                color = when {
                    row.isCurrent -> MaterialTheme.colorScheme.primary
                    row.matchKind == TocMatchKind.NONE && !node.hasTarget && isGroup -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = tocRowSubtitle(row)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 有独立目标的卷：单独的「读卷首」，与展开/收起分开（7.2 第 1 条）。
        if (isGroup && node.hasTarget) {
            TextButton(
                onClick = { onSelect(node.nodeId) },
                modifier = Modifier.widthIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.reader_toc_read_volume))
            }
        }
    }
}

/** 行的副标题：卷显示覆盖范围或「暂无可读章节」，命中项显示祖先面包屑。 */
@Composable
private fun tocRowSubtitle(row: TocRow): String? {
    row.coverage?.let { coverage ->
        return when (coverage.displayKind) {
            TocCoverage.DisplayKind.NONE -> stringResource(R.string.reader_toc_no_readable)
            TocCoverage.DisplayKind.RANGE -> stringResource(
                R.string.reader_toc_coverage_range,
                (coverage.firstOrdinal ?: 0) + 1,
                (coverage.lastOrdinal ?: 0) + 1,
                coverage.readableCount
            )
            TocCoverage.DisplayKind.NON_CONTIGUOUS -> stringResource(
                R.string.reader_toc_coverage_non_contiguous,
                coverage.readableCount
            )
        }
    }
    return null
}

private fun tocRowDescription(row: TocRow): String {
    val parts = mutableListOf(row.node.label)
    when (row.node.kind) {
        TocNodeKind.GROUP -> {
            parts.add(if (row.isExpanded) "已展开" else "已收起")
            row.coverage?.let { coverage ->
                if (coverage.readableCount == 0) {
                    parts.add("暂无可读章节")
                } else {
                    parts.add("${coverage.readableCount} 节")
                }
            }
        }
        TocNodeKind.ENTRY -> Unit
    }
    if (row.isCurrent) parts.add("正在阅读")
    return parts.joinToString("，")
}

@Composable
private fun <T> NotesTab(
    annotations: List<T>,
    chapters: List<Chapter>,
    palette: ReaderPalette,
    emptyMessage: String,
    titleOf: (T) -> String,
    bodyOf: (T) -> String,
    keyOf: (T) -> String,
    onClick: (T) -> Unit,
    onDelete: ((T) -> Unit)? = null,
    onEdit: ((T) -> Unit)? = null
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FlowSpacing.lg, vertical = FlowSpacing.sm)
    ) {
        if (annotations.isEmpty()) {
            item { TocEmpty(emptyMessage, palette) }
        }
        items(annotations, key = { keyOf(it) }) { entry ->
            TocNoteRow(
                title = titleOf(entry).ifBlank { chapters.getOrNull(0)?.title.orEmpty() },
                body = bodyOf(entry),
                palette = palette,
                onClick = { onClick(entry) },
                onDelete = onDelete?.let { { it(entry) } },
                onEdit = onEdit?.let { { it(entry) } }
            )
        }
    }
}

@Composable
private fun TocNoteRow(
    title: String,
    body: String,
    palette: ReaderPalette,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = FlowSpacing.md),
        verticalArrangement = Arrangement.spacedBy(FlowSpacing.xs)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (onEdit != null) IconButton(onClick = onEdit) { Icon(FlowIcons.Notes, stringResource(R.string.reader_selection_note)) }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(FlowIcons.Trash, stringResource(R.string.action_delete)) }
        }
        Text(
            text = body.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(FlowSpacing.xs))
    }
}

@Composable
private fun TocEmpty(message: String, palette: ReaderPalette) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(FlowSpacing.xxl),
        contentAlignment = Alignment.Center
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
