package com.flowreader.app.ui.screens.reader.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.component.PaperLandscape
import com.flowreader.app.core.designsystem.reader.ReaderMetrics
import com.flowreader.app.core.designsystem.reader.ReaderPalette
import com.flowreader.app.core.designsystem.reader.background
import com.flowreader.app.core.designsystem.reader.paragraphSpacing
import com.flowreader.app.core.designsystem.reader.readerBodyStyle
import com.flowreader.app.core.designsystem.reader.readerChapterTitleStyle
import com.flowreader.app.core.designsystem.reader.readerHeadingStyle
import com.flowreader.app.core.designsystem.reader.text
import com.flowreader.app.core.designsystem.theme.flowColorScheme
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.app.core.util.ChapterBody
import com.flowreader.app.core.util.ChineseConverter
import com.flowreader.app.domain.model.Annotation
import com.flowreader.app.domain.model.AnnotationDisplayStyle
import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.model.ChineseConversion
import com.flowreader.app.domain.model.DisplayMapping
import com.flowreader.app.domain.model.MultiBlockSelectionAssembler
import com.flowreader.app.domain.model.ReadingSettings
import com.flowreader.app.util.epub.ChapterImageRef
import com.flowreader.app.util.epub.ChapterMarks
import com.flowreader.feature.reader.ScrollViewportProjector
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * The reader body.
 *
 * Every style here comes from `:core`'s `ReaderTypography`, so the font family, the imported
 * custom font, the line height, the paragraph gap and the first-line indent all take effect —
 * before v52 the renderer hard-coded `bodyLarge` and dropped all four settings on the floor.
 *
 * Since v54 long-pressing a paragraph opens an in-house text selection engine
 * ([ReaderParagraph]) with its own handles and the floating [ReaderSelectionBar]; the selected
 * range can be turned into a highlight, a bookmark or a copy. The platform `SelectionContainer`
 * was deliberately not used: its selection state API is `internal` in Compose 1.7.x.
 */
@Composable
fun ReaderContent(
    chapter: Chapter,
    settings: ReadingSettings,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    scrollState: ScrollState,
    onTap: (Offset, Size) -> Unit,
    onDoubleTap: () -> Unit,
    onHorizontalDrag: (Float) -> Unit,
    onHighlightSelection: (String, Int, Int) -> Unit,
    onBookmarkSelection: (String, Int, Int) -> Unit,
    onPositionChanged: (Int) -> Unit,
    /**
     * 跨块选择提交（主计划第 9 节）。
     *
     * 与 [onHighlightSelection] 的区别是**表达能力**：这里给出**多段不相连的源范围**，
     * 因此跨段落选择不会把中间未选中的文字一并吞掉，重叠也不会重复输出。
     * 无法装配（源文本缺失/只选中人工字符）时给出 null，调用方必须保持既有批注不变。
     */
    onHighlightRanges: (MultiBlockSelection) -> Unit = {},
    onBookmarkRanges: (MultiBlockSelection) -> Unit = {},
    annotations: List<Annotation> = emptyList(),
    totalChapters: Int = 1,
    chapterIndex: Int = chapter.index,
    currentPosition: Int = 0,
    onPageTurn: (Boolean) -> Unit = {},
    landAtChapterEnd: Boolean = false,
    onLandedAtChapterEnd: (Int) -> Unit = {},
    onPreviousChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
    /**
     * 滚动视口投影回调（S5 / 主计划 6.6）。
     *
     * 与 [onPositionChanged] 的区别是**单位**：这里给出的是规范源文本 UTF-16 偏移
     * （经 [ScrollViewportProjector] 由 `TextLayoutResult` 行盒投影得到），
     * 而不是随字号/行距/机型变化的像素值。传 null 表示当前布局尚不足以给出可靠源坐标，
     * 调用方应保持既有权威锚点不动，绝不拿像素顶替。
     */
    onLocatorProjected: (ScrollViewportProjection) -> Unit = {},
    /** 当前章的结构图片（S5 / 4.6）：章内 U+FFFC 偏移 → 本机绝对路径。 */
    images: List<ChapterImageRef> = emptyList(),
    /** 当前章的呈现标注（schema v3）：列表前缀 / 表头加粗 / 注音。旧书为空。 */
    marks: ChapterMarks = ChapterMarks.EMPTY,
    /** 点击插图 → 打开放大预览（4.6 第 10 条）：手势由预览层消费，不传给翻页。 */
    onImageClick: (path: String, alt: String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    onNoteSelection: ((String, Int, Int, String) -> Unit)? = null,
    onNoteRanges: ((MultiBlockSelection, String) -> Unit)? = null,
    initialLocator: com.flowreader.app.domain.model.TextLocator? = null,
    onInitialLocatorApplied: () -> Unit = {}
) {
    // Same echo-stripping as the paged renderer: the heading is drawn from the table of
    // contents, so a copy at the top of the body would show it twice.
    val bodyText = remember(chapter.content, chapter.title) {
        ChapterBody.stripLeadingTitleEcho(chapter.content, chapter.title)
    }
    // 图片偏移是 **content 坐标**，而段落偏移相对 body：必须补回被去掉的标题回显长度。
    val bodyShift = chapter.content.length - bodyText.length
    val paragraphs = remember(bodyText) { bodyText.split("\n\n") }
    val imagesByOffset = remember(images) {
        if (images.isEmpty()) emptyMap() else images.associateBy { it.offsetUtf16 }
    }
    val imageAlts = remember(images) { altByPath(images) }

    // 该章节在规范内容坐标里的文档 key：与 ViewModel 构造 locator 时使用的规则保持一致。
    val scrollDocumentKey = remember(chapter.index) { "chapter:${chapter.index}" }
    val bodyStyle = readerBodyStyle(settings, fontFamily, palette)
    val titleStyle = readerChapterTitleStyle(settings, fontFamily, palette)
    val headingStyle = readerHeadingStyle(settings, fontFamily, palette)
    val paragraphGap = paragraphSpacing(settings)
    val indentSp = ReaderMetrics.firstLineIndentSp(settings.fontSize, settings.firstLineIndent)
    val latestPrevious by rememberUpdatedState(onPreviousChapter)
    val latestNext by rememberUpdatedState(onNextChapter)
    val latestPositionChanged by rememberUpdatedState(onPositionChanged)
    val latestLanded by rememberUpdatedState(onLandedAtChapterEnd)
    val latestPageTurn by rememberUpdatedState(onPageTurn)

    DisposableEffect(settings.volumeKeyTurnPage) {
        if (settings.volumeKeyTurnPage) {
            com.flowreader.app.util.VolumeKeyHandler.onVolumeDown = {
                latestPageTurn(true)
                true
            }
            com.flowreader.app.util.VolumeKeyHandler.onVolumeUp = {
                latestPageTurn(false)
                true
            }
        }
        onDispose {
            com.flowreader.app.util.VolumeKeyHandler.onVolumeDown = null
            com.flowreader.app.util.VolumeKeyHandler.onVolumeUp = null
        }
    }

    var positionRestored by remember(scrollState) { mutableStateOf(false) }
    // 视口投影注册表：每个已测量段落登记自己的块几何。滚动停止时用它把像素翻译成源坐标；
    // 跨块选择也用它做窗口坐标命中测试（手指可以拖到别的段落上）。
    val projectionRegistry = remember(chapter.index) { ScrollViewportRegistry() }
    // 跨块选择状态（主计划第 9 节）：提升到 ReaderContent，段落自身不再各存一份，
    // 否则选择被物理限制在单个段落内，跨块批注无法成立。
    val selectionModel = remember(chapter.index) { CrossBlockSelectionModel() }
    // 选择模型是普通对象（非 Compose State），用一个版本号驱动重绘/重取区间。
    var selectionVersion by remember(chapter.index) { mutableStateOf(0) }
    val latestProjected by rememberUpdatedState(onLocatorProjected)
    var settledTick by remember(scrollState) { mutableStateOf(0L) }
    val statusBarTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val cutoutTopDp = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    val safeTopDp = maxOf(statusBarTopDp, cutoutTopDp)
    val topInsetDp = safeTopDp + (ReaderMetrics.HEADER_HEIGHT_DP + ReaderMetrics.TOP_BREATHING_SPACE_DP).dp
    var readerTopPx by remember(scrollState) { mutableFloatStateOf(Float.NaN) }
    val viewportTopPx = readerTopPx + with(LocalDensity.current) { topInsetDp.toPx() }
    val latestViewportTopPx by rememberUpdatedState(viewportTopPx)

    LaunchedEffect(scrollState) {
        // maxValue is Int.MAX_VALUE until measurement. Do not report the old chapter's offset
        // or acknowledge an end landing before the new viewport exists.
        snapshotFlow { scrollState.maxValue }.first { it != Int.MAX_VALUE }
        val target = when {
            landAtChapterEnd -> scrollState.maxValue
            initialLocator != null && initialLocator.offsetUtf16 <= bodyShift -> 0
            initialLocator != null -> {
                val blocks = snapshotFlow {
                    if (latestViewportTopPx.isFinite()) projectionRegistry.snapshot(scrollState.value.toFloat(), latestViewportTopPx)
                    else emptyList()
                }.first { blocks ->
                    blocks.any { it.documentKey == initialLocator.documentKey &&
                        (it.sourceRange?.endUtf16Exclusive ?: -1) >= initialLocator.offsetUtf16 }
                }
                ScrollViewportProjector.scrollOffsetFor(initialLocator, blocks)?.toInt()?.coerceIn(0, scrollState.maxValue) ?: 0
            }
            else -> currentPosition.coerceIn(0, scrollState.maxValue)
        }
        scrollState.scrollTo(target)
        if (landAtChapterEnd) latestLanded(target)
        if (initialLocator != null) onInitialLocatorApplied()
        positionRestored = true
    }

    LaunchedEffect(scrollState, positionRestored) {
        if (!positionRestored) return@LaunchedEffect
        snapshotFlow { scrollState.value }.distinctUntilChanged().collect { latestPositionChanged(it) }
    }

    // 滚动**停止**后（主计划 6.6：滚动停止时生成精确 TextLocator）把视口顶部投影为源坐标。
    // 采用 `isScrollInProgress` 的下降沿而不是固定延时：没有用户手势时不会触发额外计算，
    // 也不会像 sleep 那样在慢设备上误判。
    LaunchedEffect(scrollState, positionRestored) {
        if (!positionRestored) return@LaunchedEffect
        snapshotFlow {
            Triple(scrollState.isScrollInProgress, scrollState.value, scrollState.viewportSize) to
                if (latestViewportTopPx.isFinite()) projectionRegistry.snapshot(scrollState.value.toFloat(), latestViewportTopPx)
                else emptyList()
        }.collect { (state, blocks) ->
                val (inProgress, value, viewport) = state
                if (inProgress) return@collect
                if (blocks.isEmpty()) {
                    // 布局尚未登记：如实上报 null，调用方保留既有锚点。
                    latestProjected(ScrollViewportProjection.UNRESOLVED)
                    return@collect
                }
                val snapshot = ScrollViewportProjector.project(
                    scrollTopPx = value.toFloat(),
                    viewportHeightPx = viewport.toFloat(),
                    blocks = blocks,
                    maxScrollPx = scrollState.maxValue.takeIf { it != Int.MAX_VALUE }?.toFloat()
                        ?: Float.POSITIVE_INFINITY
                )
                latestProjected(ScrollViewportProjection.of(snapshot, chapterIndex))
                settledTick = settledTick + 1L
            }
    }

    val density = LocalDensity.current
    val switchThresholdPx = with(density) { 56.dp.toPx() }
    val boundaryScroll = remember(scrollState, chapterIndex, totalChapters, switchThresholdPx) {
        ReaderBoundaryScrollConnection(
            horizontal = false,
            threshold = switchThresholdPx,
            canEnterPrevious = { chapterIndex > 0 && !scrollState.canScrollBackward },
            canEnterNext = { chapterIndex < totalChapters - 1 && !scrollState.canScrollForward },
            onPrevious = { latestPrevious() },
            onNext = { latestNext() }
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { readerTopPx = it.positionInRoot().y }
            .readerBoundaryGestures(boundaryScroll)
            .readerTapGestures(
                gestures = settings.gestureSettings,
                onTap = onTap,
                onDoubleTap = onDoubleTap
            )
            .pointerInput(settings.gestureSettings) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { onHorizontalDrag(total) },
                    onHorizontalDrag = { _, delta -> total += delta }
                )
            }
    ) {
        // Measure cap (每行字数) and page margin (页边距) are both user settings; the column is
        // centred inside whatever is left over.
        val contentWidth = ReaderMetrics.contentWidthDp(
            availableWidthDp = maxWidth.value,
            fontSize = settings.fontSize,
            marginDp = settings.horizontalMarginDp.toFloat(),
            lineChars = settings.lineChars
        ).dp

        val navBarBottomDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val cutoutBottomDp = WindowInsets.displayCutout.asPaddingValues().calculateBottomPadding()
        val safeBottomDp = maxOf(navBarBottomDp, cutoutBottomDp)

        val bottomInsetDp = safeBottomDp + (ReaderMetrics.FOOTER_HEIGHT_DP + 32f).dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                // Keep the body above the footer: it is drawn over the bottom 28dp of the viewport,
                // so without this the last visible line sits underneath it.
                .padding(top = topInsetDp, bottom = bottomInsetDp)
                .clipToBounds()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            key(chapter.index) {
                Column(
                    modifier = Modifier
                        .width(contentWidth)
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        if (palette.id == com.flowreader.app.domain.model.ReaderPaletteId.PAPER &&
                            com.flowreader.app.core.designsystem.theme.LocalFlowVisualStyle.current == com.flowreader.app.domain.model.AppVisualStyle.WARM_PAPER
                        ) {
                            PaperLandscape(Modifier.matchParentSize().padding(start = contentWidth * .45f), palette.text.copy(alpha = .13f))
                        }
                        Text(
                            text = ChineseConverter.convert(chapter.title, settings.chineseConversion),
                            style = titleStyle,
                            modifier = Modifier.padding(bottom = FlowSpacing.xl)
                        )
                    }

                    var cumulativeOffset = 0
                    paragraphs.forEach { paragraph ->
                        val paraTrimmed = paragraph.trim()
                        val paraStart = cumulativeOffset + paragraph.indexOf(paraTrimmed)

                        // S5：段落内部的插图必须成为独立元素（U+FFFC 不能当文字画出来）。
                        // 切分走与分页模式**同一个纯函数**，偏移只允许有一个答案。
                        // 没有插图时只会得到单个 Text 段，排版与从前完全一致。
                        splitParagraphForImages(paraTrimmed, paraStart, bodyShift, imagesByOffset)
                            .forEachIndexed { segmentIndex, segment ->
                                val segStart = segment.offset
                                val blockId = "chapter:${chapter.index}:offset:$segStart:$segmentIndex"
                                when (segment) {
                                    is ReaderSegment.Image -> {
                                        val path = segment.image?.absolutePath.orEmpty()
                                        ReaderImage(
                                            path = path,
                                            tint = palette.text,
                                            alt = segment.image?.alt,
                                            onPreview = { onImageClick(path, segment.image?.alt) }
                                        )
                                    }

                                    is ReaderSegment.Text -> {
                                        val segText = segment.text
                                        val segEnd = segStart + segText.length
                                        val markerPath = imagePathFromMarker(segText)
                                        when {
                                            // 旧书的 `[IMG:路径]`（新导入不再产生）走同一条图片渲染通道。
                                            markerPath != null -> ReaderImage(
                                                path = markerPath,
                                                tint = palette.text,
                                                alt = imageAlts[markerPath],
                                                onPreview = { onImageClick(markerPath, imageAlts[markerPath]) }
                                            )

                                            segText.startsWith("## ") -> {
                                                val headingText = ChineseConverter.convert(
                                                    segText.removePrefix("## "),
                                                    settings.chineseConversion
                                                )
                                                val headingLayout = remember(blockId) { mutableStateOf<TextLayoutResult?>(null) }
                                                val headingCoords = remember(blockId) { mutableStateOf<LayoutCoordinates?>(null) }
                                                Text(
                                                    text = headingText,
                                                    style = headingStyle,
                                                    onTextLayout = { headingLayout.value = it },
                                                    modifier = Modifier
                                                        .padding(top = FlowSpacing.sm, bottom = FlowSpacing.md)
                                                        .registerScrollBlock(
                                                            registry = projectionRegistry,
                                                            blockId = blockId,
                                                            documentKey = scrollDocumentKey,
                                                            layoutProvider = { headingLayout.value },
                                                            coordinatesProvider = { headingCoords.value },
                                                            mappingProvider = {
                                                                DisplayMapping.identity(scrollDocumentKey, headingText, segStart)
                                                            }
                                                        )
                                                        .onGloballyPositioned { headingCoords.value = it }
                                                )
                                            }

                                            else -> {
                                                // 跨块批注（主计划第 9 节）：按**相交**筛选，
                                                // 真正的多区间裁剪与展开在渲染层（highlightSpansFor）。
                                                val paraAnnotations = annotations.filter {
                                                    it.intersectsParagraph(segStart, segEnd)
                                                }
                                                ReaderParagraph(
                                                    paragraph = segText,
                                                    paragraphStart = segStart,
                                                    annotations = paraAnnotations,
                                                    indent = indentSp > 0f,
                                                    bodyStyle = bodyStyle,
                                                    paragraphGap = paragraphGap,
                                                    palette = palette,
                                                    conversion = settings.chineseConversion,
                                                    annotationDisplayStyle = settings.annotationDisplayStyle,
                                                    documentKey = scrollDocumentKey,
                                                    blockId = blockId,
                                                    registry = projectionRegistry,
                                                    selectionModel = selectionModel,
                                                    selectionVersion = selectionVersion,
                                                    onSelectionChanged = { selectionVersion++ },
                                                    chapterIndex = chapter.index,
                                                    sourceText = chapter.content,
                                                    onHighlightSelection = onHighlightSelection,
                                                    onBookmarkSelection = onBookmarkSelection,
                                                    onHighlightRanges = onHighlightRanges,
                                                    onBookmarkRanges = onBookmarkRanges,
                                                    onNoteSelection = onNoteSelection,
                                                    onNoteRanges = onNoteRanges,
                                                    // 呈现标注（schema v3）：列表前缀 / 表头加粗 / 注音，
                                                    // 与分页模式共用同一套段内换算（paragraphMarksFor）。
                                                    paragraphMarks = paragraphMarksFor(
                                                        marks,
                                                        segStart,
                                                        segText.length,
                                                        bodyShift
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        cumulativeOffset += paragraph.length + 2
                    }
                }
            }
        }
    }
}

private enum class DragSide { START, END }

/** 判定指针是否已拖出本段的容差（像素）：超出后改用注册表做跨块命中。 */
private const val OUTSIDE_SLOP_PX = 1f

/** 指针是否落在该布局边界之外（用于触发跨块命中测试）。 */
private fun isOutside(coordinates: LayoutCoordinates, windowPosition: Offset): Boolean {
    val origin = coordinates.localToWindow(Offset.Zero)
    val width = coordinates.size.width.toFloat()
    val height = coordinates.size.height.toFloat()
    return windowPosition.x < origin.x - OUTSIDE_SLOP_PX ||
        windowPosition.x > origin.x + width + OUTSIDE_SLOP_PX ||
        windowPosition.y < origin.y - OUTSIDE_SLOP_PX ||
        windowPosition.y > origin.y + height + OUTSIDE_SLOP_PX
}

/**
 * One selectable paragraph: long-press selects a word, dragging extends the range, the two
 * handles adjust either bound, and [ReaderSelectionBar] offers highlight / copy / bookmark.
 *
 * All gesture math runs on the public [TextLayoutResult] API; the selection range is display
 * offsets, converted back to raw chapter offsets through [ParagraphContent.rawRange].
 */
@Composable
internal fun ReaderParagraph(
    paragraph: String,
    paragraphStart: Int,
    annotations: List<Annotation>,
    indent: Boolean,
    bodyStyle: TextStyle,
    paragraphGap: Dp,
    palette: ReaderPalette,
    conversion: ChineseConversion = ChineseConversion.NONE,
    documentKey: String = "text/main",
    blockId: String? = null,
    registry: ScrollViewportRegistry? = null,
    /** 跨块选择模型（提升自 ReaderContent）。null 时退化为段落内选择。 */
    selectionModel: CrossBlockSelectionModel? = null,
    /** 选择模型版本号：变化时本段重新读取自己应显示的区间。 */
    selectionVersion: Int = 0,
    /** 通知外层选择已变化（模型不是 Compose State，需要显式触发重绘）。 */
    onSelectionChanged: () -> Unit = {},
    chapterIndex: Int = 0,
    /** 本章规范正文；跨块装配用它把源范围切成文本，保证「范围与文本自洽」。 */
    sourceText: String = "",
    onHighlightSelection: (String, Int, Int) -> Unit,
    onBookmarkSelection: (String, Int, Int) -> Unit,
    onHighlightRanges: (MultiBlockSelection) -> Unit = {},
    onBookmarkRanges: (MultiBlockSelection) -> Unit = {},
    /** 呈现标注（schema v3，段内相对坐标）：列表前缀 / 表头加粗 / 注音。 */
    paragraphMarks: ParagraphMarks = ParagraphMarks.EMPTY,
    onNoteSelection: ((String, Int, Int, String) -> Unit)? = null,
    onNoteRanges: ((MultiBlockSelection, String) -> Unit)? = null,
    onSelectionActiveChanged: (Boolean) -> Unit = {},
    annotationDisplayStyle: AnnotationDisplayStyle = AnnotationDisplayStyle.UNDERLINE
) {
    val content = remember(
        paragraph,
        paragraphStart,
        annotations,
        indent,
        conversion,
        documentKey,
        blockId,
        paragraphMarks,
        annotationDisplayStyle
    ) {
        buildParagraphContent(
            paragraph = paragraph,
            paragraphStart = paragraphStart,
            annotations = annotations,
            indent = indent,
            conversion = conversion,
            documentKey = documentKey,
            blockId = blockId,
            marks = paragraphMarks,
            annotationDisplayStyle = annotationDisplayStyle
        )
    }
    // 注音小字（schema v3）：画在基字上方，随布局更新重画。测量不变 ——
    // 注音画在行间空隙里，不参与排版高度。
    val rubyTextMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val rubyStyle = remember(bodyStyle, palette) {
        bodyStyle.copy(
            fontSize = bodyStyle.fontSize * RUBY_ANNOTATION_SCALE,
            // 注音必须用**自己的**行高：继承正文行高会把它的测量高度放大成正文一整行，
            // 于是预留带装不下它，注音又压回基字里。
            lineHeight = bodyStyle.fontSize * RUBY_ANNOTATION_SCALE * RUBY_LINE_HEIGHT_FACTOR,
            color = (bodyStyle.color.takeIf { it != Color.Unspecified } ?: palette.text).copy(alpha = 0.85f)
        )
    }
    // 含注音的段落用"预留行高"样式。判断条件必须与测量侧（PagedReader.measureWithMarks）
    // 完全一致 —— 两侧用的是同一个 withRubyLineReserve，分页高度与真实排版才对得上。
    val paragraphStyle = remember(bodyStyle, paragraphMarks) {
        if (paragraphMarks.rubyBases.isEmpty()) {
            bodyStyle
        } else {
            bodyStyle.withRubyLineReserve(bodyStyle.fontSize.value)
        }
    }
    // 选择区间由**跨块模型**按 blockId 派生；模型缺失时退回本段局部状态。
    // selectionVersion 参与 remember key，保证模型变化时重算。
    val selectionState = remember(blockId) { mutableStateOf<TextRange?>(null) }
    val derivedRange: TextRange? = remember(blockId, selectionVersion, selectionModel) {
        if (selectionModel != null && blockId != null) {
            selectionModel.rangeFor(blockId)
                ?.takeIf { !it.isEmpty() }
                ?.let { TextRange(it.first, it.last + 1) }
        } else {
            null
        }
    }
    val activeSelection: TextRange? = if (selectionModel != null) derivedRange else selectionState.value
    val latestSelection by rememberUpdatedState(activeSelection)
    val latestSelectionActiveChanged by rememberUpdatedState(onSelectionActiveChanged)
    var noteDraft by remember { mutableStateOf<ReaderNoteDraft?>(null) }
    var noteText by remember { mutableStateOf("") }
    // Both reader modes need registered display lengths before the cross-block model can begin.
    DisposableEffect(selectionModel, blockId, content.annotatedString.length) {
        if (selectionModel != null && blockId != null) {
            selectionModel.registerBlock(blockId, content.annotatedString.length)
        }
        onDispose {
            if (selectionModel != null && blockId != null) selectionModel.unregisterBlock(blockId)
            if (latestSelection != null) latestSelectionActiveChanged(false)
        }
    }
    val layoutState = remember(blockId) { mutableStateOf<TextLayoutResult?>(null) }
    val coordinatesState = remember(paragraphStart) { mutableStateOf<LayoutCoordinates?>(null) }
    val clipboard = LocalClipboardManager.current
    val viewConfiguration = LocalViewConfiguration.current

    val selectionColor = palette.text.copy(alpha = 0.28f)
    val handleColor = palette.text.copy(alpha = 0.85f)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val handleRadius = with(density) { 6.dp.toPx() }
    val handleTouchRadius = with(density) { 28.dp.toPx() }

    /**
     * 长按起选：优先交给**跨块模型**（这样后续拖动才能跨段），
     * 模型不可用时退回本段局部状态（保持 PagedReader 等旧调用点行为不变）。
     */
    fun extendFromLongPress(position: Offset) {
        val layout = layoutState.value ?: return
        val offset = layout.getOffsetForPosition(position).coerceIn(0, layout.layoutInput.text.length)
        val model = selectionModel
        val id = blockId
        if (model != null && id != null) {
            if (model.anchorPoint == null) {
                val word = layout.getWordBoundary(offset)
                model.beginAt(CrossBlockSelectionModel.Point(id, word.min))
                model.extendTo(CrossBlockSelectionModel.Point(id, word.max))
            } else {
                model.extendTo(CrossBlockSelectionModel.Point(id, offset))
            }
            onSelectionChanged()
            return
        }
        val current = selectionState.value
        selectionState.value = if (current == null) {
            layout.getWordBoundary(offset)
        } else if (offset < (current.min + current.max) / 2) {
            TextRange(offset, current.end)
        } else {
            TextRange(current.start, offset)
        }
        latestSelectionActiveChanged(selectionState.value?.collapsed == false)
    }

    /**
     * 拖动手柄：**跨块**地扩展选择。
     *
     * 关键点：拖出本段范围时，用注册表做窗口坐标命中测试，把指针映射到**别的块**，
     * 再扩展模型的焦点。这正是旧实现做不到的地方——旧代码只能在本段的
     * `TextLayoutResult` 上取偏移，拖出去就被夹回段边界。
     */
    fun dragHandle(side: DragSide, position: Offset) {
        val layout = layoutState.value ?: return
        val offset = layout.getOffsetForPosition(position).coerceIn(0, layout.layoutInput.text.length)
        val model = selectionModel
        val id = blockId
        if (model != null && id != null) {
            // 判断指针是否已拖出本段：只有拖出去才需要做跨块命中，
            // 段内拖动直接用本段布局算偏移（更精确，也避免命中测试的取整误差）。
            val coordinates = coordinatesState.value
            val window = coordinates?.localToWindow(position)
            val outside = coordinates != null && window != null && isOutside(coordinates, window)
            val hit = if (outside) registry?.hitTest(window!!) else null
            if (hit != null) {
                model.moveBoundary(side == DragSide.START, CrossBlockSelectionModel.Point(hit.blockId, hit.displayOffset))
            } else {
                model.moveBoundary(side == DragSide.START, CrossBlockSelectionModel.Point(id, offset))
            }
            onSelectionChanged()
            return
        }

        val current = selectionState.value ?: return
        selectionState.value = when (side) {
            DragSide.START -> TextRange(min(offset, current.end), current.end)
            DragSide.END -> TextRange(current.start, max(offset, current.start))
        }
        latestSelectionActiveChanged(selectionState.value?.collapsed == false)
    }

    fun clearSelection() {
        selectionModel?.clear()
        selectionState.value = null
        latestSelectionActiveChanged(false)
        onSelectionChanged()
    }

    /**
     * 把当前跨块选择装配成 [MultiBlockSelection]（源范围 + 文本）。
     *
     * 装配走 `MultiBlockSelectionAssembler`：由**每块自己的 DisplayMapping** 投影，
     * 因此人工缩进/隐藏区域被正确排除，相接区间合并、断点保持分离
     * ——「同一段文字绝不重复输出」在这里落地。
     *
     * 模型不可用或装配失败时返回 null，调用方回退旧的单区间路径。
     */
    fun assembleMultiBlock(): MultiBlockSelection? {
        val model = selectionModel ?: return null
        val segments = model.selectedBlocks().mapNotNull { (id, range) ->
            val mapping = registry?.mappingFor(id) ?: return@mapNotNull null
            MultiBlockSelectionAssembler.SelectedSegment(
                mapping = mapping,
                displayStart = range.first,
                displayEndExclusive = range.last + 1
            )
        }
        if (segments.isEmpty()) return null
        // 源文本用**本章正文**：段落的 DisplayMapping 记录的是章内绝对 UTF-16 偏移
        // （paragraphStart 就是章内偏移），两者基准一致，所以范围能直接切出文本。
        val assembled = MultiBlockSelectionAssembler.assemble(segments) { key ->
            if (key == documentKey) sourceText else null
        } ?: return null
        return MultiBlockSelection(
            ranges = assembled.ranges,
            text = assembled.text,
            quality = assembled.quality,
            chapterIndex = chapterIndex
        )
    }

    fun handlePositions(layout: TextLayoutResult, range: TextRange): Pair<Offset, Offset>? {
        if (range.collapsed) return null
        val startBox = layout.getBoundingBox(range.start)
        val endBox = layout.getBoundingBox(range.end - 1)
        return Offset(startBox.left, startBox.top) to Offset(endBox.right, endBox.bottom)
    }

    fun selectionRects(layout: TextLayoutResult, range: TextRange): List<Rect> {
        if (range.collapsed) return emptyList()
        val firstLine = layout.getLineForOffset(range.start)
        val lastLine = layout.getLineForOffset((range.end - 1).coerceAtLeast(range.start))
        return buildList {
            for (line in firstLine..lastLine) {
                val lineStart = layout.getLineStart(line)
                val lineEnd = layout.getLineEnd(line)
                val segStart = range.start.coerceAtLeast(lineStart)
                val segEnd = range.end.coerceAtMost(lineEnd)
                if (segEnd <= segStart) continue
                val left = layout.getBoundingBox(segStart).left
                val right = layout.getBoundingBox(segEnd - 1).right
                add(Rect(left, layout.getLineTop(line), right, layout.getLineBottom(line)))
            }
        }
    }

    Text(
        text = content.annotatedString,
        style = paragraphStyle,
        onTextLayout = { layoutState.value = it },
        modifier = Modifier
            .padding(bottom = paragraphGap)
            .onGloballyPositioned { coordinatesState.value = it }
            .registerScrollBlock(
                registry = registry,
                blockId = blockId,
                documentKey = documentKey,
                layoutProvider = { layoutState.value },
                coordinatesProvider = { coordinatesState.value },
                mappingProvider = { content.displayMapping }
            )
            .drawWithContent {
                drawContent()
                // 注音（schema v3）：基字上方的小字。基字是真实字符，选择/高亮/进度不受影响。
                val layout = layoutState.value
                if (layout != null && content.rubyHints.isNotEmpty()) {
                    content.rubyHints.forEach { hint ->
                        val textLength = layout.layoutInput.text.length
                        if (hint.units.size == hint.baseDisplayIndices.size) {
                            // 每个 `rt` 对一个基字：逐字对位绘制。
                            hint.baseDisplayIndices.forEachIndexed { index, displayIndex ->
                                if (displayIndex < textLength) {
                                    drawRubyUnit(
                                        unit = hint.units[index],
                                        anchor = layout.getBoundingBox(displayIndex),
                                        lineTop = layout.getLineTop(layout.getLineForOffset(displayIndex)),
                                        style = rubyStyle,
                                        measurer = rubyTextMeasurer
                                    )
                                }
                            }
                        } else {
                            val first = hint.baseDisplayIndices.first()
                            val last = hint.baseDisplayIndices.last()
                            if (last < textLength) {
                                val startBox = layout.getBoundingBox(first)
                                val endBox = layout.getBoundingBox(last)
                                drawRubyUnit(
                                    unit = hint.units.single(),
                                    anchor = Rect(
                                        startBox.left,
                                        min(startBox.top, endBox.top),
                                        max(startBox.right, endBox.right),
                                        max(startBox.bottom, endBox.bottom)
                                    ),
                                    lineTop = layout.getLineTop(layout.getLineForOffset(first)),
                                    style = rubyStyle,
                                    measurer = rubyTextMeasurer
                                )
                            }
                        }
                    }
                }
                val layout2 = layoutState.value
                val range = activeSelection
                if (layout2 != null && range != null && !range.collapsed) {
                    selectionRects(layout2, range).forEach { rect ->
                        drawRect(selectionColor, topLeft = rect.topLeft, size = rect.size)
                    }
                    handlePositions(layout2, range)?.let { (start, end) ->
                        drawHandle(start, handleColor, handleRadius)
                        drawHandle(end, handleColor, handleRadius)
                    }
                }
            }
            .pointerInput(paragraphStart, blockId, selectionModel) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val layout = layoutState.value
                    val selection = latestSelection
                    val handles = if (layout != null && selection != null) handlePositions(layout, selection) else null

                    val nearStart = handles != null && (down.position - handles.first).getDistance() <= handleTouchRadius
                    val nearEnd = handles != null && (down.position - handles.second).getDistance() <= handleTouchRadius

                    if (nearStart || nearEnd) {
                        down.consume()
                        val side = if (nearStart && (!nearEnd || (down.position - handles!!.first).getDistance() <= (down.position - handles.second).getDistance())) DragSide.START else DragSide.END
                        drag(down.id) { change ->
                            change.consume()
                            dragHandle(side, change.position)
                        }
                        return@awaitEachGesture
                    }

                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress != null) {
                        longPress.consume()
                        extendFromLongPress(longPress.position)
                        drag(down.id) { change ->
                            change.consume()
                            extendFromLongPress(change.position)
                        }
                    }
                }
            }
            // The paragraph owns no tap action of its own: single taps are dispatched by the
            // reading surface below, which is the only detector that has to decide between a page
            // turn and the control bar. A tap while text is selected just clears the selection —
            // and consumes the event so the surface does not also act on it.
            .pointerInput(paragraphStart, blockId, selectionModel) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.isConsumed) return@awaitEachGesture
                    val hadSelection = latestSelection != null
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    if (hadSelection) {
                        down.consume()
                        up.consume()
                        clearSelection()
                    }
                }
            }
    )

    val selection = activeSelection
    val layout = layoutState.value
    val coordinates = coordinatesState.value
    val ownsSelectionBar = selectionModel == null || selectionModel.selectedBlocks().lastOrNull()?.first == blockId
    if (ownsSelectionBar && selection != null && layout != null && coordinates != null && !selection.collapsed) {
        val displayText = layout.layoutInput.text
        val startBox = layout.getBoundingBox(selection.start)
        val endBox = layout.getBoundingBox(selection.end - 1)

        // Exclusive-end conversion and the text slice both live in selectionSpan() so they are unit
        // tested — a Composable's selection callbacks are not.
        val span = content.selectionSpan(selection.start, selection.end, paragraph, paragraphStart)

        ReaderSelectionBar(
            markAsUnderline = annotationDisplayStyle == AnnotationDisplayStyle.UNDERLINE,
            windowTopLeft = coordinates.localToWindow(startBox.topLeft),
            windowBottomRight = coordinates.localToWindow(endBox.bottomRight),
            palette = palette,
            onHighlight = {
                val committed = assembleMultiBlock()
                if (committed != null) {
                    // 跨块路径：给出多段源范围，中间未选中的内容不会被吞掉。
                    onHighlightRanges(committed)
                } else if (span != null) {
                    // 退化路径：模型不可用（如 PagedReader 旧调用点），保持原行为。
                    onHighlightSelection(span.text, span.startPosition, span.endPosition)
                }
                clearSelection()
            },
            onCopy = {
                // 复制「显示文本」而不是源文本：用户看到什么就复制什么。
                val committed = assembleMultiBlock()
                val textToCopy = committed?.text
                    ?: displayText.substring(selection.start, selection.end)
                clipboard.setText(AnnotatedString(textToCopy))
                clearSelection()
            },
            onBookmark = {
                val committed = assembleMultiBlock()
                if (committed != null) {
                    onBookmarkRanges(committed)
                } else if (span != null) {
                    onBookmarkSelection(span.text, span.startPosition, span.endPosition)
                }
                clearSelection()
            },
            onDismiss = { clearSelection() },
            onNote = if (onNoteSelection != null || onNoteRanges != null) {
                {
                    val ranges = assembleMultiBlock()
                    if (ranges != null || span != null) {
                        noteDraft = ReaderNoteDraft(ranges?.text ?: span!!.text, span?.startPosition ?: 0, span?.endPosition ?: 0, ranges)
                        noteText = ""
                        clearSelection()
                    }
                }
            } else {
                null
            }
        )
    }

    noteDraft?.let { draft ->
        MaterialTheme(colorScheme = flowColorScheme(palette.isDark)) {
            AlertDialog(
                onDismissRequest = { noteDraft = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
                modifier = Modifier.padding(horizontal = FlowSpacing.lg),
                containerColor = palette.background,
                titleContentColor = palette.text,
                textContentColor = palette.text,
                title = { Text(stringResource(R.string.reader_selection_note)) },
                text = {
                    Column {
                        Text(draft.text, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            label = { Text(stringResource(R.string.reader_note_label)) },
                            modifier = Modifier.fillMaxWidth().padding(top = FlowSpacing.sm),
                            minLines = 3,
                            maxLines = 6,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.text)
                        )
                    }
                },
                confirmButton = {
                    TextButton(enabled = noteText.isNotBlank(), onClick = {
                        val ranges = draft.ranges
                        if (ranges != null && onNoteRanges != null) {
                            onNoteRanges(ranges, noteText.trim())
                        } else {
                            onNoteSelection?.invoke(draft.text, draft.start, draft.end, noteText.trim())
                        }
                        noteDraft = null
                    }) {
                        Text(
                            stringResource(R.string.action_save),
                            color = if (noteText.isNotBlank()) palette.text else palette.text.copy(alpha = 0.38f)
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { noteDraft = null }) { Text(stringResource(R.string.action_cancel), color = palette.text) }
                }
            )
        }
    }
}

private data class ReaderNoteDraft(val text: String, val start: Int, val end: Int, val ranges: MultiBlockSelection?)

private fun DrawScope.drawHandle(center: Offset, color: Color, radius: Float) {
    drawCircle(color = color, radius = radius, center = center)
    drawCircle(color = Color.White.copy(alpha = 0.9f), radius = radius * 0.45f, center = center)
}

/** 注音与基字顶边之间的间距（像素）。 */
private const val RUBY_ANNOTATION_GAP_PX = 2f

/**
 * 画一个注音单元：水平居中于 [anchor]，垂直画在**本行行盒顶部**的预留带里。
 *
 * 含注音段落的行高已被 [withRubyLineReserve] 撑高，且多出来的空间全部落在基字上方
 * （`LineHeightStyle.Alignment.Bottom`），所以行盒顶部就是注音该待的地方。
 *
 * 早期实现在这里用 `anchor.top`（基字顶）减去注音高度并 `coerceAtLeast(0f)`，
 * 结果**首行的注音被压回段落顶边、与基字重叠**（2026-09-12 真机验收发现，本版修复）。
 */
private fun DrawScope.drawRubyUnit(
    unit: String,
    anchor: Rect,
    lineTop: Float,
    style: TextStyle,
    measurer: androidx.compose.ui.text.TextMeasurer
) {
    if (unit.isEmpty()) return
    val result = measurer.measure(text = unit, style = style, maxLines = 1)
    val x = (anchor.left + anchor.right - result.size.width) / 2f
    val y = lineTop + RUBY_ANNOTATION_GAP_PX
    drawText(result, topLeft = Offset(x, y))
}

/**
 * 把一个已测量的文本块登记到 [ScrollViewportRegistry]（S5 / 主计划 6.6）。
 *
 * 需要同时拿到三样东西才能做源坐标投影：
 * 1. [TextLayoutResult] —— 逐行行盒（lineTop/lineBottom/lineStart/lineEnd）；
 * 2. [LayoutCoordinates] —— 该块在滚动内容中的绝对顶部像素；
 * 3. [DisplayMapping] —— 行显示范围 → 规范源范围的换算（排除人工缩进）。
 *
 * 三者都在组合期到达的先后顺序不固定，因此这里统一在每次布局/测量更新时尝试登记；
 * 数据不全就跳过（下次回调会补齐），**绝不**用像素或估算值冒充源坐标。
 */
private fun Modifier.registerScrollBlock(
    registry: ScrollViewportRegistry?,
    blockId: String?,
    documentKey: String,
    layoutProvider: () -> TextLayoutResult?,
    coordinatesProvider: () -> LayoutCoordinates?,
    mappingProvider: () -> DisplayMapping?
): Modifier {
    if (registry == null || blockId == null) return this
    // `Text` 自己的 `onTextLayout` 参数负责交付测量结果；这里只在全局定位回调里登记，
    // 因为行盒与绝对位置必须来自**同一帧**，否则会得到错位的几何。
    return this.onGloballyPositioned { coordinates ->
        tryRegister(registry, blockId, documentKey, layoutProvider(), coordinates, mappingProvider())
    }
}

private fun tryRegister(
    registry: ScrollViewportRegistry,
    blockId: String,
    documentKey: String,
    layout: TextLayoutResult?,
    coordinates: LayoutCoordinates,
    mapping: DisplayMapping?
) {
    if (layout == null || !coordinates.isAttached) return
    // `positionInRoot` 给出相对窗口内容原点的位置，与滚动容器自身在窗口中的位置差一个常量。
    // 因为 [ScrollViewportProjector] 只关心块之间的**相对**顺序与间距（滚动像素同样是相对的），
    // 这个常量在两侧一致地出现，不参与差分，所以可以直接使用，不需要再减容器原点。
    val position = coordinates.positionInRoot()
    registry.register(
        blockId = blockId,
        contentTopPx = position.y,
        contentLeftPx = position.x,
        layout = layout,
        mapping = mapping,
        documentKey = documentKey,
        coordinates = coordinates
    )
}

/** 资源缺失/解析不出时的固定占位高度（与分页器的测量保持一致，避免换行跳动）。 */
internal val MISSING_IMAGE_HEIGHT = 120.dp

/**
 * 一张结构插图的渲染（S5 / 主计划 4.6 第 7、9、10 条）。
 *
 * 三种结果**可区分、绝不静默丢图**（INV10）：
 * 1. 路径为空或文件不存在 → 占位图标 + alt 文案；
 * 2. 文件在但**解码失败** → 明确的"解码失败"占位 + alt；
 * 3. 正常 → `ContentScale.Fit` 绘制，传入 [onPreview] 时可点击放大。
 *
 * 高度【测量与绘制同源】（4.6 第 7 条）：分页器已经算好该图应占多少像素
 * （[heightPx]），这里就用**同一个值**定高，因此图不会压字、也不会留出莫名空白；
 * 滚动模式不传 [heightPx]，退化为按真实宽高比占位。
 */
@Composable
internal fun ReaderImage(
    path: String,
    tint: Color,
    alt: String? = null,
    /** 分页模式传入分页器测得的像素高度；滚动模式传 null（按宽高比自适应）。 */
    heightPx: Int? = null,
    /** 传入即可点击放大预览。 */
    onPreview: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    val exists = remember(path) { path.isNotBlank() && File(path).isFile }
    val description = alt ?: stringResource(R.string.reader_illustration)

    val aspect = remember(path, exists) { if (exists) imageAspectRatio(path) else null }
    val heightModifier = when {
        heightPx != null && heightPx > 0 -> Modifier.height(with(density) { heightPx.toDp() })
        aspect != null -> Modifier.aspectRatio(aspect)
        else -> Modifier.height(MISSING_IMAGE_HEIGHT)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(heightModifier)
            .padding(vertical = FlowSpacing.sm)
            .clip(RoundedCornerShape(FlowSpacing.xs))
            .then(
                if (onPreview != null) {
                    Modifier.clickable(
                        onClickLabel = stringResource(R.string.reader_image_preview_open)
                    ) { onPreview() }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (!exists) {
            IllustrationPlaceholder(
                label = stringResource(R.string.reader_illustration_missing),
                alt = alt,
                tint = tint
            )
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(File(path)).crossfade(false).build(),
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            ) {
                // 文件存在却解不出来（损坏/不支持的格式）也必须看得见原因，不能只留白（4.6 第 9 条）。
                if (painter.state is AsyncImagePainter.State.Error) {
                    IllustrationPlaceholder(
                        label = stringResource(R.string.reader_illustration_failed),
                        alt = alt,
                        tint = tint
                    )
                } else {
                    SubcomposeAsyncImageContent()
                }
            }
        }
    }
}

/** 占位：图标 + （有 alt 时）alt 文案。**不静默丢图**，也不假装这是内容。 */
@Composable
private fun IllustrationPlaceholder(label: String, alt: String?, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = label,
            tint = tint.copy(alpha = 0.3f)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = 0.5f)
        )
        if (!alt.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.reader_illustration_alt, alt),
                style = MaterialTheme.typography.labelSmall,
                color = tint.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 图片宽高比（宽/高）；读不到（缺失/损坏）返回 null。只读文件头，不整图解码。 */
private fun imageAspectRatio(path: String): Float? {
    if (path.endsWith(".svg", ignoreCase = true)) {
        // BitmapFactory 不认 SVG；固有尺寸从头部属性提取（coil-svg 只管解码）。
        val svg = svgIntrinsicSize(path) ?: return null
        return svg.first / svg.second
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(path, options) }
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    return options.outWidth.toFloat() / options.outHeight.toFloat()
}
