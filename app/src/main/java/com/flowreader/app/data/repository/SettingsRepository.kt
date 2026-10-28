package com.flowreader.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flowreader.app.core.util.ReaderBackgroundImage
import com.flowreader.app.domain.model.AnnotationDisplayStyle
import com.flowreader.app.domain.model.AppLanguage
import com.flowreader.app.domain.model.AppSettings
import com.flowreader.app.domain.model.AppThemeMode
import com.flowreader.app.domain.model.ChineseConversion
import com.flowreader.app.domain.model.ColorSource
import com.flowreader.app.domain.model.GestureAction
import com.flowreader.app.domain.model.GestureSettings
import com.flowreader.app.domain.model.LibraryCoverSize
import com.flowreader.app.domain.model.LibraryViewMode
import com.flowreader.app.domain.model.PageMode
import com.flowreader.app.domain.model.PageTurnAnimation
import com.flowreader.app.domain.model.ReaderFontFamily
import com.flowreader.app.domain.model.ReaderPaletteId
import com.flowreader.app.domain.model.ReaderTextAlign
import com.flowreader.app.domain.model.ReadingSettings
import com.flowreader.app.domain.model.TapZoneMode
import com.flowreader.app.domain.model.TxtProcessingRules
import com.flowreader.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.conflate

private val Context.defaultDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 隔离环境可以提供独立存储；真实应用仍使用同名、同路径的应用级单例。 */
internal interface SettingsDataStoreOwner {
    val settingsDataStore: DataStore<Preferences>
}

internal val Context.dataStore: DataStore<Preferences>
    get() = (applicationContext as? SettingsDataStoreOwner)?.settingsDataStore ?: applicationContext.defaultDataStore

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {
    private object PreferencesKeys {
        val THEME = stringPreferencesKey(SettingsRepository.THEME_KEY)
        val SPEECH_ENABLED = booleanPreferencesKey("speech_enabled")
        val VISUAL_STYLE = stringPreferencesKey("app_visual_style")
        val COLOR_SOURCE = stringPreferencesKey("color_source")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val FONT_SIZE = intPreferencesKey("font_size")
        val LINE_SPACING = floatPreferencesKey("line_spacing")
        val PARAGRAPH_SPACING = floatPreferencesKey("paragraph_spacing")
        val FIRST_LINE_INDENT = booleanPreferencesKey("first_line_indent")
        val READER_MARGIN_DP = intPreferencesKey("reader_margin_dp")
        val READER_LINE_CHARS = intPreferencesKey("reader_line_chars")
        val READER_TEXT_ALIGN = stringPreferencesKey("reader_text_align")
        val READER_CHINESE_CONVERSION = stringPreferencesKey("reader_chinese_conversion")
        val READER_COLOR_TEMPERATURE = intPreferencesKey("reader_color_temperature")
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val READER_PALETTE = stringPreferencesKey("reader_theme")
        val READER_NIGHT_PALETTE = stringPreferencesKey("reader_night_palette")
        val PAGE_MODE = stringPreferencesKey("page_mode")
        val PAGE_TURN_ANIMATION = stringPreferencesKey("page_turn_animation")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SCREEN_TIMEOUT_MINUTES = intPreferencesKey("screen_timeout_minutes")
        val EYE_PROTECTION_INTERVAL_MINUTES = intPreferencesKey("eye_protection_interval_minutes")
        val AUTO_NIGHT_MODE = booleanPreferencesKey("auto_night_mode")
        val TAP_ZONE_RATIO = floatPreferencesKey("tap_zone_ratio")
        val READING_REMINDER_ENABLED = booleanPreferencesKey("reading_reminder_enabled")
        val READING_REMINDER_HOUR = intPreferencesKey("reading_reminder_hour")
        val READING_REMINDER_MINUTE = intPreferencesKey("reading_reminder_minute")
        val WIDGET_BOOK_TITLE = stringPreferencesKey("widget_book_title")
        val WIDGET_PROGRESS_PERCENT = intPreferencesKey("widget_progress_percent")
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
        val DAILY_READING_GOAL_MINUTES = intPreferencesKey("daily_reading_goal_minutes")
        val WEEKLY_READING_GOAL_MINUTES = intPreferencesKey("weekly_reading_goal_minutes")
        val MONTHLY_READING_GOAL_MINUTES = intPreferencesKey("monthly_reading_goal_minutes")
        val GESTURE_LEFT_TAP = stringPreferencesKey("gesture_left_tap")
        val GESTURE_MIDDLE_TAP = stringPreferencesKey("gesture_middle_tap")
        val GESTURE_RIGHT_TAP = stringPreferencesKey("gesture_right_tap")
        val GESTURE_SWIPE_LEFT = stringPreferencesKey("gesture_swipe_left")
        val GESTURE_SWIPE_RIGHT = stringPreferencesKey("gesture_swipe_right")
        val GESTURE_DOUBLE_TAP = stringPreferencesKey("gesture_double_tap")
        val GESTURE_DOUBLE_TAP_ENABLED = booleanPreferencesKey("gesture_double_tap_enabled")
        val GESTURE_TAP_ZONE_MODE = stringPreferencesKey("gesture_tap_zone_mode")
        val GESTURE_LONG_PRESS = stringPreferencesKey("gesture_long_press")
        val GESTURE_EDGE_ENABLED = booleanPreferencesKey("gesture_edge_enabled")
        val GESTURE_LEFT_EDGE_WIDTH = intPreferencesKey("gesture_left_edge_width")
        val GESTURE_RIGHT_EDGE_WIDTH = intPreferencesKey("gesture_right_edge_width")
        val CUSTOM_FONT_PATH = stringPreferencesKey("custom_font_path")
        val LIBRARY_VIEW_MODE = stringPreferencesKey("library_view_mode")
        val CUSTOM_TEXT_COLOR = longPreferencesKey("reader_custom_text_color")
        val CUSTOM_BACKGROUND_COLOR = longPreferencesKey("reader_custom_background_color")
        val READER_BACKGROUND_PATH = stringPreferencesKey("reader_background_path")
        val READER_BACKGROUND_SCRIM = floatPreferencesKey("reader_background_scrim")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val ANNOTATION_DISPLAY_STYLE = stringPreferencesKey("annotation_display_style")
        val VOLUME_KEY_TURN_PAGE = booleanPreferencesKey("volume_key_turn_page")
        val SHOW_CONTINUE_TAB = booleanPreferencesKey("show_continue_tab")
        val SHOW_STATS_TAB = booleanPreferencesKey("show_stats_tab")
        val HIDE_GROUPED_FROM_ALL = booleanPreferencesKey("hide_grouped_from_all")
        val LIBRARY_GRID_COLUMNS = intPreferencesKey("library_grid_columns")
        val LIBRARY_COVER_SIZE = stringPreferencesKey("library_cover_size")
        val OPEN_READ_BOOK_DIRECTLY = booleanPreferencesKey("open_read_book_directly")
        val TXT_STRIP_BLANK_LINES = booleanPreferencesKey("txt_strip_blank_lines")
        val TXT_REMOVE_AD_LINES = booleanPreferencesKey("txt_remove_ad_lines")
        val TXT_CUSTOM_REPLACEMENTS = stringPreferencesKey("txt_custom_replacements")
        val TXT_CHAPTER_REGEX = stringPreferencesKey("txt_chapter_regex")
        val TXT_REGEX_ONLY = booleanPreferencesKey("txt_regex_only")
        val SCAN_FOLDER_URI = stringPreferencesKey("scan_folder_uri")
        val LAST_SCAN_TIME = longPreferencesKey("last_scan_time")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
    }

    private fun readGestureSettings(preferences: Preferences): GestureSettings {
        val defaults = GestureSettings()
        return GestureSettings(
            leftTapAction = GestureAction.fromStoredName(preferences[PreferencesKeys.GESTURE_LEFT_TAP], defaults.leftTapAction),
            middleTapAction = GestureAction.fromStoredName(preferences[PreferencesKeys.GESTURE_MIDDLE_TAP], defaults.middleTapAction),
            rightTapAction = GestureAction.fromStoredName(preferences[PreferencesKeys.GESTURE_RIGHT_TAP], defaults.rightTapAction),
            swipeLeftAction = GestureAction.fromStoredName(preferences[PreferencesKeys.GESTURE_SWIPE_LEFT], defaults.swipeLeftAction),
            swipeRightAction = GestureAction.fromStoredName(preferences[PreferencesKeys.GESTURE_SWIPE_RIGHT], defaults.swipeRightAction),
            doubleTapAction = GestureAction.fromStoredName(preferences[PreferencesKeys.GESTURE_DOUBLE_TAP], defaults.doubleTapAction),
            doubleTapEnabled = preferences[PreferencesKeys.GESTURE_DOUBLE_TAP_ENABLED] ?: defaults.doubleTapEnabled,
            longPressAction = GestureAction.fromStoredName(preferences[PreferencesKeys.GESTURE_LONG_PRESS], defaults.longPressAction),
            tapZoneMode = TapZoneMode.fromStoredName(preferences[PreferencesKeys.GESTURE_TAP_ZONE_MODE]),
            edgeGestureEnabled = preferences[PreferencesKeys.GESTURE_EDGE_ENABLED] ?: defaults.edgeGestureEnabled,
            leftEdgeWidth = (preferences[PreferencesKeys.GESTURE_LEFT_EDGE_WIDTH] ?: defaults.leftEdgeWidth).coerceIn(0, 45),
            rightEdgeWidth = (preferences[PreferencesKeys.GESTURE_RIGHT_EDGE_WIDTH] ?: defaults.rightEdgeWidth).coerceIn(0, 45)
        )
    }

    private fun readReadingSettings(preferences: Preferences): ReadingSettings {
        val palette = ReaderPaletteId.fromStoredName(preferences[PreferencesKeys.READER_PALETTE], ReaderPaletteId.PAPER)
        return ReadingSettings(
            fontSize = (preferences[PreferencesKeys.FONT_SIZE] ?: 18).coerceIn(12, 32),
            lineSpacing = (preferences[PreferencesKeys.LINE_SPACING] ?: 1.5f).coerceIn(1.0f, 2.5f),
            paragraphSpacing = ReadingSettings.normalizeParagraphSpacing(preferences[PreferencesKeys.PARAGRAPH_SPACING] ?: 1.0f),
            firstLineIndent = preferences[PreferencesKeys.FIRST_LINE_INDENT] ?: true,
            horizontalMarginDp = (preferences[PreferencesKeys.READER_MARGIN_DP] ?: 20)
                .coerceIn(ReadingSettings.MARGIN_MIN_DP, ReadingSettings.MARGIN_MAX_DP),
            lineChars = (preferences[PreferencesKeys.READER_LINE_CHARS] ?: 34)
                .coerceIn(ReadingSettings.LINE_CHARS_MIN, ReadingSettings.LINE_CHARS_MAX),
            textAlign = ReaderTextAlign.fromStoredName(preferences[PreferencesKeys.READER_TEXT_ALIGN]),
            chineseConversion = ChineseConversion.fromStoredName(preferences[PreferencesKeys.READER_CHINESE_CONVERSION]),
            colorTemperature = (preferences[PreferencesKeys.READER_COLOR_TEMPERATURE] ?: 0)
                .coerceIn(0, ReadingSettings.COLOR_TEMPERATURE_MAX),
            fontFamily = ReaderFontFamily.fromStoredName(preferences[PreferencesKeys.FONT_FAMILY]),
            customFontPath = preferences[PreferencesKeys.CUSTOM_FONT_PATH],
            palette = palette,
            nightPalette = ReaderPaletteId.fromStoredName(
                preferences[PreferencesKeys.READER_NIGHT_PALETTE],
                ReaderPaletteId.NIGHT
            ).takeIf { it.isDark } ?: ReaderPaletteId.NIGHT,
            pageMode = PageMode.fromStoredName(preferences[PreferencesKeys.PAGE_MODE]),
            pageTurnAnimation = PageTurnAnimation.fromStoredName(preferences[PreferencesKeys.PAGE_TURN_ANIMATION]),
            keepScreenOn = preferences[PreferencesKeys.KEEP_SCREEN_ON] ?: true,
            screenTimeoutMinutes = preferences[PreferencesKeys.SCREEN_TIMEOUT_MINUTES] ?: 0,
            eyeProtectionIntervalMinutes = preferences[PreferencesKeys.EYE_PROTECTION_INTERVAL_MINUTES] ?: 20,
            autoNightMode = preferences[PreferencesKeys.AUTO_NIGHT_MODE] ?: false,
            tapZoneRatio = (preferences[PreferencesKeys.TAP_ZONE_RATIO] ?: 0.3f).coerceIn(0.1f, 0.45f),
            customTextColorArgb = preferences[PreferencesKeys.CUSTOM_TEXT_COLOR],
            customBackgroundColorArgb = preferences[PreferencesKeys.CUSTOM_BACKGROUND_COLOR],
            backgroundImagePath = preferences[PreferencesKeys.READER_BACKGROUND_PATH],
            // Clamped on read as well as on write: a value persisted by an older build (or an
            // interrupted write) must not be able to render text over a bare image.
            backgroundScrimAlpha = ReaderBackgroundImage.clampScrimAlpha(
                preferences[PreferencesKeys.READER_BACKGROUND_SCRIM] ?: ReaderBackgroundImage.DEFAULT_SCRIM_ALPHA
            ),
            gestureSettings = readGestureSettings(preferences),
            volumeKeyTurnPage = preferences[PreferencesKeys.VOLUME_KEY_TURN_PAGE] ?: true,
            annotationDisplayStyle = AnnotationDisplayStyle.fromStoredName(preferences[PreferencesKeys.ANNOTATION_DISPLAY_STYLE])
        )
    }

    override val appSettings: Flow<AppSettings> = context.dataStore.data
        .retry(3) { it is IOException }
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AppSettings(
                themeMode = AppThemeMode.fromStoredName(preferences[PreferencesKeys.THEME]),
                visualStyle = com.flowreader.app.domain.model.AppVisualStyle.fromStoredName(preferences[PreferencesKeys.VISUAL_STYLE]),
                colorSource = ColorSource.fromStoredName(preferences[PreferencesKeys.COLOR_SOURCE]),
                language = AppLanguage.fromStoredName(preferences[PreferencesKeys.APP_LANGUAGE]),
                defaultReadingSettings = readReadingSettings(preferences),
                readingReminderEnabled = preferences[PreferencesKeys.READING_REMINDER_ENABLED] ?: false,
                readingReminderHour = preferences[PreferencesKeys.READING_REMINDER_HOUR] ?: 20,
                readingReminderMinute = preferences[PreferencesKeys.READING_REMINDER_MINUTE] ?: 0,
                showContinueTab = preferences[PreferencesKeys.SHOW_CONTINUE_TAB] ?: false,
                showStatsTab = preferences[PreferencesKeys.SHOW_STATS_TAB] ?: false,
                hideGroupedBooksFromAll = preferences[PreferencesKeys.HIDE_GROUPED_FROM_ALL] ?: false,
                libraryGridColumns = (preferences[PreferencesKeys.LIBRARY_GRID_COLUMNS] ?: 3).coerceIn(2, 5),
                libraryCoverSize = LibraryCoverSize.fromStoredName(preferences[PreferencesKeys.LIBRARY_COVER_SIZE]),
                openReadBookDirectly = preferences[PreferencesKeys.OPEN_READ_BOOK_DIRECTLY] ?: true,
                txtProcessing = TxtProcessingRules(
                    stripBlankLines = preferences[PreferencesKeys.TXT_STRIP_BLANK_LINES] ?: false,
                    removeAdLines = preferences[PreferencesKeys.TXT_REMOVE_AD_LINES] ?: true,
                    customReplacements = preferences[PreferencesKeys.TXT_CUSTOM_REPLACEMENTS].orEmpty(),
                    customChapterRegex = preferences[PreferencesKeys.TXT_CHAPTER_REGEX].orEmpty(),
                    customRegexOnly = preferences[PreferencesKeys.TXT_REGEX_ONLY] ?: false
                ),
                scanFolderUri = preferences[PreferencesKeys.SCAN_FOLDER_URI],
                lastScanTime = preferences[PreferencesKeys.LAST_SCAN_TIME] ?: 0L,
                speechEnabled = preferences[PreferencesKeys.SPEECH_ENABLED] ?: false,
                appLockEnabled = preferences[PreferencesKeys.APP_LOCK_ENABLED] ?: false,
                appLockPinHash = preferences[PreferencesKeys.APP_LOCK_PIN_HASH]
            )
        }.distinctUntilChanged().flowOn(Dispatchers.Default).conflate()

    override suspend fun updateThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME] = mode.name
        }
    }

    override suspend fun updateSpeechEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.SPEECH_ENABLED] = enabled }
    }

    override suspend fun updateVisualStyle(style: com.flowreader.app.domain.model.AppVisualStyle) {
        context.dataStore.edit { it[PreferencesKeys.VISUAL_STYLE] = style.name }
    }

    override suspend fun updateColorSource(source: ColorSource) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.COLOR_SOURCE] = source.name
        }
    }

    override suspend fun updateLanguage(language: AppLanguage) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_LANGUAGE] = language.name
        }
    }

    override suspend fun updateReadingSettings(settings: ReadingSettings) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FONT_SIZE] = settings.fontSize
            preferences[PreferencesKeys.LINE_SPACING] = settings.lineSpacing
            preferences[PreferencesKeys.PARAGRAPH_SPACING] = settings.paragraphSpacing
            preferences[PreferencesKeys.FIRST_LINE_INDENT] = settings.firstLineIndent
            preferences[PreferencesKeys.READER_MARGIN_DP] =
                settings.horizontalMarginDp.coerceIn(ReadingSettings.MARGIN_MIN_DP, ReadingSettings.MARGIN_MAX_DP)
            preferences[PreferencesKeys.READER_LINE_CHARS] =
                settings.lineChars.coerceIn(ReadingSettings.LINE_CHARS_MIN, ReadingSettings.LINE_CHARS_MAX)
            preferences[PreferencesKeys.READER_TEXT_ALIGN] = settings.textAlign.name
            preferences[PreferencesKeys.READER_CHINESE_CONVERSION] = settings.chineseConversion.name
            preferences[PreferencesKeys.READER_COLOR_TEMPERATURE] =
                settings.colorTemperature.coerceIn(0, ReadingSettings.COLOR_TEMPERATURE_MAX)
            preferences[PreferencesKeys.FONT_FAMILY] = settings.fontFamily.name
            preferences[PreferencesKeys.READER_PALETTE] = settings.palette.name
            preferences[PreferencesKeys.READER_NIGHT_PALETTE] = settings.nightPalette.name
            preferences[PreferencesKeys.PAGE_MODE] = settings.pageMode.name
            preferences[PreferencesKeys.PAGE_TURN_ANIMATION] = settings.pageTurnAnimation.name
            preferences[PreferencesKeys.KEEP_SCREEN_ON] = settings.keepScreenOn
            preferences[PreferencesKeys.SCREEN_TIMEOUT_MINUTES] = settings.screenTimeoutMinutes
            preferences[PreferencesKeys.EYE_PROTECTION_INTERVAL_MINUTES] = settings.eyeProtectionIntervalMinutes
            preferences[PreferencesKeys.AUTO_NIGHT_MODE] = settings.autoNightMode
            preferences[PreferencesKeys.TAP_ZONE_RATIO] = settings.tapZoneRatio
            val customTextColor = settings.customTextColorArgb
            if (customTextColor != null) {
                preferences[PreferencesKeys.CUSTOM_TEXT_COLOR] = customTextColor
            } else {
                preferences.remove(PreferencesKeys.CUSTOM_TEXT_COLOR)
            }
            val customBackgroundColor = settings.customBackgroundColorArgb
            if (customBackgroundColor != null) {
                preferences[PreferencesKeys.CUSTOM_BACKGROUND_COLOR] = customBackgroundColor
            } else {
                preferences.remove(PreferencesKeys.CUSTOM_BACKGROUND_COLOR)
            }
            writeGestureSettings(preferences, settings.gestureSettings)
            val customFontPath = settings.customFontPath
            if (customFontPath != null) {
                preferences[PreferencesKeys.CUSTOM_FONT_PATH] = customFontPath
            } else {
                preferences.remove(PreferencesKeys.CUSTOM_FONT_PATH)
            }
            val backgroundImagePath = settings.backgroundImagePath?.takeIf { it.isNotBlank() }
            if (backgroundImagePath != null) {
                preferences[PreferencesKeys.READER_BACKGROUND_PATH] = backgroundImagePath
            } else {
                preferences.remove(PreferencesKeys.READER_BACKGROUND_PATH)
            }
            preferences[PreferencesKeys.READER_BACKGROUND_SCRIM] =
                ReaderBackgroundImage.clampScrimAlpha(settings.backgroundScrimAlpha)
            preferences[PreferencesKeys.VOLUME_KEY_TURN_PAGE] = settings.volumeKeyTurnPage
            preferences[PreferencesKeys.ANNOTATION_DISPLAY_STYLE] = settings.annotationDisplayStyle.name
        }
    }

    private fun writeGestureSettings(preferences: MutablePreferences, settings: GestureSettings) {
        preferences[PreferencesKeys.GESTURE_LEFT_TAP] = settings.leftTapAction.name
        preferences[PreferencesKeys.GESTURE_MIDDLE_TAP] = settings.middleTapAction.name
        preferences[PreferencesKeys.GESTURE_RIGHT_TAP] = settings.rightTapAction.name
        preferences[PreferencesKeys.GESTURE_SWIPE_LEFT] = settings.swipeLeftAction.name
        preferences[PreferencesKeys.GESTURE_SWIPE_RIGHT] = settings.swipeRightAction.name
        preferences[PreferencesKeys.GESTURE_DOUBLE_TAP] = settings.doubleTapAction.name
        preferences[PreferencesKeys.GESTURE_DOUBLE_TAP_ENABLED] = settings.doubleTapEnabled
        preferences[PreferencesKeys.GESTURE_TAP_ZONE_MODE] = settings.tapZoneMode.name
        preferences[PreferencesKeys.GESTURE_LONG_PRESS] = settings.longPressAction.name
        preferences[PreferencesKeys.GESTURE_EDGE_ENABLED] = settings.edgeGestureEnabled
        preferences[PreferencesKeys.GESTURE_LEFT_EDGE_WIDTH] = settings.leftEdgeWidth
        preferences[PreferencesKeys.GESTURE_RIGHT_EDGE_WIDTH] = settings.rightEdgeWidth
    }

    override suspend fun updateNavigationTabs(showContinueTab: Boolean, showStatsTab: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SHOW_CONTINUE_TAB] = showContinueTab
            preferences[PreferencesKeys.SHOW_STATS_TAB] = showStatsTab
        }
    }

    override suspend fun updateShelfPreferences(hideGroupedBooksFromAll: Boolean, gridColumns: Int, coverSize: LibraryCoverSize) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HIDE_GROUPED_FROM_ALL] = hideGroupedBooksFromAll
            preferences[PreferencesKeys.LIBRARY_GRID_COLUMNS] = gridColumns.coerceIn(2, 5)
            preferences[PreferencesKeys.LIBRARY_COVER_SIZE] = coverSize.name
        }
    }

    override suspend fun updateOpenReadBookDirectly(direct: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.OPEN_READ_BOOK_DIRECTLY] = direct
        }
    }

    override suspend fun updateTxtProcessing(rules: TxtProcessingRules) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TXT_STRIP_BLANK_LINES] = rules.stripBlankLines
            preferences[PreferencesKeys.TXT_REMOVE_AD_LINES] = rules.removeAdLines
            preferences[PreferencesKeys.TXT_CUSTOM_REPLACEMENTS] =
                rules.customReplacements.take(TxtProcessingRules.MAX_REPLACEMENTS_LENGTH)
            preferences[PreferencesKeys.TXT_CHAPTER_REGEX] =
                rules.customChapterRegex.take(TxtProcessingRules.MAX_REGEX_LENGTH)
            preferences[PreferencesKeys.TXT_REGEX_ONLY] = rules.customRegexOnly
        }
    }

    override suspend fun updateScanFolder(uri: String?) {
        context.dataStore.edit { preferences ->
            if (uri.isNullOrBlank()) {
                preferences.remove(PreferencesKeys.SCAN_FOLDER_URI)
            } else {
                preferences[PreferencesKeys.SCAN_FOLDER_URI] = uri
            }
        }
    }

    override suspend fun updateLastScanTime(time: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_SCAN_TIME] = time
        }
    }

    override suspend fun updateAppLock(enabled: Boolean, pinHash: String?) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_LOCK_ENABLED] = enabled
            if (pinHash.isNullOrBlank()) {
                preferences.remove(PreferencesKeys.APP_LOCK_PIN_HASH)
            } else {
                preferences[PreferencesKeys.APP_LOCK_PIN_HASH] = pinHash
            }
        }
    }

    override suspend fun updateReadingReminder(enabled: Boolean, hour: Int, minute: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.READING_REMINDER_ENABLED] = enabled
            preferences[PreferencesKeys.READING_REMINDER_HOUR] = hour
            preferences[PreferencesKeys.READING_REMINDER_MINUTE] = minute
        }
    }

    override fun getLibraryViewMode(): Flow<LibraryViewMode> = context.dataStore.data
        .retry(3) { it is IOException }
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences -> LibraryViewMode.fromStoredName(preferences[PreferencesKeys.LIBRARY_VIEW_MODE]) }.distinctUntilChanged()

    override suspend fun setLibraryViewMode(mode: LibraryViewMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LIBRARY_VIEW_MODE] = mode.name
        }
    }

    override suspend fun addSearchHistory(query: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.SEARCH_HISTORY] ?: ""
            val historyList = if (current.isNotEmpty()) current.split("|").toMutableList() else mutableListOf()
            if (!historyList.contains(query)) {
                historyList.add(0, query)
                if (historyList.size > 10) historyList.removeAt(historyList.lastIndex)
            }
            preferences[PreferencesKeys.SEARCH_HISTORY] = historyList.joinToString("|")
        }
    }

    override fun getSearchHistory(): Flow<List<String>> = context.dataStore.data
        .retry(3) { it is IOException }
        .map { preferences ->
            val history = preferences[PreferencesKeys.SEARCH_HISTORY] ?: ""
            if (history.isNotEmpty()) history.split("|") else emptyList()
        }.distinctUntilChanged()

    override suspend fun clearSearchHistory() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SEARCH_HISTORY] = ""
        }
    }

    override suspend fun updateDailyReadingGoal(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DAILY_READING_GOAL_MINUTES] = minutes
        }
    }

    override fun getDailyReadingGoal(): Flow<Int> = context.dataStore.data
        .retry(3) { it is IOException }
        .map { preferences ->
            preferences[PreferencesKeys.DAILY_READING_GOAL_MINUTES] ?: 30
        }.distinctUntilChanged()

    override suspend fun updateWeeklyReadingGoal(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WEEKLY_READING_GOAL_MINUTES] = minutes
        }
    }

    override fun getWeeklyReadingGoal(): Flow<Int> = context.dataStore.data
        .retry(3) { it is IOException }
        .map { preferences ->
            preferences[PreferencesKeys.WEEKLY_READING_GOAL_MINUTES] ?: 210
        }.distinctUntilChanged()

    override suspend fun updateMonthlyReadingGoal(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MONTHLY_READING_GOAL_MINUTES] = minutes
        }
    }

    override fun getMonthlyReadingGoal(): Flow<Int> = context.dataStore.data
        .retry(3) { it is IOException }
        .map { preferences ->
            preferences[PreferencesKeys.MONTHLY_READING_GOAL_MINUTES] ?: 900
        }.distinctUntilChanged()

    override suspend fun updateReadingWidgetSnapshot(bookTitle: String, progressPercent: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WIDGET_BOOK_TITLE] = bookTitle
            preferences[PreferencesKeys.WIDGET_PROGRESS_PERCENT] = progressPercent.coerceIn(0, 100)
        }
    }

    override fun isOnboardingCompleted(): Flow<Boolean> = context.dataStore.data
        .retry(3) { it is IOException }
        .map { preferences ->
            preferences[PreferencesKeys.ONBOARDING_COMPLETED] ?: false
        }.distinctUntilChanged()

    override suspend fun setOnboardingCompleted() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ONBOARDING_COMPLETED] = true
        }
    }

    override suspend fun updateCustomFontPath(path: String?) {
        context.dataStore.edit { preferences ->
            if (path != null) {
                preferences[PreferencesKeys.CUSTOM_FONT_PATH] = path
            } else {
                preferences.remove(PreferencesKeys.CUSTOM_FONT_PATH)
            }
        }
    }

    override fun getCustomFontPath(): Flow<String?> = context.dataStore.data
        .retry(3) { it is IOException }
        .map { preferences ->
            preferences[PreferencesKeys.CUSTOM_FONT_PATH]
        }.distinctUntilChanged()
}
