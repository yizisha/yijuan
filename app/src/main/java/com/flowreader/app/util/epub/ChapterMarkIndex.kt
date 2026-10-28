package com.flowreader.app.util.epub

/**
 * 内容包里的**呈现标注**（列表/表格/注音，schema v3）在**阅读器坐标系**里的落点。
 *
 * 与 [ChapterImageIndex] 同一条换算链：`projectChapters` 把 section 的多个 range 按阅读顺序
 * 拼接成 `chapters.content`，所以文档偏移 ≠ 章内偏移；跨文档的 section 必须按每个 range
 * 的累计长度平移，否则标注会落到别的段落上。
 *
 * 全部为纯函数：不碰文件、不碰 Android，可直接单测（含跨文档与越界情况）。
 */

/** 章内坐标系里的一条列表项标注（编号已按结构推导完成）。 */
data class ChapterListMark(
    /** 该项首字符在**章内正文**里的 UTF-16 偏移。 */
    val offsetUtf16: Int,
    val ordered: Boolean,
    /**
     * 有序列表的显示编号；无序列表为 null。
     * 推导规则：`itemValue`（`li/@value`）优先；否则 = `listStart`（默认 1）+
     * 同一列表内前面同层项的个数（前一项带 `@value` 时从它续数）。
     * 编号来自**结构**而不是页码 —— 长列表跨页/跨块编号天然不重置（G5）。
     */
    val number: Int?,
    /** 显示嵌套深度（1 起，DOM 里 ul/ol 祖先数推导），渲染层据此缩进。 */
    val level: Int
)

/** 章内坐标系里的一条表格标注。 */
data class ChapterTableMark(
    val startUtf16: Int,
    val endUtf16Exclusive: Int,
    /** 表头单元格（`th`）的章内源范围；渲染层给它们加粗。 */
    val headerRanges: List<IntRange>
)

/** 章内坐标系里的一条注音标注。注音只活在显示层，主文本与字数口径不动（3.2 第 6 条）。 */
data class ChapterRubyMark(
    /** 基字在**章内正文**里的 UTF-16 范围 `[start, endExclusive)`。 */
    val baseStartUtf16: Int,
    val baseEndUtf16Exclusive: Int,
    /** `rt` 的注音文本，可能多段。 */
    val annotations: List<String>
)

/** 一章的全部呈现标注（旧书/无标注章节三个列表均为空）。 */
data class ChapterMarks(
    val lists: List<ChapterListMark> = emptyList(),
    val tables: List<ChapterTableMark> = emptyList(),
    val ruby: List<ChapterRubyMark> = emptyList()
) {
    companion object {
        val EMPTY = ChapterMarks()
    }
}

object ChapterMarkIndex {

    /**
     * @param ranges 按阅读顺序排列的 section 区间（与 `projectChapters` 拼接顺序**必须一致**）。
     * @param marksByDocument documentKey → 该文档内的呈现数据（来自
     *   [PublicationStore.readDocumentMarks]，由调用方按文档缓存）。
     */
    fun build(
        ranges: List<SectionRange>,
        marksByDocument: Map<String, PublicationStore.DocumentMarks>
    ): ChapterMarks {
        val lists = mutableListOf<ChapterListMark>()
        val tables = mutableListOf<ChapterTableMark>()
        val ruby = mutableListOf<ChapterRubyMark>()
        var chapterBase = 0

        ranges.forEach { range ->
            val length = (range.endUtf16Exclusive - range.startUtf16).coerceAtLeast(0)
            val marks = marksByDocument[range.documentKey]
            if (marks != null) {
                // 只接受**落在本区间内**的标注：区间之外的不能凭空插进这一章（与图片同一纪律）。
                fun inRange(offset: Int) = offset in range.startUtf16 until range.endUtf16Exclusive

                // 编号必须先对**全文档**的项计数、再筛出落在本区间的项：
                // section 从文档中间开始时，列表前半截属于上一章，后半截若重新计数就会重编号。
                lists += numberedLists(marks.lists)
                    .filter { inRange(it.first.offsetUtf16) }
                    .map { (entry, number) ->
                        ChapterListMark(
                            offsetUtf16 = chapterBase + (entry.offsetUtf16 - range.startUtf16),
                            ordered = entry.ordered,
                            number = number,
                            level = entry.level
                        )
                    }
                tables += marks.tables
                    .filter { inRange(it.startUtf16) }
                    .map { table ->
                        ChapterTableMark(
                            startUtf16 = chapterBase + (table.startUtf16 - range.startUtf16),
                            endUtf16Exclusive = chapterBase + (table.endUtf16Exclusive - range.startUtf16),
                            headerRanges = table.rows.flatMap { cells ->
                                cells.filter { cell -> cell.isHeader && inRange(cell.startUtf16) }
                                    .map { cell ->
                                        chapterBase + (cell.startUtf16 - range.startUtf16)..
                                            chapterBase + (cell.endUtf16Exclusive - range.startUtf16 - 1)
                                    }
                            }
                        )
                    }
                ruby += marks.ruby
                    .filter { inRange(it.baseStartUtf16) }
                    .map { mark ->
                        ChapterRubyMark(
                            baseStartUtf16 = chapterBase + (mark.baseStartUtf16 - range.startUtf16),
                            baseEndUtf16Exclusive = chapterBase + (mark.baseEndUtf16Exclusive - range.startUtf16),
                            annotations = mark.annotations
                        )
                    }
            }
            chapterBase += length
        }
        return ChapterMarks(
            lists = lists.sortedBy { it.offsetUtf16 },
            tables = tables.sortedBy { it.startUtf16 },
            ruby = ruby.sortedBy { it.baseStartUtf16 }
        )
    }

    /**
     * 列表编号推导（**纯结构**，与页码无关 —— 长列表续页编号不重置）。
     *
     * 同一 `listBlockId` 的项按源顺序编为一组；组内按嵌套层级各自计数：
     * `itemValue` 优先，否则为 `listStart`（默认 1）起、前一同层项的编号 + 1
     * （普通列表等价于 `listStart + 前面同层项个数`；带 `li/@value` 的续接也正确）。
     *
     * 返回 (原始条目, 编号)；无序列表编号为 null。**必须对全文档的项调用**，
     * 调用方再按 section 区间筛选 —— 半截列表才不会重新计数。
     */
    private fun numberedLists(
        entries: List<PublicationStore.DocumentListEntry>
    ): List<Pair<PublicationStore.DocumentListEntry, Int?>> {
        if (entries.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<PublicationStore.DocumentListEntry, Int?>>()
        val counters = HashMap<String, HashMap<Int, Int?>>() // listBlockId -> level -> 前一同层项编号
        entries.forEach { entry ->
            val number = if (entry.ordered) {
                val perList = counters.getOrPut(entry.listBlockId) { HashMap() }
                val previous = perList[entry.level]
                val next = entry.itemValue
                    ?: ((previous ?: (entry.listStart ?: 1) - 1) + 1)
                perList[entry.level] = next
                next
            } else {
                null
            }
            result += entry to number
        }
        return result
    }
}
