package com.flowreader.app.ui.screens.reader

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flowreader.app.core.designsystem.component.FlowStateHost
import com.flowreader.app.core.designsystem.reader.ReaderPalettes
import com.flowreader.app.core.designsystem.reader.background
import com.flowreader.app.core.designsystem.reader.rememberReaderFontFamily
import com.flowreader.app.core.designsystem.reader.text
import com.flowreader.app.core.util.ReaderBehavior
import com.flowreader.app.core.util.ReaderCustomTheme
import com.flowreader.app.util.ShareCardGenerator
import com.flowreader.app.core.util.ReadingProgress
import com.flowreader.app.domain.model.BookFormat
import com.flowreader.app.domain.model.GestureAction
import com.flowreader.app.domain.model.PageMode
import com.flowreader.app.R
import com.flowreader.app.ui.screens.reader.components.AnnotationsDialog
import com.flowreader.app.ui.screens.reader.components.BookmarksDialog
import com.flowreader.app.ui.screens.reader.components.ReaderBackground
import com.flowreader.app.ui.screens.reader.components.ComicReader
import com.flowreader.app.ui.screens.reader.components.PagedReader
import com.flowreader.app.ui.screens.reader.components.PdfViewer
import com.flowreader.app.ui.screens.reader.components.ReaderContent
import com.flowreader.app.ui.screens.reader.components.ReaderImagePreviewDialog
import com.flowreader.app.ui.screens.reader.components.ReaderImagePreviewTarget
import com.flowreader.app.ui.screens.reader.components.ReaderPageCache
import com.flowreader.app.ui.screens.reader.components.ReaderControls
import com.flowreader.app.ui.screens.reader.components.ReaderFooter
import com.flowreader.app.ui.screens.reader.components.ReaderHeader
import com.flowreader.app.ui.screens.reader.components.ReaderSettingsSheet
import com.flowreader.app.ui.screens.reader.components.ReaderTocSheet
import com.flowreader.app.util.LegacyTocAdapter
import com.flowreader.app.domain.model.TocLocation
import com.flowreader.app.ui.screens.reader.components.SearchDialog
import com.flowreader.app.ui.screens.reader.components.ShareProgressDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
    onOpenNextVolume: (Long) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    // A delegated state read inside a delayed callback would see a newer navigation request.
    // Capture this rendered tree's generation so an outgoing page cannot impersonate its successor.
    val renderRequestVersion = uiState.scrollRequestVersion
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val view = LocalView.current
    val settings = uiState.readingSettings
    val lifecycleOwner = LocalLifecycleOwner.current
    val contentReady = !uiState.isLoading && !uiState.isChapterSwitching && uiState.currentChapter != null
    DisposableEffect(lifecycleOwner, viewModel, view, contentReady) {
        fun updateVisibility() {
            viewModel.onReaderVisibilityChanged(contentReady && view.hasWindowFocus() &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        val observer = LifecycleEventObserver { _, _ -> updateVisibility() }
        val focusObserver = android.view.ViewTreeObserver.OnWindowFocusChangeListener { updateVisibility() }
        val treeObserver = view.viewTreeObserver
        treeObserver.addOnWindowFocusChangeListener(focusObserver)
        lifecycleOwner.lifecycle.addObserver(observer)
        updateVisibility()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (treeObserver.isAlive) treeObserver.removeOnWindowFocusChangeListener(focusObserver)
            viewModel.onReaderVisibilityChanged(false)
        }
    }
    val defaultBookmarkLabel = stringResource(R.string.reader_bookmark_default)

    // GetContent rather than OpenDocument: no persistable permission is needed because the importer
    // copies the image into filesDir straight away and never touches the content:// uri again.
    val backgroundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.onBackgroundImageSelected(uri) }

    // Font files are frequently reported as application/octet-stream, so no MIME filter.
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.onCustomFontSelected(uri) }

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val nextVolumeAction = stringResource(R.string.series_next_open)
    val nextVolumeMessage = uiState.nextVolume?.let { stringResource(R.string.series_next_prompt, it.title) }
    LaunchedEffect(uiState.showNextVolumePrompt) {
        if (uiState.showNextVolumePrompt && uiState.nextVolume != null && nextVolumeMessage != null) {
            val nextId = uiState.nextVolume!!.id
            viewModel.consumeNextVolumePrompt()
            snackbarScope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(nextVolumeMessage, nextVolumeAction, withDismissAction = true,
                    duration = androidx.compose.material3.SnackbarDuration.Long)
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenNextVolume(nextId)
            }
        }
    }
    val message = uiState.message
    LaunchedEffect(message) {
        if (message != null) {
            viewModel.clearMessage()
            // The event is consumed even if navigation interrupts its display.
            snackbarScope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    // Typeset pages outlive chapter switches. The reader below is re-created for every navigation
    // (a new scrollRequestVersion), so the cache has to live above that boundary — otherwise the
    // pager restarts from an empty page list and reports the chapter as empty while it re-measures.
    val pageCache = viewModel.pageCache
    val paginationScope = rememberCoroutineScope()

    // 插图放大预览（S5 / 4.6 第 10 条）：在**屏幕层**承载，两个渲染器共用；
    // 用独立 Dialog 呈现，因此预览期间的手势不会传到正文翻页层。
    var previewImage by remember { mutableStateOf<ReaderImagePreviewTarget?>(null) }
    var showSpeechPlugin by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.speechEnabled) { if (!uiState.speechEnabled) showSpeechPlugin = false }

    val contentScrollState = key(renderRequestVersion, settings.pageMode) {
        rememberScrollState()
    }

    val scrollScope = rememberCoroutineScope()
    var scrollPageInProgress by remember(contentScrollState) { mutableStateOf(false) }
    fun scrollPage(forward: Boolean) {
        if (uiState.book?.format == BookFormat.PDF) {
            if (forward) viewModel.goToNextChapter() else viewModel.goToPreviousChapter()
            return
        }
        if (uiState.isLoading || uiState.isChapterSwitching || scrollPageInProgress ||
            contentScrollState.isScrollInProgress ||
            contentScrollState.maxValue == Int.MAX_VALUE
        ) return
        val step = (contentScrollState.viewportSize * 0.9f).toInt().coerceAtLeast(1)
        val target = (contentScrollState.value + if (forward) step else -step).coerceIn(0, contentScrollState.maxValue)
        if (target == contentScrollState.value) {
            if (forward) viewModel.goToNextChapter() else viewModel.goToPreviousChapterAtEnd()
            return
        }
        scrollPageInProgress = true
        scrollScope.launch {
            try {
                if (settings.pageMode == PageMode.NONE) contentScrollState.scrollTo(target)
                else contentScrollState.animateScrollTo(target)
            } finally {
                scrollPageInProgress = false
            }
        }
    }

    // Auto night mode is re-evaluated every minute, so 19:00 actually arrives while reading.
    // Before v52 the hour was sampled once during composition and never again.
    val isNightWindow by produceState(
        initialValue = ReaderBehavior.isNightHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)),
        key1 = settings.autoNightMode
    ) {
        while (settings.autoNightMode) {
            value = ReaderBehavior.isNightHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
            delay(60_000L)
        }
    }

    val visualStyle = com.flowreader.app.core.designsystem.theme.LocalFlowVisualStyle.current
    val palette = remember(visualStyle, settings.palette, settings.nightPalette, settings.autoNightMode, isNightWindow,
        settings.customTextColorArgb, settings.customBackgroundColorArgb) {
        val id = if (settings.autoNightMode && isNightWindow) settings.nightPalette else settings.palette
        ReaderCustomTheme.resolve(
            ReaderPalettes.of(id, visualStyle),
            settings.customTextColorArgb,
            settings.customBackgroundColorArgb
        )
    }

    // v52 cleared this dead setting: keepScreenOn was persisted but never applied.
    DisposableEffect(activity, settings.keepScreenOn) {
        val window = activity?.window
        if (window != null) {
            if (settings.keepScreenOn) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(activity, view, uiState.isImmersiveMode) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (uiState.isImmersiveMode) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            activity?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    fun dispatchGesture(action: GestureAction) {
        when (action) {
            GestureAction.PREVIOUS_PAGE -> scrollPage(forward = false)
            GestureAction.NEXT_PAGE -> scrollPage(forward = true)
            GestureAction.TOGGLE_CONTROLS -> viewModel.toggleControls()
            GestureAction.SHOW_SETTINGS -> viewModel.showSettings(true)
            GestureAction.SHOW_BOOKMARKS -> viewModel.showBookmarks(true)
            GestureAction.SHOW_TOC -> viewModel.showChapterList(true)
            GestureAction.ADD_BOOKMARK -> viewModel.addBookmark("第 ${uiState.currentChapterIndex + 1} 章书签")
            GestureAction.NONE -> Unit
        }
    }

    val fontFamily = rememberReaderFontFamily(settings)
    val shareCardLabel = stringResource(R.string.reader_share_card_chooser)

    // Derived so scroll updates repaint only the control layer, never the body. In PAGED mode
    // the page fraction comes from the ViewModel instead of the (unused) scroll state.
    var pdfVisiblePage by remember(uiState.book?.id) { mutableStateOf(-1) }
    val chapterFraction: Float by remember(settings.pageMode, contentScrollState) {
        derivedStateOf {
            if (uiState.book?.format == BookFormat.PDF) {
                if (pdfVisiblePage == uiState.currentChapterIndex) 1f else 0f
            } else if (settings.pageMode == PageMode.PAGED) {
                viewModel.currentChapterFraction
            } else {
                ReadingProgress.scrollFraction(contentScrollState.value, contentScrollState.maxValue)
            }
        }
    }

    val toggleControlsLabel = stringResource(R.string.reader_toggle_controls)
    ReaderBackground(
        palette = palette,
        backgroundImagePath = uiState.readingSettings.backgroundImagePath,
        scrimAlpha = uiState.readingSettings.backgroundScrimAlpha,
        modifier = Modifier
            .fillMaxSize()
            // TalkBack custom action: body double-tap toggles the control bar.
            .semantics {
                onClick(label = toggleControlsLabel) {
                    viewModel.toggleControls()
                    true
                }
            }
    ) {
        FlowStateHost(
            isLoading = uiState.isLoading,
            isEmpty = uiState.currentChapter == null && uiState.error == null && !uiState.isLoading,
            error = uiState.error,
            emptyTitle = stringResource(R.string.reader_chapter_empty),
            onRetry = { viewModel.retryLoadBook() },
            onDismissError = onBackClick,
            contentColor = palette.text
        ) {
            val chapter = uiState.currentChapter
            val book = uiState.book
            if (chapter != null) {
                key(if (book?.format == BookFormat.PDF) 0L else renderRequestVersion, settings.pageMode) {
                    if (book != null && book.format == BookFormat.COMIC) {
                        ComicReader(
                            chapters = uiState.chapters,
                            currentChapterIndex = uiState.currentChapterIndex,
                            settings = settings,
                            palette = palette,
                            onTap = { offset, size ->
                                dispatchGesture(ReaderBehavior.tapAction(offset.x, size.width, settings))
                            },
                            onHorizontalDrag = { drag -> dispatchGesture(ReaderBehavior.swipeAction(drag, settings)) },
                            onPageVisible = { index -> viewModel.setCurrentComicPage(index) },
                            onPositionChanged = { position ->
                                val total = uiState.chapters.size
                                val fraction = if (total > 1) position.toFloat() / (total - 1) else 0f
                                viewModel.updatePosition(position, fraction)
                            }
                        )
                    } else if (book != null && book.format == BookFormat.PDF) {
                        PdfViewer(
                            filePath = book.filePath,
                            currentPage = uiState.currentChapterIndex,
                            textColor = palette.text,
                            backgroundColor = palette.background,
                            annotations = uiState.annotations.filter { it.chapterIndex == uiState.currentChapterIndex },
                            onAddPdfAnnotation = { page, x0, y0, x1, y1 ->
                                viewModel.addPdfAnnotation(page, x0, y0, x1, y1)
                            },
                            onPageChange = { if (it >= uiState.chapters.size) viewModel.goToNextChapter() else viewModel.goToChapter(it) },
                            onTap = { viewModel.toggleControls() },
                            onPageRendered = { viewModel.updatePosition(0, 1f); pdfVisiblePage = uiState.currentChapterIndex }
                        )
                    } else if (settings.pageMode == PageMode.PAGED) {
                        PagedReader(
                            chapter = chapter,
                            chapterIndex = uiState.currentChapterIndex,
                            settings = settings,
                            palette = palette,
                            fontFamily = fontFamily,
                            annotations = uiState.annotations,
                            currentPosition = uiState.currentPosition,
                            totalChapters = uiState.chapters.size,
                            initialLocator = uiState.searchTarget?.takeIf { it.documentKey == "chapter:${chapter.index}" },
                            onInitialLocatorApplied = { viewModel.clearSearchTarget() },
                            landAtChapterEnd = uiState.landAtChapterEnd,
                            onLandedAtChapterEnd = { pos -> viewModel.clearLandAtChapterEnd(pos, renderRequestVersion) },
                            onPageChanged = { page, fraction -> viewModel.updatePosition(page, fraction, renderRequestVersion) },
                            onChapterPageChanged = { prepared, page, fraction ->
                                viewModel.onPagedPageVisible(prepared, page, fraction, renderRequestVersion)
                            },
                            onMiddleTap = { viewModel.toggleControls() },
                            onDoubleTap = { dispatchGesture(settings.gestureSettings.doubleTapAction) },
                            onHorizontalDrag = { drag ->
                                dispatchGesture(ReaderBehavior.swipeAction(drag, settings))
                            },
                            onHighlightSelection = { text, start, end ->
                                viewModel.addAnnotation(text, start, end)
                            },
                            onNoteSelection = { text, start, end, note ->
                                viewModel.addAnnotation(text, start, end, note = note)
                            },
                            onHighlightRanges = { selection -> viewModel.addAnnotationRanges(selection) },
                            onBookmarkRanges = { selection -> viewModel.addBookmarkRanges(selection) },
                            onNoteRanges = { selection, note -> viewModel.addAnnotationRanges(selection, note = note) },
                            onBookmarkSelection = { text, _, _ ->
                                viewModel.addBookmark(text.ifBlank { defaultBookmarkLabel })
                            },
                            onPreviousChapter = { viewModel.goToPreviousChapterAtEnd() },
                            onNextChapter = { viewModel.goToNextChapter() },
                            pageCache = pageCache,
                            paginationScope = paginationScope,
                            bookId = uiState.book?.id ?: 0L,
                            loadChapterForPagination = { index -> viewModel.loadChapterForPagination(index) },
                            loadImagesForPagination = { index -> viewModel.loadChapterImagesForPagination(index) },
                            loadMarksForPagination = { index -> viewModel.loadChapterMarksForPagination(index) },
                            images = uiState.chapterImages,
                            marks = uiState.chapterMarks,
                            onImageClick = { path, alt -> previewImage = ReaderImagePreviewTarget(path, alt) }
                        )
                    } else {
                        ReaderContent(
                            chapter = chapter,
                            settings = settings,
                            palette = palette,
                            fontFamily = fontFamily,
                            scrollState = contentScrollState,
                            chapterIndex = uiState.currentChapterIndex,
                            currentPosition = uiState.currentPosition,
                            onPageTurn = { scrollPage(it) },
                            initialLocator = uiState.searchTarget?.takeIf { it.documentKey == "chapter:${chapter.index}" },
                            onInitialLocatorApplied = { viewModel.clearSearchTarget() },
                            annotations = uiState.annotations,
                            totalChapters = uiState.chapters.size,
                            landAtChapterEnd = uiState.landAtChapterEnd,
                            onLandedAtChapterEnd = { pos -> viewModel.clearLandAtChapterEnd(pos, renderRequestVersion) },
                            onTap = { offset, size ->
                                dispatchGesture(ReaderBehavior.tapAction(offset.x, size.width, settings))
                            },
                            onDoubleTap = { dispatchGesture(settings.gestureSettings.doubleTapAction) },
                            onHorizontalDrag = { drag ->
                                dispatchGesture(ReaderBehavior.swipeAction(drag, settings))
                            },
                            onHighlightSelection = { text, start, end ->
                                viewModel.addAnnotation(text, start, end)
                            },
                            onNoteSelection = { text, start, end, note ->
                                viewModel.addAnnotation(text, start, end, note = note)
                            },
                            onNoteRanges = { selection, note ->
                                viewModel.addAnnotationRanges(selection, note = note)
                            },
                            onBookmarkSelection = { text, _, _ ->
                                viewModel.addBookmark(text.ifBlank { defaultBookmarkLabel })
                            },
                            // 跨块选择（主计划第 9 节）：多段源范围直接落库，
                            // 中间未选中的文字不会被吞掉，重叠也不会重复输出。
                            onHighlightRanges = { selection ->
                                viewModel.addAnnotationRanges(selection)
                            },
                            onBookmarkRanges = { selection ->
                                viewModel.addBookmarkRanges(selection)
                            },
                            onPositionChanged = { position ->
                                viewModel.updatePosition(position, chapterFraction, renderRequestVersion)
                            },
                            onLocatorProjected = { projection ->
                                viewModel.onScrollViewportProjected(projection)
                            },
                            // Scrolling past either edge continues into the neighbouring chapter; going
                            // back enters the previous chapter at its end.
                            onPreviousChapter = { viewModel.goToPreviousChapterAtEnd() },
                            onNextChapter = { viewModel.goToNextChapter() },
                            images = uiState.chapterImages,
                            marks = uiState.chapterMarks,
                            onImageClick = { path, alt -> previewImage = ReaderImagePreviewTarget(path, alt) }
                        )
                    }
                }
            }
        }

        // 插图放大预览：只在需要时存在，关闭不产生导航或阅读进度。
        previewImage?.let { target ->
            ReaderImagePreviewDialog(
                path = target.path,
                alt = target.alt,
                onDismiss = { previewImage = null }
            )
        }

        // Reading Header Status Bar (左上角轻灵章节页眉)
        if (uiState.currentChapter != null && uiState.error == null && !uiState.isLoading) {
            ReaderHeader(
                chapterTitle = uiState.currentChapter?.title ?: "",
                palette = palette,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
            )
        }

        // Reading Footer Status Bar (电量 + 时间 + 章节进度)
        if (uiState.currentChapter != null && uiState.error == null && !uiState.isLoading) {
            ReaderFooter(
                chapterTitle = uiState.currentChapter?.title ?: "",
                progressFraction = ReadingProgress.fraction(
                    uiState.currentChapterIndex,
                    chapterFraction,
                    uiState.chapters.size
                ),
                palette = palette,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            )
        }

        // 色温 / 护眼滤蓝光: a warm wash over the reading surface. Drawn after the body and the
        // footer but before the controls, so the controls stay crisp.
        if (settings.colorTemperature > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color(0xFFFFB74D).copy(
                            alpha = ReaderBehavior.colorTemperatureAlpha(settings.colorTemperature)
                        )
                    )
            )
        }

        // Always composed: it animates its own reveal so showing the bar does not re-inflate an
        // app bar and the bottom surface on every tap (v0.0.3 responsiveness fix).
        ReaderControls(
            visible = uiState.showControls && uiState.error == null && !uiState.isLoading,
            bookTitle = uiState.book?.title ?: "",
            chapterTitle = uiState.currentChapter?.title ?: "",
            currentChapter = uiState.currentChapterIndex + 1,
            totalChapters = uiState.chapters.size,
            pageBasedDocument = uiState.book?.format == BookFormat.PDF,
            hasNextVolume = uiState.nextVolume != null,
            progressProvider = {
                // 按内容权重（主计划 10.2）；ViewModel 在索引未就绪时内部回退等权。
                if (uiState.book?.format == BookFormat.PDF) {
                    ReadingProgress.fraction(uiState.currentChapterIndex, if (pdfVisiblePage == uiState.currentChapterIndex) 1f else 0f, uiState.chapters.size)
                } else viewModel.bookProgressFraction
            },
            isTtsPlaying = uiState.isTtsPlaying,
            ttsVisible = uiState.speechEnabled,
            ttsAvailable = uiState.currentChapter?.content?.isNotBlank() == true && uiState.book?.format !in listOf(com.flowreader.app.domain.model.BookFormat.PDF, com.flowreader.app.domain.model.BookFormat.COMIC),
            palette = palette,
            chapterTitleAt = { fraction ->
                val index = viewModel.chapterOrdinalAt(fraction)
                uiState.chapters.getOrNull(index)?.title ?: "第 ${index + 1} 章"
            },
            onBackClick = onBackClick,
            onPreviousChapterClick = { viewModel.goToPreviousChapter() },
            onNextChapterClick = { viewModel.goToNextChapter() },
            onChapterClick = { viewModel.showChapterList(true) },
            onBookmarkClick = { viewModel.showBookmarks(true) },
            onAddBookmark = { viewModel.addBookmark(defaultBookmarkLabel) },
            onTtsClick = { showSpeechPlugin = true },
            onAnnotationClick = { viewModel.showAnnotations(true) },
            onSearchClick = { viewModel.showSearch(true) },
            onImmersiveClick = { viewModel.toggleImmersiveMode() },
            onShareClick = { viewModel.shareProgress() },
            onSettingsClick = { viewModel.showSettings(true) },
            onNightModeToggle = { viewModel.toggleNightMode() },
            onProgressCommit = { viewModel.goToProgress(it) },
            onDismiss = { viewModel.toggleControls() },
            isBookmarked = uiState.bookmarks.any {
                it.chapterIndex == uiState.currentChapterIndex && it.position == uiState.currentPosition
            }
        )

        if (showSpeechPlugin && uiState.speechEnabled) {
            androidx.compose.material3.MaterialTheme(
                colorScheme = com.flowreader.app.core.designsystem.theme.flowColorScheme(palette.isDark)
            ) {
            com.flowreader.app.ui.screens.settings.SpeechControlsSheet(
                onDismiss = { showSpeechPlugin = false },
                onRequestContent = { viewModel.toggleTts() }
            )
            }
        }

        if (uiState.showChapterList || uiState.showBookmarks || uiState.showAnnotations) {
            // 优先用**真实目录树**（toc_nodes，EPUB 导入时写入的真实层级）；
            // 旧书没有节点时才回退到兼容适配器。两条路径都只在章节/书身份变化时重算。
            val tocTree = remember(
                uiState.tocTree,
                uiState.book?.publicationId,
                uiState.book?.activeRevision,
                uiState.chapters
            ) {
                uiState.tocTree ?: LegacyTocAdapter.toTocTree(
                    publicationId = uiState.book?.publicationId ?: "book-${uiState.book?.id ?: 0}",
                    revision = uiState.book?.activeRevision.orEmpty(),
                    chapters = uiState.chapters
                )
            }
            // 当前定位必须用**该棵树自己的** section 坐标：
            // 真实树用清单 sectionId（由 ordinal 反查），兼容树用 section_<ordinal>。
            val currentLocation = remember(tocTree, uiState.currentChapterIndex) {
                TocLocation(
                    sectionId = tocTree.sectionOrdinalById.entries
                        .firstOrNull { it.value == uiState.currentChapterIndex }
                        ?.key
                        ?: LegacyTocAdapter.sectionIdOf(uiState.currentChapterIndex)
                )
            }
            ReaderTocSheet(
                bookTitle = uiState.book?.title.orEmpty(),
                tocTree = tocTree,
                currentLocation = currentLocation,
                expandedNodeIds = uiState.tocExpandedNodeIds,
                onExpandedChange = { viewModel.setTocExpandedNodes(it) },
                bookmarks = uiState.bookmarks,
                annotations = uiState.annotations,
                chapters = uiState.chapters,
                palette = palette,
                onNodeSelect = { nodeId -> viewModel.goToTocNode(tocTree, nodeId) },
                onBookmarkSelect = { viewModel.goToBookmark(it) },
                onAnnotationSelect = { viewModel.goToChapter(it.chapterIndex) },
                onDismiss = { viewModel.showChapterList(false); viewModel.showBookmarks(false); viewModel.showAnnotations(false) },
                initialTab = when { uiState.showBookmarks -> 1; uiState.showAnnotations -> 2; else -> 0 },
                onBookmarkDelete = { viewModel.deleteBookmark(it) },
                onAnnotationDelete = { viewModel.deleteAnnotation(it) },
                onAnnotationNoteUpdate = { id, note -> viewModel.updateAnnotationNote(id, note) }
            )
        }

        if (uiState.showSearch) {
            SearchDialog(
                query = uiState.searchQuery,
                results = uiState.searchResults,
                isSearching = uiState.isSearching,
                hasSearched = uiState.hasSearched,
                progress = uiState.searchProgress,
                error = uiState.searchError,
                onCancel = { viewModel.cancelSearch() },
                onQueryChange = { viewModel.updateSearchQuery(it) },
                onSearch = { viewModel.searchInBook() },
                onResultClick = { chapterIndex, locatorOffset ->
        viewModel.goToSearchResult(chapterIndex, locatorOffset)
    },
                onDismiss = { viewModel.showSearch(false) }
            )
        }

        if (uiState.showSettings) {
            ReaderSettingsSheet(
                settings = settings,
                onSettingsChange = { viewModel.updateReadingSettings(it) },
                onPickBackgroundImage = { backgroundPickerLauncher.launch("image/*") },
                onClearBackgroundImage = { viewModel.clearBackgroundImage() },
                onPickFont = { fontPickerLauncher.launch("*/*") },
                onClearFont = { viewModel.clearCustomFont() },
                onDismiss = { viewModel.showSettings(false) },
                darkOverride = palette.isDark
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = if (uiState.showControls) 172.dp else 24.dp)
        )

        uiState.shareText?.let { shareText ->
            ShareProgressDialog(
                shareText = shareText,
                onDismiss = { viewModel.clearShareText() },
                onShare = { intent ->
                    context.startActivity(intent)
                    viewModel.clearShareText()
                },
                onShareCard = {
                    val book = uiState.book ?: run { viewModel.clearShareText(); return@ShareProgressDialog }
                    val progress = ReadingProgress.fraction(
                        uiState.currentChapterIndex,
                        chapterFraction,
                        uiState.chapters.size
                    )
                    val percent = if (progress.isFinite()) (progress * 100).toInt() else 0
                    val card = ShareCardGenerator.generate(
                        cacheDir = context.cacheDir,
                        bookTitle = book.title,
                        chapterTitle = uiState.currentChapter?.title ?: "",
                        progressPercent = percent,
                        backgroundArgb = palette.backgroundArgb,
                        textArgb = palette.textArgb,
                        accentArgb = palette.textArgb
                    )
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        card
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, shareCardLabel))
                    viewModel.clearShareText()
                }
            )
        }
    }
}
