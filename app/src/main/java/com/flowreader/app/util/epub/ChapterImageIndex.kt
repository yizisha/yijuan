package com.flowreader.app.util.epub

/**
 * 内容包里的结构图片在**阅读器坐标系**里的落点（主计划 4.6）。
 *
 * 规范源文本里图片只占一个 `U+FFFC`（不是文本标记）；渲染层必须把它换成真正的图。
 * [offsetUtf16] 是**当前章正文**（`chapters.content`）里的 U+FFFC 偏移，
 * 与批注/书签/定位用的同一套坐标系，因此图上下的取词、高亮、进度都自洽。
 */
data class ChapterImageRef(
    /** 该图在本章正文中的 U+FFFC 偏移。 */
    val offsetUtf16: Int,
    /**
     * 本机可渲染文件的**绝对路径**；资源缺失、未解析或被拒时为 null。
     * null **不等于**「没有图」——UI 必须显示占位与 [alt]，不得静默丢图（INV10）。
     */
    val absolutePath: String?,
    /** `img/@alt`，用于占位文案与无障碍。 */
    val alt: String?
)

/** section 的一个源区间：某文档里的 `[start, end)`。 */
data class SectionRange(
    val documentKey: String,
    val startUtf16: Int,
    val endUtf16Exclusive: Int
)

/** 某个文档内的一张图片（偏移相对**该文档**）。 */
data class DocumentImage(
    val offsetUtf16: Int,
    val containerPath: String?,
    val alt: String?
)

/**
 * 把「section 的源区间」+「各文档内的图片」映射成**章内偏移**。
 *
 * 为什么需要它：`projectChapters` 把 section 的多个 range **按阅读顺序拼接**成
 * `chapters.content`，所以文档偏移 ≠ 章内偏移。跨文档的 section（一个 section 含两个
 * XHTML）必须按每个 range 的累计长度换算，否则图片会落到别的段落上。
 *
 * 纯函数：不碰文件、不碰 Android，可直接单测（含跨文档与越界情况）。
 */
object ChapterImageIndex {

    /**
     * @param ranges 按阅读顺序排列的 section 区间（与 `projectChapters` 拼接顺序**必须一致**）。
     * @param imagesByDocument documentKey → 该文档内的图片。
     * @param resolvePath 把容器路径解析成本机绝对路径；解析不了返回 null（保留占位）。
     */
    fun build(
        ranges: List<SectionRange>,
        imagesByDocument: Map<String, List<DocumentImage>>,
        resolvePath: (String?) -> String?
    ): List<ChapterImageRef> {
        val result = mutableListOf<ChapterImageRef>()
        var chapterBase = 0
        ranges.forEach { range ->
            val length = (range.endUtf16Exclusive - range.startUtf16).coerceAtLeast(0)
            imagesByDocument[range.documentKey].orEmpty().forEach { image ->
                // 只接受**落在本区间内**的图片：图片在 section 之外时不能凭空插进这一章。
                if (image.offsetUtf16 < range.startUtf16 || image.offsetUtf16 >= range.endUtf16Exclusive) {
                    return@forEach
                }
                result += ChapterImageRef(
                    offsetUtf16 = chapterBase + (image.offsetUtf16 - range.startUtf16),
                    absolutePath = resolvePath(image.containerPath),
                    alt = image.alt
                )
            }
            chapterBase += length
        }
        return result.sortedBy { it.offsetUtf16 }
    }
}
