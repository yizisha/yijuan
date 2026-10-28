package com.flowreader.app.ui.screens.search

import androidx.lifecycle.ViewModelStore
import com.flowreader.app.domain.model.GlobalSearchResult
import com.flowreader.app.domain.repository.SearchRepository
import com.flowreader.app.domain.repository.SettingsRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private fun TestScope.withModel(block: TestScope.(SearchViewModel, SearchRepository) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val repository = mockk<SearchRepository>()
            val settings = mockk<SettingsRepository>(relaxed = true)
            every { settings.getSearchHistory() } returns flowOf(emptyList())
            coEvery { repository.searchBooks(any()) } returns emptyList()
            val model = SearchViewModel(repository, settings)
            store.put("search", model)
            runCurrent()
            block(model, repository)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
    private fun hits(start: Int, count: Int) = (start until start + count).map {
        GlobalSearchResult(1, "书", it, "章 $it", "原文")
    }

    @Test fun cancelledOldQueryCannotOverwriteNewResultsOrReportFailure() = runTest {
        withModel { model, repository ->
            val old = CompletableDeferred<Unit>()
            coEvery { repository.searchChaptersWithProgress("旧", any(), any(), any()) } coAnswers {
                withContext(NonCancellable) { old.await() }
                hits(9, 1)
            }
            coEvery { repository.searchChaptersWithProgress("新", any(), any(), any()) } returns hits(2, 1)
            model.updateQuery("旧")
            advanceTimeBy(351); runCurrent()
            model.updateQuery("新")
            advanceTimeBy(351); runCurrent()
            old.complete(Unit); runCurrent()
            assertEquals(listOf(2), model.uiState.value.chapterResults.map { it.chapterIndex })
            assertNull(model.uiState.value.error)
            assertFalse(model.uiState.value.isSearching)
        }
    }

    @Test fun cancelStopsWorkAndRetainsQueryWithoutNoResultsError() = runTest {
        withModel { model, repository ->
            coEvery { repository.searchChaptersWithProgress(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
            model.updateQuery("山"); advanceTimeBy(351); runCurrent()
            assertTrue(model.uiState.value.isSearching)
            model.cancelSearch(); runCurrent()
            assertTrue(model.uiState.value.cancelled)
            assertEquals("山", model.uiState.value.query)
            assertFalse(model.uiState.value.isSearching)
            assertNull(model.uiState.value.error)
        }
    }

    @Test fun failedNextPageRetriesSameOffsetAndDoubleClickStartsOnlyOneRequest() = runTest {
        withModel { model, repository ->
            coEvery { repository.searchChaptersWithProgress(any(), any(), 0, any()) } returns hits(0, 21)
            coEvery { repository.searchChaptersWithProgress(any(), any(), 20, any()) } throws IllegalStateException("read failure")
            model.updateQuery("词"); advanceTimeBy(351); runCurrent()
            model.loadMoreChapters(); runCurrent()
            assertEquals(20, model.uiState.value.chapterResults.size)
            assertNotNull(model.uiState.value.error)
            val gate = CompletableDeferred<Unit>()
            coEvery { repository.searchChaptersWithProgress(any(), any(), 20, any()) } coAnswers {
                gate.await(); hits(20, 2)
            }
            model.loadMoreChapters(); model.loadMoreChapters(); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals((0..21).toList(), model.uiState.value.chapterResults.map { it.chapterIndex })
            coVerify(exactly = 2) { repository.searchChaptersWithProgress(any(), 21, 20, any()) }
            assertFalse(model.uiState.value.hasMoreChapters)
        }
    }

    @Test fun oldLoadMoreCannotAppendAfterQueryChanges() = runTest {
        withModel { model, repository ->
            val gate = CompletableDeferred<Unit>()
            coEvery { repository.searchChaptersWithProgress("旧", any(), 0, any()) } returns hits(0, 21)
            coEvery { repository.searchChaptersWithProgress("旧", any(), 20, any()) } coAnswers {
                withContext(NonCancellable) { gate.await() }; hits(20, 5)
            }
            coEvery { repository.searchChaptersWithProgress("新", any(), 0, any()) } returns hits(99, 1)
            model.updateQuery("旧"); advanceTimeBy(351); runCurrent()
            model.loadMoreChapters(); runCurrent()
            model.updateQuery("新"); advanceTimeBy(351); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals(listOf(99), model.uiState.value.chapterResults.map { it.chapterIndex })
        }
    }

    @Test fun oneCharacterSearchAndExactPageEndDoNotShowPhantomMore() = runTest {
        withModel { model, repository ->
            coEvery { repository.searchChaptersWithProgress("山", 21, 0, any()) } returns hits(0, 20)
            model.updateQuery("山"); advanceTimeBy(351); runCurrent()
            assertEquals(20, model.uiState.value.chapterResults.size)
            assertFalse(model.uiState.value.hasMoreChapters)
            model.updateQuery(" "); advanceTimeBy(351); runCurrent()
            assertTrue(model.uiState.value.chapterResults.isEmpty())
            assertNull(model.uiState.value.error)
        }
    }
}
