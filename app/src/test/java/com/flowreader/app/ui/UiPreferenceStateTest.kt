package com.flowreader.app.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.flowreader.app.data.repository.SettingsRepositoryImpl
import com.flowreader.app.data.repository.dataStore
import com.flowreader.app.domain.model.*
import com.flowreader.app.domain.repository.*
import com.flowreader.app.ui.screens.notes.NotesViewModel
import com.flowreader.app.ui.screens.settings.SettingsViewModel
import com.flowreader.app.ui.screens.stats.StatsViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verify state wiring against the existing repositories, independently of visual fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiPreferenceStateTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test fun parsingStoredSettingsDoesNotRunOnTheUiCollectorThread() = runBlocking {
        val readers = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        val preferences = mockk<androidx.datastore.preferences.core.Preferences>()
        every { preferences.get(any<androidx.datastore.preferences.core.Preferences.Key<Any>>()) } answers {
            readers += Thread.currentThread(); null
        }
        val store = mockk<androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>>()
        every { store.data } returns kotlinx.coroutines.flow.flowOf(preferences)
        val context = object : android.content.ContextWrapper(ApplicationProvider.getApplicationContext<Context>()),
            com.flowreader.app.data.repository.SettingsDataStoreOwner {
            override val settingsDataStore = store
            override fun getApplicationContext(): Context = this
        }
        val repository = SettingsRepositoryImpl(context)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val ui = executor.asCoroutineDispatcher()
        try {
            var collector: Thread? = null
            val value = kotlinx.coroutines.withContext(ui) {
                collector = Thread.currentThread()
                repository.appSettings.first()
            }
            assertEquals(AppThemeMode.FOLLOW_SYSTEM, value.themeMode)
            assertTrue(readers.isNotEmpty())
            assertFalse("Preference decoding must not block the UI collector", readers.any { it === collector })
        } finally { ui.close() }
    }

    private fun preferencesWithStoredState(app: MutableStateFlow<AppSettings>): Pair<SettingsViewModel, SettingsRepository> {
        val repository = mockk<SettingsRepository>()
        every { repository.appSettings } returns app
        every { repository.getDailyReadingGoal() } returns MutableStateFlow(30)
        every { repository.getWeeklyReadingGoal() } returns MutableStateFlow(210)
        every { repository.getMonthlyReadingGoal() } returns MutableStateFlow(900)
        every { repository.isOnboardingCompleted() } returns MutableStateFlow(true)
        every { repository.getCustomFontPath() } returns MutableStateFlow(null)
        every { repository.getLibraryViewMode() } returns MutableStateFlow(LibraryViewMode.LIST)
        return SettingsViewModel(
            ApplicationProvider.getApplicationContext<Context>(), repository,
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk()
        ) to repository
    }

    @Test fun rapidReadingEditsKeepTheLatestPreviewAcrossOldStorageEmissionsAndRecreation() = runTest(dispatcher) {
        val app = MutableStateFlow(AppSettings())
        val (vm, repository) = preferencesWithStoredState(app)
        coEvery { repository.updateReadingSettings(any()) } coAnswers {
            val requested = firstArg<ReadingSettings>()
            delay(100)
            app.value = app.value.copy(defaultReadingSettings = requested)
        }
        runCurrent()
        vm.updateReadingSettings(vm.uiState.value.readingSettings.copy(fontSize = 23))
        assertEquals(23, vm.uiState.value.readingSettings.fontSize)
        runCurrent()
        vm.updateReadingSettings(vm.uiState.value.readingSettings.copy(pageMode = PageMode.SLIDE))
        app.value = app.value.copy(themeMode = AppThemeMode.DARK)
        runCurrent()
        assertEquals(23, vm.uiState.value.readingSettings.fontSize)
        assertEquals(PageMode.SLIDE, vm.uiState.value.readingSettings.pageMode)
        assertEquals(AppThemeMode.DARK, vm.uiState.value.themeMode)
        advanceTimeBy(101)
        runCurrent()
        assertEquals(PageMode.SLIDE, vm.uiState.value.readingSettings.pageMode)
        advanceTimeBy(101)
        runCurrent()
        assertEquals(vm.uiState.value.readingSettings, app.value.defaultReadingSettings)
        val (recreated, _) = preferencesWithStoredState(app)
        runCurrent()
        assertEquals(vm.uiState.value.readingSettings, recreated.uiState.value.readingSettings)
    }

    @Test fun revertingAnInFlightEditDoesNotMistakeOldStorageForAnAcknowledgment() = runTest(dispatcher) {
        val app = MutableStateFlow(AppSettings())
        val (vm, repository) = preferencesWithStoredState(app)
        coEvery { repository.updateReadingSettings(any()) } coAnswers {
            val requested = firstArg<ReadingSettings>()
            delay(100)
            app.value = app.value.copy(defaultReadingSettings = requested)
        }
        runCurrent()
        val original = vm.uiState.value.readingSettings
        vm.updateReadingSettings(original.copy(fontSize = 23))
        runCurrent()
        vm.updateReadingSettings(original)
        app.value = app.value.copy(themeMode = AppThemeMode.DARK)
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertEquals(original, vm.uiState.value.readingSettings)
        advanceTimeBy(101)
        runCurrent()
        assertEquals(original, app.value.defaultReadingSettings)
        val fromReader = original.copy(lineSpacing = 2.0f)
        app.value = app.value.copy(defaultReadingSettings = fromReader)
        runCurrent()
        assertEquals(fromReader, vm.uiState.value.readingSettings)
    }

    @Test fun failedReadingSaveKeepsPreviewAndAllowsRetryOfTheSameSelection() = runTest(dispatcher) {
        val app = MutableStateFlow(AppSettings())
        val (vm, repository) = preferencesWithStoredState(app)
        coEvery { repository.updateReadingSettings(any()) } throws java.io.IOException("disk failure")
        runCurrent()
        val requested = vm.uiState.value.readingSettings.copy(pageMode = PageMode.SLIDE)
        vm.updateReadingSettings(requested)
        runCurrent()
        assertEquals(requested, vm.uiState.value.readingSettings)
        assertEquals("disk failure", vm.uiState.value.readingSettingsSaveError)
        app.value = app.value.copy(themeMode = AppThemeMode.DARK)
        runCurrent()
        assertEquals(requested, vm.uiState.value.readingSettings)
        vm.clearReadingSettingsError("disk failure")
        coEvery { repository.updateReadingSettings(any()) } coAnswers {
            app.value = app.value.copy(defaultReadingSettings = firstArg())
        }
        vm.updateReadingSettings(requested)
        runCurrent()
        assertNull(vm.uiState.value.readingSettingsSaveError)
        assertEquals(requested, app.value.defaultReadingSettings)
        coVerify(exactly = 2) { repository.updateReadingSettings(requested) }
    }

    @Test fun noteCoversFollowRealBookMetadataWithoutChangingAnnotations() = runTest(dispatcher) {
        val book =
            Book(id = 7, title = "实际书籍", author = "作者", coverPath = "/covers/original.jpg", filePath = "book.txt", format = BookFormat.TXT)
        val books = MutableStateFlow(listOf(book))
        val note = Annotation(id = 9, bookId = 7, chapterIndex = 2, startPosition = 0, endPosition = 4, selectedText = "原有摘录")
        val annotations = mockk<AnnotationRepository>()
        val library = mockk<BookRepository>()
        every { annotations.getAllAnnotations() } returns MutableStateFlow(listOf(note))
        every { library.getAllBooks() } returns books
        val vm = NotesViewModel(annotations, library)
        runCurrent()
        assertEquals(book.coverPath, vm.uiState.value.notes.single().coverPath)
        assertEquals(book.author, vm.uiState.value.notes.single().author)
        books.value = listOf(book.copy(title = "新书名", coverPath = "/covers/updated.jpg"))
        runCurrent()
        assertEquals("新书名", vm.uiState.value.notes.single().bookTitle)
        assertEquals("/covers/updated.jpg", vm.uiState.value.notes.single().coverPath)
        assertEquals(note, vm.uiState.value.notes.single().annotation)
    }

    @Test fun goalChangesFlowFromSettingsToStatisticsWithoutRefreshingReadingData() = runTest(dispatcher) {
        val settings = mockk<SettingsRepository>()
        val day = MutableStateFlow(30)
        val week = MutableStateFlow(210)
        val month = MutableStateFlow(900)
        val app = MutableStateFlow(AppSettings(showContinueTab = true, showStatsTab = false))
        every { settings.appSettings } returns app
        every { settings.getDailyReadingGoal() } returns day
        every { settings.getWeeklyReadingGoal() } returns week
        every { settings.getMonthlyReadingGoal() } returns month
        every { settings.isOnboardingCompleted() } returns MutableStateFlow(true)
        every { settings.getCustomFontPath() } returns MutableStateFlow(null)
        every { settings.getLibraryViewMode() } returns MutableStateFlow(LibraryViewMode.LIST)
        coEvery { settings.updateDailyReadingGoal(any()) } answers { day.value = firstArg() }
        coEvery { settings.updateWeeklyReadingGoal(any()) } answers { week.value = firstArg() }
        coEvery { settings.updateMonthlyReadingGoal(any()) } answers { month.value = firstArg() }
        val stats = mockk<ReadingStatsRepository>(relaxed = true)
        every { stats.getRecentDailyStats(any()) } returns MutableStateFlow(emptyList())
        coEvery { stats.getReadingSummary() } returns ReadingSummary(0, 0, 3, 0, 0)
        coEvery { stats.getReadingReport(any()) } returns ReadingReport("", 0, 0, null, null, emptyList())
        val books = mockk<BookRepository>()
        val preferences = SettingsViewModel(
            ApplicationProvider.getApplicationContext<Context>(), settings,
            mockk(), books, mockk(), mockk(), mockk(), mockk(), mockk()
        )
        val reports = StatsViewModel(stats, books, settings)
        val visibleStatistics = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { reports.uiState.collect {} }
        runCurrent()
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { reports.uiState.first { it.totalBooks == 3 } }
        }
        assertEquals(210, reports.uiState.value.weeklyGoalMinutes)
        preferences.updateReadingGoal(0, 45)
        preferences.updateReadingGoal(1, 350)
        preferences.updateReadingGoal(2, 1500)
        runCurrent()
        assertEquals(45, preferences.uiState.value.dailyReadingGoal)
        assertEquals(350, preferences.uiState.value.weeklyReadingGoal)
        assertEquals(1500, preferences.uiState.value.monthlyReadingGoal)
        assertEquals(350, reports.uiState.value.weeklyGoalMinutes)
        assertEquals(1500, reports.uiState.value.monthlyGoalMinutes)
        assertEquals(3, reports.uiState.value.totalBooks)
        assertEquals(app.value, AppSettings(showContinueTab = true, showStatsTab = false))
        coVerify(exactly = 1) { stats.getReadingReport(7) }
        coVerify(exactly = 1) { stats.getReadingReport(30) }
        visibleStatistics.cancel()
        androidx.lifecycle.ViewModelStore().apply { put("statistics", reports); put("preferences", preferences); clear() }
    }

    @Test fun visualThemeAndGridDefaultsPersistWithoutOverwritingExplicitPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.dataStore.edit { it.clear() }
        val repository = SettingsRepositoryImpl(context)
        assertEquals(AppVisualStyle.CLEAN_BLUE, repository.appSettings.first().visualStyle)
        assertEquals(LibraryViewMode.GRID, repository.getLibraryViewMode().first())
        repository.updateVisualStyle(AppVisualStyle.WARM_PAPER)
        repository.setLibraryViewMode(LibraryViewMode.LIST)
        repository.updateReadingSettings(ReadingSettings(fontSize = 24, palette = ReaderPaletteId.INK_BLUE))
        val reopened = SettingsRepositoryImpl(context)
        assertEquals(AppVisualStyle.WARM_PAPER, reopened.appSettings.first().visualStyle)
        assertEquals(LibraryViewMode.LIST, reopened.getLibraryViewMode().first())
        repository.updateVisualStyle(AppVisualStyle.CLEAN_BLUE)
        assertEquals(24, reopened.appSettings.first().defaultReadingSettings.fontSize)
        assertEquals(ReaderPaletteId.INK_BLUE, reopened.appSettings.first().defaultReadingSettings.palette)
    }

    @Test fun rapidThemeChoicesKeepTheLatestChoiceAcrossOldStorageEmissions() = runTest(dispatcher) {
        val app = MutableStateFlow(AppSettings())
        val (vm, repository) = preferencesWithStoredState(app)
        coEvery { repository.updateVisualStyle(any()) } coAnswers {
            val requested = firstArg<AppVisualStyle>()
            delay(100)
            app.value = app.value.copy(visualStyle = requested)
        }
        runCurrent()
        vm.updateVisualStyle(AppVisualStyle.WARM_PAPER)
        runCurrent()
        vm.updateVisualStyle(AppVisualStyle.CLEAN_BLUE)
        app.value = app.value.copy(themeMode = AppThemeMode.DARK)
        runCurrent()
        assertEquals(AppVisualStyle.CLEAN_BLUE, vm.uiState.value.visualStyle)
        advanceTimeBy(101)
        runCurrent()
        assertEquals(AppVisualStyle.CLEAN_BLUE, vm.uiState.value.visualStyle)
        advanceTimeBy(101)
        runCurrent()
        assertEquals(AppVisualStyle.CLEAN_BLUE, app.value.visualStyle)
        assertNull(vm.uiState.value.visualStyleSaveError)
    }

    @Test fun failedThemeSaveRestoresStoredChoiceAndCanRetry() = runTest(dispatcher) {
        val app = MutableStateFlow(AppSettings())
        val (vm, repository) = preferencesWithStoredState(app)
        coEvery { repository.updateVisualStyle(any()) } throws IllegalStateException("disk failure")
        runCurrent()
        vm.updateVisualStyle(AppVisualStyle.WARM_PAPER)
        runCurrent()
        assertEquals(AppVisualStyle.CLEAN_BLUE, vm.uiState.value.visualStyle)
        assertNotNull(vm.uiState.value.visualStyleSaveError)
        coEvery { repository.updateVisualStyle(any()) } coAnswers { app.value = app.value.copy(visualStyle = firstArg()) }
        vm.updateVisualStyle(AppVisualStyle.WARM_PAPER)
        runCurrent()
        assertEquals(AppVisualStyle.WARM_PAPER, vm.uiState.value.visualStyle)
        assertNull(vm.uiState.value.visualStyleSaveError)
    }

    @Test fun annotationStyleDefaultsToUnderlineAndPersistsWithoutResettingLayout() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.dataStore.edit { it.clear() }
        val repository = SettingsRepositoryImpl(context)
        assertEquals(AnnotationDisplayStyle.UNDERLINE, repository.appSettings.first().defaultReadingSettings.annotationDisplayStyle)
        repository.updateReadingSettings(
            ReadingSettings(
                fontSize = 25,
                pageMode = PageMode.SLIDE,
                annotationDisplayStyle = AnnotationDisplayStyle.SOFT_HIGHLIGHT
            )
        )
        val reopened = SettingsRepositoryImpl(context).appSettings.first().defaultReadingSettings
        assertEquals(AnnotationDisplayStyle.SOFT_HIGHLIGHT, reopened.annotationDisplayStyle)
        assertEquals(25, reopened.fontSize)
        assertEquals(PageMode.SLIDE, reopened.pageMode)
        repository.updateReadingSettings(reopened.copy(annotationDisplayStyle = AnnotationDisplayStyle.UNDERLINE))
        assertEquals(AnnotationDisplayStyle.UNDERLINE, repository.appSettings.first().defaultReadingSettings.annotationDisplayStyle)
        assertEquals(AnnotationDisplayStyle.UNDERLINE, AnnotationDisplayStyle.fromStoredName("future-style"))
    }
}
