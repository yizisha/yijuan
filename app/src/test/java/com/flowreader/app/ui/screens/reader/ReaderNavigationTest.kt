package com.flowreader.app.ui.screens.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.flowreader.app.domain.model.*
import com.flowreader.app.domain.model.Annotation
import com.flowreader.app.domain.repository.*
import com.flowreader.app.util.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderNavigationTest {
    @Test fun explicitJumpCannotBeReversedBeforeItsFirstPageIsAcknowledged() = runTest {
        withReader(paged = true) { reader ->
            val oldChapter = reader.uiState.value.currentChapter!!
            val page = listOf(listOf(com.flowreader.feature.reader.ChapterPaginator.Fragment("a", 0, false, false, false)))
            val oldPrepared = com.flowreader.app.ui.screens.reader.components.ReaderChapterPages(
                0, oldChapter, page, emptyList(), com.flowreader.app.util.epub.ChapterMarks.EMPTY, 0
            )
            reader.goToNextChapter()
            runCurrent()
            val destination = reader.uiState.value
            assertEquals(1, destination.currentChapterIndex)

            // A delayed callback can read a newer delegated UI state while still carrying the
            // previous reader's page. It must not turn an explicit next/TOC jump back around.
            reader.onPagedPageVisible(oldPrepared, 0, 0f, destination.scrollRequestVersion)
            runCurrent()
            assertEquals("The requested chapter must render before a neighbour can be adopted", 1, reader.uiState.value.currentChapterIndex)

            val newPrepared = oldPrepared.copy(chapterIndex = 1, chapter = destination.currentChapter!!)
            reader.onPagedPageVisible(newPrepared, 0, 0f, destination.scrollRequestVersion)
            runCurrent()
            reader.onPagedPageVisible(oldPrepared, 0, 0f, destination.scrollRequestVersion)
            runCurrent()
            assertEquals("A genuine reverse turn remains valid after the requested chapter has rendered", 0, reader.uiState.value.currentChapterIndex)
        }
    }

    @Test fun tocChapterStartOverridesAPendingLocatorInTheSameChapter() = runTest {
        withReader(paged = true) { reader ->
            coEvery { chapters.getChapterContentsForSearch(1, listOf(10)) } returns mapOf(10 to "abcdefghi")
            reader.goToSearchHit(0, 3)
            runCurrent()
            assertNotNull(reader.uiState.value.searchTarget)
            val tree = LegacyTocAdapter.toTocTree("legacy-1", "legacy", reader.uiState.value.chapters)
            reader.goToTocNode(tree, tree.allNodes.first { it.targetSectionId == "section_0" }.nodeId)
            runCurrent()
            assertNull(reader.uiState.value.searchTarget)
            assertEquals(0, reader.uiState.value.currentPosition)
        }
    }
    @Test fun tocOpensTheChapterStartInsteadOfItsPreviouslyRememberedLastPage() = runTest {
        withReader(paged = true) { reader ->
            reader.updatePosition(7, .95f)
            reader.goToNextChapter()
            runCurrent()
            val state = reader.uiState.value
            val tree = LegacyTocAdapter.toTocTree("legacy-1", "legacy", state.chapters)
            reader.goToTocNode(tree, tree.allNodes.first { it.targetSectionId == "section_0" }.nodeId)
            runCurrent()
            assertEquals(0, reader.uiState.value.currentChapterIndex)
            assertEquals("A deliberate TOC jump must expose the chapter from its beginning", 0, reader.uiState.value.currentPosition)
        }
    }
    @Test fun stayingOnTheSamePageForTwelveMinutesStillRecordsTwelveMinutes() = runTest {
        val stats = mockk<ReadingStatsRepository>(relaxed = true)
        withReader(stats = stats) { reader ->
            reader.onReaderVisibilityChanged(true)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(12))
            reader.onReaderVisibilityChanged(false)
            coVerify(timeout = 5000) { stats.updateTodayStats(1, 0, 720) }
        }
    }
    @Test fun readerVisibilityFlushesOnlyForegroundReadingTime() = runTest {
        val stats = mockk<ReadingStatsRepository>(relaxed = true)
        withReader(stats = stats) { reader ->
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofHours(6))
            reader.onReaderVisibilityChanged(true)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(12))
            reader.onReaderVisibilityChanged(false)
            coVerify(timeout = 5000) { stats.updateTodayStats(1, 0, 12) }
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofHours(86))
            reader.onReaderVisibilityChanged(false)
            coVerify(exactly = 1) { stats.updateTodayStats(any(), any(), any()) }
        }
    }
    @Test fun pagedBookmarkStoresMeasuredSourceOffsetAndSurvivesAChapterRoundTrip() = runTest {
        withReader(paged = true) { reader ->
            coEvery { bookmarks.addBookmark(any()) } coAnswers { firstArg<Bookmark>().copy(id = 50) }
            reader.goToChapter(1)
            runCurrent()
            val state = reader.uiState.value
            val prepared = com.flowreader.app.ui.screens.reader.components.ReaderChapterPages(
                chapterIndex = 1, chapter = state.currentChapter!!,
                pages = listOf(
                    listOf(com.flowreader.feature.reader.ChapterPaginator.Fragment("Chapter", 0, false, true, false)),
                    listOf(com.flowreader.feature.reader.ChapterPaginator.Fragment("text", 7, false, false, false))
                ), images = emptyList(), marks = com.flowreader.app.util.epub.ChapterMarks.EMPTY, bodyShift = 1
            )
            reader.onPagedPageVisible(prepared, 1, .5f, state.scrollRequestVersion)
            reader.addBookmark("text")
            runCurrent()
            val saved = reader.uiState.value.bookmarks.single()
            assertEquals(8, saved.locator?.offsetUtf16)
            assertEquals("chapter:13", saved.locator?.documentKey)
            reader.goToChapter(0)
            runCurrent()
            reader.goToBookmark(saved)
            runCurrent()
            assertEquals(1, reader.uiState.value.currentChapterIndex)
            assertEquals(8, reader.uiState.value.searchTarget?.offsetUtf16)
        }
    }
    @Test fun bookmarkNavigationUsesItsChapterAndMeasuredLocatorInsteadOfAPageNumber() = runTest {
        withReader(paged = true) { reader ->
            val locator = com.flowreader.app.domain.model.TextLocator(
                version = 1, publicationId = "legacy-1", revision = "legacy",
                sectionId = "section_1", documentKey = "chapter:13", offsetUtf16 = 7
            )
            reader.goToBookmark(Bookmark(bookId = 1, chapterIndex = 1, position = 2, text = "text", locator = locator))
            runCurrent()
            assertEquals(1, reader.uiState.value.currentChapterIndex)
            assertEquals(0, reader.uiState.value.currentPosition)
            assertEquals(7, reader.uiState.value.searchTarget?.offsetUtf16)
            assertEquals("chapter:13", reader.uiState.value.searchTarget?.documentKey)
            coVerify(exactly = 0) { chapters.getChapterTextLengths(any()) }
        }
    }

    @Test fun bookmarkWithAnOldStaleChapterLocatorStillReturnsToItsRecordedChapter() = runTest {
        withReader(paged = true) { reader ->
            val locator = reader.buildCurrentLocator(0, 3)
            reader.goToBookmark(Bookmark(bookId = 1, chapterIndex = 2, position = 4, text = "text", locator = locator))
            runCurrent()
            assertEquals(2, reader.uiState.value.currentChapterIndex)
            assertEquals(4, reader.uiState.value.currentPosition)
        }
    }
    private val books = mockk<BookRepository>(relaxed = true)
    private val chapters = mockk<ChapterRepository>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val annotations = mockk<AnnotationRepository>(relaxed = true)
    private val bookmarks = mockk<BookmarkRepository>(relaxed = true)
    private val tts = mockk<TtsManager>(relaxed = true)
    private val writes = mutableListOf<Pair<Int, Int>>()
    private val imageResolver = mockk<com.flowreader.app.util.epub.ChapterImageResolver>(relaxed = true)

    @Test fun bookmarkFeedbackWaitsForPersistenceAndKeepsTheSavedPosition() = runTest {
        withReader(paged = true) { reader ->
            reader.updatePosition(4, .5f)
            val gate = CompletableDeferred<Bookmark>()
            val saved = slot<Bookmark>()
            coEvery { bookmarks.addBookmark(capture(saved)) } coAnswers { gate.await() }
            reader.addBookmark("原文")
            runCurrent()
            assertNull(reader.uiState.value.message)
            assertTrue(reader.uiState.value.bookmarks.isEmpty())
            gate.complete(saved.captured.copy(id = 9))
            runCurrent()
            assertEquals("书签已添加", reader.uiState.value.message)
            assertEquals(4, reader.uiState.value.bookmarks.single().position)
            assertEquals(0, reader.uiState.value.bookmarks.single().chapterIndex)
            reader.clearMessage()
            assertNull(reader.uiState.value.message)
        }
    }

    @Test fun bookmarkFailureIsFeedbackAndDoesNotReplaceTheReadingContent() = runTest {
        withReader { reader ->
            val chapter = reader.uiState.value.currentChapter
            coEvery { bookmarks.addBookmark(any()) } throws IllegalStateException("disk full")
            reader.addBookmark("原文")
            runCurrent()
            assertTrue(reader.uiState.value.message!!.contains("disk full"))
            assertNull(reader.uiState.value.error)
            assertEquals(chapter, reader.uiState.value.currentChapter)
            assertTrue(reader.uiState.value.bookmarks.isEmpty())
        }
    }

    @Test fun selectedTextBookmarkAlsoReportsPersistenceSuccess() = runTest {
        withReader { reader ->
            coEvery { bookmarks.addBookmark(any()) } coAnswers { firstArg<Bookmark>().copy(id = 10) }
            val ranges = listOf(SourceRange("chapter:0", 0, 3), SourceRange("chapter:0", 8, 12))
            reader.addBookmarkRanges(com.flowreader.app.ui.screens.reader.components.MultiBlockSelection(ranges, "原文", chapterIndex = 0))
            runCurrent()
            assertEquals("书签已添加", reader.uiState.value.message)
            assertEquals(10L, reader.uiState.value.bookmarks.single().id)
        }
    }

    @Test fun cancelledBookmarkSaveDoesNotReportAnErrorOrFalseSuccess() = runTest {
        withReader { reader ->
            coEvery { bookmarks.addBookmark(any()) } throws CancellationException("cancelled")
            reader.addBookmark("原文")
            runCurrent()
            assertNull(reader.uiState.value.message)
            assertNull(reader.uiState.value.error)
            assertTrue(reader.uiState.value.bookmarks.isEmpty())
        }
    }

    @Test fun selectedNotePersistsItsTextAndOriginalChapterBeforeANavigation() = runTest {
        val saved = slot<Annotation>()
        coEvery { annotations.insertAnnotation(capture(saved)) } returns 42L
        withReader { reader ->
            reader.addAnnotation("Chapter", 0, 7, note = "My thought")
            reader.goToChapter(1)
            runCurrent()
            assertEquals("Chapter", saved.captured.selectedText)
            assertEquals("My thought", saved.captured.note)
            assertEquals(0, saved.captured.chapterIndex)
            assertEquals(0, saved.captured.startPosition)
            assertEquals(7, saved.captured.endPosition)
            assertEquals(42L, reader.uiState.value.annotations.single().id)
        }
    }

    @Test fun multiBlockNotePersistsTheAuthoritativeRangesAndComment() = runTest {
        val saved = slot<Annotation>()
        coEvery { annotations.insertAnnotation(capture(saved)) } returns 43L
        withReader { reader ->
            val ranges = listOf(SourceRange("chapter:0", 0, 3), SourceRange("chapter:0", 8, 12))
            val selection = com.flowreader.app.ui.screens.reader.components.MultiBlockSelection(ranges, "Chatext", chapterIndex = 0)
            reader.addAnnotationRanges(selection, note = "Across the text")
            runCurrent()
            assertEquals("Across the text", saved.captured.note)
            assertEquals(ranges, saved.captured.rangeLocator?.ranges)
            assertEquals("Chatext", saved.captured.selectedText)
            assertEquals(0, saved.captured.chapterIndex)
        }
    }

    @Test fun pageTurnSettingsSurviveWidgetEmissionsBeforeTheirDebouncedSave() = runTest {
        val stored = AppSettings(defaultReadingSettings = ReadingSettings(pageMode = PageMode.PAGED))
        val preferences = MutableSharedFlow<AppSettings>(replay = 1, extraBufferCapacity = 1)
        preferences.tryEmit(stored)
        withReader(preferences = preferences) { reader ->
            val selected = reader.uiState.value.readingSettings.copy(
                pageMode = PageMode.SLIDE,
                pageTurnAnimation = PageTurnAnimation.COVER,
                volumeKeyTurnPage = false,
                gestureSettings = reader.uiState.value.readingSettings.gestureSettings.copy(tapZoneMode = TapZoneMode.entries.last())
            )
            reader.updateReadingSettings(selected)
            assertEquals(selected, reader.uiState.value.readingSettings)
            // Widget-only DataStore edits emit AppSettings again with the old reader preferences.
            assertTrue(preferences.tryEmit(stored))
            runCurrent()
            assertEquals("A widget emission must not undo the selected reader settings", selected, reader.uiState.value.readingSettings)
            advanceTimeBy(351)
            runCurrent()
            coVerify(exactly = 1) { settings.updateReadingSettings(selected) }
            assertTrue(preferences.tryEmit(stored.copy(defaultReadingSettings = selected)))
            runCurrent()
            assertEquals(selected, reader.uiState.value.readingSettings)
        }
    }

    @Test fun rapidPageTurnChoicesPersistOnlyTheLatestSnapshotAndKeepTheChapterPosition() = runTest {
        val initial = AppSettings(defaultReadingSettings = ReadingSettings(pageMode = PageMode.PAGED))
        val preferences = MutableSharedFlow<AppSettings>(replay = 1, extraBufferCapacity = 1)
        preferences.tryEmit(initial)
        withReader(preferences = preferences) { reader ->
            reader.updatePosition(4, 0.5f)
            val before = reader.uiState.value
            val scroll = before.readingSettings.copy(pageMode = PageMode.SLIDE)
            val latest = scroll.copy(pageMode = PageMode.NONE, volumeKeyTurnPage = false)
            reader.updateReadingSettings(scroll)
            advanceTimeBy(200)
            reader.updateReadingSettings(latest)
            assertTrue(preferences.tryEmit(initial))
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            coVerify(exactly = 0) { settings.updateReadingSettings(any()) }
            advanceTimeBy(151)
            runCurrent()
            coVerify(exactly = 1) { settings.updateReadingSettings(latest) }
            assertEquals(latest, reader.uiState.value.readingSettings)
            assertEquals(before.currentChapterIndex, reader.uiState.value.currentChapterIndex)
            assertEquals(before.currentPosition, reader.uiState.value.currentPosition)
            assertEquals(before.scrollRequestVersion, reader.uiState.value.scrollRequestVersion)
        }
    }

    @Test fun slowSettingsSaveCannotRestoreAnOldSelectionAndLaterExternalChangesStillApply() = runTest {
        val initial = AppSettings(defaultReadingSettings = ReadingSettings(pageMode = PageMode.PAGED))
        val preferences = MutableSharedFlow<AppSettings>(replay = 1, extraBufferCapacity = 1)
        preferences.tryEmit(initial)
        withReader(preferences = preferences) { reader ->
            val gate = CompletableDeferred<Unit>()
            coEvery { settings.updateReadingSettings(any()) } coAnswers { gate.await() }
            val selected = initial.defaultReadingSettings.copy(pageMode = PageMode.SLIDE)
            reader.updateReadingSettings(selected)
            advanceTimeBy(351)
            runCurrent()
            assertTrue(preferences.tryEmit(initial.copy(defaultReadingSettings = selected.copy(pageMode = PageMode.NONE))))
            runCurrent()
            assertEquals(selected, reader.uiState.value.readingSettings)
            gate.complete(Unit)
            runCurrent()
            assertTrue(preferences.tryEmit(initial.copy(defaultReadingSettings = selected)))
            runCurrent()
            assertTrue(preferences.tryEmit(initial))
            runCurrent()
            assertEquals(initial.defaultReadingSettings, reader.uiState.value.readingSettings)
        }
    }

    @Test fun failedSettingsSaveReleasesThePendingSelectionAndCanRetry() = runTest {
        withReader(paged = true) { reader ->
            val selected = reader.uiState.value.readingSettings.copy(pageMode = PageMode.SLIDE)
            coEvery { settings.updateReadingSettings(any()) } throws java.io.IOException("disk failure")
            reader.updateReadingSettings(selected)
            advanceTimeBy(351)
            runCurrent()
            assertTrue(reader.uiState.value.message.orEmpty().contains("阅读设置保存失败"))
            coEvery { settings.updateReadingSettings(any()) } returns Unit
            reader.clearMessage()
            reader.updateReadingSettings(selected)
            advanceTimeBy(351)
            runCurrent()
            assertNull(reader.uiState.value.message)
            coVerify(exactly = 2) { settings.updateReadingSettings(selected) }
        }
    }

    @Test fun settledContinuousPageAdoptsPreparedContentWithoutRestartingNavigation() = runTest {
        withReader(paged = true) { reader ->
            val version = reader.uiState.value.scrollRequestVersion
            clearMocks(chapters, answers = false)
            coEvery { chapters.getChapterContent(1, any()) } throws IllegalStateException("Storage unavailable")
            val prepared = com.flowreader.app.ui.screens.reader.components.ReaderChapterPages(
                1,
                Chapter(bookId = 1, index = 13, title = "Prepared", content = "Prepared next chapter"),
                List(3) { emptyList() },
                emptyList(),
                com.flowreader.app.util.epub.ChapterMarks.EMPTY,
                0
            )
            reader.onPagedPageVisible(prepared, 1, 0.5f, version)
            runCurrent()
            assertEquals(1, reader.uiState.value.currentChapterIndex)
            assertEquals("Prepared next chapter", reader.uiState.value.currentChapter?.content)
            assertEquals(1, reader.uiState.value.currentPosition)
            assertEquals(version, reader.uiState.value.scrollRequestVersion)
            assertFalse(reader.uiState.value.isChapterSwitching)
            assertEquals(1 to 1, writes.last())
            coVerify(exactly = 0) { chapters.getChapterContent(1, 13) }
            reader.onPagedPageVisible(prepared.copy(chapterIndex = 2), 1, 0.5f, version)
            assertEquals("Mismatched stored chapter identity must be rejected", 1, reader.uiState.value.currentChapterIndex)
            reader.onPagedPageVisible(prepared, 2, 1f, version - 1)
            assertEquals("An old navigation generation cannot change progress", 1, reader.uiState.value.currentPosition)
        }
    }

    @Test fun adjacentChapterPresentationIsPreparedBeforeNavigationAndReused() = runTest {
        withReader(structured = true) { reader ->
            coVerify(exactly = 1) { imageResolver.imagesForSection("pub", "rev", 1) }
            coVerify(exactly = 1) { imageResolver.marksForSection("pub", "rev", 1) }
            clearMocks(imageResolver, answers = false)
            reader.goToNextChapter()
            runCurrent()
            assertEquals(1, reader.uiState.value.currentChapterIndex)
            coVerify(exactly = 0) { imageResolver.imagesForSection("pub", "rev", 1) }
            coVerify(exactly = 0) { imageResolver.marksForSection("pub", "rev", 1) }
            coVerify { chapters.getChapterContent(1, 13) }
            coVerify(exactly = 0) { chapters.getChapterContent(1, 1) }
        }
    }

    @Test fun slowProgressWriteDoesNotDelayChapterDisplayOrBlockTheNextTurn() = runTest {
        withReader { reader ->
            val writeGate = CompletableDeferred<Unit>()
            coEvery { books.updateReadingProgress(any(), any(), any(), any()) } coAnswers {
                writeGate.await()
                writes.add(secondArg<Int>() to thirdArg<Int>())
            }
            reader.goToNextChapter()
            runCurrent()
            assertEquals("Chapter display must not wait for storage", 1, reader.uiState.value.currentChapterIndex)
            assertFalse(reader.uiState.value.isChapterSwitching)
            reader.goToNextChapter()
            runCurrent()
            assertEquals(2, reader.uiState.value.currentChapterIndex)
            assertFalse(reader.uiState.value.isChapterSwitching)
            writeGate.complete(Unit)
            runCurrent()
            assertEquals(2 to 0, writes.last())
        }
    }

    @Test fun cancelledSlowWriteCannotOverwriteTheLatestChapter() = runTest {
        withReader { reader ->
            val writeGate = CompletableDeferred<Unit>()
            coEvery { books.updateReadingProgress(any(), any(), any(), any()) } coAnswers {
                val chapter = secondArg<Int>()
                val position = thirdArg<Int>()
                if (chapter == 1) withContext(NonCancellable) { writeGate.await() }
                writes.add(chapter to position)
            }
            reader.goToNextChapter()
            runCurrent()
            reader.goToNextChapter()
            runCurrent()
            reader.goToChapter(0)
            runCurrent()
            assertEquals(0, reader.uiState.value.currentChapterIndex)
            assertFalse(reader.uiState.value.isChapterSwitching)
            writeGate.complete(Unit)
            runCurrent()
            assertEquals("The last stored chapter must match the visible chapter", 0 to 0, writes.last())
        }
    }

    private fun TestScope.withReader(
        structured: Boolean = false,
        paged: Boolean = false,
        preferences: Flow<AppSettings>? = null,
        search: ChapterTextSearch = mockk(relaxed = true),
        initialSearchIndex: Int = -1,
        initialOffset: Int = -1,
        stats: ReadingStatsRepository = mockk(relaxed = true),
        series: Flow<List<Book>>? = null,
        block: TestScope.(ReaderViewModel) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { books.getBookById(1) } returns Book(
                id = 1, title = "Book", author = "Author", filePath = "book.txt", format = BookFormat.TXT,
                publicationId = if (structured) "pub" else null, activeRevision = if (structured) "rev" else null,
                seriesName = if (series != null) "山海" else null, seriesOrder = 1
            )
            if (series != null) every { books.getAllBooks() } returns series
            coEvery { imageResolver.imagesForSection(any(), any(), any()) } returns emptyList()
            coEvery { imageResolver.marksForSection(any(), any(), any()) } returns com.flowreader.app.util.epub.ChapterMarks.EMPTY
            coEvery { chapters.getChapterMetadataList(1) } returns (0..2).map { Chapter(bookId = 1, index = it * 3 + 10, title = "Chapter $it") }
            coEvery { chapters.getChapterContent(1, any()) } returns "Chapter text"
            coEvery { chapters.getChapterContentsForSearch(1, any()) } coAnswers {
                secondArg<List<Int>>().associateWith { chapters.getChapterContent(1, it).orEmpty() }
            }
            coEvery { books.updateReadingProgress(any(), any(), any(), any()) } coAnswers {
                writes.add(secondArg<Int>() to thirdArg<Int>())
                Unit
            }
            every { settings.appSettings } returns (
                preferences ?: MutableStateFlow(
                    AppSettings(
                        defaultReadingSettings =
                        ReadingSettings(pageMode = if (paged) PageMode.PAGED else PageMode.SLIDE)
                    )
                )
                )
            every { settings.getDailyReadingGoal() } returns MutableStateFlow(30)
            every { tts.state } returns MutableStateFlow(TtsState())
            val reader = ReaderViewModel(
                SavedStateHandle(mapOf("bookId" to 1L, "chapterIndex" to initialSearchIndex, "locatorOffset" to initialOffset)), books, chapters,
                bookmarks, annotations, settings, stats,
                mockk(relaxed = true), mockk(relaxed = true), search, tts,
                mockk(relaxed = true), mockk(relaxed = true), chapterImageResolver = imageResolver
            )
            store.put("reader", reader)
            runCurrent()
            assertFalse(reader.uiState.value.isLoading)
            block(reader)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun seriesEndOffersTheNextManualVolumeAndUpdatesAfterAMoveOrDelete() = runTest {
        val first = Book(id = 1, title = "一", author = "", filePath = "1", seriesName = "山海", seriesOrder = 1)
        val second = first.copy(id = 2, title = "二", seriesOrder = 2)
        val third = first.copy(id = 3, title = "三", seriesOrder = 3)
        val library = MutableStateFlow(listOf(first, second, third))
        withReader(series = library) { reader ->
            assertEquals(2L, reader.uiState.value.nextVolume!!.id)
            reader.goToChapter(2); runCurrent(); reader.goToNextChapter()
            assertTrue(reader.uiState.value.showNextVolumePrompt)
            reader.consumeNextVolumePrompt()
            library.value = listOf(first, second.copy(seriesName = "其他"), third); runCurrent()
            assertEquals(3L, reader.uiState.value.nextVolume!!.id)
            library.value = listOf(first); runCurrent()
            assertNull(reader.uiState.value.nextVolume)
            reader.goToNextChapter()
            assertFalse(reader.uiState.value.showNextVolumePrompt)
        }
    }

    @Test fun nextAndPreviousAreSymmetricAndUseStoredIndexOnlyForContent() = runTest {
        withReader { reader ->
            reader.updatePosition(4, 1f)
            reader.goToNextChapter()
            runCurrent()
            assertEquals(1, reader.uiState.value.currentChapterIndex)
            assertEquals(0, reader.uiState.value.currentPosition)
            coVerify { chapters.getChapterContent(1, 13) }
            assertEquals(1 to 0, writes.last())
            reader.goToPreviousChapterAtEnd()
            runCurrent()
            assertEquals(0, reader.uiState.value.currentChapterIndex)
            assertTrue(reader.uiState.value.landAtChapterEnd)
            reader.clearLandAtChapterEnd(7)
            assertEquals(7, reader.uiState.value.currentPosition)
            assertFalse(reader.uiState.value.landAtChapterEnd)
            advanceTimeBy(3_001)
            runCurrent()
            assertEquals(0 to 7, writes.last())
        }
    }

    @Test fun searchHitInUnloadedSparseChapterLoadsTargetAndKeepsUtf16Offset() = runTest {
        withReader(structured = true) { reader ->
            coEvery { chapters.getChapterContent(1, 16) } returns "这是较长正文，目标词在这里。"
            reader.goToSearchResult(16, 7)
            runCurrent()
            assertEquals(2, reader.uiState.value.currentChapterIndex)
            val target = reader.consumePendingScrollTarget()!!
            assertEquals(7, target.offsetUtf16)
            assertEquals("chapter:16", target.documentKey)
            assertEquals("section_2", target.sectionId)
            coVerify { chapters.getChapterContent(1, 16) }
        }
    }

    @Test fun globalSearchRouteMapsSparseStoredChapterIndexBeforeLanding() = runTest {
        withReader(initialSearchIndex = 16, initialOffset = 2) { reader ->
            assertEquals(2, reader.uiState.value.currentChapterIndex)
            assertEquals(2, reader.uiState.value.searchTarget!!.offsetUtf16)
            assertEquals("chapter:16", reader.uiState.value.searchTarget!!.documentKey)
        }
    }

    @Test fun cancelledBookSearchCannotPublishLateResults() = runTest {
        val search = mockk<ChapterTextSearch>()
        val gate = CompletableDeferred<Unit>()
        coEvery { search.search(any(), any(), any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { gate.await() }
            listOf(GlobalSearchResult(1, "书", 16, "章", "命中"))
        }
        withReader(search = search) { reader ->
            reader.showSearch(true); reader.updateSearchQuery("词"); reader.searchInBook(); runCurrent()
            assertTrue(reader.uiState.value.isSearching)
            reader.showSearch(false); gate.complete(Unit); runCurrent()
            assertFalse(reader.uiState.value.showSearch)
            assertFalse(reader.uiState.value.isSearching)
            assertTrue(reader.uiState.value.searchResults.isEmpty())
            assertNull(reader.uiState.value.searchError)
        }
    }

    @Test fun bookSearchReadFailureHasMessageInsteadOfFalseNoMatch() = runTest {
        val search = mockk<ChapterTextSearch>()
        coEvery { search.search(any(), any(), any(), any(), any()) } throws IllegalStateException("file unavailable")
        withReader(search = search) { reader ->
            reader.updateSearchQuery("词"); reader.searchInBook(); runCurrent()
            assertTrue(reader.uiState.value.searchError!!.contains("file unavailable"))
            assertFalse(reader.uiState.value.hasSearched)
            assertFalse(reader.uiState.value.isSearching)
        }
    }

    @Test fun duplicateRequestsAndLatePositionCallbacksCannotRaceLoading() = runTest {
        withReader { reader ->
            val oldVersion = reader.uiState.value.scrollRequestVersion
            val deferred = CompletableDeferred<String>()
            coEvery { chapters.getChapterContent(1, 13) } coAnswers { deferred.await() }
            reader.updatePosition(5, 1f)
            reader.goToNextChapter()
            reader.goToNextChapter()
            runCurrent()
            reader.updatePosition(99, 1f, oldVersion)
            // An in-book transition keeps the reader mounted: isChapterSwitching is the in-flight
            // guard, isLoading is reserved for opening the book itself. Flipping isLoading here is
            // what used to blank the screen into a spinner and then flash "本章暂无内容".
            assertTrue(reader.uiState.value.isChapterSwitching)
            assertFalse(reader.uiState.value.isLoading)
            // The chapter being left stays on screen (and readable) until the new one is ready.
            assertEquals(0, reader.uiState.value.currentChapterIndex)
            assertNotNull(reader.uiState.value.currentChapter)
            deferred.complete("Second chapter")
            runCurrent()
            assertFalse(reader.uiState.value.isChapterSwitching)
            reader.updatePosition(99, 1f, oldVersion)
            reader.clearLandAtChapterEnd(99, oldVersion)
            assertEquals(1, reader.uiState.value.currentChapterIndex)
            assertEquals(0, reader.uiState.value.currentPosition)
            advanceTimeBy(3_001)
            runCurrent()
            assertEquals(listOf(1 to 0), writes)
        }
    }

    @Test fun failedChapterLoadKeepsCurrentChapterAndAllowsRetry() = runTest {
        withReader { reader ->
            coEvery { chapters.getChapterContent(1, 13) } throws IllegalStateException("load failure")
            reader.goToNextChapter()
            runCurrent()
            assertFalse(reader.uiState.value.isLoading)
            // A failed switch must release the in-flight guard, otherwise the reader would be stuck
            // refusing every later navigation.
            assertFalse(reader.uiState.value.isChapterSwitching)
            assertEquals(0, reader.uiState.value.currentChapterIndex)
            assertNotNull(reader.uiState.value.message)
            coEvery { chapters.getChapterContent(1, 13) } returns "Recovered"
            reader.goToNextChapter()
            runCurrent()
            assertEquals(1, reader.uiState.value.currentChapterIndex)
        }
    }

    @Test fun readerStateIsPersistedWithMonotonicSequence() = runTest {
        val readerStateDao = mockk<com.flowreader.app.data.local.dao.ReaderStateDao>(relaxed = true)
        val savedStates = mutableListOf<com.flowreader.app.data.local.entity.ReaderStateEntity>()
        coEvery { readerStateDao.saveIfNewer(any()) } coAnswers {
            val state = firstArg<com.flowreader.app.data.local.entity.ReaderStateEntity>()
            savedStates.add(state)
            com.flowreader.app.data.local.dao.ReaderStateSaveOutcome.SAVED
        }

        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { books.getBookById(1) } returns Book(id = 1, title = "Book", author = "Author", filePath = "book.txt", format = BookFormat.TXT)
            coEvery { chapters.getChapterMetadataList(1) } returns (0..2).map { Chapter(bookId = 1, index = it * 3 + 10, title = "Chapter $it") }
            coEvery { chapters.getChapterContent(1, any()) } returns "Chapter text"
            every { settings.appSettings } returns MutableStateFlow(AppSettings())
            every { settings.getDailyReadingGoal() } returns MutableStateFlow(30)
            every { tts.state } returns MutableStateFlow(TtsState())

            val reader = ReaderViewModel(
                SavedStateHandle(mapOf("bookId" to 1L)), books, chapters,
                mockk(relaxed = true), mockk(relaxed = true), settings, mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), tts,
                mockk(relaxed = true), mockk(relaxed = true), readerStateDao,
                chapterImageResolver = mockk(relaxed = true)
            )
            store.put("reader", reader)
            runCurrent()

            reader.updatePosition(4, 1f)
            reader.goToNextChapter()
            runCurrent()

            assertTrue(savedStates.isNotEmpty())
            val firstSeq = savedStates.first().navigationSequence
            assertTrue("Sequence must be positive", firstSeq > 0)
            for (i in 1 until savedStates.size) {
                assertTrue(
                    "Sequence must be strictly monotonic",
                    savedStates[i].navigationSequence >= savedStates[i - 1].navigationSequence
                )
            }
            val lastState = savedStates.last()
            assertNotNull(lastState.locatorJson)
            val locator = com.flowreader.app.domain.model.TextLocatorCodec.fromJson(lastState.locatorJson!!)
            assertNotNull(locator)
            assertEquals("section_1", locator!!.sectionId)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun readerRestoresPositionFromPersistedTextLocator() = runTest {
        val readerStateDao = mockk<com.flowreader.app.data.local.dao.ReaderStateDao>(relaxed = true)
        val initialLocator = com.flowreader.app.domain.model.TextLocator(
            version = 1,
            publicationId = "pub-1",
            revision = "rev-1",
            sectionId = "section_1",
            documentKey = "chapter:13",
            offsetUtf16 = 42
        )
        val locatorJson = com.flowreader.app.domain.model.TextLocatorCodec.toJson(initialLocator)
        val persistedEntity = com.flowreader.app.data.local.entity.ReaderStateEntity(
            bookId = 1,
            locatorJson = locatorJson,
            quality = "EXACT",
            navigationSequence = 15,
            updatedAtMillis = System.currentTimeMillis()
        )
        coEvery { readerStateDao.getState(1) } returns persistedEntity

        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            coEvery { books.getBookById(1) } returns Book(id = 1, title = "Book", author = "Author", filePath = "book.txt", format = BookFormat.TXT)
            coEvery { chapters.getChapterMetadataList(1) } returns (0..2).map { Chapter(bookId = 1, index = it * 3 + 10, title = "Chapter $it") }
            coEvery { chapters.getChapterContent(1, any()) } returns "Chapter content that is at least 50 characters long for testing."
            every { settings.appSettings } returns MutableStateFlow(AppSettings())
            every { settings.getDailyReadingGoal() } returns MutableStateFlow(30)
            every { tts.state } returns MutableStateFlow(TtsState())

            val reader = ReaderViewModel(
                SavedStateHandle(mapOf("bookId" to 1L)), books, chapters,
                mockk(relaxed = true), mockk(relaxed = true), settings, mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), tts,
                mockk(relaxed = true), mockk(relaxed = true), readerStateDao,
                chapterImageResolver = mockk(relaxed = true)
            )
            store.put("reader", reader)
            runCurrent()

            assertEquals(1, reader.uiState.value.currentChapterIndex)
            assertEquals(42, reader.uiState.value.currentPosition)
            assertTrue(reader.getNavigationSequence() >= 15)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
