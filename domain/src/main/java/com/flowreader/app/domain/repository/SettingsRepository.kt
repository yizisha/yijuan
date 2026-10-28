package com.flowreader.app.domain.repository

import com.flowreader.app.domain.model.AppLanguage
import com.flowreader.app.domain.model.AppSettings
import com.flowreader.app.domain.model.AppThemeMode
import com.flowreader.app.domain.model.ColorSource
import com.flowreader.app.domain.model.LibraryCoverSize
import com.flowreader.app.domain.model.LibraryViewMode
import com.flowreader.app.domain.model.ReadingSettings
import com.flowreader.app.domain.model.TxtProcessingRules
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    companion object {
        const val THEME_KEY = "theme"
    }

    val appSettings: Flow<AppSettings>

    suspend fun updateThemeMode(mode: AppThemeMode)
    suspend fun updateSpeechEnabled(enabled: Boolean)
    suspend fun updateVisualStyle(style: com.flowreader.app.domain.model.AppVisualStyle)
    suspend fun updateColorSource(source: ColorSource)
    suspend fun updateLanguage(language: AppLanguage)
    suspend fun updateReadingSettings(settings: ReadingSettings)
    suspend fun updateReadingReminder(enabled: Boolean, hour: Int = 20, minute: Int = 0)
    suspend fun addSearchHistory(query: String)
    fun getSearchHistory(): Flow<List<String>>
    fun getLibraryViewMode(): Flow<LibraryViewMode>
    suspend fun setLibraryViewMode(mode: LibraryViewMode)
    suspend fun clearSearchHistory()
    suspend fun updateDailyReadingGoal(minutes: Int)
    fun getDailyReadingGoal(): Flow<Int>
    suspend fun updateWeeklyReadingGoal(minutes: Int)
    fun getWeeklyReadingGoal(): Flow<Int>
    suspend fun updateMonthlyReadingGoal(minutes: Int)
    fun getMonthlyReadingGoal(): Flow<Int>
    suspend fun updateReadingWidgetSnapshot(bookTitle: String, progressPercent: Int)
    fun isOnboardingCompleted(): Flow<Boolean>
    suspend fun setOnboardingCompleted()
    suspend fun updateCustomFontPath(path: String?)
    fun getCustomFontPath(): Flow<String?>

    /** Shows/hides the optional 继续阅读 and 统计 destinations in the bottom bar. */
    suspend fun updateNavigationTabs(showContinueTab: Boolean, showStatsTab: Boolean)

    /** Shelf layout + grouping behaviour (v0.0.6). */
    suspend fun updateShelfPreferences(hideGroupedBooksFromAll: Boolean, gridColumns: Int, coverSize: LibraryCoverSize)

    /** Whether tapping an already-started book on the shelf opens the reader directly. */
    suspend fun updateOpenReadBookDirectly(direct: Boolean)

    /** TXT import rules: cleaning toggles, custom replacements and the custom chapter regex. */
    suspend fun updateTxtProcessing(rules: TxtProcessingRules)

    /** Remembers the SAF folder that is scanned for new books (null disables auto-scan). */
    suspend fun updateScanFolder(uri: String?)

    /** Records that a folder scan finished, so the automatic scan can be throttled. */
    suspend fun updateLastScanTime(time: Long)

    /** App lock: enable flag and the salted PIN hash (null clears the PIN). */
    suspend fun updateAppLock(enabled: Boolean, pinHash: String?)
}
