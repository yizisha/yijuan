package com.flowreader.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.component.FlowIcons
import com.flowreader.app.util.OfflineSpeechModel
import com.flowreader.app.util.TtsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class SpeechViewModel @Inject constructor(val manager: TtsManager) : ViewModel() {
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { manager.setEnabled(enabled) }
    }
    fun select(id: String?) {
        viewModelScope.launch { manager.selectModel(id) }
    }
    fun voice(voice: Int, rate: Float) {
        viewModelScope.launch { manager.setOfflineVoice(voice, rate) }
    }
    fun remove(model: OfflineSpeechModel) {
        viewModelScope.launch { manager.removeModel(model) }
    }
    fun preview(model: OfflineSpeechModel) {
        viewModelScope.launch {
            manager.selectModel(model.id)
            if (manager.models?.state?.value?.selected == model.id) manager.speak("欢迎使用一卷。清晨的阳光落在书页上，故事从这里开始。")
        }
    }
}

@Composable
fun SpeechSettingsRow(viewModel: SpeechViewModel = hiltViewModel()) {
    val enabled by viewModel.manager.enabled.collectAsState()
    val loaded by viewModel.manager.loaded.collectAsState()
    val saving by viewModel.manager.saving.collectAsState()
    val feedback by viewModel.manager.feedback.collectAsState()
    var showModels by remember { mutableStateOf(false) }
    SettingsSwitch(
        icon = FlowIcons.Headphones,
        title = stringResource(R.string.speech_title),
        subtitle = feedback ?: stringResource(if (enabled) R.string.speech_enabled_desc else R.string.speech_disabled_desc),
        checked = enabled,
        enabled = loaded && !saving,
        onCheckedChange = viewModel::setEnabled
    )
    if (viewModel.manager.models != null) {
        SettingsItem(
            icon = FlowIcons.Headphones,
            title = "语音模型",
            subtitle = "按需下载、试听与管理，软件不附带模型",
            trailing = { Icon(FlowIcons.Next, null) },
            onClick = { showModels = true }
        )
        if (showModels) SpeechModelsSheet(onDismiss = { showModels = false }, viewModel = viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechControlsSheet(onDismiss: () -> Unit, onRequestContent: () -> Unit, viewModel: SpeechViewModel = hiltViewModel()) {
    val enabled by viewModel.manager.enabled.collectAsState()
    val latestContent by rememberUpdatedState(onRequestContent)
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val modelState = viewModel.manager.models?.state?.collectAsState()?.value
    var showModels by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, shape = MaterialTheme.shapes.extraLarge) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.speech_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(if (enabled) R.string.speech_enabled_desc else R.string.speech_disabled_desc),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            if (enabled) {
                if (modelState?.selected != null) {
                    OfflineSpeechControls(modelState, { latestContent() }, viewModel)
                } else {
                    key(colors.onSurface, colors.primary, modelState?.selected) {
                        AndroidView(
                            factory = { context ->
                                runCatching {
                                    viewModel.manager.createControls(
                                        context, colors.onSurface.toArgb(), colors.onSurfaceVariant.toArgb(), colors.primary.toArgb(),
                                        Runnable {
                                            latestContent()
                                        }
                                    )
                                }.getOrNull() ?: android.widget.TextView(context).apply {
                                    text = context.getString(R.string.speech_controls_failed)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            onReset = null,
                            onRelease = { viewModel.manager.detachControls(it) }
                        )
                    }
                }
                if (modelState != null) TextButton(onClick = { showModels = true }) { Text("选择或下载语音模型") }
            }
        }
    }
    if (showModels) SpeechModelsSheet(onDismiss = { showModels = false }, viewModel = viewModel)
}
