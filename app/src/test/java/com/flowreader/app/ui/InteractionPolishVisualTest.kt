package com.flowreader.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.theme.FlowTheme
import com.flowreader.app.domain.model.*
import com.flowreader.app.ui.screens.library.LibraryScreen
import com.flowreader.app.ui.screens.library.LibraryUiState
import com.flowreader.app.ui.screens.library.LibraryViewModel
import com.flowreader.app.ui.screens.library.ReadingFilter
import com.flowreader.app.ui.screens.reader.ReaderScreen
import com.flowreader.app.ui.screens.reader.ReaderUiState
import com.flowreader.app.ui.screens.reader.ReaderViewModel
import com.flowreader.app.ui.screens.reader.components.ReaderPageCache
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InteractionPolishVisualTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun importNotificationIsConsumedBeforeLeavingAndDoesNotReplayOnReturn() {
        val state = MutableStateFlow(LibraryUiState(message = com.flowreader.app.ui.screens.library.LibraryMessage.Imported("import-feedback-book")))
        val vm = mockk<LibraryViewModel>(relaxed = true)
        every { vm.uiState } returns state
        every { vm.isRefreshing } returns MutableStateFlow(false)
        every { vm.clearMessage() } answers { state.value = state.value.copy(message = null) }
        var visible by mutableStateOf(true)
        var initialUri by mutableStateOf<android.net.Uri?>(android.net.Uri.parse("content://fixture/one.epub"))
        rule.setContent {
            FlowTheme {
                if (visible) LibraryScreen(
                    initialImportUri = initialUri,
                    onBookClick = {}, onContinueReading = {}, onSearchClick = {}, onSettingsClick = {},
                    onNotesClick = {}, onReadingListsClick = {}, onOpdsClick = {}, viewModel = vm,
                    onInitialImportConsumed = { initialUri = null }
                )
            }
        }
        rule.onNode(hasText("import-feedback-book", substring = true)).assertIsDisplayed()
        assertNull(state.value.message)
        assertNull(initialUri)
        rule.onRoot().captureRoboImage("build/0.3.8-preview/import-feedback.png")
        rule.runOnIdle { visible = false }
        rule.waitForIdle()
        rule.runOnIdle { visible = true }
        rule.onNode(hasText("import-feedback-book", substring = true)).assertDoesNotExist()
        verify(exactly = 1) { vm.clearMessage() }
        verify(exactly = 1) { vm.importInitialBook(any()) }
    }

    @Test fun bookmarkConfirmationAndSettingsAreVisibleInDaylight() = reader(false)
    @Test fun bookmarkConfirmationKeepsTheExistingNightPalette() = reader(true)

    private fun reader(night: Boolean) {
        val chapter = Chapter(bookId = 1, index = 0, title = "阅读反馈", content = "正文保持可读，书签保存后给出明确反馈。")
        val book = Book(id = 1, title = "阅读反馈", author = "作者", filePath = "fixture.txt", format = BookFormat.TXT)
        val state = MutableStateFlow(
            ReaderUiState(
                book = book, chapters = listOf(chapter), currentChapter = chapter, isLoading = false,
                readingSettings = ReadingSettings(pageMode = PageMode.NONE, palette = if (night) ReaderPaletteId.NIGHT else ReaderPaletteId.PAPER)
            )
        )
        val vm = mockk<ReaderViewModel>(relaxed = true)
        every { vm.uiState } returns state
        every { vm.pageCache } returns ReaderPageCache()
        every { vm.addBookmark(any()) } answers {
            state.value = state.value.copy(bookmarks = listOf(Bookmark(id = 1, bookId = 1, chapterIndex = 0, position = 0, text = "原文")), message = "书签已添加")
        }
        every { vm.showSettings(true) } answers { state.value = state.value.copy(showSettings = true) }
        rule.setContent { FlowTheme(if (night) AppThemeMode.DARK else AppThemeMode.LIGHT) { ReaderScreen(vm, {}) } }
        rule.onNodeWithText(rule.activity.getString(R.string.settings_title)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.reader_shortcut_font)).assertDoesNotExist()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_bookmark_add)).performClick()
        rule.onNodeWithText("书签已添加").assertIsDisplayed()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_bookmark_saved)).assertIsDisplayed()
        rule.onNode(hasText("正文保持可读", substring = true)).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/reports/0.3.1/reader-feedback-${if (night) "night" else "light"}.png")
        rule.onNodeWithText(rule.activity.getString(R.string.settings_title)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.reader_tab_layout)).assertIsDisplayed()
        verify { vm.showSettings(true) }
    }

    @Test fun gridShelfGroupsUnreadWithTheOtherStatesAndLabelsHistoryHonestly() = shelf(LibraryViewMode.GRID)
    @Test fun listShelfUsesTheSameStateFiltersAndHistoryEntry() = shelf(LibraryViewMode.LIST)

    @Config(sdk = [34], qualifiers = "zh-rCN-w320dp-h891dp-420dpi")
    @Test fun narrowShelfWithLargeTextKeepsAllFiltersReachable() = shelf(LibraryViewMode.GRID, 1.6f)

    private fun shelf(mode: LibraryViewMode, fontScale: Float = 1f) {
        val books = listOf(
            Book(id = 1, title = "正在阅读的书", author = "作者", filePath = "one.txt", format = BookFormat.TXT, lastReadTime = Date(), readingProgress = .3f),
            Book(id = 2, title = "尚未阅读的书", author = "作者", filePath = "two.txt", format = BookFormat.TXT),
            Book(id = 3, title = "收藏的书", author = "作者", filePath = "three.txt", format = BookFormat.TXT, isFavorite = true)
        )
        val state = MutableStateFlow(LibraryUiState(books = books, allBooks = books, viewMode = mode))
        val vm = mockk<LibraryViewModel>(relaxed = true)
        every { vm.uiState } returns state
        every { vm.isRefreshing } returns MutableStateFlow(false)
        every { vm.setReadingFilter(any()) } answers {
            val filter = firstArg<ReadingFilter>()
            state.value = state.value.copy(readingFilter = filter, books = books.filter(filter::matches))
        }
        var resumed = 0L
        var history = 0
        rule.setContent {
            FlowTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    LibraryScreen(
                        onBookClick = {}, onContinueReading = { resumed = it }, onSearchClick = {}, onSettingsClick = {},
                        onNotesClick = {}, onReadingListsClick = {}, onOpdsClick = {}, onHistoryClick = { history++ }, viewModel = vm
                    )
                }
            }
        }
        rule.onNodeWithText(books[0].title).performClick()
        rule.runOnIdle { assertEquals(1L, resumed) }
        rule.onNodeWithText(rule.activity.getString(R.string.library_filter_unread)).performClick().assertIsSelected()
        verify { vm.setReadingFilter(ReadingFilter.UNREAD) }
        rule.onNodeWithText(books[1].title).assertIsDisplayed()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_more)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.continue_title)).assertDoesNotExist()
        rule.onAllNodesWithText(rule.activity.getString(R.string.library_filter_unread)).assertCountEquals(1)
        rule.mainClock.advanceTimeBy(500)
        rule.onNode(isPopup()).captureRoboImage("build/reports/0.3.1/shelf-menu-${mode.name.lowercase()}-$fontScale.png")
        rule.onNodeWithText(rule.activity.getString(R.string.ui_history)).performClick()
        rule.runOnIdle { assertEquals(1, history) }
        val favorite = rule.onNodeWithText(rule.activity.getString(R.string.shelf_favorites))
        favorite.performScrollTo().performClick().assertIsSelected()
        verify { vm.setReadingFilter(ReadingFilter.FAVORITES) }
        rule.onRoot().captureRoboImage("build/reports/0.3.1/shelf-filters-${mode.name.lowercase()}-$fontScale.png")
    }

    @Test fun enteringAndReturningFromReaderLeavesNoOutgoingTextOrCrossFade() {
        lateinit var nav: NavHostController
        rule.setContent {
            FlowTheme {
                nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val visible by nav.visibleEntries.collectAsState()
                FlowShellScaffold({
                    if (shouldShowPrimaryNavigation(entry?.destination?.route, visible.map { it.destination.route })) {
                        FlowPrimaryNavigation({ it == Screen.Library }, {})
                    }
                }) {
                    NavHost(
                        nav, Screen.Library.route,
                        enterTransition = { destinationEnterTransition(initialState.destination.route, targetState.destination.route) },
                        exitTransition = { destinationExitTransition(initialState.destination.route, targetState.destination.route) }
                    ) {
                        composable(Screen.Library.route) { Box(Modifier.fillMaxSize().background(Color.White)) { Text("书架已显示") } }
                        composable(Screen.Reader.route) { Box(Modifier.fillMaxSize().background(Color(0xFFF5F0E4))) { Text("退出后不应残留的正文") } }
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { nav.navigate(Screen.Reader.createRoute(1)) }
        rule.mainClock.advanceTimeBy(64)
        rule.onNodeWithText("退出后不应残留的正文").assertIsDisplayed()
        rule.onNodeWithText("书架已显示").assertDoesNotExist()
        rule.runOnIdle { assertTrue(nav.popBackStack()) }
        // NavHost commits its new measured content asynchronously. Until then, do not resize the
        // still-visible reader by bringing back the main bar; verify each intervening frame.
        repeat(4) {
            rule.mainClock.advanceTimeByFrame()
            if (rule.onAllNodesWithText("退出后不应残留的正文").fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithText(rule.activity.getString(R.string.nav_shelf)).assertDoesNotExist()
                if (it == 0) rule.onRoot().captureRoboImage("build/reports/0.3.1/reader-return-pending-frame.png")
            }
        }
        rule.onNodeWithText("书架已显示").assertIsDisplayed()
        rule.onNodeWithText("退出后不应残留的正文").assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText(rule.activity.getString(R.string.nav_shelf)).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/reports/0.3.1/reader-return-stable-frame.png")
    }

    @Test fun readerAndPrimaryNavigationAvoidSimultaneouslyAnimatingWholeScreens() {
        listOf(Screen.Library.route, Screen.BookDetail.route, Screen.Search.route).forEach { route ->
            assertEquals(EnterTransition.None, destinationEnterTransition(route, Screen.Reader.route))
            assertEquals(ExitTransition.None, destinationExitTransition(route, Screen.Reader.route))
            assertEquals(EnterTransition.None, destinationEnterTransition(Screen.Reader.route, route))
            assertEquals(ExitTransition.None, destinationExitTransition(Screen.Reader.route, route))
        }
        assertEquals(EnterTransition.None, destinationEnterTransition(Screen.Library.route, Screen.Settings.route))
        assertEquals(ExitTransition.None, destinationExitTransition(Screen.Library.route, Screen.Settings.route))
        assertFalse(shouldShowPrimaryNavigation(Screen.Library.route, listOf(Screen.Reader.route, Screen.Library.route)))
        assertTrue(shouldShowPrimaryNavigation(Screen.Library.route, listOf(Screen.Library.route)))
        assertFalse(shouldShowPrimaryNavigation(Screen.Reader.route, listOf(Screen.Reader.route)))
    }
}
