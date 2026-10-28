package com.flowreader.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowreader.app.core.util.ShelfExporter
import com.flowreader.app.domain.model.AppLanguage
import com.flowreader.app.domain.model.AppThemeMode
import com.flowreader.app.domain.model.ColorSource
import com.flowreader.app.domain.model.GestureSettings
import com.flowreader.app.domain.model.LibraryCoverSize
import com.flowreader.app.domain.model.LibraryViewMode
import com.flowreader.app.domain.model.ReadingSettings
import com.flowreader.app.domain.model.TxtProcessingRules
import com.flowreader.app.domain.repository.BackupRepository
import com.flowreader.app.domain.repository.BookRepository
import com.flowreader.app.domain.repository.CategoryRepository
import com.flowreader.app.domain.repository.ReadingListRepository
import com.flowreader.app.domain.repository.SettingsRepository
import com.flowreader.app.util.LanTransferClient
import com.flowreader.app.util.LanTransferServer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shelf export target format (v55): CSV table or JSON dump. */
enum class ShelfExportFormat { CSV, JSON }

data class SettingsUiState(
    val visualStyle: com.flowreader.app.domain.model.AppVisualStyle = com.flowreader.app.domain.model.AppVisualStyle.CLEAN_BLUE,
    val visualStyleSaveError: String? = null,
    val themeMode: AppThemeMode = AppThemeMode.FOLLOW_SYSTEM,
    val colorSource: ColorSource = ColorSource.BRAND,
    val language: AppLanguage = AppLanguage.FOLLOW_SYSTEM,
    val readingSettings: ReadingSettings = ReadingSettings(),
    val readingSettingsSaveError: String? = null,
    val isLoading: Boolean = true,
    val readingReminderEnabled: Boolean = false,
    val readingReminderHour: Int = 20,
    val readingReminderMinute: Int = 0,
    val dailyReadingGoal: Int = 30,
    val weeklyReadingGoal: Int = 210,
    val monthlyReadingGoal: Int = 900,
    val exportResult: String? = null,
    val importResult: String? = null,
    val backupPreview: com.flowreader.app.domain.repository.BackupPreview? = null,
    val backupBusy: Boolean = false,
    val restoring: Boolean = false,
    val backupStatus: String? = null,
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val isOnboardingCompleted: Boolean = false,
    val customFontPath: String? = null,
    val shelfExportFormat: ShelfExportFormat? = null,
    val lanServerUrl: String? = null,
    val lanTransferMessage: String? = null,
    val showContinueTab: Boolean = false,
    val showStatsTab: Boolean = false,
    val libraryViewMode: LibraryViewMode = LibraryViewMode.LIST,
    val libraryGridColumns: Int = 3,
    val libraryCoverSize: LibraryCoverSize = LibraryCoverSize.MEDIUM,
    val hideGroupedBooksFromAll: Boolean = false,
    val openReadBookDirectly: Boolean = true,
    val txtProcessing: TxtProcessingRules = TxtProcessingRules(),
    val scanFolderUri: String? = null,
    val lastScanTime: Long = 0L,
    val appLockEnabled: Boolean = false,
    val hasAppLockPin: Boolean = false,
    val isScanning: Boolean = false,
    val scanMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val backupRepository: BackupRepository,
    private val bookRepository: BookRepository,
    private val categoryRepository: CategoryRepository,
    private val readingListRepository: ReadingListRepository,
    private val folderScanner: com.flowreader.app.util.FolderScanner,
    private val fontImporter: com.flowreader.app.util.CustomFontImporter,
    private val backgroundImporter: com.flowreader.app.util.ReaderBackgroundImporter,
    private val automaticBackup: com.flowreader.app.util.AutomaticBackup = com.flowreader.app.util.AutomaticBackup(context, backupRepository)
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    val automaticBackupState = automaticBackup.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.flowreader.app.util.AutomaticBackupState())
    private var backupJob: kotlinx.coroutines.Job? = null
    private var previewFromLan = false
    private val readingSettingsWriter = Mutex()
    private val visualStyleWriter = Mutex()
    private var pendingVisualStyle: com.flowreader.app.domain.model.AppVisualStyle? = null
    private var lastStoredVisualStyle = com.flowreader.app.domain.model.AppVisualStyle.CLEAN_BLUE
    private var visualStyleGeneration = 0L
    private var completedVisualStyleGeneration = 0L
    private var pendingReadingSettings: ReadingSettings? = null
    private var lastStoredReadingSettings: ReadingSettings? = null
    private var readingSettingsSaveGeneration = 0L
    private var completedReadingSettingsGeneration = 0L

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            combine(
                settingsRepository.appSettings,
                combine(
                    settingsRepository.getDailyReadingGoal(),
                    settingsRepository.getWeeklyReadingGoal(),
                    settingsRepository.getMonthlyReadingGoal()
                ) { day, week, month -> Triple(day, week, month) },
                settingsRepository.isOnboardingCompleted(),
                settingsRepository.getCustomFontPath(),
                settingsRepository.getLibraryViewMode()
            ) { settings, goal, onboardingCompleted, fontPath, viewMode ->
                SettingsUiState(
                    themeMode = settings.themeMode,
                    colorSource = settings.colorSource,
                    visualStyle = settings.visualStyle,
                    language = settings.language,
                    readingSettings = settings.defaultReadingSettings,
                    isLoading = false,
                    readingReminderEnabled = settings.readingReminderEnabled,
                    readingReminderHour = settings.readingReminderHour,
                    readingReminderMinute = settings.readingReminderMinute,
                    dailyReadingGoal = goal.first,
                    weeklyReadingGoal = goal.second,
                    monthlyReadingGoal = goal.third,
                    isOnboardingCompleted = onboardingCompleted,
                    customFontPath = fontPath,
                    showContinueTab = settings.showContinueTab,
                    showStatsTab = settings.showStatsTab,
                    libraryViewMode = viewMode,
                    libraryGridColumns = settings.libraryGridColumns,
                    libraryCoverSize = settings.libraryCoverSize,
                    hideGroupedBooksFromAll = settings.hideGroupedBooksFromAll,
                    openReadBookDirectly = settings.openReadBookDirectly,
                    txtProcessing = settings.txtProcessing,
                    scanFolderUri = settings.scanFolderUri,
                    lastScanTime = settings.lastScanTime,
                    appLockEnabled = settings.appLockEnabled,
                    hasAppLockPin = !settings.appLockPinHash.isNullOrBlank()
                )
            }.collect { state ->
                lastStoredVisualStyle = state.visualStyle
                if (completedVisualStyleGeneration == visualStyleGeneration && pendingVisualStyle == state.visualStyle) pendingVisualStyle = null
                lastStoredReadingSettings = state.readingSettings
                if (completedReadingSettingsGeneration == readingSettingsSaveGeneration &&
                    pendingReadingSettings == state.readingSettings
                ) {
                    pendingReadingSettings = null
                }
                // Preserve transient scan feedback: a DataStore emission during a scan must not
                // clear the "scanning…" indicator.
                _uiState.update {
                    state.copy(
                        visualStyle = pendingVisualStyle ?: state.visualStyle,
                        visualStyleSaveError = it.visualStyleSaveError,
                        readingSettings = pendingReadingSettings ?: state.readingSettings,
                        readingSettingsSaveError = it.readingSettingsSaveError,
                        isScanning = it.isScanning,
                        scanMessage = it.scanMessage,
                        exportResult = it.exportResult,
                        importResult = it.importResult,
                        isExporting = it.isExporting,
                        isImporting = it.isImporting,
                        shelfExportFormat = it.shelfExportFormat,
                        lanServerUrl = it.lanServerUrl,
                        lanTransferMessage = it.lanTransferMessage,
                        backupPreview = it.backupPreview,
                        backupBusy = it.backupBusy,
                        restoring = it.restoring,
                        backupStatus = it.backupStatus
                    )
                }
            }
        }
    }

    fun checkOnboardingStatus() {
        viewModelScope.launch {
            settingsRepository.isOnboardingCompleted().collect { completed ->
                _uiState.update { it.copy(isOnboardingCompleted = completed) }
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.setOnboardingCompleted()
        }
    }

    fun exportData() {
        _uiState.update { it.copy(isExporting = true, exportResult = null) }
    }

    fun onExportReady(uri: Uri) {
        if (_uiState.value.backupBusy) return
        backupJob = viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true, backupStatus = "正在打包正文、记录和设置…") }
            try {
                backupRepository.exportData(uri)
                    .onSuccess { _uiState.update { it.copy(exportResult = "完整备份已保存") } }
                    .onFailure { e -> _uiState.update { it.copy(exportResult = "备份失败：${e.message}，请勿使用未完成的文件") } }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(exportResult = "已取消备份，请勿使用未完成的文件") }
                throw e
            } finally {
                _uiState.update { it.copy(isExporting = false, backupBusy = false, backupStatus = null) }
            }
        }
    }

    fun importData() { _uiState.update { it.copy(isImporting = true, importResult = null) } }
    fun cancelDocumentSelection() { _uiState.update { it.copy(isExporting = false, isImporting = false, shelfExportFormat = null) } }
    fun cancelBackupOperation() { if (!_uiState.value.restoring) backupJob?.cancel() }

    fun setAutomaticBackupDirectory(uri: Uri) = viewModelScope.launch {
        runCatching { automaticBackup.chooseDirectory(uri) }.onFailure { e -> _uiState.update { it.copy(importResult = "目录设置失败：${e.message}") } }
    }
    fun setAutomaticBackupEnabled(enabled: Boolean) = viewModelScope.launch {
        runCatching { automaticBackup.setEnabled(enabled) }.onFailure { e -> _uiState.update { it.copy(importResult = e.message) } }
    }
    fun setAutomaticBackupKeep(keep: Int) = viewModelScope.launch { automaticBackup.setKeep(keep) }
    fun runAutomaticBackup() = viewModelScope.launch { automaticBackup.run() }

    private var lanServer: LanTransferServer? = null
    private var lanBackupFile: java.io.File? = null

    fun startLanServer() {
        if (_uiState.value.backupBusy || lanServer?.isRunning == true) return
        backupJob = viewModelScope.launch {
            _uiState.update { it.copy(lanTransferMessage = null, backupBusy = true, backupStatus = "正在准备局域网完整备份…") }
            val backupFile = java.io.File(context.cacheDir, "lan_backup_${System.currentTimeMillis()}.zip")
            try {
            backupRepository.exportDataToFile(backupFile)
                .onSuccess {
                    val server = LanTransferServer(backupFile)
                    val url = server.start()
                    if (url != null) {
                        lanServer?.stop()
                        lanBackupFile?.delete()
                        lanBackupFile = backupFile
                        lanServer = server
                        _uiState.update { it.copy(lanServerUrl = url, lanTransferMessage = "服务已启动，同一 WiFi 下的设备可接收") }
                    } else {
                        _uiState.update { it.copy(lanTransferMessage = "启动失败：无法绑定端口或获取局域网地址") }
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(lanTransferMessage = "生成备份失败: ${e.message}") }
                }
            } finally {
                if (lanBackupFile != backupFile) backupFile.delete()
                _uiState.update { it.copy(backupBusy = false, backupStatus = null) }
            }
        }
    }

    fun stopLanServer() {
        lanServer?.stop()
        lanServer = null
        lanBackupFile?.delete()
        lanBackupFile = null
        _uiState.update { it.copy(lanServerUrl = null, lanTransferMessage = "服务已停止") }
    }

    fun importFromLanUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(lanTransferMessage = "请输入接收链接") }
            return
        }
        if (_uiState.value.backupBusy) return
        backupJob = viewModelScope.launch {
            _uiState.update { it.copy(lanTransferMessage = "正在接收…", backupBusy = true, backupStatus = "正在接收并校验备份…") }
            val target = java.io.File(context.cacheDir, "lan_import_${System.currentTimeMillis()}.json")
            try {
            LanTransferClient.download(trimmed, target)
                .onSuccess {
                    backupRepository.prepareImportFromFile(target)
                        .onSuccess { preview ->
                            previewFromLan = true
                            _uiState.update { it.copy(backupPreview = preview, lanTransferMessage = "校验完成，请查看恢复预览") }
                        }
                        .onFailure { e -> _uiState.update { it.copy(lanTransferMessage = "校验失败：${e.message}") } }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(lanTransferMessage = "接收失败: ${e.message}") }
                }
            } finally {
                target.delete()
                _uiState.update { it.copy(backupBusy = false, backupStatus = null) }
            }
        }
    }

    fun clearLanTransferMessage() {
        _uiState.update { it.copy(lanTransferMessage = null) }
    }

    fun requestShelfExport(format: ShelfExportFormat) {
        _uiState.update { it.copy(shelfExportFormat = format, isExporting = true, exportResult = null) }
    }

    fun onShelfExportReady(uri: Uri) {
        val format = _uiState.value.shelfExportFormat ?: return
        viewModelScope.launch {
            runCatching {
                val books = bookRepository.getAllBooks().first()
                val categories = categoryRepository.getAllCategories().first()
                val lists = readingListRepository.getAllLists().first()
                val listsByBook = buildMap<Long, String> {
                    lists.forEach { list ->
                        readingListRepository.getBooksInList(list.id).first().forEach { entry ->
                            put(entry.book.id, list.name)
                        }
                    }
                }
                val content = when (format) {
                    ShelfExportFormat.CSV -> ShelfExporter.toCsv(books, categories, lists, listsByBook)
                    ShelfExportFormat.JSON -> ShelfExporter.toJson(books, categories, lists, listsByBook)
                }
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(content.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                _uiState.update { it.copy(isExporting = false, shelfExportFormat = null, exportResult = "书架信息已导出") }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(isExporting = false, shelfExportFormat = null, exportResult = "导出失败: ${e.message}")
                }
            }
        }
    }

    fun onImportReady(uri: Uri) {
        if (_uiState.value.backupBusy) return
        backupJob = viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true, backupStatus = "正在校验文件与关联记录…") }
            try {
                backupRepository.prepareImport(uri)
                    .onSuccess { preview -> previewFromLan = false; _uiState.update { it.copy(backupPreview = preview) } }
                    .onFailure { e -> _uiState.update { it.copy(importResult = "校验失败：${e.message}") } }
            } finally { _uiState.update { it.copy(isImporting = false, backupBusy = false, backupStatus = null) } }
        }
    }

    fun dismissBackupPreview() {
        if (_uiState.value.backupBusy) return
        val token = _uiState.value.backupPreview?.token ?: return
        _uiState.update { it.copy(backupPreview = null) }
        viewModelScope.launch { backupRepository.discardPrepared(token) }
    }

    fun restoreBackup(mode: com.flowreader.app.domain.repository.RestoreMode, restoreSettings: Boolean) {
        if (_uiState.value.backupBusy) return
        val token = _uiState.value.backupPreview?.token ?: return
        backupJob = viewModelScope.launch {
            _uiState.update { it.copy(backupBusy = true, restoring = true, backupStatus = "正在恢复，请稍候…") }
            try {
                backupRepository.restorePrepared(token, mode, restoreSettings)
                    .onSuccess { result ->
                        val message = "已恢复 ${result.booksImported} 本书，合并 ${result.booksMerged} 本，${result.bookmarksImported} 个书签、${result.annotationsImported} 条笔记" +
                            if (result.warnings.isEmpty()) "" else "；${result.warnings.take(2).joinToString("；") }"
                        _uiState.update { it.copy(backupPreview = null, importResult = if (previewFromLan) null else message, lanTransferMessage = if (previewFromLan) message else it.lanTransferMessage) }
                    }
                    .onFailure { e -> _uiState.update { it.copy(importResult = "恢复失败：${e.message}") } }
            } finally { _uiState.update { it.copy(backupBusy = false, restoring = false, backupStatus = null) } }
        }
    }

    fun clearExportResult() {
        _uiState.update { it.copy(exportResult = null) }
    }

    fun clearImportResult() {
        _uiState.update { it.copy(importResult = null) }
    }

    fun updateThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            settingsRepository.updateThemeMode(mode)
        }
    }

    fun updateVisualStyle(style: com.flowreader.app.domain.model.AppVisualStyle) {
        val generation = ++visualStyleGeneration
        pendingVisualStyle = style
        _uiState.update { it.copy(visualStyle = style, visualStyleSaveError = null) }
        viewModelScope.launch {
            visualStyleWriter.withLock {
                if (generation != visualStyleGeneration) return@withLock
                try {
                    settingsRepository.updateVisualStyle(style)
                    completedVisualStyleGeneration = generation
                    if (generation == visualStyleGeneration && lastStoredVisualStyle == style) pendingVisualStyle = null
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (generation == visualStyleGeneration) {
                        pendingVisualStyle = null
                        completedVisualStyleGeneration = generation
                        _uiState.update { it.copy(visualStyle = lastStoredVisualStyle, visualStyleSaveError = error.message ?: "主题保存失败") }
                    }
                }
            }
        }
    }

    fun clearVisualStyleError() {
        _uiState.update { it.copy(visualStyleSaveError = null) }
    }

    fun updateColorSource(source: ColorSource) {
        viewModelScope.launch {
            settingsRepository.updateColorSource(source)
        }
    }

    fun updateLanguage(language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.updateLanguage(language)
        }
    }

    fun updateKeepScreenOn(keepOn: Boolean) {
        updateReadingSettings(_uiState.value.readingSettings.copy(keepScreenOn = keepOn))
    }

    /**
     * Single writer for every reader default. The 设置 → 阅读 sub-pages edit the same
     * [ReadingSettings] the in-reader panel does, so the two surfaces can no longer drift apart.
     */
    fun updateReadingSettings(settings: ReadingSettings) {
        if (pendingReadingSettings == null && settings == lastStoredReadingSettings) return
        pendingReadingSettings = settings
        val generation = ++readingSettingsSaveGeneration
        _uiState.update { it.copy(readingSettings = settings, readingSettingsSaveError = null) }
        viewModelScope.launch {
            readingSettingsWriter.withLock {
                if (generation != readingSettingsSaveGeneration) return@withLock
                try {
                    // Serialize immutable snapshots; an older in-flight write cannot replace
                    // the latest preview, which stays protected until storage acknowledges it.
                    settingsRepository.updateReadingSettings(settings)
                    if (generation == readingSettingsSaveGeneration) {
                        completedReadingSettingsGeneration = generation
                        if (lastStoredReadingSettings == settings) pendingReadingSettings = null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (generation == readingSettingsSaveGeneration) {
                        _uiState.update { it.copy(readingSettingsSaveError = e.localizedMessage ?: e.javaClass.simpleName) }
                    }
                }
            }
        }
    }

    fun clearReadingSettingsError(error: String) {
        _uiState.update { if (it.readingSettingsSaveError == error) it.copy(readingSettingsSaveError = null) else it }
    }

    fun importBackgroundImage(uri: android.net.Uri) {
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                backgroundImporter.import(uri)
            }
            result.onSuccess { path ->
                updateReadingSettings(_uiState.value.readingSettings.copy(backgroundImagePath = path))
            }.onFailure { error ->
                _uiState.update { it.copy(scanMessage = "导入背景图失败：${error.message}") }
            }
        }
    }

    fun clearBackgroundImage() {
        viewModelScope.launch {
            backgroundImporter.clear(_uiState.value.readingSettings.backgroundImagePath)
            updateReadingSettings(_uiState.value.readingSettings.copy(backgroundImagePath = null))
        }
    }

    fun updateNavigationTabs(continueTab: Boolean, statsTab: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateNavigationTabs(continueTab, statsTab)
        }
    }

    fun updateLibraryViewMode(mode: LibraryViewMode) {
        viewModelScope.launch {
            settingsRepository.setLibraryViewMode(mode)
        }
    }

    fun updateShelfPreferences(hideGroupedBooksFromAll: Boolean, gridColumns: Int, coverSize: LibraryCoverSize) {
        viewModelScope.launch {
            settingsRepository.updateShelfPreferences(hideGroupedBooksFromAll, gridColumns, coverSize)
        }
    }

    fun updateOpenReadBookDirectly(direct: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateOpenReadBookDirectly(direct)
        }
    }

    fun updateTxtProcessing(rules: TxtProcessingRules) {
        viewModelScope.launch {
            settingsRepository.updateTxtProcessing(rules)
        }
    }

    fun setScanFolder(uri: Uri?) {
        viewModelScope.launch {
            settingsRepository.updateScanFolder(uri?.toString())
            _uiState.update { it.copy(scanMessage = null) }
        }
    }

    /** Manual scan of the configured folder; also refreshes the throttle timestamp. */
    fun scanNow() {
        val folder = _uiState.value.scanFolderUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, scanMessage = null) }
            val result = runCatching { folderScanner.scan(Uri.parse(folder)) }
            settingsRepository.updateLastScanTime(System.currentTimeMillis())
            _uiState.update {
                it.copy(
                    isScanning = false,
                    scanMessage = result.fold(
                        onSuccess = { scan -> "扫描完成：新增 ${scan.imported} 本，跳过 ${scan.skipped} 本" },
                        onFailure = { error -> "扫描失败：${error.message}" }
                    )
                )
            }
        }
    }

    fun clearScanMessage() {
        _uiState.update { it.copy(scanMessage = null) }
    }

    fun updateAppLock(enabled: Boolean, pinHash: String?) {
        viewModelScope.launch {
            settingsRepository.updateAppLock(enabled, pinHash)
        }
    }

    fun updateReadingReminder(enabled: Boolean, hour: Int = 20, minute: Int = 0) {
        viewModelScope.launch {
            settingsRepository.updateReadingReminder(enabled, hour, minute)
        }
    }

    fun updateReadingGoal(period: Int, minutes: Int) {
        viewModelScope.launch {
            when (period) {
                1 -> settingsRepository.updateWeeklyReadingGoal(minutes)
                2 -> settingsRepository.updateMonthlyReadingGoal(minutes)
                else -> settingsRepository.updateDailyReadingGoal(minutes)
            }
        }
    }

    fun updateDailyReadingGoal(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.updateDailyReadingGoal(minutes)
        }
    }

    fun updateGestureSettings(gestureSettings: GestureSettings) {
        updateReadingSettings(_uiState.value.readingSettings.copy(gestureSettings = gestureSettings))
    }

    fun onCustomFontSelected(uri: Uri) {
        viewModelScope.launch {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@launch
                val fontDir = java.io.File(context.filesDir, "fonts")
                if (!fontDir.exists()) fontDir.mkdirs()
                val fileName = "custom_font_${System.currentTimeMillis()}.ttf"
                val targetFile = java.io.File(fontDir, fileName)
                java.io.FileOutputStream(targetFile).use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()
                settingsRepository.updateCustomFontPath(targetFile.absolutePath)
                val currentSettings = _uiState.value.readingSettings
                updateReadingSettings(
                    currentSettings.copy(customFontPath = targetFile.absolutePath)
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(exportResult = "导入字体失败: ${e.message}") }
            }
        }
    }

    fun clearCustomFont() {
        viewModelScope.launch {
            val currentSettings = _uiState.value.readingSettings
            settingsRepository.updateCustomFontPath(null)
            updateReadingSettings(
                currentSettings.copy(customFontPath = null)
            )
        }
    }
}
