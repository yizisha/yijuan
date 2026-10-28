package com.flowreader.app.ui.screens.library

import androidx.lifecycle.ViewModelStore
import com.flowreader.app.domain.model.*
import com.flowreader.app.domain.repository.*
import io.mockk.*
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteShelfViewModelTest {
    private val parser = mockk<com.flowreader.app.util.BookParser>(relaxed = true)
    private val importer = mockk<com.flowreader.app.util.BookImporter>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        store.clear()
        Dispatchers.resetMain()
    }
    private val source = MutableStateFlow(
        listOf(
            Book(
                id = 1,
                title = "甲",
                author = "作者",
                filePath = "1",
                categoryId = 10,
                isFavorite = true,
                readingProgress = .2f,
                lastReadTime = Date(1)
            ),
            Book(id = 2, title = "乙", author = "作者", filePath = "2", categoryId = 20, readingProgress = .8f, lastReadTime = Date(2))
        )
    )
    private fun fixture(): Pair<LibraryViewModel, BookRepository> {
        val repo = mockk<BookRepository>(relaxed = true)
        every { repo.getAllBooks() } returns source
        coEvery { repo.setFavorite(any(), any()) } coAnswers {
            val id = firstArg<Long>()
            val favorite = secondArg<Boolean>()
            source.value = source.value.map { if (it.id == id) it.copy(isFavorite = favorite) else it }
        }
        coEvery { repo.deleteBooksByIds(any()) } coAnswers {
            val ids = firstArg<List<Long>>()
            source.value = source.value.filter { it.id !in ids }
        }
        val settings = mockk<SettingsRepository>()
        every { settings.appSettings } returns MutableStateFlow(AppSettings(hideGroupedBooksFromAll = true))
        every { settings.getLibraryViewMode() } returns MutableStateFlow(LibraryViewMode.GRID)
        val categories = mockk<CategoryRepository>()
        every { categories.getAllCategories() } returns MutableStateFlow(listOf(Category(10, "甲组"), Category(20, "乙组")))
        val lists = mockk<ReadingListRepository>()
        every { lists.getAllLists() } returns MutableStateFlow(emptyList())
        val vm = LibraryViewModel(repo, mockk(), categories, lists, parser, mockk(), importer, mockk(), settings)
        store.put("shelf", vm)
        return vm to repo
    }
    private suspend fun TestScope.settle() {
        runCurrent()
        withContext(Dispatchers.Default) { delay(40) }
        runCurrent()
    }

    @Test fun sharedImportIsNotReplayedWhenTheShelfReturnsButManualImportStillWorks() = runTest(dispatcher) {
        val (vm, _) = fixture()
        val uri = mockk<android.net.Uri>()
        val pendingFile = kotlinx.coroutines.CompletableDeferred<String>()
        coEvery { parser.displayName(uri) } coAnswers { pendingFile.await() }
        vm.importInitialBook(uri)
        runCurrent()
        vm.importInitialBook(uri)
        runCurrent()
        coVerify(exactly = 1) { parser.displayName(uri) }
        vm.importBook(uri)
        runCurrent()
        coVerify(exactly = 2) { parser.displayName(uri) }
    }

    @Test fun switchingGroupsKeepsReadingStatusAndFavoritesStayGlobal() = runTest(dispatcher) {
        val (vm, _) = fixture()
        settle()
        vm.selectCategory(10)
        vm.setReadingFilter(ReadingFilter.READING)
        settle()
        assertEquals(listOf(1L), vm.uiState.value.books.map { it.id })
        vm.selectCategory(20)
        settle()
        assertEquals(ReadingFilter.READING, vm.uiState.value.readingFilter)
        assertEquals(listOf(2L), vm.uiState.value.books.map { it.id })
        vm.setReadingFilter(ReadingFilter.FAVORITES)
        settle()
        assertEquals(listOf(1L), vm.uiState.value.books.map { it.id })
        vm.selectCategory(10)
        settle()
        assertEquals(ReadingFilter.FAVORITES, vm.uiState.value.readingFilter)
        assertEquals(listOf(1L), vm.uiState.value.books.map { it.id })
    }

    @Test fun duplicateMergeKeepsTheMostReadCopyAndAnyExistingFavorite() = runTest(dispatcher) {
        val (vm, repo) = fixture()
        settle()
        vm.mergeDuplicates(listOf(source.value))
        settle()
        coVerify { repo.setFavorite(2, true) }
        assertEquals(listOf(2L), source.value.map { it.id })
        assertTrue(source.value.single().isFavorite)
    }

    @Test fun failedFavoriteWriteKeepsTheBookAndShowsAnError() = runTest(dispatcher) {
        val (vm, repo) = fixture()
        settle()
        coEvery { repo.setFavorite(any(), any()) } throws IllegalStateException("disk failure")
        vm.setFavorite(source.value.first())
        settle()
        assertTrue(vm.uiState.value.allBooks.first().isFavorite)
        assertEquals("disk failure", vm.uiState.value.error)
    }
}
