package com.flowreader.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flowreader.app.domain.repository.BackupPreview
import com.flowreader.app.domain.repository.RestoreMode

@Composable
internal fun BackupOperationDialogs(state: SettingsUiState, viewModel: SettingsViewModel) {
    if (state.backupBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("备份与恢复") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.backupStatus ?: "正在处理…")
                    Text("完成前请保留应用运行。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { if (!state.restoring) TextButton(onClick = viewModel::cancelBackupOperation) { Text("取消操作") } }
        )
    } else {
        state.backupPreview?.let { preview ->
            BackupRestoreDialog(preview, viewModel::dismissBackupPreview, viewModel::restoreBackup)
        }
    }
}

@Composable
internal fun BackupRestoreDialog(preview: BackupPreview, onDismiss: () -> Unit, onRestore: (RestoreMode, Boolean) -> Unit) {
    var mode by remember(preview.token) { mutableStateOf(RestoreMode.MERGE) }
    var settings by remember(preview.token) { mutableStateOf(false) }
    var replaceConfirmed by remember(preview.token) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复预览") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (preview.legacy) "旧版记录备份" else "完整书库备份", style = MaterialTheme.typography.titleSmall)
                if (preview.createdAt > 0) {
                    Text(
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(preview.createdAt)),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "${preview.books} 本书 · ${preview.categories} 个分组\n" +
                        "${preview.bookmarks} 个书签 · ${preview.annotations} 条笔记\n" +
                        "${preview.files} 个文件 · ${preview.duplicates} 本可合并书籍"
                )
                HorizontalDivider()
                RestoreOption(mode == RestoreMode.MERGE, "合并到当前书库", "保留现有进度与正文，合并收藏、书签和笔记。不同内容版本保留为独立书籍。") {
                    mode = RestoreMode.MERGE
                    replaceConfirmed = false
                }
                if (!preview.legacy) {
                    RestoreOption(
                        mode == RestoreMode.REPLACE,
                        "替换当前书库",
                        "书籍、分组、笔记和统计替换为备份中的内容。"
                    ) { mode = RestoreMode.REPLACE }
                }
                if (mode == RestoreMode.REPLACE) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            replaceConfirmed = !replaceConfirmed
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(replaceConfirmed, onCheckedChange = { replaceConfirmed = it })
                        Text("我已确认替换当前书库", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (preview.hasSettings) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { settings = !settings },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(settings, onCheckedChange = { settings = it })
                        Text("同时恢复主题、排版和阅读目标")
                    }
                }
                if (preview.hasSettings) {
                    Text(
                        "应用锁、目录授权和自动备份配置保留本机值。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (preview.warnings.isNotEmpty()) {
                    HorizontalDivider()
                    Text("需要注意", style = MaterialTheme.typography.titleSmall)
                    preview.warnings.take(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (preview.warnings.size > 10) Text("另有 ${preview.warnings.size - 10} 条提示", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = mode != RestoreMode.REPLACE || replaceConfirmed, onClick = { onRestore(mode, settings) }) { Text("开始恢复") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RestoreOption(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp), verticalAlignment = Alignment.Top) {
        RadioButton(selected, onClick = onClick)
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
