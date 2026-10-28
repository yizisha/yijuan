package com.flowreader.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.reader.ReaderPalette
import com.flowreader.app.core.designsystem.reader.ReaderPalettes
import com.flowreader.app.core.designsystem.reader.background
import com.flowreader.app.core.designsystem.reader.rememberReaderFontFamily
import com.flowreader.app.core.designsystem.theme.FlowTheme
import com.flowreader.app.core.designsystem.theme.flowColorScheme
import com.flowreader.app.core.designsystem.token.FlowMonochromeDarkColorScheme
import com.flowreader.app.domain.model.*
import com.flowreader.app.ui.screens.bookdetail.*
import com.flowreader.app.ui.screens.continue_reading.*
import com.flowreader.app.ui.screens.library.*
import com.flowreader.app.ui.screens.notes.*
import com.flowreader.app.ui.screens.reader.components.*
import com.flowreader.app.ui.screens.search.*
import com.flowreader.app.ui.screens.settings.*
import com.flowreader.app.ui.screens.stats.*
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native Compose rendering plus navigation/setting assertions for the reading-room redesign. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingRoomVisualTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val books = listOf("山与生活", "人间值得", "百年孤独", "三体", "活着", "被讨厌的勇气", "沙丘", "小王子", "我与地坛").mapIndexed { index, title ->
        Book(
            id = index + 1L,
            title = title,
            author = listOf("林间", "中村恒子", "加西亚·马尔克斯", "刘慈欣", "余华", "岸见一郎", "弗兰克·赫伯特", "圣埃克苏佩里", "史铁生")[index],
            filePath = "fixture.txt",
            format = BookFormat.TXT,
            totalChapters = 24,
            readingProgress = (.42f - index * .06f).coerceAtLeast(.01f),
            lastReadTime = if (index == 0) Date() else null
        )
    }
    private val chapter =
        Chapter(
            bookId = 1,
            index = 0,
            title = "山与生活",
            content = "清晨，山间有薄雾升起，像一层轻纱，把世界柔和地包裹起来。风穿过树梢，带来泥土与草木的气息，安静而温柔。\n\n我沿着一条熟悉的小路向上走，脚下是落叶与碎石，耳边传来远处的鸟鸣。阳光慢慢洒在山脊上，云雾散去，露出层层叠叠的山峰。那一刻，内心也变得格外平静。\n\n生活或许就是这样，在平凡的日子里，依然可以找到属于自己的风景。只要愿意放慢脚步，用心去感受，山川湖海、草木人间，都会在不经意间，给你温柔的回应。"
        )

    @Test fun warmThemeShelfAdaptsToTheSharedLayout() = shelf(
        LibraryViewMode.GRID,
        AppThemeMode.LIGHT,
        "shelf-warm",
        AppVisualStyle.WARM_PAPER
    )

    @Test fun themeChoicesHaveWorkingSelectionAndKeepTheExistingNightColors() {
        val state = MutableStateFlow(SettingsUiState(isLoading = false))
        val vm = mockk<SettingsViewModel>(relaxed = true)
        every { vm.uiState } returns state
        every { vm.updateVisualStyle(any()) } answers { state.value = state.value.copy(visualStyle = firstArg()) }
        var night: androidx.compose.material3.ColorScheme? = null
        var day: androidx.compose.material3.ColorScheme? = null
        rule.setContent {
            FlowTheme(AppThemeMode.LIGHT, visualStyle = state.collectAsState().value.visualStyle) {
                night = flowColorScheme(true)
                day = flowColorScheme(false)
                AppearanceSettingsScreen({}, vm)
            }
        }
        rule.onNodeWithText(AppVisualStyle.CLEAN_BLUE.displayName).performScrollTo().assertIsSelected()
        rule.onNodeWithText(AppVisualStyle.WARM_PAPER.displayName).performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(com.flowreader.app.core.designsystem.token.FlowDarkColorScheme, night) }
        capture("theme-warm-preview")
        rule.onNodeWithText(AppVisualStyle.CLEAN_BLUE.displayName).performClick().assertIsSelected()
        rule.runOnIdle {
            assertEquals(androidx.compose.ui.graphics.Color.White, day?.background)
            assertEquals(androidx.compose.ui.graphics.Color.White, day?.surface)
            assertEquals(androidx.compose.ui.graphics.Color.White, day?.surfaceTint)
            assertEquals(androidx.compose.ui.graphics.Color(0xFF28679D), day?.primary)
        }
        capture("theme-clean-preview")
    }

    @Test fun groupPickerAndFavoritesUseTheSameBooksAndKeepTheirCallbacks() {
        val initial = books.mapIndexed { index, book -> book.copy(isFavorite = index < 2, categoryId = if (index == 0) 10 else 20) }
        val state =
            MutableStateFlow(
                LibraryUiState(books = initial, allBooks = initial, categories = listOf(Category(10, "文学"), Category(20, "科幻")))
            )
        val vm = mockk<LibraryViewModel>(relaxed = true)
        every { vm.uiState } returns state
        every { vm.isRefreshing } returns MutableStateFlow(false)
        fun update(
            group: Long? = state.value.selectedCategoryId,
            filter: ReadingFilter = state.value.readingFilter,
            source: List<Book> = state.value.allBooks
        ) {
            val computed = LibraryViewModel.ShelfQuery(
                source,
                SortOrder.TITLE,
                group,
                filter,
                LibraryViewModel.ShelfPreferences(hideGroupedBooksFromAll = true)
            ).compute()
            state.value = state.value.copy(books = computed.books, allBooks = source, selectedCategoryId = group, readingFilter = filter)
        }
        every { vm.selectCategory(any()) } answers { update(group = firstArg()) }
        every { vm.setReadingFilter(any()) } answers { update(filter = firstArg()) }
        every { vm.setFavorite(any()) } answers {
            val chosen = firstArg<Book>()
            update(source = state.value.allBooks.map { if (it.id == chosen.id) it.copy(isFavorite = !chosen.isFavorite) else it })
        }
        rule.setContent {
            FlowTheme(AppThemeMode.LIGHT) {
                LibraryScreen(onBookClick = {
                }, onContinueReading = {
                }, onSearchClick = {}, onSettingsClick = {}, onNotesClick = {}, onReadingListsClick = {}, onOpdsClick = {}, viewModel = vm)
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.shelf_favorites)).performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.runOnIdle { rule.activity.window.decorView.invalidate() }
        capture("shelf-favorites")
        rule.onNodeWithText(rule.activity.getString(R.string.library_filter_all)).performClick()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/clean-ui/shelf-groups.png")
        rule.onNodeWithText("科幻").performClick()
        rule.runOnIdle {
            assertEquals(ReadingFilter.FAVORITES, state.value.readingFilter)
            assertEquals(2, state.value.books.size)
        }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.library_book_actions, initial.first().title)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.shelf_unfavorite)).performClick()
        rule.runOnIdle { assertEquals(listOf(2L), state.value.books.map { it.id }) }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/reports/clean-ui/$name.png")
    }

    @Test fun shelfGridUsesRealBooksAndEveryShortcutNavigates() = shelf(LibraryViewMode.GRID, AppThemeMode.LIGHT, "shelf-grid")

    @Test fun shelfListPreservesBookActions() = shelf(LibraryViewMode.LIST, AppThemeMode.LIGHT, "shelf-list")

    @Test fun shelfDarkThemeRemainsLegible() = shelf(LibraryViewMode.GRID, AppThemeMode.DARK, "shelf-dark")

    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h640dp-320dpi")
    fun longTitleOnCompactScreenKeepsTheReadingButtonVisible() {
        val title = "在平凡的日子里寻找属于自己的山川与生活"
        val vm = mockk<LibraryViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(LibraryUiState(books = listOf(books.first().copy(title = title)), allBooks = books))
        every { vm.isRefreshing } returns MutableStateFlow(false)
        var opened = 0L
        rule.setContent {
            FlowTheme(AppThemeMode.LIGHT) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                    LibraryScreen(onBookClick = {
                    }, onContinueReading = {
                        opened = it
                    }, onSearchClick = {}, onSettingsClick = {}, onNotesClick = {}, onReadingListsClick = {}, onOpdsClick = {}, viewModel = vm)
                }
            }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText(
            rule.activity.getString(R.string.library_filter_reading)
        ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        capture("compact-long-title")
        rule.runOnIdle {
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { layout ->
                // Shrink-wrapped Text may report didOverflowWidth because the paragraph keeps
                // the wider incoming constraint. Verify the actual visible glyphs and paint bounds.
                assertEquals(layout.layoutInput.text.length, layout.getLineEnd(0, visibleEnd = true))
                assertFalse(layout.isLineEllipsized(0))
                assertTrue(layout.getLineLeft(0) >= 0f)
                assertTrue(layout.getLineRight(0) <= layout.size.width + 1f)
            }
        }
        rule.onNode(hasText(title) and hasClickAction()).performClick()
        rule.runOnIdle { assertEquals(1L, opened) }
        rule.onNodeWithText(rule.activity.getString(R.string.shelf_favorites)).assertIsDisplayed()
    }

    private fun shelf(mode: LibraryViewMode, theme: AppThemeMode, name: String, style: AppVisualStyle = AppVisualStyle.CLEAN_BLUE) {
        val vm = mockk<LibraryViewModel>(relaxed = true)
        every {
            vm.uiState
        } returns MutableStateFlow(LibraryUiState(books = books, allBooks = books, recentlyRead = books.take(1), viewMode = mode))
        every { vm.isRefreshing } returns MutableStateFlow(false)
        var opened = 0L
        var resumed = 0L
        val destinations = mutableListOf<String>()
        rule.setContent {
            FlowTheme(theme, visualStyle = style) {
                FlowShellScaffold({ FlowPrimaryNavigation({ it == Screen.Library }, {}) }) {
                    LibraryScreen(
                        onBookClick = { opened = it }, onContinueReading = { resumed = it }, onSearchClick = {}, onSettingsClick = {},
                        onNotesClick = {
                            destinations += "notes"
                        }, onReadingListsClick = {
                            destinations += "lists"
                        }, onOpdsClick = {
                            destinations += "discover"
                        }, onStatsClick = { destinations += "stats" }, onHistoryClick = { destinations += "history" }, viewModel = vm
                    )
                }
            }
        }
        capture(name)
        rule.onNodeWithText("山与生活").performClick()
        rule.runOnIdle { assertEquals(1L, resumed) }
        listOf(R.string.room_lists, R.string.room_discover).forEach {
            rule.onNodeWithText(rule.activity.getString(it)).assertDoesNotExist()
        }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_more)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.ui_history)).performClick()
        rule.runOnIdle { assertEquals(listOf("history"), destinations) }
        rule.onNodeWithText("人间值得").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(2L, opened) }
        rule.onNodeWithText("人间值得").performTouchInput { longClick() }
        verify { vm.toggleSelection(2L) }
    }

    @Test fun notesUseExistingPassageAndBookFilter() {
        val vm = mockk<NotesViewModel>(relaxed = true)
        val annotation = Annotation(
            id = 1,
            bookId = 1,
            chapterIndex = 2,
            startPosition = 0,
            endPosition = 20,
            selectedText = "生活中的小温柔，往往藏在我们放慢脚步的时刻。",
            note = "把阅读留给自己，把温柔带回生活。"
        )
        every {
            vm.uiState
        } returns MutableStateFlow(NotesUiState(isLoading = false, books = books.take(2), notes = listOf(NoteItem(annotation, "山与生活"), NoteItem(annotation.copy(id = 2, bookId = 2, selectedText = "向前走，也记得欣赏路边的风景。", color = AnnotationColor.GREEN), "人间值得"))))
        var passage: Pair<Long, Int>? = null
        rule.setContent {
            FlowTheme(AppThemeMode.LIGHT) {
                FlowShellScaffold({
                    FlowPrimaryNavigation({ it == Screen.Notes }, {})
                }) { NotesScreen({}, { book, chapter -> passage = book to chapter }, vm) }
            }
        }
        capture("notes")
        rule.onNodeWithText(annotation.selectedText).performClick()
        rule.runOnIdle { assertEquals(1L to 2, passage) }
        rule.onNode(hasText("人间值得") and hasClickAction()).performClick()
        verify { vm.selectBook(2L) }
    }

    @Test fun statisticsSwitchPeriodAndChartMatchesHeadlineWindow() {
        val vm = mockk<StatsViewModel>(relaxed = true)
        val anchor = LocalDate.of(2026, 10, 11)
        val rows = listOf(
            32,
            58,
            26,
            70,
            40,
            35,
            51
        ).mapIndexed { index, minutes -> DailyStats(anchor.minusDays(6L - index).toString(), minutes * 60L, minutes / 2, 1) }
        val state =
            MutableStateFlow(
                StatsUiState(
                    isLoading = false, heatmapStats = rows, recentDailyStats = rows, anchor = anchor,
                    periodSummary = StatsPeriodSummary.of(
                        rows,
                        StatsPeriod.WEEK,
                        anchor
                    ),
                    rangeLabel = "10月05日至10月11日", todayReadTime = 51 * 60L, todayReadPages = 25, totalBooks = 3, currentStreak = 7
                )
            )
        every { vm.uiState } returns state
        every { vm.selectPeriod(any()) } answers {
            val period = firstArg<StatsPeriod>()
            state.value = state.value.copy(period = period, periodSummary = StatsPeriodSummary.of(rows, period, anchor), rangeLabel = StatsPeriodSummary.rangeLabel(period, anchor))
        }
        rule.setContent {
            FlowTheme(AppThemeMode.LIGHT) { FlowShellScaffold({ FlowPrimaryNavigation({ it == Screen.Stats }, {}) }) { StatsScreen(vm) } }
        }
        capture("statistics")
        rule.onNodeWithText("5").assertIsDisplayed()
        rule.onNodeWithText("12").assertIsDisplayed()
        rule.onNodeWithText(StatsPeriod.MONTH.label).performClick()
        rule.runOnIdle {
            assertEquals(StatsPeriod.MONTH, state.value.period)
            assertEquals(31, periodChartStats(state.value).size)
        }
        rule.onNodeWithText("2026年10月").assertIsDisplayed()
    }

    @Test fun settingsCategoriesRemainClickable() {
        val vm = mockk<SettingsViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(SettingsUiState(isLoading = false))
        var category: SettingsCategory? = null
        rule.setContent {
            FlowTheme(AppThemeMode.LIGHT) {
                FlowShellScaffold({ FlowPrimaryNavigation({ it == Screen.Settings }, {}) }) { SettingsScreen({ category = it }, vm) }
            }
        }
        capture("settings")
        rule.onNodeWithText(rule.activity.getString(SettingsCategory.READ.titleRes)).performClick()
        rule.runOnIdle { assertEquals(SettingsCategory.READ, category) }
    }

    @Test fun paperReadingRendersWithTheExistingPaginator() = readingPage(
        ReaderPalettes.of(ReaderPaletteId.PAPER, AppVisualStyle.CLEAN_BLUE),
        "reading-paper"
    )

    @Test fun nightReadingRendersWithTheChosenTypeface() = readingPage(ReaderPalettes.Night, "reading-night")

    @Test fun warmPaperReadingRetainsItsLandscapeAndChosenTypeface() = readingPage(
        ReaderPalettes.Paper,
        "reading-warm",
        AppVisualStyle.WARM_PAPER
    )

    private fun readingPage(palette: ReaderPalette, name: String, style: AppVisualStyle = AppVisualStyle.CLEAN_BLUE) {
        rule.setContent {
            FlowTheme(AppThemeMode.LIGHT, visualStyle = style) {
                val settings = ReadingSettings(fontFamily = ReaderFontFamily.SERIF, fontSize = 19, lineSpacing = 1.7f, palette = palette.id)
                val family = rememberReaderFontFamily(settings)
                Box(Modifier.fillMaxSize().background(palette.background)) {
                    PagedReader(chapter, 0, settings, palette, family, emptyList(), 0, { _, _ -> }, {
                    }, {
                    }, onHighlightSelection = { _, _, _ -> }, onBookmarkSelection = { _, _, _ -> }, onImageClick = { _, _ -> }, paginationDispatcher = Dispatchers.Main.immediate)
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText(chapter.title).fetchSemanticsNodes().isNotEmpty() }
        capture(name)
        rule.onNodeWithText(chapter.title).assertIsDisplayed()
    }

    @Test fun readerTypefacePresetRemainsUserSelectable() {
        var settings by mutableStateOf(ReadingSettings())
        rule.setContent { FlowTheme(AppThemeMode.LIGHT) { ReaderSettingsSheet(settings, { settings = it }, {}, {}, {}, {}, {}) } }
        rule.onNode(
            hasText(rule.activity.getString(R.string.reader_tab_font)) and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, true)
        ).assertIsDisplayed()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_setting_font)).performClick()
        rule.onNodeWithText(ReaderFontFamily.SERIF.displayName).performClick()
        rule.runOnIdle { assertEquals(ReaderFontFamily.SERIF, settings.fontFamily) }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_setting_font)).performClick()
        rule.onNodeWithText(ReaderFontFamily.DEFAULT.displayName).performClick()
        rule.runOnIdle { assertEquals(ReaderFontFamily.DEFAULT, settings.fontFamily) }
    }

    @Test fun nightPanelChangesRealSettingsAndRestoresSystemBrightness() {
        var settings by mutableStateOf(ReadingSettings(palette = ReaderPaletteId.NIGHT))
        rule.setContent {
            FlowTheme(AppThemeMode.LIGHT) {
                ReaderSettingsSheet(settings, { settings = it }, {}, {}, {}, {}, {})
            }
        }
        rule.waitForIdle()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/clean-ui/reading-night-panel.png")
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.room_font_larger)).performClick()
        rule.runOnIdle { assertEquals(19, settings.fontSize) }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.room_line_relaxed)).performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(1.8f, settings.lineSpacing) }
        rule.onNodeWithText(rule.activity.getString(R.string.room_brightness)).performScrollTo().performClick()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.room_brightness)).performScrollTo().performTouchInput { click() }
        rule.runOnIdle { assertTrue(rule.activity.window.attributes.screenBrightness >= .01f) }
        rule.onNode(
            SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Text,
                listOf(androidx.compose.ui.text.AnnotatedString(rule.activity.getString(R.string.reader_brightness_system)))
            )
        ).performClick()
        rule.runOnIdle { assertEquals(-1f, rule.activity.window.attributes.screenBrightness) }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.room_night)).performClick()
        rule.runOnIdle { assertEquals(ReaderPaletteId.PAPER, settings.palette) }
    }

    @Test fun fixedPrimaryNavigationChangesSelectionAndDoesNotExposeHistoryAsATab() {
        (listOf(SettingsCategory.READ.route, SettingsCategory.TXT.route) + ReadingCategory.entries.map { it.route }).forEach { route ->
            assertTrue(isPrimaryDestinationSelected(Screen.Settings, route))
            assertFalse(isPrimaryDestinationSelected(Screen.Library, route))
        }
        assertTrue(isPrimaryDestinationSelected(Screen.Library, Screen.Continue.route))
        assertTrue(primaryDestinations.none { isPrimaryDestinationSelected(it, Screen.Reader.route) })
        var selected by mutableStateOf<Screen>(Screen.Library)
        rule.setContent { FlowTheme { FlowPrimaryNavigation({ it == selected }, { selected = it }) } }
        for (screen in primaryDestinations) {
            rule.onNodeWithText(rule.activity.getString(screen.titleRes)).performClick().assertIsSelected()
            rule.runOnIdle { assertEquals(screen, selected) }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.continue_title)).assertDoesNotExist()
    }

    @Test fun settingDirectoryHasExactlyFiveCategoriesAndNoDecorativeBanner() {
        val vm = settingsVm()
        val opened = mutableListOf<SettingsCategory>()
        rule.setContent { FlowTheme { SettingsScreen({ opened += it }, vm) } }
        settingsMenuCategories.forEach { category -> rule.onNodeWithText(rule.activity.getString(category.titleRes)).performClick() }
        rule.runOnIdle {
            assertEquals(settingsMenuCategories, opened)
            assertEquals(5, opened.size)
        }
        listOf(
            R.string.settings_section_txt,
            R.string.settings_section_security,
            R.string.settings_section_navigation,
            R.string.room_settings_subtitle
        )
            .forEach { rule.onNodeWithText(rule.activity.getString(it)).assertDoesNotExist() }
    }

    @Test fun readingMenuSharesFourGroupsWithTheSheetAndAddsOnlyHabits() {
        val vm = settingsVm()
        var opened: ReadingCategory? = null
        rule.setContent { FlowTheme { ReadingMenuScreen({}, { opened = it }, vm) } }
        capture("settings-reading")
        (readerCategories + ReadingCategory.GOALS).forEach { category ->
            rule.onNodeWithText(rule.activity.getString(category.titleRes)).performScrollTo().performClick()
            rule.runOnIdle { assertEquals(category, opened) }
        }
    }

    @Test fun typographyAdvancedControlsAreCollapsedButKeepExistingCustomValues() {
        val vm = settingsVm(SettingsUiState(isLoading = false, readingSettings = ReadingSettings(horizontalMarginDp = 28)))
        rule.setContent { FlowTheme { TypographySettingsScreen({}, vm) } }
        capture("settings-typography")
        rule.onNodeWithText(rule.activity.getString(R.string.reader_setting_conversion)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(com.flowreader.core.R.string.flow_customized)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.ui_advanced_typography)).performScrollTo().performClick()
        rule.onNode(hasText(ChineseConversion.TRADITIONAL_TO_SIMPLIFIED.displayName) and hasClickAction()).performScrollTo().performClick()
        verify {
            vm.updateReadingSettings(
                match { it.horizontalMarginDp == 28 && it.chineseConversion == ChineseConversion.TRADITIONAL_TO_SIMPLIFIED }
            )
        }
        capture("settings-typography-expanded")
    }

    @Test fun pageTurningAndGestureCustomizationAreAvailableOnTheSamePage() {
        val vm = settingsVm()
        rule.setContent { FlowTheme { PageTurnSettingsScreen({}, vm) } }
        capture("settings-page-turn")
        rule.onNodeWithText(rule.activity.getString(R.string.gesture_left_tap)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.ui_advanced_gestures)).performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.gesture_left_tap)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.gesture_double_tap_enable)).performScrollTo().performClick()
        verify { vm.updateReadingSettings(match { it.gestureSettings.doubleTapEnabled }) }
        capture("settings-gestures-expanded")
    }

    @Test fun shelfImportContainsTxtAndLanWhileBackupDoesNotContainLan() {
        val vm = settingsVm()
        var txt = false
        rule.setContent { FlowTheme { ShelfSettingsScreen({}, vm, { txt = true }) } }
        capture("settings-shelf")
        rule.onNodeWithText(rule.activity.getString(R.string.ui_txt_advanced)).performScrollTo().performClick()
        rule.runOnIdle { assertTrue(txt) }
        rule.onNodeWithText(rule.activity.getString(R.string.settings_lan_transfer)).performScrollTo()
        rule.onNodeWithText(rule.activity.getString(R.string.settings_lan_transfer)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.settings_lan_receive)).assertIsDisplayed()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/clean-ui/dialog-lan.png")
        rule.onNodeWithText(rule.activity.getString(R.string.action_cancel)).performClick()
    }

    @Test fun dataAndPrivacyPageKeepsLockAndBackupInDistinctGroups() {
        val vm = settingsVm()
        rule.setContent { FlowTheme { DataSettingsScreen({}, vm) } }
        capture("settings-data")
        rule.onNodeWithText(rule.activity.getString(R.string.settings_backup)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.settings_lan_transfer)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.settings_app_lock)).performScrollTo()
        rule.onNodeWithText(rule.activity.getString(R.string.settings_app_lock)).performClick()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/clean-ui/dialog-pin.png")
        rule.onNodeWithText(rule.activity.getString(R.string.action_cancel)).performClick()
    }

    @Test fun habitsUsesExistingDayWeekAndMonthGoalCallbacks() {
        val vm = settingsVm()
        rule.setContent { FlowTheme { ReminderSettingsScreen({}, vm) } }
        capture("settings-habits")
        rule.onNodeWithText(rule.activity.getString(R.string.stats_goal_weekly)).performClick()
        rule.onNode(
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress)
        ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) {
            it(350f)
        }
        rule.onNodeWithText(rule.activity.getString(R.string.action_confirm)).performClick()
        verify { vm.updateReadingGoal(1, 350) }
    }

    @Test fun darkSettingsUsesTheSameThemeAsTheRestOfTheApp() = settingsAppearance(AppThemeMode.DARK, ColorSource.BRAND, "settings-dark")

    @Test fun monochromeSettingsContainsNoBrandColourOverrides() = settingsAppearance(
        AppThemeMode.LIGHT,
        ColorSource.MONOCHROME,
        "settings-monochrome"
    )

    @Test fun dynamicSettingsUsesTheResolvedWallpaperScheme() = settingsAppearance(
        AppThemeMode.LIGHT,
        ColorSource.DYNAMIC,
        "settings-dynamic"
    )

    private fun settingsAppearance(theme: AppThemeMode, source: ColorSource, name: String) {
        val vm = settingsVm(SettingsUiState(isLoading = false, themeMode = theme, colorSource = source))
        rule.setContent { FlowTheme(theme, source) { AppearanceSettingsScreen({}, vm) } }
        capture(name)
    }

    @Test fun nightReadingPanelResolvesAllMonochromeSurfaceTokens() {
        var actual: androidx.compose.material3.ColorScheme? = null
        rule.setContent {
            FlowTheme(AppThemeMode.LIGHT, ColorSource.MONOCHROME) {
                actual = flowColorScheme(true)
                ReaderSettingsSheet(ReadingSettings(palette = ReaderPaletteId.NIGHT), {}, {}, {}, {}, {}, {})
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(FlowMonochromeDarkColorScheme, actual) }
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/clean-ui/reader-monochrome-night.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w600dp-h960dp-320dpi")
    fun expandedScreenSettingsRetainsReadableGrouping() {
        val vm = settingsVm()
        var selected by mutableStateOf<Screen>(Screen.Settings)
        rule.setContent {
            FlowTheme {
                Row(Modifier.fillMaxSize()) {
                    FlowPrimaryRail({ it == selected }, { selected = it })
                    Box(Modifier.weight(1f)) { SettingsScreen({}, vm) }
                }
            }
        }
        capture("settings-wide")
        rule.onNodeWithText(rule.activity.getString(R.string.ui_settings_data)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.ui_nav_notes)).performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(Screen.Notes, selected) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h640dp-320dpi")
    fun largerSystemTypefaceLeavesAllSettingsReachableOnCompactScreen() {
        val vm = settingsVm()
        rule.setContent {
            FlowTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) { SettingsScreen({}, vm) }
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.settings_section_about)).performScrollTo().assertIsDisplayed()
        capture("settings-large-type")
    }

    private fun settingsVm(state: SettingsUiState = SettingsUiState(isLoading = false)): SettingsViewModel {
        val vm = mockk<SettingsViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(state)
        return vm
    }

    @Test fun bookDetailUsesSharedCoverAndPreservesContinueReading() {
        val vm = mockk<BookDetailViewModel>(relaxed = true)
        every {
            vm.uiState
        } returns MutableStateFlow(BookDetailUiState(book = books.first(), chapters = listOf(chapter.copy(title = "第一章 · 山与生活")), isLoading = false))
        var resumed: Pair<Long, Int>? = null
        rule.setContent { FlowTheme { BookDetailScreen(1L, {}, { book, index -> resumed = book to index }, viewModel = vm) } }
        capture("book-detail")
        rule.onNodeWithText(rule.activity.getString(R.string.book_detail_continue_reading)).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1L to -1, resumed) }
    }

    @Test fun independentSearchPreservesBookResultNavigation() {
        val vm = mockk<SearchViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(SearchUiState(query = "山与", bookResults = books.take(1)))
        var opened = 0L
        rule.setContent { FlowTheme { SearchScreen("", {}, { opened = it }, { _, _, _ -> }, vm) } }
        capture("search")
        rule.onNodeWithText("山与生活").performClick()
        rule.runOnIdle { assertEquals(1L, opened) }
    }

    @Test fun readerDirectoryUsesSharedThemeAndKeepsTheActualSectionTarget() {
        val node = TocNode("entry-1", null, 0, "第一章 · 山与生活", TocNodeKind.ENTRY, targetSectionId = "section-1")
        val nodes = listOf(node) + (2..24).map { index ->
            TocNode("entry-$index", null, index - 1, "第${index}章 · 阅读片段", TocNodeKind.ENTRY, targetSectionId = "section-$index")
        }
        val tree = TocTree("fixture", "1", nodes, (1..24).associate { "section-$it" to it - 1 })
        var selected: String? = null
        rule.setContent {
            FlowTheme {
                ReaderTocSheet("山与生活", tree, TocLocation("section-1", "entry-1"), emptySet(), {
                }, emptyList(), emptyList(), listOf(chapter), ReaderPalettes.of(ReaderPaletteId.PAPER), { selected = it }, {}, {}, {})
            }
        }
        rule.waitForIdle()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/clean-ui/reader-directory.png")
        rule.onNodeWithText(node.label).performClick()
        rule.runOnIdle { assertEquals("entry-1", selected) }
    }

    @Test fun advancedTxtImportRetainsExplicitSaveAndImmediateCleaningSwitches() {
        val vm = settingsVm()
        rule.setContent { FlowTheme { TxtSettingsScreen({}, vm) } }
        capture("settings-txt")
        rule.onNodeWithText(rule.activity.getString(R.string.settings_txt_remove_ads)).performClick()
        verify { vm.updateTxtProcessing(match { !it.removeAdLines }) }
        rule.onNodeWithText(rule.activity.getString(R.string.settings_txt_chapter_regex)).performScrollTo().performTextReplacement("第.*章")
        rule.onNodeWithText(rule.activity.getString(R.string.action_save)).performScrollTo().performClick()
        verify { vm.updateTxtProcessing(match { it.customChapterRegex == "第.*章" }) }
    }

    @Test fun advancedReaderBackgroundRemainsCollapsedAndMarksCustomConfiguration() {
        val vm =
            settingsVm(SettingsUiState(isLoading = false, readingSettings = ReadingSettings(backgroundImagePath = "fixture-paper.png")))
        rule.setContent { FlowTheme { ThemeSettingsScreen({}, vm) } }
        capture("settings-theme")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.core.R.string.flow_customized)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.ui_advanced_background)).performScrollTo().performClick()
        capture("settings-theme-expanded")
    }

    @Test fun aboutGroupsCurrentReleaseBeforeProvenanceAndKeepsHistoryCollapsed() {
        rule.setContent { FlowTheme { AboutSettingsScreen({}) } }
        capture("settings-about")
        rule.onNodeWithText(com.flowreader.app.BuildConfig.VERSION_NAME).assertExists()
        rule.onNodeWithText("0.2.17").assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.settings_about_upstream)).performScrollTo().assertIsDisplayed()
    }

    @Test fun shelfHistoryPreservesResumeAndBackCallbacks() {
        val vm = mockk<ContinueViewModel>(relaxed = true)
        every { vm.books } returns MutableStateFlow(books.take(1))
        every { vm.isLoading } returns MutableStateFlow(false)
        var resumed = 0L
        var returned = false
        rule.setContent {
            FlowTheme {
                FlowShellScaffold({ FlowPrimaryNavigation({ it == Screen.Library }, {}) }) {
                    ContinueScreen({}, { resumed = it }, vm, onBack = { returned = true })
                }
            }
        }
        capture("shelf-history")
        rule.onNodeWithText(rule.activity.getString(R.string.continue_action)).performClick()
        rule.runOnIdle { assertEquals(1L, resumed) }
        rule.onNodeWithContentDescription(rule.activity.getString(com.flowreader.core.R.string.flow_action_navigate_up)).performClick()
        rule.runOnIdle { assertTrue(returned) }
    }

    @Test fun screenAndEyeCareKeepsExistingImmediateSettingsCallbacks() {
        val vm = settingsVm()
        rule.setContent { FlowTheme { EyeCareSettingsScreen({}, vm) } }
        capture("settings-eye-care")
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_setting_keep_screen_on)).performClick()
        verify { vm.updateReadingSettings(match { !it.keepScreenOn }) }
    }

    @Test fun hiddenFeaturesStayAbsentInOverflowAndMultiSelection() {
        val vm = mockk<LibraryViewModel>(relaxed = true)
        val state = MutableStateFlow(LibraryUiState(books = books, allBooks = books))
        every { vm.uiState } returns state
        every { vm.isRefreshing } returns MutableStateFlow(false)
        rule.setContent {
            FlowTheme {
                LibraryScreen(onBookClick = {
                }, onContinueReading = {
                }, onSearchClick = {}, onSettingsClick = {}, onNotesClick = {}, onReadingListsClick = {}, onOpdsClick = {}, viewModel = vm)
            }
        }
        rule.onAllNodesWithContentDescription(rule.activity.getString(R.string.action_more)).onFirst().performClick()
        listOf(
            R.string.reading_lists_title,
            R.string.opds_title
        ).forEach { rule.onNodeWithText(rule.activity.getString(it)).assertDoesNotExist() }
        rule.onNodeWithText(rule.activity.getString(R.string.ui_sort)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.ui_shelf_tools)).assertIsDisplayed()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/clean-ui/shelf-menu.png")
        rule.onNodeWithText(rule.activity.getString(R.string.library_sort_title)).performClick()
        verify { vm.updateSortOrder(SortOrder.TITLE) }
        rule.runOnIdle { state.value = state.value.copy(selectedBookIds = setOf(1L, 2L)) }
        rule.onNodeWithText(rule.activity.getString(R.string.batch_add_to_list)).assertDoesNotExist()
        capture("shelf-selection")
    }

    @Test fun lightReadingChromeKeepsTtsBookmarkAndSettingsWorking() = readingChrome(false, "reader-controls-light")

    @Test fun nightReadingChromeKeepsItsExistingColorsAndActions() = readingChrome(true, "reader-controls-night")
    private fun readingChrome(initialNight: Boolean, name: String) {
        var night by mutableStateOf(initialNight)
        var speaking by mutableStateOf(false)
        var bookmarks = 0
        var nextChapterRequests = 0
        var settingsOpen by mutableStateOf(false)
        rule.setContent {
            FlowTheme(AppThemeMode.LIGHT) {
                val palette = ReaderPalettes.of(if (night) ReaderPaletteId.NIGHT else ReaderPaletteId.PAPER, AppVisualStyle.CLEAN_BLUE)
                val settings = ReadingSettings(palette = palette.id, fontFamily = ReaderFontFamily.SERIF)
                Box(Modifier.fillMaxSize().background(palette.background)) {
                    PagedReader(
                        chapter, 0, settings, palette, rememberReaderFontFamily(settings), emptyList(), 0, { _, _ -> }, {}, {},
                        onHighlightSelection = { _, _, _ -> }, onBookmarkSelection = { _, _, _ -> }, onImageClick = { _, _ -> }, paginationDispatcher = Dispatchers.Main.immediate
                    )
                    ReaderControls(
                        visible = true, bookTitle = "山与生活", chapterTitle = chapter.title, currentChapter = 1, totalChapters = 24,
                        progressProvider = { .42f }, isTtsPlaying = speaking, palette = palette, chapterTitleAt = { chapter.title },
                        onBackClick = {}, onPreviousChapterClick = {}, onNextChapterClick = { nextChapterRequests++ }, onChapterClick = {}, onBookmarkClick = {},
                        onAddBookmark = { bookmarks++ }, onTtsClick = { speaking = !speaking }, onAnnotationClick = {}, onSearchClick = {},
                        onImmersiveClick = {}, onShareClick = {}, onSettingsClick = { settingsOpen = true }, onNightModeToggle = { night = !night },
                        onProgressCommit = {}, onDismiss = {}
                    )
                    if (settingsOpen) ReaderSettingsSheet(settings, {}, {}, {}, {}, {}, { settingsOpen = false })
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText(chapter.title).fetchSemanticsNodes().isNotEmpty() }
        capture(name)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_tts_play)).performClick()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_tts_pause)).assertIsDisplayed()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_bookmark_add)).performClick()
        rule.runOnIdle { assertEquals(1, bookmarks) }
        rule.onNodeWithText(rule.activity.getString(R.string.action_more)).assertDoesNotExist()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_next_chapter)).performClick()
        rule.runOnIdle { assertEquals(1, nextChapterRequests) }
        rule.onNodeWithText(rule.activity.getString(R.string.settings_title)).performClick()
        rule.onNode(
            hasText(rule.activity.getString(R.string.reader_tab_font)) and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, true)
        ).assertIsDisplayed()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/clean-ui/$name-panel.png")
    }

    @Test fun noteEditorSavesOnlyOnConfirmationAndPreservesTheOriginalPassage() {
        val annotation =
            Annotation(id = 1, bookId = 1, chapterIndex = 0, startPosition = 5, endPosition = 9, selectedText = "选中原文", note = "旧笔记")
        val vm = mockk<NotesViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(NotesUiState(isLoading = false, notes = listOf(NoteItem(annotation, "山与生活"))))
        rule.setContent { FlowTheme { NotesScreen({}, { _, _ -> }, vm) } }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_more)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.reader_selection_note)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.reader_selection_note)).assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("新的想法")
        rule.mainClock.advanceTimeByFrame()
        rule.onNode(hasSetTextAction() and hasText("新的想法")).assertExists()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/clean-ui/note-editor.png")
        rule.onNodeWithText(rule.activity.getString(R.string.action_save)).performClick()
        verify { vm.updateNote(annotation, "新的想法") }
    }

    @Test fun bookmarkEntryOpensTheSharedTabAndDeletionRequiresConfirmation() {
        val node = TocNode("entry", null, 0, "第一章", TocNodeKind.ENTRY, targetSectionId = "section")
        val tree = TocTree("fixture", "1", listOf(node), mapOf("section" to 0))
        val bookmark = Bookmark(id = 1, bookId = 1, chapterIndex = 0, position = 0, text = "书签原文")
        var deleted = 0L
        rule.setContent {
            FlowTheme {
                ReaderTocSheet(
                    "山与生活", tree, TocLocation("section", "entry"), emptySet(), {}, listOf(bookmark), emptyList(), listOf(chapter),
                    ReaderPalettes.Paper, {}, {}, {}, {}, initialTab = 1, onBookmarkDelete = { deleted = it.id }
                )
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.reader_toc_tab_bookmarks)).assertIsSelected()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/clean-ui/reader-bookmarks.png")
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_delete)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.action_cancel)).performClick()
        rule.runOnIdle { assertEquals(0L, deleted) }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_delete)).performClick()
        rule.onNode(hasText(rule.activity.getString(R.string.action_delete)) and hasClickAction()).performClick()
        rule.runOnIdle { assertEquals(1L, deleted) }
    }

    @Test fun annotationEntryOpensTheSharedNotesTabAndKeepsItsEditCallback() {
        val node = TocNode("entry", null, 0, "第一章", TocNodeKind.ENTRY, targetSectionId = "section")
        val tree = TocTree("fixture", "1", listOf(node), mapOf("section" to 0))
        val annotation =
            Annotation(id = 1, bookId = 1, chapterIndex = 0, startPosition = 0, endPosition = 4, selectedText = "原文摘录", note = "旧注释")
        var saved: Pair<Long, String>? = null
        rule.setContent {
            FlowTheme {
                ReaderTocSheet(
                    "山与生活", tree, TocLocation("section", "entry"), emptySet(), {}, emptyList(), listOf(annotation), listOf(chapter),
                    ReaderPalettes.Paper, {}, {}, {}, {}, initialTab = 2, onAnnotationNoteUpdate = { id, note -> saved = id to note }
                )
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.reader_toc_tab_notes)).assertIsSelected()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/clean-ui/reader-notes.png")
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_selection_note)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.reader_selection_note)).assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("新的注释")
        rule.mainClock.advanceTimeByFrame()
        rule.onNode(hasSetTextAction() and hasText("新的注释")).assertExists()
        rule.onNode(hasText(rule.activity.getString(R.string.action_save)) and hasClickAction()).performClick()
        rule.runOnIdle { assertEquals(1L to "新的注释", saved) }
    }

    @Test fun notesTabsKeepHighlightsAndSourceActionsReachable() {
        val vm = mockk<NotesViewModel>(relaxed = true)
        val note = Annotation(
            id = 20,
            bookId = 1,
            chapterIndex = 0,
            startPosition = 0,
            endPosition = 2,
            selectedText = "带批注的原文",
            note = "自己的想法"
        )
        val highlight = note.copy(id = 21, selectedText = "仅划线的原文", note = "")
        every { vm.uiState } returns MutableStateFlow(
            NotesUiState(
                isLoading = false,
                notes = listOf(NoteItem(note, "山与生活"), NoteItem(highlight, "山与生活"))
            )
        )
        var source: Pair<Long, Int>? = null
        rule.setContent { FlowTheme(AppThemeMode.LIGHT) { NotesScreen({}, { book, chapter -> source = book to chapter }, vm) } }
        rule.onNodeWithText(note.selectedText).assertIsDisplayed()
        rule.onNodeWithText(highlight.selectedText).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.notes_tab_highlights)).performClick().assertIsSelected()
        rule.onNodeWithText(highlight.selectedText).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1L to 0, source) }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_more)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.reader_selection_note)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.action_cancel)).performClick()
        verify(exactly = 0) { vm.updateNote(any(), any()) }
        capture("notes-highlights")
    }

    @Test fun compactAppearancePreservesCustomValuesUntilAPresetIsChosen() {
        var settings by mutableStateOf(ReadingSettings(horizontalMarginDp = 22, paragraphSpacing = 1.2f, lineSpacing = 1.65f))
        rule.setContent { FlowTheme(AppThemeMode.LIGHT) { ReaderSettingsSheet(settings, { settings = it }, {}, {}, {}, {}, {}) } }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(22, settings.horizontalMarginDp)
            assertEquals(1.2f, settings.paragraphSpacing)
            assertEquals(1.65f, settings.lineSpacing)
        }
        rule.onNodeWithText(rule.activity.getString(R.string.reader_font_serif_short)).performClick().assertIsSelected()
        rule.onNodeWithContentDescription(
            rule.activity.getString(R.string.reader_setting_margin_value, 28)
        ).performClick().assertIsSelected()
        rule.onNodeWithContentDescription(
            rule.activity.getString(R.string.reader_setting_para_spacing_value, "1.5")
        ).performClick().assertIsSelected()
        rule.onNodeWithContentDescription(ReaderPaletteId.GREEN.displayName).performClick()
        rule.runOnIdle {
            assertEquals(ReaderFontFamily.SERIF, settings.fontFamily)
            assertEquals(28, settings.horizontalMarginDp)
            assertEquals(1.5f, settings.paragraphSpacing)
            assertEquals(1.65f, settings.lineSpacing)
            assertEquals(ReaderPaletteId.GREEN, settings.palette)
        }
    }

    @Test fun importMethodSheetUsesExistingFileAndScanCallbacks() {
        var files = 0
        var archive = 0
        var scan = 0
        rule.setContent { FlowTheme(AppThemeMode.LIGHT) { ShelfImportSheet({}, { files++ }, { archive++ }, { scan++ }) } }
        rule.waitForIdle()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/clean-ui/import-methods.png")
        rule.onNodeWithText(rule.activity.getString(R.string.library_import_files)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.settings_scan_folder)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.library_import_archive)).performClick()
        rule.runOnIdle {
            assertEquals(1, files)
            assertEquals(1, scan)
            assertEquals(1, archive)
        }
    }

    @Test fun noteAndHighlightExportsFollowTheActiveTabWithoutLosingSourceTitles() {
        val annotated = Annotation(
            id = 1,
            bookId = 1,
            chapterIndex = 0,
            startPosition = 0,
            endPosition = 2,
            selectedText = "带有笔记的原文",
            note = "个人想法"
        )
        val highlight = annotated.copy(id = 2, selectedText = "单独划线的原文", note = "")
        val blankNote = annotated.copy(id = 3, selectedText = "空批注笔记", note = "", type = AnnotationType.NOTE)
        val annotations = mockk<com.flowreader.app.domain.repository.AnnotationRepository>(relaxed = true)
        val library = mockk<com.flowreader.app.domain.repository.BookRepository>(relaxed = true)
        every { annotations.getAllAnnotations() } returns MutableStateFlow(listOf(annotated, highlight, blankNote))
        every { library.getAllBooks() } returns MutableStateFlow(books.take(1))
        lateinit var vm: NotesViewModel
        rule.runOnUiThread { vm = NotesViewModel(annotations, library) }
        rule.waitUntil(10_000) { !vm.uiState.value.isLoading }
        rule.runOnIdle {
            vm.exportVisible(com.flowreader.app.domain.repository.AnnotationExportFormat.TEXT, true)
            assertEquals(1, vm.uiState.value.exportedCount)
            assertTrue(vm.uiState.value.exportText.orEmpty().contains(highlight.selectedText))
            assertFalse(vm.uiState.value.exportText.orEmpty().contains(annotated.selectedText))
            assertTrue(vm.uiState.value.exportText.orEmpty().contains("山与生活"))
            vm.clearExport()
            vm.exportVisible(com.flowreader.app.domain.repository.AnnotationExportFormat.TEXT, false)
            assertEquals(2, vm.uiState.value.exportedCount)
            assertTrue(vm.uiState.value.exportText.orEmpty().contains(blankNote.selectedText))
            assertFalse(vm.uiState.value.exportText.orEmpty().contains(highlight.selectedText))
        }
    }

    @Test fun readerUnderlineLightKeepsTheTextAndPagination() = annotationPreview(
        ReaderPaletteId.PAPER,
        AnnotationDisplayStyle.UNDERLINE,
        "annotation-underline-light"
    )

    @Test fun readerUnderlineNightKeepsTheExistingNightPalette() = annotationPreview(
        ReaderPaletteId.NIGHT,
        AnnotationDisplayStyle.UNDERLINE,
        "annotation-underline-night"
    )

    @Test fun readerSoftHighlightIsMuted() = annotationPreview(
        ReaderPaletteId.PAPER,
        AnnotationDisplayStyle.SOFT_HIGHLIGHT,
        "annotation-soft-highlight"
    )

    private fun annotationPreview(paletteId: ReaderPaletteId, style: AnnotationDisplayStyle, name: String) {
        val text = "乌云黑压压的，堆积在空中，大地一片昏暗，一场排山倒海般的倾盆暴雨，即将袭来。\n\n山河破碎，万马齐喑。一群人急匆匆的，往前方的破庙赶去。\n\n周围的山川，显得杂乱无章，就像是造物主在布置这片山川时，用心留下了一段故事。"
        val selected = "山河破碎，万马齐喑。一群人急匆匆的，往前方的破庙赶去。"
        val start = text.indexOf(selected)
        rule.setContent {
            FlowTheme(if (paletteId == ReaderPaletteId.NIGHT) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                PagedReader(
                    Chapter(bookId = 1, index = 0, title = "第一章 · 雨夜乱桃山", content = text), 0,
                    ReadingSettings(fontSize = 22, annotationDisplayStyle = style), ReaderPalettes.of(paletteId),
                    androidx.compose.ui.text.font.FontFamily.Default,
                    listOf(
                        Annotation(
                            bookId = 1,
                            chapterIndex = 0,
                            startPosition = start,
                            endPosition = start + selected.length,
                            selectedText = selected
                        )
                    ),
                    0, { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> }, onBookmarkSelection = { _, _, _ -> },
                    onImageClick = { _, _ -> }, paginationDispatcher = Dispatchers.Main.immediate
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/reports/annotation-location/$name.png")
        rule.onNode(hasText(selected, substring = true)).assertIsDisplayed()
    }

    @Test fun fileLocationLinksAndCopiesWithoutChangingTheReadingCopy() {
        var copied = ""
        var opened = 0
        var linked = 0
        var saved = 0
        val path = "/data/user/0/com.flowreader.app/files/books/山与生活.txt"
        rule.setContent {
            FlowTheme {
                androidx.compose.material3.Surface {
                    BookFileLocationContent(
                        path, "/storage/emulated/0/Download/山与生活.txt", null, true, false, null, true,
                        { copied = it }, { opened++ }, {}, { linked++ }, { saved++ }
                    )
                }
            }
        }
        rule.onRoot().captureRoboImage("build/reports/annotation-location/file-location-known.png")
        rule.onAllNodesWithText(rule.activity.getString(R.string.book_file_copy_location)).onFirst().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_open_directory)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_associate)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_save_copy)).performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals("/storage/emulated/0/Download/山与生活.txt", copied)
            assertEquals(1, opened)
            assertEquals(1, linked)
            assertEquals(1, saved)
        }
    }

    @Test fun oldBookLocationExplainsPrivateStorageAndOffersAssociation() {
        rule.setContent {
            FlowTheme(AppThemeMode.DARK) {
                androidx.compose.material3.Surface {
                    BookFileLocationContent(
                        "/data/user/0/com.flowreader.app/files/books/山与生活.txt", null, null, true, false, null, false,
                        {}, {}, {}, {}, {}
                    )
                }
            }
        }
        rule.onRoot().captureRoboImage("build/reports/annotation-location/file-location-old-night.png")
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_open_directory)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_associate)).assertIsEnabled()
    }

    @Test fun bookDetailOpensTheRealFileLocationSheet() {
        val vm = mockk<BookDetailViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(BookDetailUiState(book = books.first(), isLoading = false))
        rule.setContent { FlowTheme { BookDetailScreen(1, {}, { _, _ -> }, viewModel = vm) } }
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_location)).performScrollTo()
        rule.onRoot().captureRoboImage("build/reports/annotation-location/book-detail-entry.png")
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_location)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_reading_copy)).assertIsDisplayed()
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/annotation-location/file-location-sheet.png")
    }

    @Test fun annotationAppearanceControlsUseTheSharedSettingsAndKeepOtherFields() {
        var settings by mutableStateOf(ReadingSettings(fontSize = 25, pageMode = PageMode.SLIDE))
        rule.setContent { FlowTheme { ReaderSettingsSheet(settings, { settings = it }, {}, {}, {}, {}, {}) } }
        rule.onNode(hasText("主题") and hasClickAction()).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.reader_soft_highlight)).performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(AnnotationDisplayStyle.SOFT_HIGHLIGHT, settings.annotationDisplayStyle)
            assertEquals(25, settings.fontSize)
            assertEquals(PageMode.SLIDE, settings.pageMode)
        }
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/annotation-location/annotation-settings.png")
    }

    @Test fun fileLocationSheetReadsTheRecordedSourceAndCopiesToTheClipboard() {
        val book = books.first().copy(filePath = "/data/user/0/com.flowreader.app/files/books/山与生活.txt")
        // A non-primary document provider also exercises the system-browser fallback without
        // displaying Windows-host temp directories in the native UI fixture.
        val source = android.net.Uri.parse("content://local-files/document/山与生活.txt")
        val store = com.flowreader.app.util.BookFileLocationStore(rule.activity)
        store.recordOriginal(book.filePath, source)
        val path = store.displayLocation(source)
        val vm = mockk<BookDetailViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(BookDetailUiState(book = book, isLoading = false))
        rule.setContent { FlowTheme { BookDetailScreen(1, {}, { _, _ -> }, viewModel = vm) } }
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_location)).performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(path).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(rule.activity.getString(R.string.book_file_copy_location)).onFirst().performClick()
        rule.runOnIdle {
            val clipboard = rule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals(path, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(source, store.sources(book.filePath).original)
        }
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/reports/annotation-location/file-location-source-sheet.png")
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_open_directory)).performClick()
        rule.runOnIdle {
            val intent = org.robolectric.Shadows.shadowOf(rule.activity).nextStartedActivity
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, intent.action)
            assertEquals(source, intent.getParcelableExtra<android.net.Uri>(android.provider.DocumentsContract.EXTRA_INITIAL_URI))
        }
    }

    @Config(sdk = [34], qualifiers = "zh-rCN-w320dp-h760dp-420dpi")
    @Test
    fun fileLocationWorksOnANarrowScreenWithLargeTextAndLongPaths() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.4f)) {
                FlowTheme {
                    androidx.compose.material3.Surface {
                        BookFileLocationContent(
                            "/data/user/0/com.flowreader.app/files/books/很长的书名与作者信息以及原始导入的文件名称.txt",
                            "/storage/emulated/0/Download/文学与生活/很长的书名与作者信息以及原始导入的文件名称.txt",
                            null, true, false, null, true, {}, {}, {}, {}, {}
                        )
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/reports/annotation-location/file-location-narrow-large.png")
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_open_directory)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_save_copy)).performScrollTo().assertIsEnabled()
    }

    @Test fun savedCopyKeepsItsOwnLocationAndBusyOperationsDisableButtons() {
        rule.setContent {
            FlowTheme {
                androidx.compose.material3.Surface {
                    BookFileLocationContent(
                        "private.txt", null, "/storage/emulated/0/Documents/山与生活.txt",
                        true, true, null, false, {}, {}, {}, {}, {}
                    )
                }
            }
        }
        rule.onRoot().captureRoboImage("build/reports/annotation-location/file-location-saved-copy.png")
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_open_directory)).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(rule.activity.getString(R.string.book_file_associate)).assertIsNotEnabled()
    }
}
