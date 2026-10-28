package com.flowreader.app.ui

import androidx.lifecycle.ViewModelStore
import com.flowreader.app.domain.model.*
import com.flowreader.app.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShellSettingsPerformanceTest {
    @Test fun bookSnapshotsAreMappedAwayFromTheUiCollector() = runBlocking {
        val dao = mockk<com.flowreader.app.data.local.dao.BookDao>()
        var sourceThread: Thread? = null
        every { dao.getAllBooks() } returns flow {
            sourceThread = Thread.currentThread()
            emit(listOf(com.flowreader.app.data.local.entity.BookEntity(title = "Book", author = "Author", filePath = "/book.epub")))
        }
        val repository = com.flowreader.app.data.repository.BookRepositoryImpl(dao)
        val collector = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            var uiThread: Thread? = null
            val result = withContext(collector) { uiThread = Thread.currentThread(); repository.getAllBooks().first() }
            assertEquals("Book", result.single().title)
            org.junit.Assert.assertNotNull(sourceThread)
            org.junit.Assert.assertNotSame("Book projection must not block its UI collector", uiThread, sourceThread)
        } finally { collector.close() }
    }
    @Test fun retainedStatisticsTabStopsItsQueriesWhenHiddenAndRefreshDoesNotAccumulateCollectors() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val active = java.util.concurrent.atomic.AtomicInteger()
            val stats = mockk<com.flowreader.app.domain.repository.ReadingStatsRepository>(relaxed = true)
            every { stats.getRecentDailyStats(any()) } returns kotlinx.coroutines.flow.flow {
                active.incrementAndGet()
                try { kotlinx.coroutines.awaitCancellation() } finally { active.decrementAndGet() }
            }
            val settings = mockk<SettingsRepository>(relaxed = true)
            every { settings.getWeeklyReadingGoal() } returns kotlinx.coroutines.flow.flowOf(210)
            every { settings.getMonthlyReadingGoal() } returns kotlinx.coroutines.flow.flowOf(900)
            val model = com.flowreader.app.ui.screens.stats.StatsViewModel(stats, mockk(relaxed = true), settings)
            store.put("stats", model)
            runCurrent()
            assertEquals(0, active.get())
            val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
            runCurrent()
            awaitCollectors(active, 2)
            repeat(3) { model.refresh(); runCurrent() }
            awaitCollectors(active, 2)
            subscription.cancel()
            runCurrent()
            awaitCollectors(active, 0)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    private fun awaitCollectors(active: java.util.concurrent.atomic.AtomicInteger, expected: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (active.get() != expected && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(expected, active.get())
    }

    @Test fun readerControlsDoNotInvalidateTheThemeOrNavigationButAppearanceStillDoes() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val source = MutableStateFlow(AppSettings())
            val repository = mockk<SettingsRepository>()
            every { repository.appSettings } returns source
            val shell = AppShellViewModel(repository, mockk(relaxed = true))
            store.put("shell", shell)
            val emissions = mutableListOf<ShellSettings>()
            val fullSettings = mutableListOf<AppSettings>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                source.collect { fullSettings += it }
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                shell.appSettings.collect { emissions += it }
            }
            runCurrent()
            val initialCount = emissions.size
            repeat(30) { step ->
                source.value = source.value.copy(
                    defaultReadingSettings = source.value.defaultReadingSettings.copy(fontSize = 12 + step % 20),
                    lastScanTime = step.toLong()
                )
                runCurrent()
            }
            assertEquals("Thirty reader edits must cause zero whole-app invalidations", initialCount, emissions.size)
            assertEquals("Control: the previous full-settings subscription sees every edit", 31, fullSettings.size)
            source.value = source.value.copy(themeMode = AppThemeMode.DARK)
            runCurrent()
            assertEquals(initialCount + 1, emissions.size)
            assertEquals(AppThemeMode.DARK, emissions.last().themeMode)
            source.value = source.value.copy(language = AppLanguage.ENGLISH)
            runCurrent()
            assertEquals(initialCount + 2, emissions.size)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
