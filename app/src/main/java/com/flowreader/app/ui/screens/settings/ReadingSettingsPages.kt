package com.flowreader.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.component.FlowPreferenceGroup
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.app.ui.screens.reader.components.EyeCarePage
import com.flowreader.app.ui.screens.reader.components.FontPage
import com.flowreader.app.ui.screens.reader.components.PageTurnPage
import com.flowreader.app.ui.screens.reader.components.ThemePage
import com.flowreader.app.ui.screens.reader.components.TypographyPage

/**
 * The 设置 → 阅读 pages.
 *
 * Each one is a full screen wrapping the *same* composables the in-reader panel uses, so the two
 * surfaces cannot drift apart — a default has exactly one implementation and one entry point.
 *
 * There is deliberately **no** scroll container here: `SettingsDetailScaffold` already wraps its
 * content in a `verticalScroll`. Nesting a second one with `fillMaxSize` handed the inner column
 * unbounded height, which rendered the page blank and locked the UI — the reported
 * 「设置里点阅读卡死空白」.
 */
@Composable
private fun ReadingDetailScaffold(
    titleRes: Int,
    onBack: () -> Unit,
    viewModel: SettingsViewModel,
    content: @Composable ColumnScope.() -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val error = uiState.readingSettingsSaveError
    val message = stringResource(R.string.ui_reading_save_failed)
    LaunchedEffect(error) {
        if (error != null) {
            snackbar.showSnackbar(message)
            viewModel.clearReadingSettingsError(error)
        }
    }
    SettingsDetailScaffold(titleRes, onBack, snackbar) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = FlowSpacing.sm),
            content = { FlowPreferenceGroup(content = content) }
        )
    }
}

/** 1.1 排版: size, spacing, margins, line length, alignment, indent. */
@Composable
fun TypographySettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.onCustomFontSelected(uri)
    }
    ReadingDetailScaffold(R.string.ui_typography, onBack, viewModel) {
        FontPage(
            uiState.readingSettings,
            { viewModel.updateReadingSettings(it) },
            { fontPicker.launch("*/*") },
            { viewModel.clearCustomFont() }
        )
        TypographyPage(uiState.readingSettings) { viewModel.updateReadingSettings(it) }
    }
}

/** 1.2 字体: built-in faces, import, clear, live preview. */
@Composable
fun FontSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.onCustomFontSelected(uri) }

    ReadingDetailScaffold(R.string.settings_read_font, onBack, viewModel) {
        FontPage(
            settings = uiState.readingSettings,
            onSettingsChange = { viewModel.updateReadingSettings(it) },
            onPickFont = { fontPickerLauncher.launch("*/*") },
            onClearFont = { viewModel.clearCustomFont() }
        )
    }
}

/** 1.3 翻页: mode, animation, volume keys, tap zones. */
@Composable
fun PageTurnSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    ReadingDetailScaffold(R.string.ui_page_turn, onBack, viewModel) {
        PageTurnPage(
            settings = uiState.readingSettings,
            onSettingsChange = { viewModel.updateReadingSettings(it) }
        )
    }
}

/** 1.5 主题与背景: palettes, auto night, background image, custom colours. */
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.importBackgroundImage(uri) }

    ReadingDetailScaffold(R.string.ui_theme, onBack, viewModel) {
        ThemePage(
            settings = uiState.readingSettings,
            onSettingsChange = { viewModel.updateReadingSettings(it) },
            onPickBackgroundImage = { backgroundPicker.launch("image/*") },
            onClearBackgroundImage = { viewModel.clearBackgroundImage() }
        )
    }
}

/** 1.6 护眼与屏幕: keep-screen-on, break reminder, colour temperature. */
@Composable
fun EyeCareSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    ReadingDetailScaffold(R.string.ui_screen, onBack, viewModel) {
        EyeCarePage(
            settings = uiState.readingSettings,
            onSettingsChange = { viewModel.updateReadingSettings(it) }
        )
    }
}
