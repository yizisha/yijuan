package com.flowreader.app.ui.screens.reader.components

import com.flowreader.app.util.epub.ChapterImageRef

/**
 * 一段正文按结构图片占位符切出来的片段（主计划 4.6 / S5）。
 *
 * [Text.offset] 与 [Image.offset] 都是**章内源坐标**（`chapter.content` 的坐标系），
 * 图片占它自己那个 U+FFFC 偏移，紧跟其后的文本从「该偏移 + 1」继续 ——
 * 偏移一断，批注 / 书签 / 进度就会整体错位。
 */
sealed interface ReaderSegment {
    /** 该片段起点在**章内源坐标**里的偏移（两个实现的共同字段，便于调用方先取偏移）。 */
    val offset: Int

    data class Text(val text: String, override val offset: Int) : ReaderSegment

    /** [image] 为 null 表示"这个偏移上有图，但我们没能解析出它"（必须显示占位，INV10）。 */
    data class Image(override val offset: Int, val image: ChapterImageRef?) : ReaderSegment
}

/** 结构图片在规范源文本里的占位符（与 `EpubDocumentParser.OBJECT_REPLACEMENT` 同一字符）。 */
internal const val STRUCTURAL_IMAGE_MARKER: Char = '\uFFFC'

/**
 * 纯函数：把一段正文按 U+FFFC 切成「文本 / 图片」片段。
 *
 * **两个渲染器共用**（分页 [PagedReader] 与滚动 [ReaderContent]），
 * 因为"偏移怎么算"只允许有一个答案：重复实现必然分叉（本项目已经吃过一次亏）。
 *
 * @param text 已经 trim 过、且相对 `body` 定位的段落文本。
 * @param baseOffset 该段首字符在 `body` 里的偏移。
 * @param bodyShift `body` 相对 `content` 的位移（`ChapterBody.stripLeadingTitleEcho` 会去掉
 *   与标题重复的开头行，图片偏移是 **content 坐标**，必须补回这个位移）。
 * @param imagesByOffset 章内偏移 → 图片（来自 [com.flowreader.app.util.epub.ChapterImageIndex]）。
 *
 * 没有占位符时返回单个 [ReaderSegment.Text]，调用方保持原有排版行为。
 */
internal fun splitParagraphForImages(
    text: String,
    baseOffset: Int,
    bodyShift: Int,
    imagesByOffset: Map<Int, ChapterImageRef>
): List<ReaderSegment> {
    if (text.isEmpty()) return emptyList()
    if (imagesByOffset.isEmpty() || text.indexOf(STRUCTURAL_IMAGE_MARKER) < 0) {
        return listOf(ReaderSegment.Text(text, baseOffset))
    }
    val segments = mutableListOf<ReaderSegment>()
    var runStart = 0
    var index = 0
    while (index < text.length) {
        if (text[index] != STRUCTURAL_IMAGE_MARKER) {
            index++
            continue
        }
        if (index > runStart) {
            segments += ReaderSegment.Text(text.substring(runStart, index), baseOffset + runStart)
        }
        segments += ReaderSegment.Image(
            offset = baseOffset + index,
            image = imagesByOffset[baseOffset + index + bodyShift]
        )
        runStart = index + 1
        index++
    }
    if (runStart < text.length) {
        segments += ReaderSegment.Text(text.substring(runStart), baseOffset + runStart)
    }
    return segments
}

/** 图片块的内存标记（复用既有的 `[IMG:路径]` 测量/绘制通道；只在内存里存在）。 */
internal const val IMAGE_MARKER_PREFIX = "[IMG:"

/** 内存中图片块的文本：路径为空表示资源缺失 → 渲染层显示占位。 */
internal fun imageBlockToken(image: ChapterImageRef?): String = "$IMAGE_MARKER_PREFIX${image?.absolutePath.orEmpty()}]"

/** 从一个完整的图片标记里取出路径；不是标记时返回 null。 */
internal fun imagePathFromMarker(text: String): String? {
    if (!text.startsWith(IMAGE_MARKER_PREFIX) || !text.endsWith("]")) return null
    return text.substring(IMAGE_MARKER_PREFIX.length, text.length - 1)
}

/** 本章图片指纹（偏移 + 解析结果），用于分页缓存键：有图与无图必须是两份缓存。 */
internal fun imageKeyOf(images: List<ChapterImageRef>): String =
    if (images.isEmpty()) "" else images.joinToString("|") { "${it.offsetUtf16}:${it.absolutePath.orEmpty()}" }

/** 路径 → alt，供渲染层给图片显示占位文案与无障碍描述（4.6 第 9 条）。 */
internal fun altByPath(images: List<ChapterImageRef>): Map<String, String> = buildMap {
    images.forEach { image ->
        val path = image.absolutePath ?: return@forEach
        val alt = image.alt ?: return@forEach
        putIfAbsent(path, alt)
    }
}

// ------------------------------------------------------------------ SVG 固有尺寸

/** 从 SVG 头部提取 width/height 或 viewBox（coil-svg 只管解码，**占位几何**必须自己算）。 */
private val SVG_SIZE_ATTRS = Regex(
    pattern = "<svg[^>]*?width\\s*=\\s*\"([^\"]+)\"[^>]*?height\\s*=\\s*\"([^\"]+)\"",
    options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

private val SVG_VIEW_BOX = Regex(
    pattern = "viewBox\\s*=\\s*\"\\s*([\\d.eE+-]+)[\\s,]+([\\d.eE+-]+)[\\s,]+([\\d.eE+-]+)[\\s,]+([\\d.eE+-]+)",
    options = setOf(RegexOption.IGNORE_CASE)
)

/**
 * 数字 + 可选单位（`512`、`100pt`、`12.5mm`）→ 数值。
 * **百分比返回 null**：`width="100%"` 相对视口，不是固有尺寸，必须回退 `viewBox`。
 */
private fun svgLength(raw: String): Float? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.contains('%')) return null
    val value = trimmed.toFloatOrNull() ?: run {
        val match = Regex("^([\\d.]+)").find(trimmed) ?: return null
        match.groupValues[1].toFloatOrNull() ?: return null
    }
    return value.takeIf { it > 0f }
}

/**
 * SVG 的固有 (宽, 高)：优先 `width/height` 属性，其次 `viewBox`。
 * 只读文件头部（64 KiB）做正则匹配，不做任何解析器构建 —— 这是排版测量路径，必须廉价。
 * 读不出（损坏/不完整）返回 null，调用方退回占位高度。
 */
internal fun svgIntrinsicSize(path: String): Pair<Float, Float>? = runCatching {
    java.io.File(path).inputStream().use { stream ->
        val buffer = ByteArray(64 * 1024)
        val read = stream.read(buffer)
        if (read <= 0) return@runCatching null
        val head = String(buffer, 0, read, Charsets.UTF_8)
        SVG_SIZE_ATTRS.find(head)?.destructured?.let { (width, height) ->
            val w = svgLength(width)
            val h = svgLength(height)
            if (w != null && h != null) return@runCatching w to h
        }
        SVG_VIEW_BOX.find(head)?.destructured?.let { (x, y, w, h) ->
            val width = svgLength(w)
            val height = svgLength(h)
            if (width != null && height != null) return@runCatching width to height
        }
        null
    }
}.getOrNull()
