package com.flowreader.app.ui.screens.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.component.PaperLandscape
import com.flowreader.app.core.designsystem.reader.ReaderMetrics
import com.flowreader.app.core.designsystem.reader.ReaderPalette
import com.flowreader.app.core.designsystem.reader.background
import com.flowreader.app.core.designsystem.reader.paragraphSpacing
import com.flowreader.app.core.designsystem.reader.readerBodyStyle
import com.flowreader.app.core.designsystem.reader.readerChapterTitleStyle
import com.flowreader.app.core.designsystem.reader.readerHeadingStyle
import com.flowreader.app.core.designsystem.reader.secondaryText
import com.flowreader.app.core.designsystem.reader.text
import com.flowreader.app.core.util.ChapterBody
import com.flowreader.app.core.util.ChineseConverter
import com.flowreader.app.core.util.ReaderBehavior
import com.flowreader.app.domain.model.Annotation
import com.flowreader.app.domain.model.AnnotationDisplayStyle
import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.model.ChineseConversion
import com.flowreader.app.domain.model.GestureAction
import com.flowreader.app.domain.model.PageTurnAnimation
import com.flowreader.app.domain.model.ReadingSettings
import com.flowreader.app.util.epub.ChapterImageRef
import com.flowreader.app.util.epub.ChapterMarks
import com.flowreader.feature.reader.Block
import com.flowreader.feature.reader.ChapterPaginator
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Darkest the incoming page's leading shadow gets while it fully covers the page below. */
private const val COVER_SHADOW_MAX_ALPHA = 0.30f

/**
 * The `PAGED` page-turn mode: the chapter is measured with the live reader styles and split
 * into horizontal pages, then swiped left/right. Tap zones flip a single page instead of the
 * whole chapter. Adjacent chapters share one pager and one animation; metadata changes only
 * when the visible page settles. Explicit navigation and layout changes reset the window.
 */
@Composable
fun PagedReader(
    chapter: Chapter,
    chapterIndex: Int,
    settings: ReadingSettings,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    annotations: List<Annotation>,
    currentPosition: Int,
    onPageChanged: (Int, Float) -> Unit,
    onMiddleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onHorizontalDrag: (Float) -> Unit = {},
    onHighlightSelection: (String, Int, Int) -> Unit,
    onBookmarkSelection: (String, Int, Int) -> Unit,
    totalChapters: Int = 1,
    landAtChapterEnd: Boolean = false,
    onLandedAtChapterEnd: (Int) -> Unit = {},
    onPreviousChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
    /** Receives the ready chapter and its local page after a continuous page turn settles. */
    onChapterPageChanged: ((ReaderChapterPages, Int, Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
    paginationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Typeset pages shared across chapter switches. Hoisted above the `key(scrollRequestVersion)`
     * boundary in `ReaderScreen` so a re-created reader finds the pages it already measured
     * instead of starting from an empty list.
     */
    pageCache: ReaderPageCache = remember { ReaderPageCache() },
    paginationScope: CoroutineScope = androidx.compose.runtime.rememberCoroutineScope(),
    /** Pages are never shared between books; part of the cache key. */
    bookId: Long = 0L,
    /**
     * Loads another chapter's text so its pages can be typeset ahead of navigation. Returning null
     * (book boundary, unreadable chapter) simply skips that neighbour.
     */
    loadChapterForPagination: suspend (Int) -> Chapter? = { null },
    /**
     * 供**相邻章预排版**使用：图片必须与正文一起算进缓存键，否则预热出的页
     * 与真正翻过去时要的页不是同一份（少图 / 重排）。
     */
    loadImagesForPagination: suspend (Int) -> List<ChapterImageRef> = { emptyList() },
    /**
     * 供**相邻章预排版**使用：呈现标注（schema v3）同理 —— 各章标注不同，
     * 必须按目标章自己的标注测量，否则预热页与真实页不一致。
     */
    loadMarksForPagination: suspend (Int) -> ChapterMarks = { ChapterMarks.EMPTY },
    /** 当前章的结构图片（S5 / 4.6）：章内 U+FFFC 偏移 → 本机绝对路径。 */
    images: List<ChapterImageRef> = emptyList(),
    /** 当前章的呈现标注（schema v3）：列表前缀 / 表头加粗 / 注音。旧书为空。 */
    marks: ChapterMarks = ChapterMarks.EMPTY,
    /** 点击插图 → 打开放大预览（4.6 第 10 条）。手势由预览层消费，不传给翻页。 */
    onImageClick: (path: String, alt: String?) -> Unit,
    onNoteSelection: ((String, Int, Int, String) -> Unit)? = null,
    onHighlightRanges: ((MultiBlockSelection) -> Unit)? = null,
    onBookmarkRanges: ((MultiBlockSelection) -> Unit)? = null,
    onNoteRanges: ((MultiBlockSelection, String) -> Unit)? = null,
    initialLocator: com.flowreader.app.domain.model.TextLocator? = null,
    onInitialLocatorApplied: () -> Unit = {}
) {
    val bodyStyle = readerBodyStyle(settings, fontFamily, palette)
    val titleStyle = readerChapterTitleStyle(settings, fontFamily, palette)
    val headingStyle = readerHeadingStyle(settings, fontFamily, palette)
    val gap = paragraphSpacing(settings)
    val textMeasurer = rememberTextMeasurer()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 标注偏移是**正文坐标**，段落偏移相对 body（标题回显被剥掉）：与图片同一套位移约定。
    val bodyShift = remember(chapter.content, chapter.title) {
        chapter.content.length - ChapterBody.stripLeadingTitleEcho(chapter.content, chapter.title).length
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        val contentWidth = ReaderMetrics.contentWidthDp(
            availableWidthDp = maxWidth.value,
            fontSize = settings.fontSize,
            marginDp = settings.horizontalMarginDp.toFloat(),
            lineChars = settings.lineChars
        ).dp
        val contentWidthPx = with(density) { contentWidth.toPx() }
        // Reserve exactly what PageContent consumes: status bar + header height + breathing space at the top,
        // plus navigation bar + footer band at the bottom.
        val statusBarTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val cutoutTopDp = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
        val safeTopDp = maxOf(statusBarTopDp, cutoutTopDp)

        val navBarBottomDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val cutoutBottomDp = WindowInsets.displayCutout.asPaddingValues().calculateBottomPadding()
        val safeBottomDp = maxOf(navBarBottomDp, cutoutBottomDp)

        val topInsetDp = safeTopDp + (ReaderMetrics.HEADER_HEIGHT_DP + ReaderMetrics.TOP_BREATHING_SPACE_DP).dp
        val bottomInsetDp = safeBottomDp + (ReaderMetrics.FOOTER_HEIGHT_DP + 32f).dp
        val pageHeightPx = with(density) {
            (maxHeight - topInsetDp - bottomInsetDp).coerceAtLeast(1.dp).toPx().toInt()
        }
        val indentEnabled = ReaderMetrics.firstLineIndentSp(settings.fontSize, settings.firstLineIndent) > 0f
        val gapPx = with(density) { gap.toPx() }.roundToInt()
        val titleGapPx = with(density) {
            ReaderMetrics.chapterTitleGapDp(settings.fontSize, settings.paragraphSpacing).dp.toPx()
        }.roundToInt()

        // Measured height must equal rendered height: the paginator reserves one gap *between*
        // fragments (PageContent uses Arrangement.spacedBy), so nothing here may add per-item
        // padding beyond the title gap, which the renderer adds identically.
        val imageHeights = remember(contentWidthPx, pageHeightPx) { java.util.concurrent.ConcurrentHashMap<String, Int>() }

        /** Exact height an illustration will occupy at the current column width. */
        fun imageBlockHeight(path: String): Int = imageHeights.getOrPut(path) {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { android.graphics.BitmapFactory.decodeFile(path, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                // 位图解不出：可能是 SVG（BitmapFactory 不认）或缺文件。
                // SVG 用头部固有尺寸占位，否则整页预排高度与真实渲染对不上。
                val svg = svgIntrinsicSize(path)
                if (svg != null) {
                    (contentWidthPx * svg.second / svg.first).toInt().coerceAtMost(pageHeightPx)
                } else {
                    // Missing file: ReaderImage falls back to a fixed placeholder.
                    with(density) { 120.dp.toPx() }.toInt()
                }
            } else {
                (contentWidthPx * options.outHeight / options.outWidth).toInt().coerceAtMost(pageHeightPx)
            }
        }

        /** 按给定标注测量一段文本；测量与绘制必须同源（同一 buildParagraphContent）。 */
        fun measureWithMarks(text: String, offset: Int, indent: Boolean, isTitle: Boolean, paragraphMarks: ParagraphMarks): Int {
            // 图片块是不可分割的整体；这里的规则见 measureImageAwareText 的注释。
            measureImageAwareText(text, isTitle) { measuredText ->
                imageBlockHeight(measuredText.removePrefix(IMAGE_MARKER_PREFIX).removeSuffix("]"))
            }?.let { return it }
            val display = buildParagraphContent(
                text,
                offset,
                emptyList(),
                indent && !isTitle,
                settings.chineseConversion,
                marks = paragraphMarks
            ).annotatedString
            val base = when {
                isTitle -> titleStyle
                text.startsWith("## ") -> headingStyle
                else -> bodyStyle
            }
            // 含注音的段落要和渲染侧用**同一个**预留行高样式，否则测量高度与真实排版分叉。
            // 条件与 ReaderParagraph 里的 paragraphStyle 必须一字不差。
            val style = if (base === bodyStyle && paragraphMarks.rubyBases.isNotEmpty()) {
                bodyStyle.withRubyLineReserve(bodyStyle.fontSize.value)
            } else {
                base
            }
            val result = textMeasurer.measure(
                text = display,
                style = style,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = contentWidthPx.toInt())
            )
            return result.size.height.toInt() + if (isTitle) titleGapPx else 0
        }

        /** 当前章的测量：标注取当前章的。 */
        fun measureBlock(text: String, offset: Int, indent: Boolean, isTitle: Boolean): Int = measureWithMarks(
            text,
            offset,
            indent,
            isTitle,
            paragraphMarksFor(marks, offset, text.length, bodyShift)
        )

        // Pagination is O(blocks × text measurement) and used to run inside composition, which
        // blocked the first frame of the reader on a long chapter. It now runs on the default
        // dispatcher and the result is retained in [ReaderPageCache].
        // marks 参与测量闭包，必须进 remember key，否则换标注后量的是旧标注。
        val paginator = remember(chapter, settings, maxWidth, maxHeight, textMeasurer, marks, bodyShift) {
            ChapterPaginator(::measureBlock)
        }

        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val fontCacheKey = remember(settings.fontFamily, settings.customFontPath, fontFamily) {
            val file = settings.customFontPath?.let { java.io.File(it) }
            if (file?.isFile == true) {
                "custom:${file.path}:${file.length()}:${file.lastModified()}"
            } else {
                when (settings.fontFamily) {
                    com.flowreader.app.domain.model.ReaderFontFamily.DEFAULT,
                    com.flowreader.app.domain.model.ReaderFontFamily.SERIF,
                    com.flowreader.app.domain.model.ReaderFontFamily.SANS_SERIF,
                    com.flowreader.app.domain.model.ReaderFontFamily.MONOSPACE -> fontFamily.toString()
                    else -> "system:${settings.fontFamily.name}"
                }
            }
        }
        val fingerprint = remember(chapter.content) { contentFingerprint(chapter.content) }
        val pageKey = ReaderPageKey(
            bookId = bookId,
            chapterIndex = chapterIndex,
            contentLength = chapter.content.length,
            contentHash = chapter.content.hashCode(),
            title = chapter.title,
            settings = paginationSettings(settings),
            fontFamilyKey = fontCacheKey,
            widthPx = contentWidthPx.toInt(),
            heightPx = pageHeightPx,
            gapPx = gapPx,
            imageKey = remember(images) { imageKeyOf(images) },
            marksKey = remember(marks) { marksKeyOf(marks) },
            contentFingerprint = fingerprint,
            environmentKey = "${density.density}:${density.fontScale}:${configuration.locales.toLanguageTags()}:${android.os.Build.VERSION.SDK_INT}"
        )

        // null means "not typeset yet"; an *empty* list means the chapter genuinely draws nothing.
        // Conflating the two is exactly what rendered "本章暂无内容" over a healthy chapter for as
        // long as re-measuring took (the reader is recreated on every chapter change, so the page
        // list always restarted empty).
        // Keep the live window independently of the LRU: eviction must never unmount a page
        // that is already displayed or prepared in this pager.
        val layoutKey = pageKey.copy(
            chapterIndex = 0,
            contentLength = 0,
            contentHash = 0,
            title = "",
            imageKey = "",
            marksKey = "",
            contentFingerprint = ""
        )
        val chapterWindow = remember(layoutKey) { mutableStateMapOf<Int, ReaderChapterPages>() }
        var pagination by remember(pageKey) {
            mutableStateOf(
                pageCache.get(pageKey) ?: chapterWindow[chapterIndex]
                    ?.takeIf { it.chapter == chapter && it.images == images && it.marks == marks }?.pages
            )
        }

        var previewPage by remember(pageKey) { mutableStateOf(pageCache.preview(pageKey, currentPosition)) }
        LaunchedEffect(pageKey) {
            if (pagination == null && !landAtChapterEnd) {
                previewPage = pageCache.previewVersion.map { pageCache.preview(pageKey, currentPosition) }.filterNotNull().first()
            }
        }

        LaunchedEffect(pageKey) {
            if (pagination != null) return@LaunchedEffect
            pagination = pageCache.getOrCompute(pageKey, paginationScope) {
                withContext(paginationDispatcher) {
                    val blocks = buildChapterBlocks(chapter.title, chapter.content, indentEnabled, images)
                    val job = currentCoroutineContext()
                    paginator.paginateProgressively(blocks, pageHeightPx, gapPx, { index, fragments ->
                        job.ensureActive()
                        pageCache.publishPreview(pageKey, index, fragments)
                    })
                }
            }
        }

        suspend fun prepareWindowChapter(target: Int): ReaderChapterPages? {
            if (target !in 0 until totalChapters) return null
            val neighbour = loadChapterForPagination(target) ?: return null
            val neighbourImages = loadImagesForPagination(target)
            val neighbourMarks = loadMarksForPagination(target)
            val neighbourBodyShift = neighbour.content.length -
                ChapterBody.stripLeadingTitleEcho(neighbour.content, neighbour.title).length
            val neighbourKey = pageKey.copy(
                chapterIndex = target,
                contentLength = neighbour.content.length,
                contentHash = neighbour.content.hashCode(),
                title = neighbour.title,
                imageKey = imageKeyOf(neighbourImages),
                marksKey = marksKeyOf(neighbourMarks),
                contentFingerprint = contentFingerprint(neighbour.content)
            )
            val neighbourPages = pageCache.getOrCompute(neighbourKey, paginationScope, foreground = false) {
                withContext(paginationDispatcher) {
                    val neighbourBlocks = buildChapterBlocks(neighbour.title, neighbour.content, indentEnabled, neighbourImages)
                    val job = currentCoroutineContext()
                    paginator.paginateProgressively(neighbourBlocks, pageHeightPx, gapPx, { index, fragments ->
                        job.ensureActive()
                        pageCache.publishPreview(neighbourKey, index, fragments)
                    }) { text, offset, indent, isTitle ->
                        measureWithMarks(
                            text,
                            offset,
                            indent,
                            isTitle,
                            paragraphMarksFor(neighbourMarks, offset, text.length, neighbourBodyShift)
                        )
                    }
                }
            }
            return ReaderChapterPages(target, neighbour, neighbourPages, neighbourImages, neighbourMarks, neighbourBodyShift)
        }

        val paginated = pagination
        when (pageRenderState(paginated)) {
            // Still measuring: the chapter is not empty, so it must never claim to be.
            PageRenderState.TYPESETTING -> {
                // A preview requested by a legacy page number cannot represent a source locator.
                val ready = previewPage.takeIf { initialLocator == null }
                if (ready == null) {
                    TypesettingPlaceholder(palette)
                } else {
                    // Render the exact requested page while the remaining chapter is measured.
                    // No provisional page count or fraction is written to reading progress.
                    PageContent(
                        ready, bodyStyle, titleStyle, headingStyle, contentWidth,
                        topInsetDp, bottomInsetDp, gap, titleGapPx.let { with(density) { it.toDp() } },
                        palette, annotations, settings.chineseConversion, settings.annotationDisplayStyle,
                        onHighlightSelection, onBookmarkSelection, onNoteSelection,
                        onHighlightRanges, onBookmarkRanges, onNoteRanges,
                        chapterIndex, chapter.content, {}, altByPath(images), ::imageBlockHeight,
                        onImageClick, marks, bodyShift,
                        modifier = Modifier.readerTapGestures(settings.gestureSettings, { _, _ -> onMiddleTap() }, onDoubleTap)
                    )
                }
                return@BoxWithConstraints
            }
            // Measured, and genuinely nothing to draw.
            PageRenderState.EMPTY -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.reader_chapter_empty), color = palette.text.copy(alpha = 0.5f))
                }
                return@BoxWithConstraints
            }
            PageRenderState.PAGES -> Unit
        }
        // pageRenderState returned PAGES, so this is non-null and non-empty.
        val pages = paginated.orEmpty()

        // Chapter identity is deliberately absent: sequential chapter turns retain the pager.
        key(layoutKey) {
            val currentPrepared = remember(chapterIndex, chapter, pages, images, marks, bodyShift) {
                ReaderChapterPages(chapterIndex, chapter, pages, images, marks, bodyShift)
            }
            val retainedChapters = chapterWindow.toMap()
            val renderChapters = remember(retainedChapters, currentPrepared) {
                retainedChapters + (chapterIndex to currentPrepared)
            }
            val latestRenderChapters by rememberUpdatedState(renderChapters)
            val windowPages = remember(renderChapters, chapterIndex) { readerWindowPages(renderChapters.values, chapterIndex) }
            val latestWindowPages by rememberUpdatedState(windowPages)
            val initialPage = if (landAtChapterEnd && pages.isNotEmpty()) {
                pages.size - 1
            } else if (initialLocator != null) {
                paginator.findPageForLocator(pages, initialLocator.copy(
                    documentKey = "text/main",
                    offsetUtf16 = (initialLocator.offsetUtf16 - bodyShift).coerceAtLeast(0),
                    blockId = null
                ))
            } else {
                currentPosition.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            }
            val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { windowPages.size })
            LaunchedEffect(pagerState) { if (initialLocator != null) onInitialLocatorApplied() }
            var pageTurnInProgress by remember { mutableStateOf(false) }
            val selectedPages = remember { mutableStateMapOf<String, Boolean>() }

            LaunchedEffect(pageKey) {
                chapterWindow[chapterIndex] = currentPrepared
                for (delta in intArrayOf(1, 2, -1)) {
                    val target = chapterIndex + delta
                    if (target !in 0 until totalChapters) continue
                    val prepared = chapterWindow[target] ?: prepareWindowChapter(target) ?: continue
                    // Changing the window during a drag would move the page being animated.
                    snapshotFlow { !pagerState.isScrollInProgress && !pageTurnInProgress }.first { it }
                    chapterWindow[target] = prepared
                }
                snapshotFlow { !pagerState.isScrollInProgress && !pageTurnInProgress }.first { it }
                chapterWindow.keys.toList().filter { it !in (chapterIndex - 1)..(chapterIndex + 2) }
                    .forEach { chapterWindow.remove(it) }
            }

            LaunchedEffect(landAtChapterEnd) {
                if (landAtChapterEnd && pages.isNotEmpty()) {
                    val lastPage = pages.size - 1
                    val fraction = if (pages.size > 1) 1f else 0f
                    onPageChanged(lastPage, fraction)
                    onLandedAtChapterEnd(lastPage)
                }
            }

            // Position reports never feed back into scroll commands. Explicit navigation recreates
            // this reader with a new scrollRequestVersion at the screen boundary.
            val latestPageChanged by rememberUpdatedState(onPageChanged)
            val latestChapterPageChanged by rememberUpdatedState(onChapterPageChanged)
            val latestChapterIndex by rememberUpdatedState(chapterIndex)
            val latestPrevious by rememberUpdatedState(onPreviousChapter)
            val latestNext by rememberUpdatedState(onNextChapter)
            LaunchedEffect(pagerState) {
                snapshotFlow {
                    val settled = pagerState.settledPage
                    val visible = pagerState.layoutInfo.visiblePagesInfo.firstOrNull { it.index == settled }
                    val consistent = pagerState.currentPage == settled && visible != null &&
                        latestWindowPages.getOrNull(visible.index)?.key == visible.key
                    (pagerState.isScrollInProgress || !consistent) to visible?.key
                }
                    .filter { !it.first }
                    .distinctUntilChanged()
                    .collect { (_, visibleKey) ->
                        val address = ReaderPageAddress.fromKey(visibleKey) ?: return@collect
                        val visibleChapter = latestRenderChapters[address.chapterIndex] ?: return@collect
                        val count = visibleChapter.pages.size
                        val fraction = if (count > 1) address.pageIndex.toFloat() / (count - 1) else 0f
                        val report = latestChapterPageChanged
                        if (report != null) {
                            report(visibleChapter, address.pageIndex, fraction)
                        } else if (address.chapterIndex == latestChapterIndex) {
                            latestPageChanged(address.pageIndex, fraction)
                        } else if (address.chapterIndex > latestChapterIndex) {
                            latestNext()
                        } else {
                            latestPrevious()
                        }
                    }
            }

            fun turnPage(forward: Boolean) {
                if (selectedPages.isNotEmpty()) return
                if (pageTurnInProgress || pagerState.isScrollInProgress) return
                val address = ReaderPageAddress.fromKey(
                    pagerState.layoutInfo.visiblePagesInfo
                        .firstOrNull { it.index == pagerState.settledPage }?.key
                ) ?: return
                val sourceIndex = latestWindowPages.indexOf(address)
                if (sourceIndex < 0) return
                val target = sourceIndex + if (forward) 1 else -1
                val missingChapter = address.chapterIndex + if (forward) 1 else -1
                if (target !in latestWindowPages.indices && missingChapter !in 0 until totalChapters) {
                    if (forward) latestNext()
                    return
                }
                // Claim synchronously, before launching; never queue a relative page increment.
                pageTurnInProgress = true
                scope.launch {
                    try {
                        if (target in latestWindowPages.indices) {
                            pagerState.animateScrollToPage(target)
                        } else {
                            // Cold edge: keep the outgoing page mounted while preparing, then use
                            // the same pager animation. A cache miss never recreates the reader.
                            val prepared = prepareWindowChapter(missingChapter)
                            if (prepared == null) {
                                if (forward) latestNext() else latestPrevious()
                                return@launch
                            }
                            chapterWindow[missingChapter] = prepared
                            if (prepared.pages.isEmpty()) {
                                if (forward) latestNext() else latestPrevious()
                                return@launch
                            }
                            val newPages = readerWindowPages(chapterWindow.values, address.chapterIndex)
                            snapshotFlow { pagerState.pageCount }.first { it == newPages.size }
                            val destination = ReaderPageAddress(missingChapter, if (forward) 0 else prepared.pages.lastIndex)
                            val destinationIndex = newPages.indexOf(destination)
                            if (destinationIndex >= 0) pagerState.animateScrollToPage(destinationIndex)
                        }
                    } finally {
                        pageTurnInProgress = false
                    }
                }
            }

            val switchThresholdPx = with(density) { 56.dp.toPx() }
            val boundaryScroll = remember(pagerState, totalChapters, switchThresholdPx) {
                ReaderBoundaryScrollConnection(
                    horizontal = true,
                    threshold = switchThresholdPx,
                    canEnterPrevious = { (latestWindowPages.firstOrNull()?.chapterIndex ?: 0) > 0 && !pagerState.canScrollBackward },
                    canEnterNext = { (latestWindowPages.lastOrNull()?.chapterIndex ?: totalChapters) < totalChapters - 1 && !pagerState.canScrollForward },
                    onPrevious = { turnPage(false) },
                    onNext = { turnPage(true) }
                )
            }

            DisposableEffect(settings.volumeKeyTurnPage, pagerState, windowPages.size) {
                if (settings.volumeKeyTurnPage) {
                    com.flowreader.app.util.VolumeKeyHandler.onVolumeDown = {
                        turnPage(true)
                        true
                    }
                    com.flowreader.app.util.VolumeKeyHandler.onVolumeUp = {
                        turnPage(false)
                        true
                    }
                }
                onDispose {
                    com.flowreader.app.util.VolumeKeyHandler.onVolumeDown = null
                    com.flowreader.app.util.VolumeKeyHandler.onVolumeUp = null
                }
            }

            val handleTap: (Offset, Size) -> Unit = { offset, size ->
                when (if (selectedPages.isNotEmpty()) GestureAction.NONE else ReaderBehavior.tapAction(offset.x, size.width, settings)) {
                    GestureAction.PREVIOUS_PAGE -> turnPage(false)
                    GestureAction.NEXT_PAGE -> turnPage(true)
                    GestureAction.NONE -> Unit
                    else -> onMiddleTap()
                }
            }

            HorizontalPager(
                state = pagerState,
                key = { windowPages[it].key },
                beyondViewportPageCount = 1,
                userScrollEnabled = !pageTurnInProgress && selectedPages.isEmpty(),
                modifier = Modifier
                    .fillMaxSize()
                    .readerBoundaryGestures(boundaryScroll)
                    .readerTapGestures(
                        gestures = settings.gestureSettings,
                        onTap = handleTap,
                        onDoubleTap = {
                            when (settings.gestureSettings.doubleTapAction) {
                                GestureAction.PREVIOUS_PAGE -> turnPage(false)
                                GestureAction.NEXT_PAGE -> turnPage(true)
                                else -> onDoubleTap()
                            }
                        }
                    )
            ) { pageIndex ->
                val address = windowPages[pageIndex]
                val renderedChapter = renderChapters[address.chapterIndex] ?: return@HorizontalPager
                val coverEnabled = settings.pageTurnAnimation == PageTurnAnimation.COVER
                PageContent(
                    fragments = renderedChapter.pages[address.pageIndex],
                    bodyStyle = bodyStyle,
                    titleStyle = titleStyle,
                    headingStyle = headingStyle,
                    contentWidth = contentWidth,
                    topInset = topInsetDp,
                    bottomInset = bottomInsetDp,
                    gap = gap,
                    titleGap = with(density) {
                        ReaderMetrics.chapterTitleGapDp(settings.fontSize, settings.paragraphSpacing).dp
                    },
                    palette = palette,
                    annotations = annotations.filter { it.chapterIndex == address.chapterIndex },
                    conversion = settings.chineseConversion,
                    annotationDisplayStyle = settings.annotationDisplayStyle,
                    onHighlightSelection = onHighlightSelection,
                    onBookmarkSelection = onBookmarkSelection,
                    onNoteSelection = onNoteSelection,
                    onHighlightRanges = onHighlightRanges,
                    onBookmarkRanges = onBookmarkRanges,
                    onNoteRanges = onNoteRanges,
                    chapterIndex = address.chapterIndex,
                    sourceText = renderedChapter.chapter.content,
                    onSelectionActiveChanged = { active ->
                        if (active) selectedPages[address.key] = true else selectedPages.remove(address.key)
                    },
                    imageAlts = remember(renderedChapter.images) { altByPath(renderedChapter.images) },
                    // 测量与绘制同源：这里的像素高度就是分页器为该图预留的高度（4.6 第 7 条）。
                    imageHeightPx = { path -> imageBlockHeight(path) },
                    onImageClick = onImageClick,
                    marks = renderedChapter.marks,
                    bodyShift = renderedChapter.bodyShift,
                    modifier = Modifier.testTag("reader-page-${address.key}").then(
                        if (coverEnabled) {
                            // 覆盖翻页: pin the page on the left so the next page
                            // slides over it, and shade the incoming page's leading edge. The offset
                            // decides which page stays pinned; currentPage flips halfway through the
                            // animation and must not suddenly pin the incoming page. All state is
                            // read in the draw/layer phase, so scrolling invalidates the layer, not the
                            // composition.
                            Modifier
                                .graphicsLayer {
                                    val pageX = pagerState.layoutInfo.visiblePagesInfo
                                        .firstOrNull { it.index == pageIndex }
                                        ?.offset?.toFloat() ?: 0f
                                    translationX = if (pageX < 0f) -pageX else 0f
                                }
                                .background(palette.background)
                                .drawWithContent {
                                    drawContent()
                                    val pageX = pagerState.layoutInfo.visiblePagesInfo
                                        .firstOrNull { it.index == pageIndex }
                                        ?.offset?.toFloat() ?: 0f
                                    if (pageX <= 0f || size.width <= 0f) return@drawWithContent
                                    val progress = (1f - pageX / size.width).coerceIn(0f, 1f)
                                    if (progress <= 0f) return@drawWithContent
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(
                                                Color.Black.copy(alpha = COVER_SHADOW_MAX_ALPHA * progress),
                                                Color.Transparent
                                            ),
                                            startX = 0f,
                                            endX = size.width * 0.09f
                                        )
                                    )
                                }
                        } else {
                            Modifier
                        }
                    )
                )
            }
        }
    }
}

/**
 * One measured page.
 *
 * The vertical space this consumes must match exactly what [measureBlock] told the paginator:
 * 40dp top + 40dp bottom + the footer band, one [gap] *between* fragments (via
 * `Arrangement.spacedBy`, not per-item padding) and a [titleGap] under the chapter title. Any extra
 * padding here pushes the last line past the page edge, where it was silently clipped.
 */
@Composable
private fun PageContent(
    fragments: List<ChapterPaginator.Fragment>,
    bodyStyle: TextStyle,
    titleStyle: TextStyle,
    headingStyle: TextStyle,
    contentWidth: androidx.compose.ui.unit.Dp,
    topInset: androidx.compose.ui.unit.Dp,
    bottomInset: androidx.compose.ui.unit.Dp,
    gap: androidx.compose.ui.unit.Dp,
    titleGap: androidx.compose.ui.unit.Dp,
    palette: ReaderPalette,
    annotations: List<Annotation>,
    conversion: ChineseConversion,
    annotationDisplayStyle: AnnotationDisplayStyle,
    onHighlightSelection: (String, Int, Int) -> Unit,
    onBookmarkSelection: (String, Int, Int) -> Unit,
    onNoteSelection: ((String, Int, Int, String) -> Unit)?,
    onHighlightRanges: ((MultiBlockSelection) -> Unit)?,
    onBookmarkRanges: ((MultiBlockSelection) -> Unit)?,
    onNoteRanges: ((MultiBlockSelection, String) -> Unit)?,
    chapterIndex: Int,
    sourceText: String,
    onSelectionActiveChanged: (Boolean) -> Unit,
    /** 路径 → alt（占位文案与无障碍）。 */
    imageAlts: Map<String, String>,
    /** 分页器测得的图片像素高度；渲染必须用同一个值，否则图文会互相挤压。 */
    imageHeightPx: (path: String) -> Int,
    onImageClick: (path: String, alt: String?) -> Unit,
    /** 本章呈现标注（schema v3）：列表前缀 / 表头加粗 / 注音。 */
    marks: ChapterMarks,
    /** body 相对章正文的位移（标题回显长度），标注坐标换算用。 */
    bodyShift: Int,
    modifier: Modifier = Modifier
) {
    val selectionModel = remember { CrossBlockSelectionModel() }
    val selectionRegistry = remember { ScrollViewportRegistry() }
    var selectionVersion by remember { mutableStateOf(0) }
    val latestSelectionActiveChanged by rememberUpdatedState(onSelectionActiveChanged)
    DisposableEffect(Unit) {
        onDispose { latestSelectionActiveChanged(false) }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                top = topInset,
                bottom = bottomInset
            )
            .clipToBounds(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.width(contentWidth),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            fragments.forEach { fragment ->
                if (fragment.isTitle) {
                    Box(Modifier.fillMaxWidth()) {
                        if (palette.id == com.flowreader.app.domain.model.ReaderPaletteId.PAPER &&
                            com.flowreader.app.core.designsystem.theme.LocalFlowVisualStyle.current == com.flowreader.app.domain.model.AppVisualStyle.WARM_PAPER
                        ) {
                            PaperLandscape(
                                Modifier.matchParentSize().padding(start = contentWidth * .45f),
                                palette.secondaryText.copy(alpha = .25f)
                            )
                        }
                        Text(
                            text = ChineseConverter.convert(fragment.text, conversion),
                            style = titleStyle,
                            modifier = Modifier.padding(bottom = titleGap)
                        )
                    }
                } else if (fragment.isHeading) {
                    Text(
                        text = ChineseConverter.convert(fragment.text.removePrefix("## "), conversion),
                        style = headingStyle
                    )
                } else if (imagePathFromMarker(fragment.text) != null) {
                    val imagePath = imagePathFromMarker(fragment.text).orEmpty()
                    ReaderImage(
                        path = imagePath,
                        tint = palette.text,
                        alt = imageAlts[imagePath],
                        heightPx = imageHeightPx(imagePath),
                        onPreview = { onImageClick(imagePath, imageAlts[imagePath]) }
                    )
                } else {
                    val fragmentEnd = fragment.paragraphStart + fragment.text.length
                    val fragmentAnnotations = annotations.filter {
                        it.intersectsParagraph(fragment.paragraphStart, fragmentEnd)
                    }
                    ReaderParagraph(
                        paragraph = fragment.text,
                        paragraphStart = fragment.paragraphStart,
                        annotations = fragmentAnnotations,
                        indent = fragment.indent,
                        bodyStyle = bodyStyle,
                        // The gap comes from the Column's arrangement, matching the paginator.
                        paragraphGap = 0.dp,
                        palette = palette,
                        conversion = conversion,
                        annotationDisplayStyle = annotationDisplayStyle,
                        onHighlightSelection = onHighlightSelection,
                        onBookmarkSelection = onBookmarkSelection,
                        onNoteSelection = onNoteSelection,
                        chapterIndex = chapterIndex,
                        sourceText = sourceText,
                        documentKey = "chapter:$chapterIndex",
                        blockId = "page-paragraph:${fragment.paragraphStart}",
                        registry = selectionRegistry,
                        selectionModel = selectionModel,
                        selectionVersion = selectionVersion,
                        onSelectionChanged = {
                            selectionVersion++
                            latestSelectionActiveChanged(selectionModel.selectedBlocks().isNotEmpty())
                        },
                        onHighlightRanges = onHighlightRanges ?: { selection ->
                            onHighlightSelection(selection.text, selection.ranges.first().startUtf16, selection.ranges.last().endUtf16Exclusive)
                        },
                        onBookmarkRanges = onBookmarkRanges ?: { selection ->
                            onBookmarkSelection(selection.text, selection.ranges.first().startUtf16, selection.ranges.last().endUtf16Exclusive)
                        },
                        onNoteRanges = onNoteRanges ?: onNoteSelection?.let { callback ->
                            { selection, note -> callback(selection.text, selection.ranges.first().startUtf16, selection.ranges.last().endUtf16Exclusive, note) }
                        },
                        onSelectionActiveChanged = {
                            latestSelectionActiveChanged(selectionModel.selectedBlocks().isNotEmpty())
                        },
                        // 呈现标注（schema v3）：列表前缀 / 表头加粗 / 注音，与测量同源。
                        paragraphMarks = paragraphMarksFor(
                            marks,
                            fragment.paragraphStart,
                            fragment.text.length,
                            bodyShift
                        )
                    )
                }
            }
        }
    }
}

/**
 * 被 `findSplit` 试探性切开的图片标记所报的高度：**永远放不下**。
 *
 * 图片标记是一个整体，绝不能被 `ChapterPaginator.findSplit` 当文本切开。一旦切开，
 * 片段就不再以 `]` 结尾，[measureImageAwareText] 的完整标记分支不成立，
 * 分页器会退回按"普通段落"测量（高度极小）→ 于是它认为自己**成功**切出了一段
 * `[IMG:/data/user/0/…`，并且这一片段会被当作正文渲染。
 * 真机现象：插图下面多出好几行绝对路径文字（0.2.9 已复现）。
 */
internal const val UNSPLITTABLE_IMAGE_HEIGHT_PX = Int.MAX_VALUE / 4

/**
 * 图片块的测量规则（纯函数，便于回归测试）。
 *
 * @return 图片块的高度；**null 表示"这不是图片块"**，由调用方按普通文本测量。
 */
internal fun measureImageAwareText(text: String, isTitle: Boolean, imageHeightPx: (imageMarkerText: String) -> Int): Int? = when {
    isTitle -> null
    // 完整标记：按图片真实尺寸预留高度（与 Composable 绘制同一套几何）。
    text.startsWith(IMAGE_MARKER_PREFIX) && text.endsWith("]") -> imageHeightPx(text)
    // 标记的**任何**残缺前缀：报"放不下"，让分页器把整张图搬到下一页。
    isImageMarkerFragment(text) -> UNSPLITTABLE_IMAGE_HEIGHT_PX
    else -> null
}

/**
 * 这段文本是否是图片标记的**残缺前缀**（`[`、`[I`、`[IM`、`[IMG`、`[IMG:`、`[IMG:/da` …）。
 *
 * 必须覆盖**短于** `[IMG:` 的前缀：`ChapterPaginator.findSplit` 是二分搜索，它会试探
 * `text.substring(0, 1)`、`(0, 2)`… 这些比 `[IMG:` 更短的片段。
 * 只判断 `startsWith("[IMG:")` 会漏掉 `[IMG` 本身 —— 0.2.10 就是这样漏的，
 * 导致 `[IMG` 与其余部分被分到两页，正文里出现 `[IMG` 和 `:/data/user/0/…` 两段路径文字。
 */
internal fun isImageMarkerFragment(text: String): Boolean {
    if (text.isEmpty()) return false
    return if (text.length < IMAGE_MARKER_PREFIX.length) {
        // 短于 "[IMG:"：只要它是该前缀的前缀，就可能是被切开的一半。
        IMAGE_MARKER_PREFIX.startsWith(text)
    } else {
        text.startsWith(IMAGE_MARKER_PREFIX)
    }
}

/**
 * Splits raw chapter text into measurable [Block]s.
 *
 * Extracted from the composable so the background prefetch of a neighbouring chapter builds its
 * blocks exactly the way the visible chapter does — any divergence would cache the wrong pages.
 *
 * S5（主计划 4.6）：规范源文本里结构图片只占一个 [STRUCTURAL_IMAGE_MARKER]，不是文本标记，
 * 而渲染层此前只认旧标记。这里把占位符换成**图片块**，复用既有的「测量与渲染同源」通道
 * （测量占位高度与实际 Composable 用的是同一套几何），**数据库里的正文一个字符都不改**。
 */
internal fun buildChapterBlocks(
    title: String,
    content: String,
    indentEnabled: Boolean,
    /**
     * **没有默认值**：预排版相邻章时曾因为默认空列表而缓存出"没有图"的页列表，
     * 翻过去命中缓存后只显示一个 U+FFFC 占位方框（真机已复现）。
     * 默认值在这里是危险的 —— 让编译器强制每个调用点显式表态。
     */
    images: List<ChapterImageRef>
): List<Block> = buildList {
    add(Block(title, 0, isTitle = true))
    var cumulative = 0
    // Imported files often repeat the chapter heading as the first body line; drawing it again
    // showed the title twice with a bold sub-heading look.
    val body = ChapterBody.stripLeadingTitleEcho(content, title)
    // `body` 是 `content` 的后缀；图片偏移是**正文坐标**，必须换算后才能匹配。
    val bodyShift = content.length - body.length
    val imagesByOffset = if (images.isEmpty()) emptyMap() else images.associateBy { it.offsetUtf16 }

    body.split("\n\n").forEach { paragraph ->
        val trimmed = paragraph.trim()
        val start = cumulative + paragraph.indexOf(trimmed)
        if (trimmed.isNotBlank()) {
            if (imagesByOffset.isNotEmpty() && trimmed.indexOf(STRUCTURAL_IMAGE_MARKER) >= 0) {
                appendTextAndImages(this, trimmed, start, bodyShift, imagesByOffset, indentEnabled)
            } else {
                when {
                    trimmed.startsWith(IMAGE_MARKER_PREFIX) && trimmed.endsWith("]") -> add(Block(trimmed, start, paragraph = false))
                    trimmed.startsWith("## ") -> add(Block(trimmed, start, isHeading = true, indent = false))
                    else -> add(Block(trimmed, start, indent = indentEnabled))
                }
            }
        }
        cumulative += paragraph.length + 2
    }
}

/**
 * 把含图片占位符的一段拆成「文本段 / 图片块 / 文本段…」。
 *
 * 偏移必须连续：图片块占用它自己的那个偏移，紧跟其后的文本段从「该偏移 + 1」继续，
 * 否则批注、书签与进度会整体错位。
 */
private fun appendTextAndImages(
    into: MutableList<Block>,
    text: String,
    baseOffset: Int,
    bodyShift: Int,
    imagesByOffset: Map<Int, ChapterImageRef>,
    indentEnabled: Boolean
) {
    var firstRun = true
    // 切分走共用的纯函数：偏移只允许有一个答案（分页与滚动必须一致）。
    splitParagraphForImages(text, baseOffset, bodyShift, imagesByOffset).forEach { segment ->
        when (segment) {
            is ReaderSegment.Text -> {
                into += Block(
                    text = segment.text,
                    offset = segment.offset,
                    indent = indentEnabled && firstRun
                )
                firstRun = false
            }

            is ReaderSegment.Image -> {
                into += Block(
                    text = imageBlockToken(segment.image),
                    offset = segment.offset,
                    paragraph = false
                )
            }
        }
    }
}

/**
 * Shown only while a chapter is being typeset and no cached pages exist for it.
 *
 * Deliberately *not* [R.string.reader_chapter_empty]: the chapter is not empty, it is being
 * measured, and saying "本章暂无内容" while that happens is the defect this replaces.
 */
@Composable
private fun TypesettingPlaceholder(palette: ReaderPalette) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.reader_chapter_paginating), color = palette.text.copy(alpha = 0.4f))
    }
}
