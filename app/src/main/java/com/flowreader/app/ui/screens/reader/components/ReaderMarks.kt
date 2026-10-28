package com.flowreader.app.ui.screens.reader.components

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.flowreader.app.util.epub.ChapterListMark
import com.flowreader.app.util.epub.ChapterMarks

/**
 * 一个段落（或分页片段）范围内的呈现标注（schema v3）。
 *
 * [ChapterMarks] 的偏移是**章内正文坐标**；段落/片段的文本偏移是**body 坐标**
 * （`ChapterBody.stripLeadingTitleEcho` 去掉标题回显后整体前移了 [bodyShift]）。
 * [paragraphMarksFor] 负责"章坐标 → 段内相对坐标"的换算 —— 与结构图片的
 * `imagesByOffset[baseOffset + index + bodyShift]` 是同一套约定，**绝不允许**第二套算法。
 *
 * 全部为纯函数，可在 JVM 上直接单测。
 */

/** 段内相对坐标的一条注音来源（基字范围 + `rt` 文本）。 */
data class ParagraphRubySource(
    val baseStart: Int,
    val baseEndExclusive: Int,
    val annotations: List<String>
)

/**
 * 段内相对坐标的呈现数据。
 *
 * - [listPrefixes]：段内相对源偏移 → 显示前缀（`• ` / `3. ` / 缩进）。
 *   前缀是**显示层**字符：正文与字数口径不动；其 rawOffsets 映射到该项的源起始偏移。
 * - [headerRanges]：表头单元格（`th`）的段内相对源范围（含端点），渲染层加粗。
 * - [rubyBases]：注音基字的段内相对范围与注音文本，渲染层画在基字上方。
 */
data class ParagraphMarks(
    val listPrefixes: Map<Int, String> = emptyMap(),
    val headerRanges: List<IntRange> = emptyList(),
    val rubyBases: List<ParagraphRubySource> = emptyList()
) {
    /** 段首就是列表项：首行缩进交给项目符号承担，不再叠加人工缩进。 */
    val startsWithListItem: Boolean get() = listPrefixes.containsKey(0)

    val isEmpty: Boolean
        get() = listPrefixes.isEmpty() && headerRanges.isEmpty() && rubyBases.isEmpty()

    companion object {
        val EMPTY = ParagraphMarks()
    }
}

/** 一个列表项的显示前缀：层级缩进（全角空格）+ 项目符号/编号。 */
internal fun listPrefixFor(mark: ChapterListMark): String {
    val indent = "　".repeat((mark.level - 1).coerceAtLeast(0))
    val bullet = if (mark.ordered) "${mark.number ?: 1}. " else "• "
    return indent + bullet
}

/** 注音小字相对正文字号的缩放。渲染与测量必须一致。 */
internal const val RUBY_ANNOTATION_SCALE = 0.55f

/** 注音小字自身的行高系数（相对注音字号）。 */
internal const val RUBY_LINE_HEIGHT_FACTOR = 1.2f

/**
 * 注音预留行高的系数（相对注音字号）。
 *
 * 1.5 是刻意留的余量：注音小字自身行高约 1.2，再加字体差异与间距，1.5 保证装得下。
 */
internal const val RUBY_LINE_RESERVE_FACTOR = 1.5f

/** 含注音段落必须额外预留的行高（sp）。 */
internal fun rubyLineReserveSp(bodyFontSizeSp: Float): Float = bodyFontSizeSp * RUBY_ANNOTATION_SCALE * RUBY_LINE_RESERVE_FACTOR

/**
 * 含注音段落的正文样式：把行高撑到能容纳注音，并让多出来的空间**全部落在基字上方**
 * （[LineHeightStyle.Alignment.Bottom]），注音才画得进预留带而不是压在基字里。
 *
 * 为什么必须撑行高：正文行高默认是字号 × 行距（约 1.33），基字上方只剩约 0.33 × 字号，
 * 而注音需要约 0.55 × 字号 —— **每一行都不够**，不是只有首行。
 *
 * 测量（PagedReader.measureWithMarks）与绘制（ReaderParagraph）**必须**共用本函数；
 * 只改一侧会让分页高度与真实排版分叉。
 */
internal fun TextStyle.withRubyLineReserve(bodyFontSizeSp: Float): TextStyle = copy(
    lineHeight = (
        (if (lineHeight.isSpecified) lineHeight.value else bodyFontSizeSp) +
            rubyLineReserveSp(bodyFontSizeSp)
        ).sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Bottom,
        trim = LineHeightStyle.Trim.None
    )
)

/**
 * 把本章的呈现标注裁剪到一个段落（或片段）里，并换算成**段内相对坐标**。
 *
 * @param paraStart 段首在 body 坐标里的偏移。
 * @param paraLength 段文本长度（UTF-16）。
 * @param bodyShift body 相对章正文的位移（标题回显长度；未去回显时为 0）。
 */
internal fun paragraphMarksFor(marks: ChapterMarks, paraStart: Int, paraLength: Int, bodyShift: Int): ParagraphMarks {
    if (paraLength <= 0 ||
        (marks.lists.isEmpty() && marks.tables.isEmpty() && marks.ruby.isEmpty())
    ) {
        return ParagraphMarks.EMPTY
    }
    val contentStart = paraStart + bodyShift
    val contentEnd = contentStart + paraLength

    val prefixes = marks.lists
        .filter { it.offsetUtf16 in contentStart until contentEnd }
        .associate { mark ->
            val relative = mark.offsetUtf16 - contentStart
            relative to listPrefixFor(mark)
        }

    val headers = marks.tables.flatMap { table ->
        table.headerRanges.mapNotNull { range ->
            val start = maxOf(range.first, contentStart)
            val end = minOf(range.last, contentEnd - 1)
            if (end < start) null else (start - contentStart)..(end - contentStart)
        }
    }

    val ruby = marks.ruby.mapNotNull { mark ->
        val relStart = maxOf(mark.baseStartUtf16 - contentStart, 0)
        val relEnd = minOf(mark.baseEndUtf16Exclusive - contentStart, paraLength)
        if (relEnd <= relStart) {
            null
        } else {
            ParagraphRubySource(relStart, relEnd, mark.annotations)
        }
    }

    if (prefixes.isEmpty() && headers.isEmpty() && ruby.isEmpty()) return ParagraphMarks.EMPTY
    return ParagraphMarks(listPrefixes = prefixes, headerRanges = headers, rubyBases = ruby)
}

/**
 * 本章标注指纹（分页缓存键的一部分）：有标注与无标注、编号不同都必须是两份排版缓存。
 * 与 [imageKeyOf] 同一纪律 —— 影响显示结果的因素漏进缓存键一项，就会显示上一次的排版。
 */
internal fun marksKeyOf(marks: ChapterMarks): String = when {
    marks.lists.isEmpty() && marks.tables.isEmpty() && marks.ruby.isEmpty() -> ""
    else -> buildString {
        marks.lists.forEach {
            append("L").append(it.offsetUtf16).append(':').append(it.ordered).append(':')
                .append(it.number ?: "-").append(':').append(it.level).append('|')
        }
        marks.tables.forEach {
            append("T").append(it.startUtf16).append('-').append(it.endUtf16Exclusive)
                .append(':').append(it.headerRanges.joinToString(",")).append('|')
        }
        marks.ruby.forEach {
            append("R").append(it.baseStartUtf16).append('-').append(it.baseEndUtf16Exclusive)
                .append(':').append(it.annotations.joinToString(",")).append('|')
        }
    }
}
