package com.flowreader.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.flowreader.app.core.designsystem.component.FlowPreferenceGroup
import com.flowreader.app.core.designsystem.component.FlowSlider
import com.flowreader.app.util.OfflineSpeechCatalog
import com.flowreader.app.util.OfflineSpeechModel
import com.flowreader.app.util.SpeechDownloadSource
import com.flowreader.app.util.SpeechModelsState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SpeechModelsSheet(onDismiss: () -> Unit, viewModel: SpeechViewModel) {
    val models = viewModel.manager.models ?: return
    val state by models.state.collectAsState()
    val enabled by viewModel.manager.enabled.collectAsState()
    val feedback by viewModel.manager.feedback.collectAsState()
    val playback by viewModel.manager.state.collectAsState()
    var source by remember { mutableStateOf(SpeechDownloadSource.GHFAST) }
    var sourceMenu by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<OfflineSpeechModel?>(null) }
    var importTarget by remember { mutableStateOf<OfflineSpeechModel?>(null) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val model = importTarget
        if (uri != null && model != null) models.import(model, uri)
        importTarget = null
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("语音模型", style = MaterialTheme.typography.titleLarge)
            if (state.selected != null) {
                Text(
                    when {
                        playback.error != null -> playback.error!!
                        playback.isSpeaking && !playback.isReady -> "正在加载或合成首句，请稍候…"
                        playback.isSpeaking -> "正在试听 · 请检查手机媒体音量与蓝牙输出"
                        playback.canResume -> "试听已暂停"
                        else -> "已选择${OfflineSpeechCatalog.find(state.selected)?.title.orEmpty()}，点击试听开始"
                    },
                    color = if (playback.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (playback.isSpeaking) TextButton(onClick = viewModel.manager::stop) { Text("停止试听") }
            }
            Text(
                "模型按需下载，保存在本机，下载后离线使用。听书开关默认关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column {
                OutlinedButton(onClick = { sourceMenu = true }, enabled = state.busyId == null) { Text("下载源 · ${source.label}⌄") }
                DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                    SpeechDownloadSource.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            source = option
                            sourceMenu = false
                        })
                    }
                }
            }
            Text(
                "镜像为社区服务；各下载源均使用同一官方 SHA-256 校验。失败可切换源重试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.message != null || feedback != null) {
                Text(
                    feedback ?: state.message.orEmpty(),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            OutlinedButton(onClick = { viewModel.select(null) }, enabled = state.busyId == null, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.selected == null) "✓ 当前使用系统语音" else "切换到系统语音")
            }
            OfflineSpeechCatalog.models.forEach { model ->
                FlowPreferenceGroup(contentPadding = 12.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(model.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${model.description}\n下载 ${model.sizeLabel} · 本地约 ${model.installedSizeLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.busyId == model.id) {
                            Text(
                                "${state.stage} · ${String.format(Locale.ROOT, "%.1f", state.downloaded / 1048576.0)} / ${model.sizeLabel}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            LinearProgressIndicator(
                                progress = { (state.downloaded.toFloat() / model.bytes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextButton(onClick = models::cancelDownload) { Text("取消") }
                        } else if (model.id in state.installed) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    viewModel.select(model.id)
                                }, enabled = state.busyId == null) { Text(if (state.selected == model.id) "✓ 已选择" else "使用") }
                                TextButton(onClick = { viewModel.preview(model) }, enabled = enabled && state.busyId == null) { Text("试听") }
                                TextButton(onClick = { removal = model }, enabled = state.busyId == null) { Text("删除") }
                            }
                            if (!enabled) Text("开启听书后可试听", style = MaterialTheme.typography.bodySmall)
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { models.download(model, source) }, enabled = state.busyId == null) { Text("下载") }
                                TextButton(onClick = {
                                    importTarget = model
                                    import.launch(arrayOf("*/*"))
                                }, enabled = state.busyId == null) { Text("本地导入") }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(model.license, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
    removal?.let { model ->
        AlertDialog(
            onDismissRequest = { removal = null },
            title = { Text("删除语音包") },
            text = { Text("删除${model.title}，释放约 ${model.installedSizeLabel}。正在使用时停止朗读并切换到系统语音，书籍和进度保留。") },
            confirmButton = {
                TextButton(onClick = {
                    removal = null
                    viewModel.remove(model)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { removal = null }) { Text("取消") } }
        )
    }
}

@Composable
internal fun OfflineSpeechControls(state: SpeechModelsState, onRequestContent: () -> Unit, viewModel: SpeechViewModel) {
    val model = OfflineSpeechCatalog.find(state.selected) ?: return
    val playback by viewModel.manager.state.collectAsState()
    Text(model.title, style = MaterialTheme.typography.titleMedium)
    val status = when {
        playback.error != null -> playback.error!!
        playback.isSpeaking && !playback.isReady -> "正在准备离线语音…"
        playback.isSpeaking -> "正在朗读"
        playback.canResume -> "已暂停"
        else -> "从当前章节开始朗读"
    }
    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (model.kokoro) {
        var chooseVoice by remember { mutableStateOf(false) }
        Column {
            OutlinedButton(onClick = { chooseVoice = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("中文音色 ${state.voice - 2}⌄") }
            DropdownMenu(expanded = chooseVoice, onDismissRequest = { chooseVoice = false }) {
                model.voiceIds.forEach { voice ->
                    DropdownMenuItem(text = { Text("中文音色 ${voice - 2}") }, onClick = {
                        chooseVoice = false
                        viewModel.voice(voice, state.rate)
                    })
                }
            }
        }
    }
    Text("语速 · ${String.format(Locale.ROOT, "%.1f", state.rate)} 倍")
    var rate by remember(state.rate) { mutableStateOf(state.rate) }
    FlowSlider(value = rate, onValueChange = {
        rate = it
    }, valueRange = .5f..2f, onValueChangeFinished = { viewModel.voice(state.voice, rate) })
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = {
                if (playback.isSpeaking) {
                    viewModel.manager.pause()
                } else if (playback.canResume) {
                    viewModel.manager.resumeOffline()
                } else {
                    onRequestContent()
                }
            },
            modifier = Modifier.weight(
                1f
            ).heightIn(min = 48.dp)
        ) { Text(if (playback.isSpeaking) "暂停" else if (playback.canResume) "继续朗读" else "开始朗读") }
        OutlinedButton(onClick = {
            viewModel.manager.stop()
        }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("停止") }
    }
}

@Composable
internal fun SpeechLicensesRow() {
    var show by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SettingsItem(
        icon = com.flowreader.app.core.designsystem.component.FlowIcons.Info,
        title = "离线语音组件与模型许可",
        subtitle = "sherpa-onnx、ONNX Runtime 及模型来源",
        onClick = { show = true }
    )
    if (show) {
        val text = remember {
            val directory = "speech-licenses"
            context.assets.list(directory).orEmpty().sortedBy { if (it == "SOURCES.txt") "" else it }.joinToString("\n\n") { name ->
                "$name\n\n" + context.assets.open("$directory/$name").bufferedReader().use { it.readText() }
            }
        }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("离线语音许可与来源") },
            text = {
                Text(
                    text,
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text("关闭") } }
        )
    }
}
