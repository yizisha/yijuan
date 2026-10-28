package com.flowreader.app.ui.screens.reader.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.flowreader.app.core.designsystem.reader.ReaderPalettes
import com.flowreader.app.domain.model.*
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val palette = ReaderPalettes.of(ReaderPaletteId.PAPER)
    private val longChapter =
        Chapter(bookId = 1, index = 0, title = "Chapter", content = List(40) { "Reading paragraph $it. ".repeat(12) }.joinToString("\n\n"))

    @Test fun pagedLongPressShowsSelectionAndCopiesTheSelectedText() {
        val chapter = Chapter(bookId = 1, index = 0, title = "Selection", content = "Before selected after.")
        var taps = 0
        var clipboard: androidx.compose.ui.platform.ClipboardManager? = null
        rule.setContent {
            MaterialTheme {
                clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                PagedReader(
                    chapter, 0, ReadingSettings(), palette, FontFamily.Default, emptyList(), 0,
                    { _, _ -> }, { taps++ }, {}, onHighlightSelection = { _, _, _ -> },
                    onBookmarkSelection = { _, _, _ -> }, onImageClick = { _, _ -> },
                    paginationDispatcher = Dispatchers.Main.immediate
                )
            }
        }
        val paragraph = rule.onNode(hasText("Before selected after.", substring = true))
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        paragraph.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val position = layout.getBoundingBox(layout.layoutInput.text.text.indexOf("selected") + 2).center
        paragraph.performTouchInput { longClick(position) }
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_copy)).assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals("selected", clipboard?.getText()?.text)
            assertEquals(0, taps)
        }
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_copy)).assertDoesNotExist()
    }

    private fun longPressWord(paragraph: String, word: String) {
        val node = rule.onNode(hasText(paragraph, substring = true))
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val position = layout.getBoundingBox(layout.layoutInput.text.text.indexOf(word) + 1).center
        node.performTouchInput { longClick(position) }
    }

    @Test fun pagedSelectionHighlightsTheExactSourceRangeAndTapsResumeAfterClearing() {
        val text = "Before selected after."
        var saved: Triple<String, Int, Int>? = null
        var taps = 0
        rule.setContent {
            MaterialTheme {
                PagedReader(
                    Chapter(bookId = 1, index = 0, title = "Selection", content = text), 0,
                    ReadingSettings(), palette, FontFamily.Default, emptyList(), 0,
                    { _, _ -> }, { taps++ }, {},
                    onHighlightSelection = { chosen, start, end -> saved = Triple(chosen, start, end) },
                    onBookmarkSelection = { _, _, _ -> }, onImageClick = { _, _ -> },
                    paginationDispatcher = Dispatchers.Main.immediate
                )
            }
        }
        longPressWord(text, "selected")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_underline)).performClick()
        rule.runOnIdle { assertEquals(Triple("selected", text.indexOf("selected"), text.indexOf("selected") + 8), saved) }
        rule.onNode(hasText(text, substring = true)).performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(1, taps) }
    }

    @Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
    @Test fun pagedSelectionCanWriteANoteAndCancelWithoutSaving() {
        val text = "Before selected after."
        var saved: List<Any>? = null
        rule.setContent {
            MaterialTheme {
                PagedReader(
                    Chapter(bookId = 1, index = 0, title = "Selection", content = text), 0,
                    ReadingSettings(), palette, FontFamily.Default, emptyList(), 0,
                    { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                    onBookmarkSelection = { _, _, _ -> }, onImageClick = { _, _ -> },
                    onNoteSelection = { chosen, start, end, note -> saved = listOf(chosen, start, end, note) },
                    paginationDispatcher = Dispatchers.Main.immediate
                )
            }
        }
        longPressWord(text, "selected")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_note)).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("A discarded thought")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.action_cancel)).performClick()
        rule.runOnIdle { assertNull(saved) }
        longPressWord(text, "selected")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_note)).performClick()
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.action_save)).assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("My reading note")
        val roots = rule.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().lastIndex].captureRoboImage("build/reports/reader-selection-note-dialog.png")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.action_save)).performClick()
        rule.runOnIdle {
            assertEquals(
                listOf("selected", text.indexOf("selected"), text.indexOf("selected") + 8, "My reading note"),
                saved
            )
        }
    }

    @Test fun scrollingSelectionRegistersParagraphsAndKeepsTheGestureThroughRecomposition() {
        val text = "Before selected after.\n\nAnother paragraph follows."
        var copied: androidx.compose.ui.platform.ClipboardManager? = null
        rule.setContent {
            MaterialTheme {
                copied = androidx.compose.ui.platform.LocalClipboardManager.current
                ReaderContent(
                    Chapter(bookId = 1, index = 0, title = "Selection", content = text),
                    ReadingSettings(pageMode = PageMode.SLIDE), palette, FontFamily.Default, rememberScrollState(),
                    { _, _ -> }, {}, {}, { _, _, _ -> }, { _, _, _ -> }, {}
                )
            }
        }
        longPressWord("Before selected after.", "selected")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_copy)).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals("selected", copied?.getText()?.text) }
    }

    @Test fun pagedHandleDragSurvivesRecompositionAndDoesNotTurnThePage() {
        val text = "Before selected after."
        var page = -1
        var copied: androidx.compose.ui.platform.ClipboardManager? = null
        rule.setContent {
            MaterialTheme {
                copied = androidx.compose.ui.platform.LocalClipboardManager.current
                PagedReader(
                    Chapter(bookId = 1, index = 0, title = "Selection", content = text), 0,
                    ReadingSettings(), palette, FontFamily.Default, emptyList(), 0,
                    { position, _ -> page = position }, {}, {}, onHighlightSelection = { _, _, _ -> },
                    onBookmarkSelection = { _, _, _ -> }, onImageClick = { _, _ -> },
                    paginationDispatcher = Dispatchers.Main.immediate
                )
            }
        }
        longPressWord(text, "selected")
        val node = rule.onNode(hasText(text, substring = true))
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val end = layout.getBoundingBox(layout.layoutInput.text.text.indexOf("selected") + 7).bottomRight
        val firstMove = layout.getBoundingBox(layout.layoutInput.text.text.indexOf("after") + 1).center
        val nextMove = layout.getBoundingBox(layout.layoutInput.text.text.indexOf("after") + 4).center
        node.performTouchInput {
            down(end)
            moveTo(firstMove)
        }
        rule.waitForIdle()
        node.performTouchInput {
            moveTo(nextMove)
            up()
        }
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_copy)).performClick()
        rule.runOnIdle {
            assertTrue(copied?.getText()?.text?.startsWith("selected aft") == true)
            assertEquals(0, page)
        }
    }

    @Test fun scrollingNoteUsesItsRegisteredSourceRange() {
        val text = "Before selected after."
        var saved: MultiBlockSelection? = null
        var comment: String? = null
        rule.setContent {
            MaterialTheme {
                ReaderContent(
                    Chapter(bookId = 1, index = 0, title = "Selection", content = text),
                    ReadingSettings(pageMode = PageMode.SLIDE), palette, FontFamily.Default, rememberScrollState(),
                    { _, _ -> }, {}, {}, { _, _, _ -> }, { _, _, _ -> }, {},
                    onNoteRanges = { selection, note ->
                        saved = selection
                        comment = note
                    }
                )
            }
        }
        longPressWord(text, "selected")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_note)).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("A source-aligned note")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.action_save)).performClick()
        rule.runOnIdle {
            assertEquals("selected", saved?.text)
            assertEquals(text.indexOf("selected"), saved?.ranges?.single()?.startUtf16)
            assertEquals(text.indexOf("selected") + 8, saved?.ranges?.single()?.endUtf16Exclusive)
            assertEquals("A source-aligned note", comment)
        }
    }

    @Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
    @Test fun nightSelectionShowsReadableNoteControlsAndSaves() {
        val text = "Before selected after."
        var comment: String? = null
        rule.setContent {
            MaterialTheme {
                PagedReader(
                    Chapter(bookId = 1, index = 0, title = "Selection", content = text), 0,
                    ReadingSettings(palette = ReaderPaletteId.NIGHT), ReaderPalettes.Night, FontFamily.Default, emptyList(), 0,
                    { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                    onBookmarkSelection = { _, _, _ -> }, onImageClick = { _, _ -> },
                    onNoteSelection = { _, _, _, note -> comment = note },
                    paginationDispatcher = Dispatchers.Main.immediate
                )
            }
        }
        longPressWord(text, "selected")
        val menuRoots = rule.onAllNodes(isRoot())
        menuRoots[menuRoots.fetchSemanticsNodes().lastIndex].captureRoboImage("build/reports/reader-selection-night-menu.png")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_note)).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("A thought in the night")
        val roots = rule.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().lastIndex].captureRoboImage("build/reports/reader-selection-night-note-dialog.png")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.action_save)).performClick()
        rule.runOnIdle { assertEquals("A thought in the night", comment) }
    }

    @Test fun pagedSelectionCanCreateABookmarkFromTheExactText() {
        val text = "Before selected after."
        var saved: Triple<String, Int, Int>? = null
        rule.setContent {
            MaterialTheme {
                PagedReader(
                    Chapter(bookId = 1, index = 0, title = "Selection", content = text), 0,
                    ReadingSettings(), palette, FontFamily.Default, emptyList(), 0,
                    { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                    onBookmarkSelection = { chosen, start, end -> saved = Triple(chosen, start, end) },
                    onImageClick = { _, _ -> }, paginationDispatcher = Dispatchers.Main.immediate
                )
            }
        }
        longPressWord(text, "selected")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_bookmark)).performClick()
        rule.runOnIdle { assertEquals(Triple("selected", text.indexOf("selected"), text.indexOf("selected") + 8), saved) }
    }

    @Test fun pagedSelectionCanExtendAcrossParagraphsWithOneActionBar() = crossParagraphCopy(paged = true)

    @Test fun scrollingSelectionCanExtendAcrossParagraphsWithOneActionBar() = crossParagraphCopy(paged = false)

    private fun crossParagraphCopy(paged: Boolean) {
        val first = "Before selected after."
        val second = "Another paragraph follows."
        val chapter = Chapter(bookId = 1, index = 0, title = "Selection", content = "$first\n\n$second")
        var copied: androidx.compose.ui.platform.ClipboardManager? = null
        rule.setContent {
            MaterialTheme {
                copied = androidx.compose.ui.platform.LocalClipboardManager.current
                if (paged) {
                    PagedReader(
                        chapter, 0, ReadingSettings(), palette, FontFamily.Default, emptyList(), 0,
                        { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                        onBookmarkSelection = { _, _, _ -> }, onImageClick = { _, _ -> },
                        paginationDispatcher = Dispatchers.Main.immediate
                    )
                } else {
                    ReaderContent(
                        chapter, ReadingSettings(pageMode = PageMode.SLIDE), palette, FontFamily.Default,
                        rememberScrollState(), { _, _ -> }, {}, {}, { _, _, _ -> }, { _, _, _ -> }, {}
                    )
                }
            }
        }
        longPressWord(first, "selected")
        val from = rule.onNode(hasText(first, substring = true))
        val to = rule.onNode(hasText(second, substring = true))
        val fromLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val toLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        from.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(fromLayouts) }
        to.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(toLayouts) }
        val origin = fromLayouts.single().getBoundingBox(fromLayouts.single().layoutInput.text.text.indexOf("selected") + 7).bottomRight
        val target = to.fetchSemanticsNode().boundsInRoot.topLeft - from.fetchSemanticsNode().boundsInRoot.topLeft +
            toLayouts.single().getBoundingBox(toLayouts.single().layoutInput.text.text.indexOf("paragraph") + 3).center
        from.performTouchInput {
            down(origin)
            moveTo(target)
        }
        rule.waitForIdle()
        from.performTouchInput { up() }
        rule.onAllNodesWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_copy)).assertCountEquals(1)
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_copy)).performClick()
        rule.runOnIdle {
            val result = copied?.getText()?.text.orEmpty()
            assertTrue(result, result.startsWith("selected after."))
            assertTrue(result, result.contains("Another"))
        }
    }

    @Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
    @Test fun chineseTextCanBeCopiedAndSavedAsANoteWithoutOffsetLoss() {
        val text = "清晨的山谷安静温柔，我们沿着小路前行。"
        var copied: androidx.compose.ui.platform.ClipboardManager? = null
        var saved: List<Any>? = null
        rule.setContent {
            MaterialTheme {
                copied = androidx.compose.ui.platform.LocalClipboardManager.current
                PagedReader(Chapter(bookId = 1, index = 0, title = "山与生活", content = text), 0,
                    ReadingSettings(), palette, FontFamily.Default, emptyList(), 0,
                    { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                    onBookmarkSelection = { _, _, _ -> }, onImageClick = { _, _ -> },
                    onNoteSelection = { chosen, start, end, note -> saved = listOf(chosen, start, end, note) },
                    paginationDispatcher = Dispatchers.Main.immediate)
            }
        }
        longPressWord(text, "山谷")
        val menuRoots = rule.onAllNodes(isRoot())
        menuRoots[menuRoots.fetchSemanticsNodes().lastIndex].captureRoboImage("build/reports/reader-selection-chinese-menu.png")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_copy)).performClick()
        rule.runOnIdle {
            val chosen = copied?.getText()?.text.orEmpty()
            assertTrue(chosen, chosen.isNotBlank() && text.contains(chosen))
        }
        longPressWord(text, "山谷")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_selection_note)).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("把这一刻记下来。")
        val roots = rule.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().lastIndex].captureRoboImage("build/reports/reader-selection-chinese-note-dialog.png")
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.action_save)).performClick()
        rule.runOnIdle {
            val result = saved!!
            assertEquals(text.substring(result[1] as Int, result[2] as Int), result[0])
            assertEquals("把这一刻记下来。", result[3])
        }
    }

    @Test fun selectionPopupStaysInsideTheWindowAtEdgesAndUsesItsMeasuredSize() {
        val window = androidx.compose.ui.unit.IntSize(400, 800)
        val popup = androidx.compose.ui.unit.IntSize(280, 70)
        assertEquals(androidx.compose.ui.unit.IntOffset(0, 48), selectionBarPosition(Offset(2f, 10f), Offset(20f, 40f), window, popup, 8))
        assertEquals(
            androidx.compose.ui.unit.IntOffset(120, 672),
            selectionBarPosition(Offset(370f, 750f), Offset(399f, 790f), window, popup, 8)
        )
    }

    @Test fun settingsSheetPageTurnButtonsChangeModesAnimationsZonesAndVolumeKeys() {
        var settings by mutableStateOf(ReadingSettings())
        rule.setContent {
            MaterialTheme {
                ReaderSettingsSheet(settings, { settings = it }, {}, {}, {}, {}, {})
            }
        }
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_tab_page)).performClick()
        for (mode in listOf(PageMode.SLIDE, PageMode.NONE, PageMode.PAGED)) {
            rule.onNodeWithText(mode.displayName).performClick().assertIsSelected()
            rule.runOnIdle { assertEquals(mode, settings.pageMode) }
        }
        for (animation in listOf(PageTurnAnimation.COVER, PageTurnAnimation.SLIDE)) {
            rule.onNode(hasText(animation.displayName) and hasClickAction()).performClick().assertIsSelected()
            rule.runOnIdle { assertEquals(animation, settings.pageTurnAnimation) }
        }
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.ui_advanced_gestures)).performScrollTo().performClick()
        for (zone in TapZoneMode.entries) {
            rule.onNode(hasText(zone.displayName) and hasClickAction()).performScrollTo().performClick().assertIsSelected()
            rule.runOnIdle { assertEquals(zone, settings.gestureSettings.tapZoneMode) }
        }
        rule.onNodeWithContentDescription(
            rule.activity.getString(com.flowreader.app.R.string.reader_setting_volume_key)
        ).performScrollTo().assertIsOn().performClick().assertIsOff()
        rule.runOnIdle { assertFalse(settings.volumeKeyTurnPage) }
        rule.onNodeWithContentDescription(
            rule.activity.getString(com.flowreader.app.R.string.reader_setting_volume_key)
        ).performClick().assertIsOn()
        rule.runOnIdle { assertTrue(settings.volumeKeyTurnPage) }
        val roots = rule.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().lastIndex].captureRoboImage("build/reports/reader-page-turn-settings.png")
    }

    @Test fun changingFromPagedToSlideActuallyMountsAScrollableReaderAndCanReturn() {
        var settings by mutableStateOf(ReadingSettings())
        var showSettings by mutableStateOf(true)
        var position = 0
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    key(settings.pageMode) {
                        if (settings.pageMode == PageMode.PAGED) {
                            PagedReader(
                                longChapter, 0, settings, palette, FontFamily.Default, emptyList(), 0,
                                { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                                onBookmarkSelection = { _, _, _ -> }, onImageClick = { _, _ -> },
                                modifier = Modifier.testTag("paged"), paginationDispatcher = Dispatchers.Main.immediate
                            )
                        } else {
                            ReaderContent(
                                longChapter, settings, palette, FontFamily.Default, rememberScrollState(),
                                { _, _ -> }, {}, {}, { _, _, _ -> }, { _, _, _ -> }, { position = it },
                                modifier = Modifier.testTag("scroll")
                            )
                        }
                    }
                    if (showSettings) ReaderSettingsSheet(settings, { settings = it }, {}, {}, {}, {}, { showSettings = false })
                }
            }
        }
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_tab_page)).performClick()
        rule.onNodeWithText(PageMode.SLIDE.displayName).performClick().assertIsSelected()
        rule.runOnIdle { showSettings = false }
        rule.onNodeWithTag("paged").assertDoesNotExist()
        rule.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        rule.runOnIdle { assertTrue("The selected vertical reader must actually scroll", position > 0) }
        rule.runOnIdle { showSettings = true }
        rule.onNodeWithText(rule.activity.getString(com.flowreader.app.R.string.reader_tab_page)).performClick()
        rule.onNodeWithText(PageMode.PAGED.displayName).performClick().assertIsSelected()
        rule.runOnIdle { showSettings = false }
        rule.onNodeWithTag("paged").assertExists()
        rule.onNodeWithTag("scroll").assertDoesNotExist()
    }

    @Test fun aTemporarilyUnavailableNeighbourCannotBeSkippedByAheadPrefetch() {
        val chapters = List(5) { Chapter(bookId = 1, index = 10 + it * 3, title = "Missing $it", content = "Body $it.") }
        val cache = ReaderPageCache()
        var currentChapter by mutableIntStateOf(1)
        var unavailable = true
        rule.setContent {
            MaterialTheme {
                PagedReader(
                    chapters[currentChapter], currentChapter, ReadingSettings(), palette, FontFamily.Default,
                    emptyList(), 0, { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                    onBookmarkSelection = { _, _, _ -> }, totalChapters = chapters.size,
                    onChapterPageChanged = { prepared, _, _ -> currentChapter = prepared.chapterIndex },
                    pageCache = cache, bookId = 1, loadChapterForPagination = {
                        if (it == 2 && unavailable) { unavailable = false; null } else chapters.getOrNull(it)
                    },
                    onImageClick = { _, _ -> }, modifier = Modifier.testTag("paged"), paginationDispatcher = Dispatchers.Main.immediate
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Missing 1").assertIsDisplayed()
        rule.onNodeWithTag("paged").performTouchInput { click(Offset(width * .9f, height * .5f)) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("Next must enter the immediate neighbour, never the ahead-prefetched chapter", 2, currentChapter) }
        rule.onNodeWithText("Missing 2").assertIsDisplayed()
    }

    @Test fun explicitChapterJumpKeepsTheRequestedChapterWhenCachedNeighboursArrive() {
        val chapters = List(10) { Chapter(bookId = 1, index = it * 3, title = "Jump $it", content = "Body $it.") }
        val cache = ReaderPageCache()
        var currentChapter by mutableIntStateOf(0)
        var request by mutableIntStateOf(0)
        rule.setContent {
            val paginationScope = rememberCoroutineScope()
            MaterialTheme {
                key(request) {
                    PagedReader(
                        chapters[currentChapter], currentChapter, ReadingSettings(), palette, FontFamily.Default,
                        emptyList(), 0, { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                        onBookmarkSelection = { _, _, _ -> }, totalChapters = chapters.size,
                        onChapterPageChanged = { prepared, _, _ -> currentChapter = prepared.chapterIndex },
                        pageCache = cache, paginationScope = paginationScope, bookId = 1,
                        loadChapterForPagination = { chapters.getOrNull(it) }, onImageClick = { _, _ -> },
                        modifier = Modifier.testTag("paged"), paginationDispatcher = Dispatchers.Main.immediate
                    )
                }
            }
        }
        rule.waitForIdle()
        for (destination in listOf(4, 3, 7, 6, 2, 1, 5, 4)) {
            rule.runOnIdle { currentChapter = destination; request++ }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals("Explicit jump must survive neighbour publication", destination, currentChapter) }
            rule.onNodeWithText("Jump $destination").assertIsDisplayed()
        }
    }

    @Test fun warmLongChapterJumpCannotReportItsCachedPredecessorAsTheDestination() {
        val body = List(30) { "Synthetic reading paragraph $it. ".repeat(18) }.joinToString("\n\n")
        val chapters = List(16) { Chapter(bookId = 1, index = it, title = "Warm chapter $it", content = body) }
        val keys = java.util.concurrent.CopyOnWriteArrayList<ReaderPageKey>()
        val cache = ReaderPageCache(maxEntries = 32, store = object : ReaderPageStore {
            override fun get(key: ReaderPageKey): List<List<com.flowreader.feature.reader.ChapterPaginator.Fragment>>? {
                keys += key
                return null
            }
            override fun put(key: ReaderPageKey, pages: List<List<com.flowreader.feature.reader.ChapterPaginator.Fragment>>) = Unit
        })
        var currentChapter by mutableIntStateOf(7)
        var request by mutableIntStateOf(0)
        val reports = mutableListOf<Int>()
        rule.setContent {
            val paginationScope = rememberCoroutineScope()
            val generation = request
            MaterialTheme {
                key(generation) {
                    PagedReader(
                        chapters[currentChapter], currentChapter, ReadingSettings(), palette, FontFamily.Default,
                        emptyList(), 0, { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                        onBookmarkSelection = { _, _, _ -> }, totalChapters = chapters.size,
                        onChapterPageChanged = { prepared, _, _ ->
                            if (generation == request) {
                                reports += prepared.chapterIndex
                                currentChapter = prepared.chapterIndex
                            }
                        }, pageCache = cache, paginationScope = paginationScope, bookId = 1,
                        loadChapterForPagination = { chapters.getOrNull(it) }, onImageClick = { _, _ -> },
                        modifier = Modifier.testTag("paged"), paginationDispatcher = Dispatchers.Main.immediate
                    )
                }
            }
        }
        rule.waitUntil(20_000) { keys.any { it.chapterIndex == 7 && cache.get(it) != null } && cache.size() >= 4 }
        rule.runOnIdle {
            val seed = keys.first { it.chapterIndex == 7 }
            val pages = cache.get(seed)!!
            assertTrue("Fixture must span multiple pages", pages.size > 3)
            chapters.forEachIndexed { index, chapter ->
                cache.put(seed.copy(chapterIndex = index, title = chapter.title), pages.map { page ->
                    page.map { fragment -> if (fragment.isTitle) fragment.copy(text = chapter.title) else fragment }
                })
            }
        }
        for (destination in listOf(8, 9, 12, 7, 8, 9)) {
            rule.runOnIdle { reports.clear(); currentChapter = destination; request++ }
            rule.waitForIdle()
            rule.runOnIdle {
                assertEquals("A warm explicit jump must not return to the preceding chapter", destination, currentChapter)
                assertTrue("Only the requested chapter may be adopted on initial layout: $reports", reports.all { it == destination })
            }
            rule.onNodeWithText("Warm chapter $destination").assertIsDisplayed()
        }
    }

    @Test fun continuousWindowKeepsTheVisiblePageThroughPrependEvictionAndReverse() {
        val chapters = List(8) { Chapter(bookId = 1, index = 10 + it * 3, title = "Window $it", content = "Body $it.") }
        val cache = ReaderPageCache()
        var currentChapter by mutableIntStateOf(3)
        var currentPage by mutableIntStateOf(0)
        val reports = mutableListOf<Int>()
        rule.setContent {
            MaterialTheme {
                PagedReader(
                    chapters[currentChapter], currentChapter, ReadingSettings(), palette, FontFamily.Default,
                    emptyList(), currentPage, { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                    onBookmarkSelection = { _, _, _ -> }, totalChapters = chapters.size,
                    onChapterPageChanged = { prepared, page, _ ->
                        currentChapter = prepared.chapterIndex
                        currentPage = page
                        reports += currentChapter
                    }, pageCache = cache, bookId = 1, loadChapterForPagination = { chapters.getOrNull(it) },
                    onImageClick = { _, _ -> }, modifier = Modifier.testTag("paged"), paginationDispatcher = Dispatchers.Main.immediate
                )
            }
        }
        rule.waitUntil(10_000) { cache.size() == 4 }
        rule.onNodeWithText("Window 3").assertIsDisplayed()
        for (next in listOf(4, 5, 6, 7, 6, 5, 4, 3, 2, 1, 0, 1)) {
            val forward = next > currentChapter
            rule.onNodeWithTag("paged").performTouchInput { click(Offset(width * (if (forward) 0.9f else 0.1f), height * 0.5f)) }
            rule.waitForIdle()
            rule.runOnIdle {
                assertEquals(next, currentChapter)
                assertEquals(0, currentPage)
            }
            rule.onNodeWithText("Window $next").assertIsDisplayed()
        }
        rule.runOnIdle { assertEquals(listOf(3, 4, 5, 6, 7, 6, 5, 4, 3, 2, 1, 0, 1), reports.distinctUntilChanged()) }
    }

    @Test fun preparedLivePagesSurviveLruClearingWithoutForegroundTypesetting() {
        val chapters = List(2) { Chapter(bookId = 1, index = it, title = "Retained $it", content = "Body $it.") }
        val cache = ReaderPageCache()
        var currentChapter by mutableIntStateOf(0)
        var measurements = 0
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                measurements++
                Dispatchers.Main.immediate.dispatch(context, block)
            }
        }
        rule.setContent {
            MaterialTheme {
                PagedReader(
                    chapters[currentChapter], currentChapter, ReadingSettings(), palette, FontFamily.Default,
                    emptyList(), 0, { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                    onBookmarkSelection = { _, _, _ -> }, totalChapters = chapters.size,
                    onChapterPageChanged = { prepared, _, _ -> currentChapter = prepared.chapterIndex },
                    pageCache = cache, bookId = 1, loadChapterForPagination = { chapters.getOrNull(it) },
                    onImageClick = { _, _ -> }, modifier = Modifier.testTag("paged"), paginationDispatcher = dispatcher
                )
            }
        }
        rule.waitUntil(10_000) { cache.size() == 2 }
        rule.runOnIdle {
            cache.clear()
            assertEquals(2, measurements)
        }
        rule.onNodeWithTag("paged").performTouchInput { click(Offset(width * 0.9f, height * 0.5f)) }
        rule.onNodeWithText("Retained 1").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(1, currentChapter)
            assertEquals(2, measurements)
        }
    }

    private fun List<Int>.distinctUntilChanged(): List<Int> = filterIndexed { index, value -> index == 0 || this[index - 1] != value }

    @Test fun continuousReverseReturnsToThePreviousChaptersLastLocalPage() {
        val chapters = listOf(longChapter, Chapter(bookId = 1, index = 13, title = "Next chapter", content = "Short body."))
        val cache = ReaderPageCache()
        var currentChapter by mutableIntStateOf(0)
        var currentPage by mutableIntStateOf(Int.MAX_VALUE)
        rule.setContent {
            MaterialTheme {
                PagedReader(
                    chapters[currentChapter], currentChapter, ReadingSettings(), palette, FontFamily.Default,
                    emptyList(), currentPage, { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                    onBookmarkSelection = { _, _, _ -> }, totalChapters = chapters.size,
                    onChapterPageChanged = { prepared, page, _ ->
                        currentChapter = prepared.chapterIndex
                        currentPage = page
                    },
                    pageCache = cache, bookId = 1, loadChapterForPagination = { chapters.getOrNull(it) },
                    onImageClick = { _, _ -> }, modifier = Modifier.testTag("paged"), paginationDispatcher = Dispatchers.Main.immediate
                )
            }
        }
        rule.waitUntil(10_000) { cache.size() == 2 }
        rule.waitForIdle()
        val lastPage = currentPage
        assertTrue(lastPage in 1 until Int.MAX_VALUE)
        rule.onNodeWithTag("paged").performTouchInput { swipeLeft() }
        rule.runOnIdle {
            assertEquals(1, currentChapter)
            assertEquals(0, currentPage)
        }
        rule.onNodeWithTag("paged").performTouchInput { swipeRight() }
        rule.runOnIdle {
            assertEquals(0, currentChapter)
            assertEquals(lastPage, currentPage)
        }
    }

    @Test fun chapterBoundaryKeepsBothPagesInTheSameAnimationUntilSettled() {
        assertContinuousAnimation(PageTurnAnimation.SLIDE, "reader-cross-chapter-mid-animation.png")
    }

    @Test fun coverChapterBoundaryKeepsBothPagesUntilSettled() {
        assertContinuousAnimation(PageTurnAnimation.COVER, "reader-cross-chapter-cover-mid-animation.png")
    }

    private fun assertContinuousAnimation(animation: PageTurnAnimation, screenshot: String) {
        val chapters = List(2) { Chapter(bookId = 1, index = it, title = "Continuous $it", content = "Body $it.") }
        val cache = ReaderPageCache()
        var currentChapter by mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                PagedReader(
                    chapters[currentChapter], currentChapter, ReadingSettings(pageTurnAnimation = animation), palette, FontFamily.Default,
                    emptyList(), 0, { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                    onBookmarkSelection = { _, _, _ -> }, totalChapters = chapters.size,
                    onChapterPageChanged = { prepared, _, _ -> currentChapter = prepared.chapterIndex },
                    pageCache = cache, bookId = 1, loadChapterForPagination = { chapters.getOrNull(it) },
                    onImageClick = { _, _ -> }, modifier = Modifier.testTag("paged"), paginationDispatcher = Dispatchers.Main.immediate
                )
            }
        }
        rule.waitUntil(10_000) { cache.size() == 2 }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("paged").performTouchInput { click(Offset(width * 0.9f, height * 0.5f)) }
        rule.mainClock.advanceTimeBy(80)
        assertEquals("Chapter metadata must change after the page animation settles", 0, currentChapter)
        val outgoing = rule.onNodeWithTag("reader-page-0:0").fetchSemanticsNode().boundsInRoot
        val incoming = rule.onNodeWithTag("reader-page-1:0").fetchSemanticsNode().boundsInRoot
        assertTrue("Both chapter pages must be present during the transition", outgoing.left < incoming.left)
        val viewport = rule.onNodeWithTag("paged").fetchSemanticsNode().boundsInRoot
        assertTrue("The outgoing chapter must still occupy the viewport", outgoing.right > viewport.left)
        assertTrue("The incoming chapter must already occupy the viewport", incoming.left < viewport.right)
        rule.onRoot().captureRoboImage("build/reports/$screenshot")
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(1, currentChapter)
        rule.onNodeWithText("Continuous 1").assertIsDisplayed()
    }

    @Test fun sequentialChapterTapsReusePreTypesetPagesWithoutForegroundMeasurement() {
        val chapters = List(3) { Chapter(bookId = 1, index = it, title = "Ready $it", content = "A short chapter $it.") }
        val cache = ReaderPageCache()
        var currentChapter by mutableIntStateOf(0)
        var measurements = 0
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                measurements++
                Dispatchers.Main.immediate.dispatch(context, block)
            }
        }
        rule.setContent {
            val screenScope = rememberCoroutineScope()
            MaterialTheme {
                key(currentChapter) {
                    PagedReader(
                        chapters[currentChapter], currentChapter, ReadingSettings(), palette, FontFamily.Default,
                        emptyList(), 0, { _, _ -> }, {}, {}, onHighlightSelection = { _, _, _ -> },
                        onBookmarkSelection = { _, _, _ -> }, totalChapters = chapters.size,
                        onNextChapter = { currentChapter++ }, onPreviousChapter = { currentChapter-- },
                        pageCache = cache, paginationScope = screenScope, bookId = 1,
                        loadChapterForPagination = { chapters.getOrNull(it) },
                        onImageClick = { _, _ -> }, modifier = Modifier.testTag("paged"), paginationDispatcher = dispatcher
                    )
                }
            }
        }
        rule.waitUntil(10_000) { cache.size() == 3 }
        rule.runOnIdle { assertEquals(3, measurements) }
        rule.onNodeWithTag("paged").performTouchInput { click(Offset(width * 0.9f, height * 0.5f)) }
        rule.onNodeWithText("Ready 1").assertIsDisplayed()
        rule.onNodeWithTag("paged").performTouchInput { click(Offset(width * 0.9f, height * 0.5f)) }
        rule.onNodeWithText("Ready 2").assertIsDisplayed()
        rule.onNodeWithTag("paged").performTouchInput { click(Offset(width * 0.1f, height * 0.5f)) }
        rule.onNodeWithText("Ready 1").assertIsDisplayed()
        rule.runOnIdle { assertEquals("Chapter taps must reuse pages without measuring again", 3, measurements) }
        rule.onRoot().captureRoboImage("build/reports/reader-seamless-chapter.png")
    }

    @Test fun tapsUseLatestCallbackAndOnlyUpDispatches() {
        var generation by mutableIntStateOf(0)
        val calls = mutableListOf<Int>()
        rule.setContent {
            val captured = generation
            Box(Modifier.size(300.dp).testTag("surface").readerTapGestures(GestureSettings(), { _, _ -> calls.add(captured) }, {}))
        }
        rule.onNodeWithTag("surface").performTouchInput { down(center) }
        rule.runOnIdle { assertTrue(calls.isEmpty()) }
        rule.onNodeWithTag("surface").performTouchInput { up() }
        rule.runOnIdle { generation = 1 }
        rule.onNodeWithTag("surface").performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(listOf(0, 1), calls) }
    }

    @Test fun returningDragAndCancelledTouchNeverBecomeTaps() {
        var taps = 0
        rule.setContent { Box(Modifier.size(300.dp).testTag("surface").readerTapGestures(GestureSettings(), { _, _ -> taps++ }, {})) }
        rule.onNodeWithTag("surface").performTouchInput {
            down(center)
            moveTo(center + Offset(100f, 0f))
            moveTo(center)
            up()
            down(center)
            cancel()
        }
        rule.runOnIdle { assertEquals(0, taps) }
    }

    @Test fun childConsumedTapDoesNotReachReadingSurface() {
        var parent = 0
        var child = 0
        rule.setContent {
            Box(Modifier.size(300.dp).readerTapGestures(GestureSettings(), { _, _ -> parent++ }, {})) {
                Box(Modifier.fillMaxSize().testTag("child").readerTapGestures(GestureSettings(), { _, _ -> child++ }, {}))
            }
        }
        rule.onNodeWithTag("child").performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(1, child)
            assertEquals(0, parent)
        }
    }

    @Test fun sideTapsAreSinglePageAndBurstDuringAnimationIsNotQueued() {
        var position by mutableIntStateOf(0)
        var middle = 0
        rule.setContent {
            MaterialTheme {
                PagedReader(
                    longChapter, 0, ReadingSettings(), palette, FontFamily.Default, emptyList(), position,
                    { page, _ -> position = page }, { middle++ }, {}, onHighlightSelection = { _, _, _ -> },
                    onBookmarkSelection = { _, _, _ -> }, onImageClick = { _, _ -> },
                    modifier = Modifier.testTag("paged"), paginationDispatcher = Dispatchers.Main.immediate
                )
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Chapter").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("paged").performTouchInput { click(Offset(width * 0.9f, height * 0.5f)) }
        rule.runOnIdle { assertEquals(1, position) }
        rule.onNodeWithTag("paged").performTouchInput { click(Offset(width * 0.1f, height * 0.5f)) }
        rule.runOnIdle { assertEquals(0, position) }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("paged").performTouchInput { click(Offset(width * 0.9f, height * 0.5f)) }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("paged").performTouchInput {
            repeat(6) {
                click(Offset(width * 0.9f, height * 0.5f))
                advanceEventTime(10)
            }
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, position) }
        rule.onNodeWithTag("paged").performTouchInput { click(center) }
        rule.runOnIdle {
            assertEquals(1, middle)
            assertEquals(1, position)
        }
        rule.onNodeWithTag("paged").performTouchInput { swipeLeft() }
        rule.runOnIdle { assertEquals(2, position) }
        rule.onNodeWithTag("paged").performTouchInput { swipeRight() }
        rule.runOnIdle { assertEquals(1, position) }
    }

    @Test fun pagedBoundaryCanImmediatelyReverseIntoPreviousChapter() {
        var chapter by mutableIntStateOf(0)
        var landEnd by mutableStateOf(false)
        var reportedChapter = -1
        var reportedPage = -1
        rule.setContent {
            MaterialTheme {
                key(chapter) {
                    PagedReader(
                        if (chapter == 0) {
                            longChapter
                        } else {
                            Chapter(
                                bookId = 1,
                                index = chapter,
                                title = "Short $chapter",
                                content = "A short chapter."
                            )
                        },
                        chapter, ReadingSettings(), palette, FontFamily.Default, emptyList(), if (chapter == 0) Int.MAX_VALUE else 0,
                        { page, _ ->
                            reportedChapter = chapter
                            reportedPage = page
                        }, {}, {}, onHighlightSelection = { _, _, _ -> }, onBookmarkSelection = { _, _, _ -> },
                        totalChapters = 2, landAtChapterEnd = landEnd, onLandedAtChapterEnd = { landEnd = false },
                        onPreviousChapter = {
                            landEnd = true
                            chapter--
                        }, onNextChapter = { chapter++ },
                        onImageClick = { _, _ -> },
                        modifier = Modifier.testTag("paged"), paginationDispatcher = Dispatchers.Main.immediate
                    )
                }
            }
        }
        rule.waitUntil(10_000) { reportedChapter == 0 }
        val lastPage = reportedPage
        assertTrue(lastPage > 0)
        rule.onNodeWithTag("paged").performTouchInput { swipeLeft() }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Short 1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("paged").performTouchInput { swipeRight() }
        rule.runOnIdle { assertEquals("Reverse swipe must dispatch the chapter request", 0, chapter) }
        rule.waitUntil(10_000) { reportedChapter == 0 && chapter == 0 }
        rule.runOnIdle { assertEquals(lastPage, reportedPage) }
    }

    @Test fun scrollViewportStaysBelowHeaderAfterCenterSwipe() {
        lateinit var scroll: androidx.compose.foundation.ScrollState
        var reported = -1
        rule.setContent {
            MaterialTheme {
                scroll = rememberScrollState()
                Box(Modifier.fillMaxSize()) {
                    ReaderContent(
                        longChapter, ReadingSettings(pageMode = PageMode.SLIDE), palette, FontFamily.Default, scroll,
                        { _, _ -> }, {}, {}, { _, _, _ -> }, { _, _, _ -> }, { reported = it },
                        modifier = Modifier.testTag("scroll")
                    )
                    ReaderHeader("Fixed header", palette, Modifier.testTag("header"))
                }
            }
        }
        rule.waitUntil(10_000) { reported >= 0 }
        rule.runOnIdle {
            assertTrue("Measured chapter must exceed viewport: ${scroll.maxValue}", scroll.maxValue > 0)
        }
        rule.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        rule.runOnIdle {
            assertTrue("Scroll must move: ${scroll.value}", scroll.value > 0)
        }
        rule.waitUntil(10_000) { reported > 0 }
        val headerBottom = rule.onNodeWithTag("header").getBoundsInRoot().bottom.value
        val viewportTop = rule.onNode(hasScrollAction()).getBoundsInRoot().top.value
        assertTrue("Body viewport must reserve header and breathing space", viewportTop >= headerBottom + 19f)
        rule.runOnIdle { assertTrue(reported > 0) }
        rule.onRoot().captureRoboImage("build/reports/reader-viewport.png")
    }

    @Test fun previousScrollChapterLandsAtMeasuredEndBeforeReportingPosition() {
        lateinit var scroll: androidx.compose.foundation.ScrollState
        var landed = -1
        var reported = -1
        rule.setContent {
            MaterialTheme {
                scroll = rememberScrollState()
                ReaderContent(
                    longChapter, ReadingSettings(pageMode = PageMode.SLIDE), palette, FontFamily.Default, scroll,
                    { _, _ -> }, {}, {}, { _, _, _ -> }, { _, _, _ -> }, { reported = it },
                    landAtChapterEnd = true, onLandedAtChapterEnd = { landed = it }
                )
            }
        }
        rule.waitUntil(10_000) { reported >= 0 }
        rule.runOnIdle {
            assertTrue(scroll.maxValue > 0)
            assertEquals(scroll.maxValue, landed)
            assertEquals(landed, reported)
        }
    }

    @Test fun shortScrollChapterCrossesBothDirectionsWithSparseStoredIndex() {
        var index by mutableIntStateOf(0)
        rule.setContent {
            MaterialTheme {
                key(index) {
                    ReaderContent(
                        Chapter(bookId = 1, index = index + 10, title = "Short $index", content = "Body"),
                        ReadingSettings(pageMode = PageMode.SLIDE), palette, FontFamily.Default, rememberScrollState(),
                        { _, _ -> }, {}, {}, { _, _, _ -> }, { _, _, _ -> }, {},
                        chapterIndex = index, totalChapters = 2, onPreviousChapter = { index-- }, onNextChapter = { index++ },
                        modifier = Modifier.testTag("scroll")
                    )
                }
            }
        }
        rule.onNodeWithTag("scroll").performTouchInput { swipeUp() }
        rule.runOnIdle { assertEquals(1, index) }
        rule.onNodeWithTag("scroll").performTouchInput { swipeDown() }
        rule.runOnIdle { assertEquals(0, index) }
    }
}
