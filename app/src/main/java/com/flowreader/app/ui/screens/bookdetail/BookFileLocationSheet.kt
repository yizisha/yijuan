package com.flowreader.app.ui.screens.bookdetail

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.flowreader.app.R
import com.flowreader.app.domain.model.Book
import com.flowreader.app.util.BookFileLocationStore
import com.flowreader.app.util.BookFileSources
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookFileLocationSheet(book: Book, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { BookFileLocationStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var sources by remember(book.filePath) { mutableStateOf(BookFileSources()) }
    var originalLocation by remember { mutableStateOf<String?>(null) }
    var copyLocation by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val stored = withContext(Dispatchers.IO) { store.sources(book.filePath) }
        originalLocation = stored.original?.let(store::displayLocation)
        copyLocation = stored.savedCopy?.let(store::displayLocation)
        sources = stored
        loaded = true
    }
    LaunchedEffect(book.filePath) { refresh() }

    val associate = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                message = null
                runCatching { withContext(Dispatchers.IO) { store.recordOriginal(book.filePath, uri) } }
                    .onSuccess { refresh() }
                    .onFailure { message = context.getString(R.string.book_file_source_failed) }
                busy = false
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                message = null
                runCatching { withContext(Dispatchers.IO) { store.saveReadingCopy(book.filePath, uri) } }
                    .onSuccess {
                        refresh()
                        message = context.getString(R.string.book_file_saved)
                    }
                    .onFailure { message = context.getString(R.string.book_file_save_failed) }
                busy = false
            }
        }
    }
    fun openLocation(uri: Uri) {
        message = null
        store.directoryUnavailableReason(uri)?.let { message = it; return }
        try {
            // 优先使用支持 EXTRA_INITIAL_URI 的系统文档浏览器；第三方 ACTION_VIEW
            // 即使启动成功也可能忽略目录 URI，不能把“成功打开应用”当作已定位。
            context.startActivity(store.browserIntent(uri))
        } catch (_: ActivityNotFoundException) {
            val direct = store.directoryIntent(uri)
            if (direct == null) message = context.getString(R.string.book_file_open_failed)
            else runCatching { context.startActivity(direct) }
                .onSuccess { message = "已请求文件管理器打开该父目录；若未定位，请复制位置。" }
                .onFailure { message = context.getString(R.string.book_file_open_failed) }
        } catch (_: SecurityException) {
            message = "系统未允许访问该父目录，可重新关联本地文件或保存副本。"
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        BookFileLocationContent(
            readingPath = book.filePath,
            originalLocation = originalLocation,
            savedCopyLocation = copyLocation,
            loaded = loaded,
            busy = busy,
            message = message,
            canOpenOriginal = sources.original?.let(store::canOpenDirectory) == true,
            originalDirectoryMessage = sources.original?.let(store::directoryUnavailableReason),
            onCopy = {
                clipboard.setText(AnnotatedString(it))
                message = context.getString(R.string.book_file_copied)
            },
            onOpenOriginal = { sources.original?.let(::openLocation) },
            onOpenSavedCopy = { sources.savedCopy?.let(::openLocation) },
            onAssociate = { associate.launch(arrayOf("*/*")) },
            onSaveCopy = { save.launch(File(book.filePath).name.ifBlank { "${book.title}.${book.format.name.lowercase()}" }) }
        )
    }
}

/** Stateless surface shared with native rendering/interaction tests. */
@Composable
internal fun BookFileLocationContent(
    readingPath: String,
    originalLocation: String?,
    savedCopyLocation: String?,
    loaded: Boolean,
    busy: Boolean,
    message: String?,
    canOpenOriginal: Boolean,
    onCopy: (String) -> Unit,
    onOpenOriginal: () -> Unit,
    onOpenSavedCopy: () -> Unit,
    onAssociate: () -> Unit,
    onSaveCopy: () -> Unit,
    originalDirectoryMessage: String? = null
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.book_file_location), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.book_file_original), style = MaterialTheme.typography.titleSmall)
        if (originalLocation != null) {
            LocationText(originalLocation)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onCopy(originalLocation) },
                    enabled = !busy
                ) { Text(stringResource(R.string.book_file_copy_location)) }
                if (canOpenOriginal) {
                    OutlinedButton(onClick = onOpenOriginal, enabled = !busy) { Text(stringResource(R.string.book_file_open_directory)) }
                }
            }
        } else {
            Text(
                stringResource(if (loaded) R.string.book_file_original_missing else R.string.book_file_loading),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        originalDirectoryMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = onAssociate, enabled = loaded && !busy) { Text(stringResource(R.string.book_file_associate)) }
        HorizontalDivider()
        Text(stringResource(R.string.book_file_reading_copy), style = MaterialTheme.typography.titleSmall)
        LocationText(readingPath)
        Text(
            stringResource(R.string.book_file_private_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { onCopy(readingPath) }, enabled = !busy) { Text(stringResource(R.string.book_file_copy_location)) }
        Button(onClick = onSaveCopy, enabled = loaded && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (busy) R.string.book_file_working else R.string.book_file_save_copy))
        }
        if (savedCopyLocation != null) {
            HorizontalDivider()
            Text(stringResource(R.string.book_file_saved_copy), style = MaterialTheme.typography.titleSmall)
            LocationText(savedCopyLocation)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onCopy(savedCopyLocation) },
                    enabled = !busy
                ) { Text(stringResource(R.string.book_file_copy_location)) }
                OutlinedButton(onClick = onOpenSavedCopy, enabled = !busy) { Text(stringResource(R.string.book_file_open_directory)) }
            }
        }
        Text(
            stringResource(R.string.book_file_provider_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun LocationText(location: String) {
    SelectionContainer { Text(location, style = MaterialTheme.typography.bodyMedium) }
}
