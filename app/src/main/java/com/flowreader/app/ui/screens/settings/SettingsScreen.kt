package com.flowreader.app.ui.screens.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwipeRight
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.flowreader.app.BuildConfig
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.component.FlowChoiceChip
import com.flowreader.app.core.designsystem.component.FlowExpandableGroup
import com.flowreader.app.core.designsystem.component.FlowFeatureIcon
import com.flowreader.app.core.designsystem.component.FlowIcons
import com.flowreader.app.core.designsystem.component.FlowPreferenceGroup
import com.flowreader.app.core.designsystem.component.FlowSlider
import com.flowreader.app.core.designsystem.component.FlowTopBar
import com.flowreader.app.core.designsystem.reader.ReaderPalettes
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.app.domain.model.AppLanguage
import com.flowreader.app.domain.model.AppThemeMode
import com.flowreader.app.domain.model.ColorSource
import com.flowreader.app.domain.model.GestureAction
import com.flowreader.app.domain.model.GestureSettings
import com.flowreader.app.domain.model.LibraryCoverSize
import com.flowreader.app.domain.model.LibraryViewMode
import com.flowreader.app.domain.model.TapZoneMode
import com.flowreader.app.util.AppLockManager
import java.io.File

/** Five app-wide categories. Reading pages share their editors with the reader panel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenCategory: (SettingsCategory) -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = { FlowTopBar(title = stringResource(R.string.settings_title), primaryPage = true) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            FlowPreferenceGroup(modifier = Modifier.padding(FlowSpacing.lg), contentPadding = 0.dp) {
                settingsMenuCategories.forEachIndexed { index, category ->
                    val subtitle = when (category) {
                        SettingsCategory.READ -> stringResource(R.string.ui_settings_read_desc)
                        SettingsCategory.SHELF -> stringResource(R.string.ui_settings_shelf_desc)
                        SettingsCategory.APPEARANCE -> stringResource(R.string.ui_settings_app_desc)
                        SettingsCategory.DATA -> stringResource(R.string.ui_settings_data_desc)
                        else -> stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)
                    }
                    SettingsItem(
                        icon = when (category) {
                            SettingsCategory.READ -> FlowIcons.Book
                            SettingsCategory.SHELF -> FlowIcons.Shelf
                            SettingsCategory.APPEARANCE -> FlowIcons.Palette
                            SettingsCategory.DATA -> FlowIcons.Cloud
                            else -> FlowIcons.Info
                        },
                        badge = index,
                        title = stringResource(category.titleRes),
                        subtitle = subtitle,
                        trailing = { Icon(FlowIcons.Next, contentDescription = null) },
                        onClick = { onOpenCategory(category) }
                    )
                    if (index < settingsMenuCategories.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Spacer(modifier = Modifier.height(FlowSpacing.xxl))
        }
    }
}

/**
 * The categories of the app-level settings menu, in the order they are listed.
 *
 * 字体 and 阅读 used to be two separate top-level pages that both edited the reader; both are now
 * reached through [SettingsCategory.READ], which is the single entry point for reader defaults.
 */
enum class SettingsCategory(val titleRes: Int, val icon: ImageVector, val route: String) {
    APPEARANCE(R.string.ui_settings_app, FlowIcons.Moon, "settings/appearance"),
    SHELF(R.string.settings_section_shelf, FlowIcons.Shelf, "settings/shelf"),
    READ(R.string.settings_section_read, FlowIcons.Book, "settings/read"),
    NAVIGATION(R.string.settings_section_navigation, Icons.Default.History, "settings/navigation"),
    REMINDER(R.string.settings_section_reminder, Icons.Default.Notifications, "settings/reminder"),
    DATA(R.string.ui_settings_data, FlowIcons.Cloud, "settings/data"),
    TXT(R.string.settings_section_txt, Icons.Default.CleaningServices, "settings/txt"),
    SECURITY(R.string.settings_section_security, FlowIcons.Lock, "settings/security"),
    ABOUT(R.string.settings_section_about, FlowIcons.Info, "settings/about")
}

internal val settingsMenuCategories = listOf(
    SettingsCategory.READ,
    SettingsCategory.SHELF,
    SettingsCategory.APPEARANCE,
    SettingsCategory.DATA,
    SettingsCategory.ABOUT
)

/**
 * Four shared reading groups, application-only habits, and aliases for legacy routes.
 * Both settings surfaces render the same ReadingSettings editors.
 */
enum class ReadingCategory(val titleRes: Int, val icon: ImageVector, val route: String) {
    TYPOGRAPHY(R.string.ui_typography, FlowIcons.Font, "settings/read/typography"),
    FONT(R.string.settings_read_font, FlowIcons.Font, "settings/read/font"),
    PAGE_TURN(R.string.ui_page_turn, Icons.Default.SwapHoriz, "settings/read/page-turn"),
    GESTURE(R.string.settings_read_gesture, Icons.Default.Gesture, "settings/read/gesture"),
    THEME(R.string.ui_theme, FlowIcons.Palette, "settings/read/theme"),
    EYE_CARE(R.string.ui_screen, FlowIcons.Eye, "settings/read/eye-care"),
    GOALS(R.string.ui_habits, Icons.Default.Flag, "settings/read/goals")
}

internal val readerCategories =
    listOf(ReadingCategory.TYPOGRAPHY, ReadingCategory.PAGE_TURN, ReadingCategory.THEME, ReadingCategory.EYE_CARE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDetailScaffold(
    titleRes: Int,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        snackbarHost = {
            if (snackbarHostState != null) {
                SnackbarHost(hostState = snackbarHostState)
            }
        },
        topBar = {
            FlowTopBar(title = stringResource(titleRes), onNavigateUp = onBack)
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(FlowSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FlowSpacing.lg)
        ) {
            content()
            Spacer(modifier = Modifier.height(FlowSpacing.xxl))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShelfSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel(), onOpenTxt: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showLanTransferDialog by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist the grant: the scan runs on every launch and must not re-ask.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.setScanFolder(uri)
        }
    }

    LaunchedEffect(uiState.scanMessage) {
        uiState.scanMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearScanMessage()
        }
    }

    SettingsDetailScaffold(R.string.settings_section_shelf, onBack, snackbarHostState) {
        FlowPreferenceGroup(stringResource(R.string.ui_layout), contentPadding = 0.dp) {
            SettingsItem(
                icon = FlowIcons.Grid,
                title = stringResource(R.string.settings_shelf_view_mode),
                subtitle = stringResource(
                    if (uiState.libraryViewMode == LibraryViewMode.GRID) {
                        R.string.settings_shelf_view_grid_desc
                    } else {
                        R.string.settings_shelf_view_list_desc
                    }
                )
            )
            ChipRow(
                options = LibraryViewMode.entries,
                selected = uiState.libraryViewMode,
                label = { mode ->
                    stringResource(
                        if (mode == LibraryViewMode.GRID) R.string.library_view_grid else R.string.library_view_list
                    )
                },
                onSelect = { viewModel.updateLibraryViewMode(it) }
            )

            if (uiState.libraryViewMode == LibraryViewMode.GRID) {
                LabelledValue(
                    label = stringResource(R.string.settings_shelf_columns),
                    value = stringResource(R.string.settings_shelf_columns_value, uiState.libraryGridColumns)
                )
                FlowSlider(
                    value = uiState.libraryGridColumns.toFloat(),
                    onValueChange = {
                        viewModel.updateShelfPreferences(
                            hideGroupedBooksFromAll = uiState.hideGroupedBooksFromAll,
                            gridColumns = it.toInt().coerceIn(2, 5),
                            coverSize = uiState.libraryCoverSize
                        )
                    },
                    valueRange = 2f..5f,
                    steps = 2,
                    modifier = Modifier.padding(horizontal = FlowSpacing.lg)
                )
            } else {
                LabelledValue(
                    label = stringResource(R.string.settings_shelf_cover_size),
                    value = uiState.libraryCoverSize.displayName
                )
                ChipRow(
                    options = LibraryCoverSize.entries,
                    selected = uiState.libraryCoverSize,
                    label = { it.displayName },
                    onSelect = { size ->
                        viewModel.updateShelfPreferences(
                            hideGroupedBooksFromAll = uiState.hideGroupedBooksFromAll,
                            gridColumns = uiState.libraryGridColumns,
                            coverSize = size
                        )
                    }
                )
            }
        }
        FlowPreferenceGroup(stringResource(R.string.ui_organization), contentPadding = 0.dp) {
            SettingsSwitch(
                icon = Icons.Default.FolderOff,
                title = stringResource(R.string.settings_hide_grouped),
                subtitle = stringResource(R.string.settings_hide_grouped_desc),
                checked = uiState.hideGroupedBooksFromAll,
                onCheckedChange = { hide ->
                    viewModel.updateShelfPreferences(
                        hideGroupedBooksFromAll = hide,
                        gridColumns = uiState.libraryGridColumns,
                        coverSize = uiState.libraryCoverSize
                    )
                }
            )

            SettingsSwitch(
                icon = FlowIcons.Book,
                title = stringResource(R.string.settings_open_directly),
                subtitle = stringResource(R.string.settings_open_directly_desc),
                checked = uiState.openReadBookDirectly,
                onCheckedChange = { viewModel.updateOpenReadBookDirectly(it) }
            )
        }
        FlowPreferenceGroup(stringResource(R.string.ui_import_files), contentPadding = 0.dp) {
            SettingsItem(
                icon = FlowIcons.Folder,
                title = stringResource(R.string.settings_scan_folder),
                subtitle = uiState.scanFolderUri ?: stringResource(R.string.settings_scan_folder_none),
                onClick = { folderPicker.launch(null) }
            )
            if (uiState.scanFolderUri != null) {
                SettingsItem(
                    icon = FlowIcons.Refresh,
                    title = stringResource(
                        if (uiState.isScanning) R.string.settings_scan_running else R.string.settings_scan_now
                    ),
                    subtitle = stringResource(R.string.settings_scan_folder_desc),
                    onClick = { if (!uiState.isScanning) viewModel.scanNow() }
                )
                SettingsItem(
                    icon = FlowIcons.Trash,
                    title = stringResource(R.string.settings_scan_clear),
                    onClick = { viewModel.setScanFolder(null) }
                )
            }
            SettingsItem(
                icon = Icons.Default.Wifi,
                title = stringResource(R.string.settings_lan_transfer),
                subtitle = stringResource(R.string.settings_lan_transfer_desc),
                onClick = { showLanTransferDialog = true }
            )
            SettingsItem(
                icon = Icons.Default.CleaningServices,
                title = stringResource(R.string.ui_txt_advanced),
                subtitle = stringResource(R.string.settings_desc_txt),
                trailing = { Icon(FlowIcons.Next, contentDescription = null) },
                onClick = onOpenTxt
            )
        }
    }
    BackupOperationDialogs(uiState, viewModel)

    if (showLanTransferDialog && uiState.backupPreview == null && !uiState.backupBusy) {
        LanTransferDialog(
            serverUrl = uiState.lanServerUrl,
            message = uiState.lanTransferMessage,
            onStartServer = { viewModel.startLanServer() },
            onStopServer = { viewModel.stopLanServer() },
            onImportUrl = { viewModel.importFromLanUrl(it) },
            onMessageShown = { viewModel.clearLanTransferMessage() },
            onDismiss = {
                showLanTransferDialog = false
                viewModel.stopLanServer()
            }
        )
    }
}

/**
 * TXT import rules. Cleaning happens once at import time, so these never slow the reader down.
 * The two text fields commit on the Save button rather than per keystroke — a DataStore write per
 * character is exactly the kind of thing that makes a settings screen feel heavy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TxtSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val rules = uiState.txtProcessing
    var replacements by remember(rules.customReplacements) { mutableStateOf(rules.customReplacements) }
    var chapterRegex by remember(rules.customChapterRegex) { mutableStateOf(rules.customChapterRegex) }
    val dirty = replacements != rules.customReplacements || chapterRegex != rules.customChapterRegex

    SettingsDetailScaffold(R.string.ui_txt_advanced, onBack) {
        FlowPreferenceGroup(contentPadding = 0.dp) {
            SettingsSwitch(
                icon = Icons.Default.CleaningServices,
                title = stringResource(R.string.settings_txt_remove_ads),
                checked = rules.removeAdLines,
                onCheckedChange = { viewModel.updateTxtProcessing(rules.copy(removeAdLines = it)) }
            )
            SettingsSwitch(
                icon = Icons.Default.Compress,
                title = stringResource(R.string.settings_txt_strip_blank),
                checked = rules.stripBlankLines,
                onCheckedChange = { viewModel.updateTxtProcessing(rules.copy(stripBlankLines = it)) }
            )

            Column(modifier = Modifier.padding(horizontal = FlowSpacing.lg, vertical = FlowSpacing.sm)) {
                OutlinedTextField(
                    value = chapterRegex,
                    onValueChange = { chapterRegex = it },
                    label = { Text(stringResource(R.string.settings_txt_chapter_regex)) },
                    supportingText = { Text(stringResource(R.string.settings_txt_chapter_regex_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SettingsSwitch(
                icon = Icons.Default.FilterAlt,
                title = stringResource(R.string.settings_txt_regex_only),
                subtitle = stringResource(R.string.settings_txt_regex_only_desc),
                checked = rules.customRegexOnly,
                onCheckedChange = { viewModel.updateTxtProcessing(rules.copy(customRegexOnly = it)) }
            )

            Column(modifier = Modifier.padding(horizontal = FlowSpacing.lg, vertical = FlowSpacing.sm)) {
                OutlinedTextField(
                    value = replacements,
                    onValueChange = { replacements = it },
                    label = { Text(stringResource(R.string.settings_txt_replacements)) },
                    supportingText = { Text(stringResource(R.string.settings_txt_replacements_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FlowSpacing.lg, vertical = FlowSpacing.sm),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    enabled = dirty,
                    onClick = {
                        viewModel.updateTxtProcessing(
                            rules.copy(
                                customReplacements = replacements,
                                customChapterRegex = chapterRegex
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

/** App lock: enable flag, PIN management. Fingerprint is offered automatically when available. */
@Composable
fun SecuritySettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    DataSettingsScreen(onBack, viewModel)
}

@Composable
private fun SecuritySettingsContent(viewModel: SettingsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showPinDialog by remember { mutableStateOf(false) }

    FlowPreferenceGroup(stringResource(R.string.ui_privacy), contentPadding = 0.dp) {
        SettingsSwitch(
            icon = FlowIcons.Lock,
            title = stringResource(R.string.settings_app_lock),
            subtitle = stringResource(R.string.settings_app_lock_desc),
            checked = uiState.appLockEnabled,
            onCheckedChange = { enabled ->
                if (enabled && !uiState.hasAppLockPin) {
                    showPinDialog = true
                } else {
                    viewModel.updateAppLock(enabled, null)
                }
            }
        )
        SettingsItem(
            icon = Icons.Default.Password,
            title = stringResource(R.string.settings_app_lock_pin),
            subtitle = if (uiState.hasAppLockPin) "••••" else stringResource(R.string.settings_app_lock_pin_hint),
            onClick = { showPinDialog = true }
        )
        if (uiState.hasAppLockPin) {
            SettingsItem(
                icon = FlowIcons.Trash,
                title = stringResource(R.string.settings_app_lock_pin_clear),
                onClick = { viewModel.updateAppLock(false, null) }
            )
        }
    }

    if (showPinDialog) {
        PinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { pin ->
                viewModel.updateAppLock(enabled = true, pinHash = AppLockManager.hashPin(pin))
                showPinDialog = false
            }
        )
    }
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    val valid = pin.length in 4..8 && pin.all { it.isDigit() }

    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = FlowSpacing.lg),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_app_lock_pin)) },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter { char -> char.isDigit() }.take(8) },
                label = { Text(stringResource(R.string.settings_app_lock_pin_hint)) },
                singleLine = true,
                isError = pin.isNotEmpty() && !valid,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(pin) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    val themeSnackbar = remember { SnackbarHostState() }
    LaunchedEffect(uiState.visualStyleSaveError) {
        uiState.visualStyleSaveError?.let {
            themeSnackbar.showSnackbar(it)
            viewModel.clearVisualStyleError()
        }
    }
    SettingsDetailScaffold(R.string.ui_settings_app, onBack, themeSnackbar) {
        FlowPreferenceGroup(title = stringResource(R.string.app_visual_style)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                com.flowreader.app.domain.model.AppVisualStyle.entries.forEach { style ->
                    val preview = com.flowreader.app.core.designsystem.theme.flowColorScheme(
                        when (uiState.themeMode) {
                            AppThemeMode.DARK -> true
                            AppThemeMode.LIGHT -> false
                            AppThemeMode.FOLLOW_SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                        },
                        uiState.colorSource,
                        style
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            color = preview.background,
                            shape = MaterialTheme.shapes.large,
                            border = androidx.compose.foundation.BorderStroke(1.dp, preview.outlineVariant),
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.updateVisualStyle(style) }
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    stringResource(R.string.reader_title),
                                    color = preview.onSurface,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    stringResource(R.string.reader_font_preview),
                                    color = preview.onSurface,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = com.flowreader.app.core.designsystem.reader.rememberReaderFontFamily(
                                        uiState.readingSettings
                                    ),
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(.95f, .84f, .9f, .7f).forEach { width ->
                                        Box(Modifier.fillMaxWidth(width).height(3.dp).background(preview.onSurface.copy(alpha = .1f)))
                                    }
                                }
                                Text(
                                    stringResource(
                                        if (style == com.flowreader.app.domain.model.AppVisualStyle.CLEAN_BLUE) R.string.clean_theme_preview else R.string.warm_theme_preview
                                    ),
                                    color = preview.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        com.flowreader.app.core.designsystem.component.FlowChoiceChip(
                            selected = uiState.visualStyle == style,
                            onClick = { viewModel.updateVisualStyle(style) },
                            label = { Text(style.displayName) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        FlowPreferenceGroup(contentPadding = 0.dp) {
            SettingsItem(
                icon = FlowIcons.Moon,
                title = stringResource(R.string.settings_theme_mode),
                subtitle = uiState.themeMode.displayName
            )
            ChipRow(
                options = AppThemeMode.entries,
                selected = uiState.themeMode,
                label = { it.displayName },
                onSelect = { viewModel.updateThemeMode(it) }
            )

            SettingsItem(
                icon = FlowIcons.Palette,
                title = stringResource(R.string.settings_color_source),
                subtitle = stringResource(R.string.settings_color_source_desc)
            )
            ChipRow(
                options = ColorSource.entries,
                selected = uiState.colorSource,
                label = { it.displayName },
                onSelect = { viewModel.updateColorSource(it) }
            )

            SettingsItem(
                icon = Icons.Default.Language,
                title = stringResource(R.string.settings_language),
                subtitle = stringResource(R.string.settings_language_desc)
            )
            ChipRow(
                options = AppLanguage.entries,
                selected = uiState.language,
                label = { language ->
                    if (language.tag == null) {
                        stringResource(R.string.settings_language_follow_system)
                    } else {
                        language.displayName
                    }
                },
                onSelect = { viewModel.updateLanguage(it) }
            )
        }
        FlowPreferenceGroup(contentPadding = 0.dp) {
            SpeechSettingsRow()
        }
    }
}

/**
 * 设置 → 阅读: the single entry point for reader defaults.
 *
 * It is a second-level menu rather than one long page (the reference reader does the same): six
 * groups that each open a full screen, so nothing is buried and nothing is duplicated.
 */
@Composable
fun ReadingMenuScreen(onBack: () -> Unit, onOpenCategory: (ReadingCategory) -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.readingSettings

    SettingsDetailScaffold(R.string.settings_section_read, onBack) {
        FlowPreferenceGroup(contentPadding = 0.dp) {
            (readerCategories + ReadingCategory.GOALS).forEach { category ->
                val subtitle = when (category) {
                    ReadingCategory.TYPOGRAPHY -> stringResource(
                        R.string.settings_read_typography_desc,
                        settings.fontSize,
                        settings.lineChars
                    )

                    ReadingCategory.FONT ->
                        settings.customFontPath
                            ?.let { path -> runCatching { File(path).takeIf { it.isFile }?.name }.getOrNull() }
                            ?: settings.fontFamily.displayName

                    ReadingCategory.PAGE_TURN -> "${settings.pageMode.displayName} · ${settings.pageTurnAnimation.displayName}"
                    ReadingCategory.GESTURE -> settings.gestureSettings.tapZoneMode.displayName
                    ReadingCategory.GOALS -> stringResource(
                        R.string.ui_goals_summary,
                        uiState.dailyReadingGoal,
                        uiState.weeklyReadingGoal,
                        uiState.monthlyReadingGoal
                    )
                    ReadingCategory.THEME -> ReaderPalettes.of(settings.palette).displayName
                    ReadingCategory.EYE_CARE -> if (settings.keepScreenOn) {
                        stringResource(R.string.settings_read_eye_care_on)
                    } else {
                        stringResource(R.string.settings_read_eye_care_desc)
                    }
                }
                SettingsItem(
                    icon = category.icon,
                    title = stringResource(category.titleRes),
                    subtitle = subtitle,
                    trailing = { Icon(FlowIcons.Next, contentDescription = null) },
                    onClick = { onOpenCategory(category) }
                )
            }
        }
    }
}

@Composable
fun NavigationSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    SettingsDetailScaffold(R.string.settings_section_navigation, onBack) {
        SettingsSwitch(
            icon = Icons.Default.History,
            title = stringResource(R.string.settings_show_continue_tab),
            subtitle = stringResource(R.string.settings_show_continue_tab_desc),
            checked = uiState.showContinueTab,
            onCheckedChange = { viewModel.updateNavigationTabs(it, uiState.showStatsTab) }
        )
        SettingsSwitch(
            icon = FlowIcons.Statistics,
            title = stringResource(R.string.settings_show_stats_tab),
            subtitle = stringResource(R.string.settings_show_stats_tab_desc),
            checked = uiState.showStatsTab,
            onCheckedChange = { viewModel.updateNavigationTabs(uiState.showContinueTab, it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var goalPeriod by remember { mutableStateOf<Int?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }

    SettingsDetailScaffold(R.string.ui_habits, onBack) {
        FlowPreferenceGroup(stringResource(R.string.ui_habits), contentPadding = 0.dp) {
            SettingsItem(
                icon = Icons.Default.Flag,
                title = stringResource(R.string.settings_daily_goal),
                subtitle = stringResource(R.string.settings_minutes, uiState.dailyReadingGoal),
                onClick = { goalPeriod = 0 }
            )
            SettingsItem(
                icon = Icons.Default.Flag,
                title = stringResource(R.string.stats_goal_weekly),
                subtitle = stringResource(R.string.settings_minutes, uiState.weeklyReadingGoal),
                onClick = { goalPeriod = 1 }
            )
            SettingsItem(
                icon = Icons.Default.Flag,
                title = stringResource(R.string.stats_goal_monthly),
                subtitle = stringResource(R.string.settings_minutes, uiState.monthlyReadingGoal),
                onClick = { goalPeriod = 2 }
            )
        }
        FlowPreferenceGroup(stringResource(R.string.ui_reminder), contentPadding = 0.dp) {
            SettingsSwitch(
                icon = Icons.Default.Notifications,
                title = stringResource(R.string.settings_daily_reminder),
                subtitle = stringResource(
                    R.string.settings_daily_reminder_time,
                    uiState.readingReminderHour,
                    uiState.readingReminderMinute
                ),
                checked = uiState.readingReminderEnabled,
                onCheckedChange = { viewModel.updateReadingReminder(it) }
            )
            // The time was displayed but not editable before; this is the missing setter.
            SettingsItem(
                icon = FlowIcons.Clock,
                title = stringResource(R.string.settings_reminder_time),
                subtitle = String.format(
                    java.util.Locale.getDefault(),
                    "%02d:%02d",
                    uiState.readingReminderHour,
                    uiState.readingReminderMinute
                ),
                onClick = { showTimePicker = true }
            )
        }
    }
    goalPeriod?.let { period ->
        GoalDialog(
            title = stringResource(
                when (period) {
                    1 -> R.string.stats_goal_weekly
                    2 -> R.string.stats_goal_monthly
                    else -> R.string.settings_daily_goal
                }
            ),
            currentGoal = when (period) {
                1 -> uiState.weeklyReadingGoal
                2 -> uiState.monthlyReadingGoal
                else -> uiState.dailyReadingGoal
            },
            onGoalChange = { viewModel.updateReadingGoal(period, it) },
            onDismiss = { goalPeriod = null },
            maxGoal = when (period) {
                1 -> 3000
                2 -> 9000
                else -> 120
            }
        )
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = uiState.readingReminderHour,
            initialMinute = uiState.readingReminderMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.settings_reminder_time)) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateReadingReminder(
                            enabled = true,
                            hour = timeState.hour,
                            minute = timeState.minute
                        )
                        showTimePicker = false
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
fun DataSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val autoBackup by viewModel.automaticBackupState.collectAsState()
    var showShelfExportDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) viewModel.onExportReady(uri) else viewModel.cancelDocumentSelection() }

    val shelfExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri -> if (uri != null) viewModel.onShelfExportReady(uri) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.onImportReady(uri) else viewModel.cancelDocumentSelection() }
    val directoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setAutomaticBackupDirectory(uri)
    }

    LaunchedEffect(uiState.exportResult) {
        uiState.exportResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearExportResult()
        }
    }
    LaunchedEffect(uiState.importResult) {
        uiState.importResult?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearImportResult()
        }
    }

    SettingsDetailScaffold(R.string.ui_settings_data, onBack, snackbarHostState) {
        FlowPreferenceGroup(stringResource(R.string.ui_backup_restore), contentPadding = 0.dp) {
            SettingsItem(
                icon = FlowIcons.Cloud,
                title = "完整备份",
                subtitle = "书籍文件、封面、分组、收藏、笔记、定位、统计与设置",
                onClick = {
                    viewModel.exportData()
                    exportLauncher.launch("一卷备份-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())}.zip")
                }
            )
            SettingsItem(
                icon = Icons.Default.Restore,
                title = stringResource(R.string.settings_restore),
                subtitle = "先校验与预览，再选择合并或替换；兼容旧 JSON",
                onClick = {
                    viewModel.importData()
                    importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "application/json", "text/plain"))
                }
            )
        }
        FlowPreferenceGroup("本地自动备份", contentPadding = 0.dp) {
            SettingsItem(icon = Icons.Default.Source, title = "备份目录",
                subtitle = if (autoBackup.directory == null) "选择本地目录后开启" else "已选择 · 文件保存在“页间自动备份”子文件夹",
                onClick = { directoryLauncher.launch(autoBackup.directory?.let { android.net.Uri.parse(it) }) })
            SettingsSwitch(icon = FlowIcons.Cloud, title = "每天自动备份",
                subtitle = "由系统在电量允许时执行；关机后重新打开应用恢复调度。",
                checked = autoBackup.enabled, enabled = autoBackup.directory != null && !autoBackup.running,
                onCheckedChange = { viewModel.setAutomaticBackupEnabled(it) })
            SettingsItem(icon = Icons.Default.History, title = "保留份数",
                subtitle = "只清理本功能创建的旧自动备份")
            ChipRow(options = listOf(3, 5, 10), selected = autoBackup.keep, label = { "$it 份" },
                onSelect = { viewModel.setAutomaticBackupKeep(it) })
            SettingsItem(icon = FlowIcons.Cloud,
                title = if (autoBackup.running) "正在自动备份…" else "立即备份到此目录",
                subtitle = if (autoBackup.lastSuccess == 0L) "尚无成功备份" else "最近成功：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(autoBackup.lastSuccess))}",
                onClick = if (autoBackup.directory != null && !autoBackup.running && !uiState.backupBusy) ({ viewModel.runAutomaticBackup(); Unit }) else null)
            autoBackup.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp)) }
        }
        FlowPreferenceGroup(stringResource(R.string.ui_export), contentPadding = 0.dp) {
            SettingsItem(
                icon = Icons.Default.TableChart,
                title = stringResource(R.string.settings_export_shelf),
                subtitle = stringResource(R.string.settings_export_shelf_desc),
                onClick = { showShelfExportDialog = true }
            )
        }
        SecuritySettingsContent(viewModel)
    }

    BackupOperationDialogs(uiState, viewModel)

    if (showShelfExportDialog) {
        AlertDialog(
            onDismissRequest = { showShelfExportDialog = false },
            title = { Text(stringResource(R.string.settings_export_shelf)) },
            text = {
                Column {
                    ShelfExportFormat.entries.forEach { format ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    when (format) {
                                        ShelfExportFormat.CSV -> stringResource(R.string.settings_export_csv)
                                        ShelfExportFormat.JSON -> stringResource(R.string.settings_export_json)
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                showShelfExportDialog = false
                                viewModel.requestShelfExport(format)
                                shelfExportLauncher.launch(
                                    if (format == ShelfExportFormat.CSV) "flowreader-shelf.csv" else "flowreader-shelf.json"
                                )
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showShelfExportDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    SettingsDetailScaffold(R.string.settings_section_about, onBack) {
        FlowPreferenceGroup(contentPadding = 0.dp) {
            SettingsItem(
                icon = FlowIcons.Info,
                title = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                subtitle = stringResource(R.string.settings_about_desc)
            )
        }
        FlowPreferenceGroup(stringResource(R.string.settings_about_changelog), contentPadding = 0.dp) {
            Column(modifier = Modifier.padding(horizontal = FlowSpacing.lg)) {
                CHANGELOG.take(1).forEach { (version, items) ->
                    Text(
                        text = version,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = FlowSpacing.md)
                    )
                    items.forEach { line ->
                        Text(
                            text = changelogLine("· $line"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                FlowExpandableGroup(stringResource(R.string.settings_about_changelog), "0.2.19 · 0.2.18 · …") {
                    CHANGELOG.drop(1).forEach { (version, items) ->
                        Text(version, style = MaterialTheme.typography.titleSmall)
                        items.forEach { Text(changelogLine("· $it"), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        FlowPreferenceGroup(stringResource(R.string.ui_source), contentPadding = 0.dp) {
            // 项目来源、致谢与许可属于同一个信息组。
            SettingsItem(
                icon = Icons.Default.Source,
                title = stringResource(R.string.settings_about_upstream),
                subtitle = stringResource(R.string.settings_about_upstream_desc),
                onClick = { openUrl(context, UPSTREAM_REPO_URL) }
            )
            SettingsItem(
                icon = Icons.Default.Code,
                title = stringResource(R.string.settings_about_fork_repo),
                subtitle = stringResource(R.string.settings_about_fork_repo_desc),
                onClick = { openUrl(context, FORK_REPO_URL) }
            )

            SettingsItem(
                icon = Icons.Default.Person,
                title = stringResource(R.string.settings_about_thanks),
                subtitle = stringResource(R.string.settings_about_author)
            )

            AboutSectionTitle(stringResource(R.string.settings_about_licenses))
            Column(modifier = Modifier.padding(horizontal = FlowSpacing.lg, vertical = FlowSpacing.xs)) {
                listOf("Jetpack Compose", "Room", "Hilt", "Coil", "jsoup", "Readium Kotlin Toolkit").forEach {
                    Text("- $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            SpeechLicensesRow()
        }
    }
}

@Composable
private fun AboutSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = FlowSpacing.lg, top = FlowSpacing.lg, bottom = FlowSpacing.xs)
    )
}

/** Opens a project link; a missing browser must not crash the settings screen. */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private const val UPSTREAM_REPO_URL = "https://github.com/HuZaiGong/flowreader"
private const val FORK_REPO_URL = "https://github.com/HuZaiGong/flowreader"

/**
 * Renders the `**bold**` emphasis used by [CHANGELOG] as real bold. Without this the markers were
 * shown literally as asterisks on the 关于 page.
 */
private fun changelogLine(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { index, part ->
        withStyle(if (index % 2 == 1) SpanStyle(fontWeight = FontWeight.SemiBold) else SpanStyle()) {
            append(part)
        }
    }
}

/**
 * Release notes shown on the 关于 page, newest first. Kept in code rather than parsed from
 * CHANGELOG.md so the app never needs asset I/O — and so it matches the APK the user installed.
 */
private val CHANGELOG: List<Pair<String, List<String>>> = listOf(
    "0.3.13 · 2026-10-28" to listOf(
        "固定阅读页面回调的请求编号，防止旧页面回写把新章节退回上一章。",
        "手动切章先确认目标章节显示，再允许连续翻页接管位置。",
        "保留原有界面与数据；已通过本地定向回归，指定 EPUB 真机复验待完成。"
    ),
    "0.3.12 · 2026-10-27" to listOf(
        "修复预加载窗口缺章时越过中间章节，目录跳转从所选章首打开。",
        "当前章节优先排版，减少 EPUB 打开时重复扫描正文及界面状态处理的等待。",
        "保留现有阅读样式、书签定位、系列与 PDF 功能，数据结构不变。"
    ),
    "0.3.11 · 2026-10-24" to listOf(
        "同系列书籍合并展示，支持手动调整卷序及本卷末尾打开下一卷。",
        "补全 PDF 原版式阅读、缩放、跳页和页码恢复，复用系统渲染器。",
        "书籍详情显示文件大小；系列信息纳入完整备份并兼容旧备份。"
    ),
    "0.3.10 · 2026-10-22" to listOf(
        "阅读统计从零重新开始，按正文前台可见时间记录，切后台或失去窗口焦点时暂停。",
        "设置修改仅刷新相关界面，主导航保留页面状态，封面改为后台按尺寸解码。",
        "采用代码与资源裁剪的发布版构建，保留字体、封面和离线语音引擎。"
    ),
    "0.3.9 · 2026-10-20" to listOf(
        "应用更名为一卷，沿用现有图标、界面与全部功能。",
        "书籍、偏好、语音模型及自动备份目录保持兼容。"
    ),
    "0.3.8 · 2026-10-20" to listOf(
        "修正分页书签跳错章及源位置恢复，返回书架不再重复导入和提示。",
        "阅读计时跟随前后台暂停，修正闲置虚增、保存丢失及并发增量覆盖。",
        "Kokoro 缩短首段合成并接入分句输出，试听页展示准备、播放和错误状态。"
    ),
    "0.3.7 · 2026-10-15" to listOf(
        "语音模型在应用内按需下载、校验、试听和删除，基础安装包不附带模型。",
        "提供小雅、超文轻量中文和 Kokoro 多音色包，可切换两个社区镜像及官方源。",
        "内置精简离线引擎，保留系统语音；听书开关仍默认关闭。"
    ),
    "0.3.6 · 2026-10-14" to listOf(
        "全库和书内按原文搜索，支持中文短语、单字、符号以及进度和取消。",
        "搜索结果按实际分页和滚动布局回到原文。"
    ),
    "0.3.5 · 2026-10-14" to listOf(
        "完整备份书籍文件、封面、记录与设置；先校验预览，再合并或替换恢复。",
        "本地自动备份默认关闭，支持目录选择、回读校验与保留 3／5／10 份。",
        "听书短句连续播放、中文本地语音优先，补充错误码、引擎选择和重新连接。",
        "听书开关使用共用设置样式，文件位置使用父目录定位并明确共享目录限制。"
    ),
    "0.3.1" to listOf("阅读栏字号入口改为设置；书签添加后显示提示与实心图标", "阅读器进出取消页面叠加过渡，避免正文残影", "未读归入首页状态筛选，继续阅读菜单更名为阅读历史"),
    "0.3.0" to listOf("更名为页间，采用奶油色三本书图标", "排版缓存支持再次进入和本地复用，当前页先于整章就绪", "进度滑杆恢复上一章／下一章，移除底部重复更多入口"),
    "0.2.23" to listOf("正文标注默认细下划线，可切换柔和高亮；已有笔记和标注位置保留", "书籍详情新增文件位置，可查看、复制和打开原目录", "记录新导入文件来源；旧书可关联原文件或保存本地副本"),
    "0.2.22" to listOf("新增默认清简蓝主题，保留暖纸墨韵及现有夜间配色", "书架分组与阅读状态统一筛选，收藏独立于分组并支持备份恢复", "阅读设置与目录使用标签面板，统一主要页面与笔记编辑"),
    "0.2.21" to listOf(
        "修复阅读正文长按没有选区的问题，恢复复制、高亮与书签操作",
        "选中文字后可直接写笔记，保存摘录与笔记内容；取消不会写入",
        "修复选区手柄拖动与菜单位置，保留连续跨章和设置保存逻辑"
    ),
    "0.2.20" to listOf(
        "统一界面组件与主题，底部固定为书架、笔记、统计和设置",
        "设置重整为五类目录，合并字体与排版、翻页与手势，高级选项按需展开",
        "继续阅读与历史归入书架，隐藏书单和发现入口；保留原功能数据",
        "目标与提醒集中管理，统计及时同步；保留连续跨章与阅读设置保存修复"
    ),
    "0.2.19" to listOf(
        "书房视觉：暖纸色、中文宋体标题、山水横幅与书封；书架、笔记、统计、设置统一样式",
        "阅读面板：夜间炭黑与暖金配色，新增亮度、字号和行距快捷调整；保留翻页方式等详细设置",
        "统计：主图跟随所选周／月／年；修复阅读总时长将秒误按毫秒换算的问题",
        "保留连续跨章与阅读设置保存修复；已有书库、笔记与阅读偏好继续使用"
    ),

    "0.2.18" to listOf(
        "修复：阅读进度更新会把刚选择的翻页设置恢复为旧值，导致上下滑动、翻页动画、点击热区和音量键选项看起来无法切换",
        "修复：阅读设置立即生效，连续调整时保存最后一次选择；保存失败会显示提示并允许重试"
    ),
    "0.2.17" to listOf(
        "连续分页：相邻章节加入同一个翻页容器，跨章与章内翻页使用同一段动画，翻过去后再更新章节信息",
        "连续分页：提前准备后续页面，向前、向后跨章均保留当前画面；插图、注音与批注使用各自章节的数据",
        "修复：跨章后的页数统计与阅读位置按章节内页码计算，进度保存继续异步执行"
    ),
    "0.2.16" to listOf(
        "切章优化：提前准备后续两章的正文、插图、列表与注音标注，并预排分页结果，连续阅读时直接复用",
        "切章优化：预排版任务跨章节保留，已经在准备的下一章不会因翻页被取消、重新排版",
        "修复：进度保存改为显示章节后异步执行，慢速存储不会阻塞继续翻页；旧写入不能覆盖较新的阅读位置"
    ),
    "0.2.15" to listOf(
        "修复：EPUB **注音（ruby）**此前被画进基字内部、与基字重叠；现在含注音的段落会为注音预留行高，注音干净地浮在基字上方",
        "说明：这是 0.2.14 真机验收发现的缺陷；正文坐标、字数口径与书签/批注均不受影响"
    ),
    "0.2.14" to listOf(
        "S5 标注：EPUB 的**列表**现在会画出项目符号与编号（有序列表按书里的起始序号续编，长列表跨页不会又从 1 开始），嵌套层级按缩进显示",
        "S5 标注：EPUB 的**表格**把制表符展开为固定间距、表头单元格加粗；在表格上选择文字/写批注不会错位（各列按最宽单元格真正对齐仍未做，原因见报告 §10.2）",
        "S5 标注：日文等**注音（ruby）**会以小字画在基字上方，基字仍是可选中、可批注的真实文字",
        "S5 插图：EPUB 里的 **SVG 矢量插图**现在可以显示（此前会留空或按占位高度排版）",
        "说明：**已导入的书需要重新导入一次**才会带列表/表格/注音标注（内容包结构升级到第 3 版）。重新导入会为该书新建一份内容，而书签/批注仍指向旧的那份 —— 实际效果等同于**该书的书签与批注会消失**，请先确认再重导",
        "说明：注音目前是叠加绘制（不参与行高计算），极端密集时可能与上一行挨得较近；表格目前做到列对齐与表头加粗，尚未画真正的表格网格线"
    ),
    "0.2.13" to listOf(
        "S5 插图：**滑动模式**（无翻页 / 滑动）现在也会显示插图（此前只有分页模式有）",
        "S5 插图：点击插图可**放大查看** —— 双指缩放、拖动、显示图注/替代文字；预览层自己消费手势，不会误翻页",
        "S5 插图：图显示不出来时会**区分**「资源缺失」与「解码失败」并显示替代文字，不再只留一片空白",
        "S5 插图：图片内存缓存有明确上限 min(64MiB, 堆内存/8)，解码并发限制为 2 张，避免大图把内存打满导致其它图被挤掉",
        "说明：列表编号、表格、注音（ruby）的呈现需要把结构信息写进内容包，属下一批；SVG 需要新增一个依赖，等你确认后再接"
    ),
    "0.2.12" to listOf(
        "修复：插图位置仍可能显示成 [IMG 加一串路径文字（分到两页）。0.2.10 只挡住了较长的被切片段，而排版用的二分查找会切出更短的片段（例如 [IMG）；现在任何残缺的图片标记都会被识别并整张换页",
        "说明：本版还补强了对应测试（先确认它在旧代码上会失败，再修复），避免同类漏判再次发生"
    ),
    "0.2.11" to listOf(
        "修复：有些插图要先退出再进来才显示（原位只留一个虚线方框）。原因是提前排版相邻章节时漏传了图片清单，缓存出了一份「没有图」的排版，翻过去正好命中它；现在图片与正文一起参与排版，并且该参数不再允许省略",
        "说明：这类问题只在「按顺序翻到下一章」时出现，从目录直接跳过去反而是好的"
    ),
    "0.2.10" to listOf(
        "修复：插图下方会多出一大段图片路径文字（例如 /data/user/0/com.flowreader.app/…）。原因是排版时把放不下的插图当文字切开了，切开后的片段被当成普通段落画了出来；现在插图是不可分割的整体，放不下就整张换页",
        "说明：这类问题只在「上一页剩余空间不够放这张图」时出现，所以不是每张图都会看到"
    ),
    "0.2.9" to listOf(
        "S5 插图（第一步）：EPUB 的插图现在会正确显示在正文里（此前新导入的 EPUB 插图完全不显示）——分页模式下按原书位置排版，图不会被截断、也不会压字",
        "S5 插图：资源找不到或损坏时显示占位图标，而不是静默留白；图片高度按真实尺寸预留，因此不会出现图文重叠或整页留白",
        "S5 插图：规范正文里图片占位符不会被写进正文，书内搜索、批注、书签的坐标不受影响",
        "说明：**已导入的旧书需要重新导入一次**才会带图片（导入格式升级为了记录插图映射）；重新导入会新建一份内容，不会动你已有的书签/批注",
        "说明：本次仅覆盖分页模式；滑动模式（无翻页/滑动）的插图、图片放大预览、SVG 插图、列表/表格/注音样式仍待做"
    ),
    "0.2.8" to listOf(
        "修复：打开书时此前会把**每一章正文都读一遍**才显示内容（长书要几百到上千次读库，真机 1434 章的书尤其明显）；现只读取真正需要的那一章，打开长书明显变快",
        "新增性能回归闸门：打开书与换章读取正文的次数**不得随书的章数增长**（20 章与 2000 章必须一致），防止后续功能（例如插图呈现）把开销重新加到换章路径上"
    ),
    "0.2.7" to listOf(
        "修复：部分机型的系统数据库不带 fts5 全文检索模块（本机测试机即如此），此前表现为「搜索永远没有结果」并且每次打开书都会在日志里报错；现明确提示「本机不支持全文检索」，不再假装搜过了",
        "修复：因为 fts5 建表失败，索引状态表从未被创建，导致「内容没变就跳过重建索引」的优化实际失效；现改为先建状态表，缺 fts5 时跳过那些写了也读不到的索引写入"
    ),
    "0.2.6" to listOf(
        "修复跨章节卡顿：切换章节不再把整屏换成转圈加载再重排，上一章会停留在屏幕上直到下一章就绪，翻章更接近翻页的体感",
        "修复「本章暂无内容」误报：章节正在排版时不再显示空章提示（此前每次换章都会闪一下），改为显示「正在排版…」",
        "新增：已排版的章节会被缓存，并会提前排版相邻章节——顺序阅读时下一章基本即点即显",
        "修复：相邻章节预取改为优先下一章，并提高章节内存缓存的下限，避免刚预取到的下一章又被挤出去",
        "修复：每次打开书都会整本重建全文索引（长书要几百次读库+写索引，和阅读抢 I/O）；现按内容指纹跳过，内容没变就不重建",
        "说明：新导入 EPUB 的插图目前仍不会显示——该修复尚未完成，不在本版内"
    ),
    "0.2.5" to listOf(
        "内部版本：本版没有用户可见的功能变化，仅为下一阶段（EPUB 插图渲染）打底，请继续用 0.2.4 的功能标准使用",
        "说明：新导入 EPUB 的插图目前仍不会显示——该修复尚未完成，不在本版内"
    ),
    "0.2.4" to listOf(
        "修复底部导航栏：底部 dock 栏此前被硬编码压到 64dp，扣掉手势条留白后图标被顶到栏顶、文字被挤到很低；现恢复系统默认高度，图标与文字间距正常",
        "修复卡顿：导入新书会触发全文索引重建，此前逐章读库（上百次查询），现改为分块批量读取",
        "修复卡顿：搜索一次会逐条读取上百章正文，现改为批量读取",
        "修复书架网格：单行标题的书格比双行标题矮一行，导致同一行里书封高低不齐；现固定两行高度",
        "说明：若仍有卡顿或导入失败，请抓取 logcat 反馈（见交接文档）"
    ),
    "0.2.3" to listOf(
        "修复：目录里的章节点不动——此前导入时目录目标没有写入，所有目录项都跳不到对应章节（真机验收发现）",
        "修复后目录层级、卷范围与跳转位置均与书中一致"
    ),
    "0.2.2" to listOf(
        "S8b 目录改为使用 EPUB 的真实目录层级：卷、章节、子章节的嵌套关系与书中完全一致（此前是从平铺章节列表按标题猜出来的）",
        "S8b 无链接的卷头（原书里只是分隔标题）不再丢失，作为可折叠分组保留",
        "S8b 目录里一条损坏的记录不再影响其他条目，也不会让整本目录打不开",
        "S8b 旧书（尚未重新解析）继续使用兼容目录，不受影响"
    ),
    "0.2.1" to listOf(
        "S8 目录重构：卷可以独立展开/收起（此前卷行完全点不动），并新增「全部展开 / 全部收起」",
        "S8 新增：目录项显示卷的覆盖范围（「第 1—12 节 · 共 12 节」；不连续的卷如实标注「非连续」）",
        "S8 新增：搜索同时匹配章标题与卷标题；命中的章自动展开完整祖先链，无结果给空态",
        "S8 新增：有独立卷首的卷提供单独「读卷首」按钮，与展开/收起分开，不会一次点击既展开又跳转",
        "S8 修复：同名卷/同名章不再互相冲突（此前用标题当标识）",
        "S8 修复：无子节点又无内容的空卷不再显示可点却点不动的展开箭头，改为「暂无可读章节」",
        "S8 修复：打开目录时自动展开当前章所在卷，其余卷保持收起"
    ),
    "0.2.0" to listOf(
        "S7 搜索升级：搜索结果携带正文坐标，点击直接跳到命中那一句（此前无论命中在哪都只跳到章首）",
        "S7 修复：搜索结果里原样显示 << >> 高亮标记；现改为干净文本",
        "S7 修复：FTS 的分词位置不再被当成字符位置——改为回到正文重新定位并校验，重复词用上下文消歧",
        "S7 无法可靠定位时如实标注「约略定位」并退回章首，不假装精确"
    ),
    "0.1.11" to listOf(
        "S6c 多区间高亮：同一段内有多段选区时，只高亮真正被选中的部分（此前会把中间未选中的文字一起点亮）",
        "S6c 修复：高亮区域内的 ** 加粗标记会泄漏到正文并导致后续选择错位"
    ),
    "0.1.10" to listOf(
        "S6b 跨块批注：手指可以从一个段落拖到另一个段落建立批注（此前选择被限制在单个段落内）",
        "S6b 修复：跨块选择不再吞掉中间未选中的文字；重叠选区不重复输出",
        "S6b 修复：跨段落批注的高亮此前在任何一段都匹配不上，导致高亮整条消失"
    ),
    "0.1.9" to listOf(
        "S6 书签/批注记录正文坐标（支持多段范围 + revision），改字号、换字体后回到同一处内容，而不是同一个页号",
        "S6 旧数据无坐标时如实标注「定位待恢复」，不猜测旧页码/像素的单位",
        "S6 新增：批注可以横跨多个不相连的文字区间"
    ),
    "0.1.8" to listOf(
        "S5 滚动阅读改用正文坐标：滚动停止时把视口顶部投影为正文位置，改字号/换字体/旋屏后回到同一处内容",
        "S5 修复：人工缩进（段首两个全角空格）会导致全书正文坐标整体偏移一位",
        "S5 新增真机验收：连续 20 次改变排版后，屏幕顶部第一行文字与锚点零漂移"
    ),
    "0.1.7" to listOf(
        "翻页重构：边界手势改为在触摸分发阶段识别（不再依赖滚动容器的越界回调），过短的章节也能跨章",
        "跨章对称：前进落到下一章开头，反向回到上一章末尾；每个手势最多跨一章，首章/末章不越界",
        "修复连翻：翻页期间同步占用状态并锁定目标页，快速连点不再跳过 2~3 页",
        "修复误翻：点击判定改为校验完整触摸轨迹（子组件消费、多指、拖动回原点、长按均不算点击）",
        "修复重复/倒退：章节加载互斥、保存顺序串行、旧会话回调按版本丢弃",
        "正文与页眉重叠：顶部留白移到固定视口外侧，滚动不再吃掉留白；分页测量与渲染共用同一可用高度"
    ),
    "0.1.6" to listOf(
        "修复：分页与滚动模式下向上/向右滑动跨章到上一章（自动定位至上一章末尾）",
        "修复：分页模式下手势冲突与跨章边界判定"
    ),
    "0.1.5" to listOf(
        "优化：跨章切换平滑度与阅读状态同步"
    ),
    "0.1.4" to listOf(
        "修复：设置 → 阅读 打开空白（同一条路由被注册了两次，空的那次生效）",
        "修复：统计选具体月份仍显示「近 2 个月」——区间改为按日历对齐（整月/整周/整年）",
        "修复：旧书不显示书字数（首次打开详情页时用 SQL 统计并入库）",
        "修复：章节标题重复出现、正文里漏出 ** 加粗标记",
        "书架「分组内的书已隐藏」空状态新增一键「显示分组内的书」"
    ),
    "0.1.3" to listOf(
        "书籍详情页重构：新增书字数（数据库 v9）与「阅读统计」二级页",
        "累计阅读支持本周 / 本月 / 全部切换；目标值改为半屏面板"
    ),
    "0.1.2" to listOf(
        "设置体系重构：新增「设置 → 阅读」二级菜单（排版 / 字体 / 翻页 / 手势 / 主题与背景 / 护眼与屏幕）",
        "手势从九项弹窗改为整页；字体与排版在阅读面板和设置中复用同一份实现",
        "统计页：周 / 月 / 年切换、总时长大字、日均与同比、四项指标"
    ),
    "0.1.1" to listOf(
        "修复：滑动到底/到顶继续拖拽可跨章（上一章从末尾进入）",
        "修复：代码中文化还原（PowerShell 5.1 回写导致的乱码）"
    ),
    "0.1.0" to listOf(
        "EPUB：插图改为持久目录保存并修复相对路径解析、目录标题取 EPUB 自带 TOC、封面页不再当章节",
        "分卷：新增 Chapter.volume 与数据库 v8，TXT/EPUB 的分卷在目录里成组显示",
        "分页：块放不下时填满当前页，章节标题不再独占首页；标题间距收窄",
        "目录改为全屏面板（目录 / 笔记 / 书签 + 分卷 + 搜索）",
        "翻页：滑动模式点击侧边为动画滚动，音量键跨章",
        "新增纯黑白阅读主题；书架网格改版；标签支持多标签增删；提醒时间可设置"
    ),
    "0.0.9" to listOf(
        "EPUB 解析修复（图片、章节、目录）", "决策转盘移除", "阅读详情页与目录改版", "流畅度优化"
    ),
    "0.0.8" to listOf(
        "覆盖翻页动画", "TXT 净化规则与自定义分章正则", "目录自动扫描", "应用锁", "统计热力图",
        "应用内书封面上传", "书名/作者修改"
    ),
    "0.0.7" to listOf("简繁转换", "护眼与色温", "排版细化（页边距 / 每行字数 / 对齐）", "多级设置")
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm),
        modifier = Modifier.padding(horizontal = FlowSpacing.lg, vertical = FlowSpacing.sm)
    ) {
        options.forEach { option ->
            FlowChoiceChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(label(option)) }
            )
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FlowSpacing.lg, vertical = FlowSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    containerColor: androidx.compose.ui.graphics.Color = Color.Transparent,
    badge: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .fillMaxWidth().heightIn(min = 64.dp).background(containerColor).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (badge != null) {
            FlowFeatureIcon(icon, badge)
        } else {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun SettingsSwitch(icon: ImageVector, title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String = "", enabled: Boolean = true) {
    ListItem(
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = if (subtitle.isNotEmpty()) {
            { Text(subtitle) }
        } else {
            null
        },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch) { onCheckedChange(!checked) }
            .semantics { toggleableState = androidx.compose.ui.state.ToggleableState(checked) }
    )
}

@Composable
internal fun GoalDialog(title: String, currentGoal: Int, onGoalChange: (Int) -> Unit, onDismiss: () -> Unit, maxGoal: Int = 120) {
    var goal by remember { mutableIntStateOf(currentGoal) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.settings_minutes, goal),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(FlowSpacing.lg))
                FlowSlider(
                    value = goal.toFloat(),
                    onValueChange = { goal = it.toInt() },
                    valueRange = 5f..maxGoal.toFloat(),
                    steps = 0
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.settings_goal_min), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.settings_minutes, maxGoal), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onGoalChange(goal)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * 1.4 手势, as a full page instead of the nine-control dialog it used to be.
 *
 * Changes write straight through (no 确认 button): every row is a single discrete choice, and the
 * reader picks it up the next time it composes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GestureSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    PageTurnSettingsScreen(onBack, viewModel)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GestureSettingsContent(settings: GestureSettings, onChange: (GestureSettings) -> Unit) {
    fun update(updated: GestureSettings) = onChange(updated)
    SectionHeader(stringResource(R.string.settings_gesture_tap_section))
    GestureRow(stringResource(R.string.gesture_left_tap), settings.leftTapAction) {
        update(settings.copy(leftTapAction = it))
    }
    GestureRow(stringResource(R.string.gesture_middle_tap), settings.middleTapAction) {
        update(settings.copy(middleTapAction = it))
    }
    GestureRow(stringResource(R.string.gesture_right_tap), settings.rightTapAction) {
        update(settings.copy(rightTapAction = it))
    }

    SectionHeader(stringResource(R.string.settings_gesture_swipe_section))
    GestureRow(stringResource(R.string.gesture_swipe_left), settings.swipeLeftAction) {
        update(settings.copy(swipeLeftAction = it))
    }
    GestureRow(stringResource(R.string.gesture_swipe_right), settings.swipeRightAction) {
        update(settings.copy(swipeRightAction = it))
    }

    SectionHeader(stringResource(R.string.settings_gesture_other_section))
    // Double tap is opt-in: while it is on, a single tap has to wait out the double-tap window
    // before it can fire, which is what makes the control bar feel sluggish. Off means every
    // single tap is dispatched on finger-up.
    SettingsSwitch(
        icon = Icons.Default.TouchApp,
        title = stringResource(R.string.gesture_double_tap_enable),
        subtitle = stringResource(R.string.gesture_double_tap_enable_desc),
        checked = settings.doubleTapEnabled,
        onCheckedChange = { update(settings.copy(doubleTapEnabled = it)) }
    )
    if (settings.doubleTapEnabled) {
        GestureRow(stringResource(R.string.gesture_double_tap), settings.doubleTapAction) {
            update(settings.copy(doubleTapAction = it))
        }
    }
    GestureRow(stringResource(R.string.gesture_long_press), settings.longPressAction) {
        update(settings.copy(longPressAction = it))
    }

    SectionHeader(stringResource(R.string.settings_gesture_zone_section))
    SettingsSwitch(
        icon = Icons.Default.SwipeRight,
        title = stringResource(R.string.gesture_edge_zone),
        checked = settings.edgeGestureEnabled,
        onCheckedChange = { update(settings.copy(edgeGestureEnabled = it)) }
    )
    if (settings.edgeGestureEnabled && settings.tapZoneMode == TapZoneMode.SYMMETRIC) {
        Text(
            text = stringResource(R.string.gesture_left_edge, settings.leftEdgeWidth),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = FlowSpacing.lg)
        )
        FlowSlider(
            value = settings.leftEdgeWidth.toFloat(),
            onValueChange = { update(settings.copy(leftEdgeWidth = it.toInt())) },
            valueRange = 5f..45f,
            modifier = Modifier.padding(horizontal = FlowSpacing.lg)
        )
        Text(
            text = stringResource(R.string.gesture_right_edge, settings.rightEdgeWidth),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = FlowSpacing.lg)
        )
        FlowSlider(
            value = settings.rightEdgeWidth.toFloat(),
            onValueChange = { update(settings.copy(rightEdgeWidth = it.toInt())) },
            valueRange = 5f..45f,
            modifier = Modifier.padding(horizontal = FlowSpacing.lg)
        )
    }
}

/** Small bold heading that separates groups inside a settings page. */
@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = FlowSpacing.lg, top = FlowSpacing.lg, bottom = FlowSpacing.xs)
    )
}

@Composable
fun GestureRow(label: String, value: GestureAction, onValueChange: (GestureAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        var expanded by remember { mutableStateOf(false) }
        Row {
            TextButton(onClick = { expanded = true }) { Text(value.displayName) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                GestureAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.displayName) },
                        onClick = {
                            onValueChange(action)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
