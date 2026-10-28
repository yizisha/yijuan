package com.flowreader.app.ui.screens.reader.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.flowreader.app.core.util.ChineseConverter
import com.flowreader.app.domain.model.Annotation
import com.flowreader.app.domain.model.AnnotationColor
import com.flowreader.app.domain.model.AnnotationDisplayStyle
import com.flowreader.app.domain.model.AnnotationType
import com.flowreader.app.domain.model.ChineseConversion
import com.flowreader.app.domain.model.DisplayBoundary
import com.flowreader.app.domain.model.DisplayMapping

/**
 * Rendered paragraph text plus the display-index -> raw-chapter-offset map and [DisplayMapping].
 *
 * The reader body strips markdown markers (`**` / `*`), prepends the CJK indent and inserts
 * highlight spans, so a selection made against the rendered text cannot be persisted directly.
 * [rawOffsets][ParagraphContent.rawOffsets] maps every rendered character back to its offset in
 * the raw chapter content, or -1 for characters the renderer invented (the indent prefix).
 * [displayMapping] provides the authoritative bidirectional boundary mapping.
 */
data class ParagraphContent(
    val annotatedString: AnnotatedString,
    val rawOffsets: IntArray,
    val displayMapping: DisplayMapping? = null,
    /** 注音显示层（schema v3）：基字显示下标 + 注音单元。空列表表示本段没有注音。 */
    val rubyHints: List<RubyHint> = emptyList()
) {
    init {
        require(rawOffsets.size == annotatedString.length) {
            "rawOffsets must be parallel to the display text"
        }
        displayMapping?.let {
            require(it.boundaries.size == annotatedString.length + 1) {
                "displayMapping boundaries must be displayText.length + 1"
            }
        }
    }

    /**
     * Converts a rendered selection range (inclusive start, exclusive end) into raw chapter
     * offsets. Returns null when either bound lands on an invented character.
     */
    fun rawRange(displayStart: Int, displayEnd: Int): IntRange? {
        if (displayStart < 0 || displayEnd > rawOffsets.size || displayStart >= displayEnd) return null
        val rawStart = rawOffsets[displayStart]
        val rawEnd = rawOffsets[displayEnd - 1]
        if (rawStart < 0 || rawEnd < 0) return null
        return rawStart..rawEnd
    }

    /**
     * Turns a rendered selection into the span an [Annotation] actually stores, together with the
     * exact text it covers. Returns null when the selection is empty or lands on an invented
     * character (indent prefix), i.e. when nothing should be persisted.
     */
    fun selectionSpan(displayStart: Int, displayEnd: Int, paragraph: String, paragraphStart: Int): RawSelectionSpan? {
        val range = rawRange(displayStart, displayEnd)?.takeIf { !it.isEmpty() } ?: return null
        val endExclusive = range.last + 1
        val text = paragraph.substring(
            (range.first - paragraphStart).coerceIn(0, paragraph.length),
            (endExclusive - paragraphStart).coerceIn(0, paragraph.length)
        )
        return RawSelectionSpan(startPosition = range.first, endPosition = endExclusive, text = text)
    }
}

/**
 * A highlight or bookmark about to be persisted: [startPosition] inclusive, [endPosition]
 * **exclusive**, [text] the raw characters between them.
 */
data class RawSelectionSpan(
    val startPosition: Int,
    val endPosition: Int,
    val text: String
)

/**
 * 一条注音的显示信息（schema v3）：只含**显示下标**，不含源坐标 ——
 * 注音不进主文本、不进字数口径（3.2 第 6 条），基字在显示文本里仍是真实字符
 * （选择/高亮/进度全部照旧），注音只由渲染层画在基字上方。
 *
 * [units] 与 [baseDisplayIndices] 等长时逐字对位（每个基字头顶一个注音单元）；
 * 否则 [units] 只有一个元素，整体居中画在基字范围上方。
 */
data class RubyHint(
    val baseDisplayIndices: List<Int>,
    val units: List<String>
)

/**
 * 把**源坐标区间** `[sourceStart, sourceEnd)` 换算为**显示坐标区间**。
 *
 * 为什么不能直接用减法：markdown 剥离会让显示文本短于源文本，且被剥离的标记字符
 * 在 [rawOffsets] 里**没有对应条目**（它们从未被 append）。因此必须：
 * 1. 找到第一个 `rawOffsets[i] >= sourceStart` 的显示下标作为起点；
 * 2. 找到最后一个 `rawOffsets[i] < sourceEnd` 的显示下标，终点取它 +1。
 *
 * 完全落在被剥离标记内的区间返回 null（没有可着色的可见字符）。
 * 人工缩进（rawOffsets 为 -1）天然被跳过，不会被高亮。
 */
internal fun sourceToDisplayRange(sourceStart: Int, sourceEnd: Int, rawOffsets: List<Int>): Pair<Int, Int>? {
    if (sourceEnd <= sourceStart || rawOffsets.isEmpty()) return null
    var displayStart = -1
    var displayEnd = -1
    for (i in rawOffsets.indices) {
        val raw = rawOffsets[i]
        if (raw < 0) continue
        if (raw in sourceStart until sourceEnd) {
            if (displayStart < 0) displayStart = i
            displayEnd = i
        }
    }
    if (displayStart < 0 || displayEnd < displayStart) return null
    return displayStart to (displayEnd + 1)
}

/** Muted ink washes; never use the stored fluorescent legacy colour directly in body text. */
internal fun softAnnotationColor(color: AnnotationColor): Color = Color(
    when (color) {
        AnnotationColor.YELLOW -> 0xFFE5C47A
        AnnotationColor.GREEN -> 0xFF8CB79F
        AnnotationColor.BLUE -> 0xFF8FAECC
        AnnotationColor.PINK -> 0xFFC9A0AD
        AnnotationColor.ORANGE -> 0xFFD7A77E
    }
).copy(alpha = 0.22f)

/**
 * 一个**高亮片段**：源坐标的开区间 + 颜色（主计划第 9 节）。
 *
 * 为什么需要它，而不是直接用 [Annotation]：一条批注可以携带**多段不相连**的源范围
 * （跨块跨段、或同段内被 synthetic 断点分开）。渲染时每一段都必须**各自**高亮，
 * 用批注整体的首尾区间会把中间未选中的文字一起点亮。
 */
data class HighlightSpan(
    /** 源文本 UTF-16 起点（含），与 `paragraphStart` 同一基准。 */
    val startPosition: Int,
    /** 源文本 UTF-16 终点（**不含**），与 [Annotation.endPosition] 的开区间约定一致。 */
    val endPosition: Int,
    val color: AnnotationColor,
    val type: AnnotationType = AnnotationType.HIGHLIGHT
) {
    init {
        require(endPosition >= startPosition) {
            "HighlightSpan must not be inverted: [$startPosition, $endPosition)"
        }
    }

    val isEmpty: Boolean get() = endPosition == startPosition
}

/**
 * 本批注是否与段落 `[paragraphStart, paragraphEndExclusive)` 有交集。
 *
 * 用于渲染前的**廉价筛选**：不再要求批注完全落在段内（那会让跨段批注在每一段都匹配不上、
 * 高亮整条消失），只要任一段范围与段落相交就算相关。
 */
fun Annotation.intersectsParagraph(paragraphStart: Int, paragraphEndExclusive: Int): Boolean {
    if (paragraphEndExclusive <= paragraphStart) return false
    val ranges = rangeLocator?.ranges
    if (ranges.isNullOrEmpty()) {
        val start = maxOf(startPosition, paragraphStart)
        val end = minOf(endPosition, paragraphEndExclusive)
        return end > start
    }
    return ranges.any { range ->
        val start = maxOf(range.startUtf16, paragraphStart)
        val end = minOf(range.endUtf16Exclusive, paragraphEndExclusive)
        end > start
    }
}

/**
 * 把本批注裁剪到本段，产出**每个相交区间一个** [HighlightSpan]。
 *
 * - 同一段内两段不相连的选区 → 两个片段 → 各自高亮，中间未选中的文字**不点亮**；
 * - 跨段落批注在每一段各自裁剪出本段那一段，不会把别的段落画进来；
 * - 无 `rangeLocator` 的旧数据退回 `startPosition/endPosition` 单区间裁剪。
 *
 * 返回空列表表示该批注与本段无关。
 */
fun Annotation.highlightSpansFor(paragraphStart: Int, paragraphEndExclusive: Int): List<HighlightSpan> {
    if (paragraphEndExclusive <= paragraphStart) return emptyList()
    val ranges = rangeLocator?.ranges
    if (ranges.isNullOrEmpty()) {
        val start = maxOf(startPosition, paragraphStart)
        val end = minOf(endPosition, paragraphEndExclusive)
        return if (end > start) listOf(HighlightSpan(start, end, color, type)) else emptyList()
    }
    return ranges.mapNotNull { range ->
        val start = maxOf(range.startUtf16, paragraphStart)
        val end = minOf(range.endUtf16Exclusive, paragraphEndExclusive)
        if (end > start) HighlightSpan(start, end, color, type) else null
    }
}

/**
 * Builds the display text for one paragraph with markdown emphasis and stored highlight spans,
 * tracking every character back to the raw chapter content and building a [DisplayMapping].
 *
 * [conversion] is applied to the displayed characters only. The mapping is 1:1 in length, so
 * [ParagraphContent.rawOffsets] keeps pointing at the same raw offsets and stored annotations do
 * not shift.
 *
 * @param paragraphStart offset of [paragraph] inside the raw chapter content.
 * @param marks 段内呈现标注（schema v3，段内相对坐标，见 [paragraphMarksFor]）：
 *   列表项显示前缀、表头单元格加粗、注音。**只改显示层**——
 *   正文、rawOffsets 的源坐标口径与字数统计一概不动。
 */
fun buildParagraphContent(
    paragraph: String,
    paragraphStart: Int,
    annotations: List<Annotation>,
    indent: Boolean,
    conversion: ChineseConversion = ChineseConversion.NONE,
    documentKey: String = "text/main",
    blockId: String? = null,
    marks: ParagraphMarks = ParagraphMarks.EMPTY,
    annotationDisplayStyle: AnnotationDisplayStyle = AnnotationDisplayStyle.SOFT_HIGHLIGHT
): ParagraphContent {
    val displayParagraph = ChineseConverter.convert(paragraph, conversion)
    // 段首是列表项时，首行缩进交给项目符号/编号承担：叠加人工缩进会出现"　　1. "双重缩进。
    val prefix = if (indent && !marks.startsWithListItem) "　　" else ""
    val rawOffsets = mutableListOf<Int>()
    val builder = AnnotatedString.Builder()

    fun appendRange(text: String, rawStart: Int) {
        for (i in text.indices) rawOffsets.add(rawStart + i)
    }

    /**
     * Appends [text] with **every** character mapped to the same raw offset.
     *
     * 用于"显示层生成的替代字符"：列表前缀（代表它后面那个项的源起点）与 TAB 的固定展开。
     * 这些字符在源文本里没有各自的实体，逐个递增偏移会把它们映射到**别人的**源字符上，
     * 选择/高亮就会错位（前缀会把编号后面的字符一起圈进源区间）。
     */
    fun appendConstRange(text: String, rawOffset: Int) {
        repeat(text.length) { rawOffsets.add(rawOffset) }
        builder.append(text)
    }

    /**
     * Appends a raw chunk, expanding each TAB into a fixed visible gap.
     *
     * TAB 只由表格单元格分隔符产生（3.2 规则 4），渲染成固定间距即是本批的"列对齐"呈现。
     * 展开出来的每个显示字符的 rawOffsets **都映射到 TAB 自己的源偏移**（显式多对一映射，
     * 见 [appendConstRange]）：选择/高亮跨过单元格边界时仍落在正确的源范围上，绝不漂移。
     */
    fun appendRaw(text: String, rawStart: Int) {
        var index = 0
        while (index < text.length) {
            val tab = text.indexOf('\t', index)
            if (tab < 0) {
                appendRange(text.substring(index), rawStart + index)
                builder.append(text.substring(index))
                break
            }
            if (tab > index) {
                appendRange(text.substring(index, tab), rawStart + index)
                builder.append(text.substring(index, tab))
            }
            appendConstRange(TAB_DISPLAY_TEXT, rawStart + tab)
            index = tab + 1
        }
    }

    /** Appends a raw chunk applying `**bold**` / `*italic*` markdown while keeping offsets true. */
    fun appendFormatted(text: String, rawStart: Int) {
        var i = 0
        while (i < text.length) {
            val boldStart = text.indexOf("**", i)
            val italicStart = text.indexOf("*", i)

            val nextMarker = when {
                boldStart >= 0 && italicStart >= 0 -> minOf(boldStart, italicStart)
                boldStart >= 0 -> boldStart
                italicStart >= 0 -> italicStart
                else -> -1
            }

            if (nextMarker < 0) {
                appendRaw(text.substring(i), rawStart + i)
                break
            }

            if (nextMarker > i) {
                appendRaw(text.substring(i, nextMarker), rawStart + i)
            }

            if (boldStart >= 0 && boldStart == nextMarker) {
                val boldEnd = text.indexOf("**", boldStart + 2)
                if (boldEnd >= 0) {
                    builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendRange(text.substring(boldStart + 2, boldEnd), rawStart + boldStart + 2)
                        append(text.substring(boldStart + 2, boldEnd))
                    }
                    i = boldEnd + 2
                } else {
                    appendRaw("**", rawStart + boldStart)
                    i = boldStart + 2
                }
            } else if (italicStart >= 0 && italicStart == nextMarker) {
                val italicEnd = text.indexOf("*", italicStart + 1)
                if (italicEnd >= 0 && (italicEnd > italicStart + 1) && !text.startsWith("*", italicEnd + 1)) {
                    builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendRange(text.substring(italicStart + 1, italicEnd), rawStart + italicStart + 1)
                        append(text.substring(italicStart + 1, italicEnd))
                    }
                    i = italicEnd + 1
                } else {
                    appendRaw("*", rawStart + italicStart)
                    i = italicStart + 1
                }
            } else {
                i = nextMarker + 1
            }
        }
    }

    if (prefix.isNotEmpty()) {
        repeat(prefix.length) { rawOffsets.add(-1) }
        builder.append(prefix)
    }

    // ---- 正文（不含高亮）与 markdown 处理
    //
    // 先把整段正文按原逻辑写出来（剥离 `**`/`*` 标记、记录 rawOffsets），
    // 高亮**稍后**以样式叠加。这样高亮不再参与文本切片，从根本上消除了旧实现里
    // 「多条批注重叠时重复 append 同一段字符」导致文本错乱、rawOffsets 与显示错位的缺陷。
    //
    // 列表项前缀（schema v3）：在项的源起始偏移处插入显示前缀。插入点把段落切成若干
    // 区间逐段处理；前缀字符的 rawOffsets **映射到该项的源起始偏移**（不是 -1、也不推走
    // 后续正文）—— 选中区从前缀开始时能落到正确的源位置，后续正文偏移一概不变。
    if (marks.listPrefixes.isEmpty()) {
        appendFormatted(displayParagraph, paragraphStart)
    } else {
        var cursor = 0
        marks.listPrefixes.keys.sorted().forEach { position ->
            if (position < cursor || position > displayParagraph.length) return@forEach
            if (position > cursor) {
                appendFormatted(displayParagraph.substring(cursor, position), paragraphStart + cursor)
            }
            val prefixText = marks.listPrefixes.getValue(position)
            // 前缀的每个字符都映射到项的源起始偏移（appendConstRange）。
            appendConstRange(prefixText, paragraphStart + position)
            cursor = position
        }
        if (cursor < displayParagraph.length) {
            appendFormatted(displayParagraph.substring(cursor), paragraphStart + cursor)
        }
    }

    // ---- 高亮渲染（主计划第 9 节）
    //
    // 一条批注可携带**多段不相连**的源范围（跨块跨段、或同段内被 synthetic 断点分开），
    // 每个区间必须**各自**着色：旧实现只取批注首尾区间并切片渲染，会把中间未选中的文字
    // 一起点亮；同段内多断点更是被压成一个连续高亮。
    //
    // 坐标换算要点：高亮区间是**源坐标**，而 markdown 剥离使显示坐标短于源坐标
    // （`**加粗**` 4 个源字符 → `加粗` 2 个显示字符）。因此必须用 rawOffsets 反查，
    // 不能把源偏移直接当显示下标（那会把高亮画到错误位置）。
    val paragraphEnd = paragraphStart + paragraph.length
    val mergedSpans = annotations
        .flatMap { it.highlightSpansFor(paragraphStart, paragraphEnd) }
        // 按起点排序；同起点时长者在前，保证结果与输入顺序无关。
        .sortedWith(compareBy({ it.startPosition }, { -(it.endPosition - it.startPosition) }))
        // 合并重叠/相接片段：重叠不得重复着色；相接合并可避免样式接缝。
        .fold(mutableListOf<HighlightSpan>()) { acc, span ->
            val last = acc.lastOrNull()
            if (last != null && span.startPosition <= last.endPosition && span.color == last.color && span.type == last.type) {
                acc[acc.size - 1] = last.copy(endPosition = maxOf(last.endPosition, span.endPosition))
            } else {
                acc.add(span)
            }
            acc
        }

    mergedSpans.forEach { span ->
        val displayRange = sourceToDisplayRange(span.startPosition, span.endPosition, rawOffsets)
        if (displayRange == null) return@forEach
        builder.addStyle(
            if (annotationDisplayStyle == AnnotationDisplayStyle.UNDERLINE || span.type == AnnotationType.UNDERLINE) {
                SpanStyle(textDecoration = TextDecoration.Underline)
            } else {
                SpanStyle(background = softAnnotationColor(span.color))
            },
            displayRange.first,
            displayRange.second
        )
    }

    // ---- 表头呈现（schema v3）：正文里表格只有 TAB，表头单元格（th）的加粗是
    // 让"这是一张表"在阅读流里可感知的最小改动。样式叠加，不改文本与坐标。
    marks.headerRanges.forEach { range ->
        val displayRange = sourceToDisplayRange(
            paragraphStart + range.first,
            paragraphStart + range.last + 1,
            rawOffsets
        )
        if (displayRange == null) return@forEach
        builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), displayRange.first, displayRange.second)
    }

    // ---- 注音 hints（schema v3）：从 rawOffsets 反查基字的显示下标。
    // 基字是真实字符（选择/高亮/进度照旧）；注音文本只进 hints，**绝不 append 进显示文本**。
    val rubyHints = marks.rubyBases.mapNotNull { source ->
        val absStart = paragraphStart + source.baseStart
        val absEnd = paragraphStart + source.baseEndExclusive
        val baseIndices = rawOffsets.withIndex().mapNotNull { (displayIndex, raw) ->
            if (raw in absStart until absEnd) displayIndex else null
        }
        if (baseIndices.isEmpty()) return@mapNotNull null
        // 注音文本与基字同受简繁转换约束（显示层一致性）。
        val annotationText = source.annotations.joinToString("") { ChineseConverter.convert(it, conversion) }
        // 每个 `rt` 一段：段数与基字数一致时逐字对位（"漢<rt>かん</rt>字<rt>じ</rt>"），
        // 否则整条注音居中画在基字范围上方（"漢字<rt>かんじ</rt>"）。
        val units = if (source.annotations.size == baseIndices.size) {
            source.annotations.map { ChineseConverter.convert(it, conversion) }
        } else {
            listOf(annotationText)
        }
        RubyHint(baseIndices, units)
    }

    val displayText = builder.toAnnotatedString()
    val boundaries = ArrayList<DisplayBoundary>(rawOffsets.size + 1)
    for (offset in rawOffsets) {
        boundaries.add(
            DisplayBoundary(
                sourceOffsetUtf16 = if (offset >= 0) offset else null,
                isSynthetic = offset < 0,
                blockId = blockId
            )
        )
    }
    val lastSourceOffset = rawOffsets.lastOrNull { it >= 0 }?.plus(1)
    boundaries.add(
        DisplayBoundary(
            sourceOffsetUtf16 = lastSourceOffset,
            isSynthetic = (lastSourceOffset == null),
            blockId = blockId
        )
    )
    val displayMapping = DisplayMapping(
        documentKey = documentKey,
        displayText = displayText.text,
        boundaries = boundaries
    )

    return ParagraphContent(displayText, rawOffsets.toIntArray(), displayMapping, rubyHints)
}

/** 表格单元格分隔符（TAB）在显示层的固定展开；宽度 = 两个全角空格。 */
internal const val TAB_DISPLAY_TEXT = "　　"
