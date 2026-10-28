package com.flowreader.app.domain.model

/**
 * 批注/书签的内容坐标（主计划 3.1、第 9 节）。
 *
 * 为什么批注不能继续用「一个 `startPosition`/一个 `endPosition`」：
 *
 * 1. **跨块选择**：选中一段文字可能横跨多个结构块（段落、图片、表格单元格），
 *    主计划 3.3 明确「一个显示范围可以对应多个源范围……不得只存一个整数」；
 * 2. **单区间表达不了断点**：若中间夹着 synthetic 人工字符或隐藏区域，真实文字被切成
 *    两段不连续源范围，用一个 `[start, end)` 会把中间从未选中的内容也包含进来；
 * 3. **必须携带 revision**：主计划第 9 节要求批注记录 revision，否则改版后无法判断
 *    该批注是否还能精确贴合，只能猜测。
 *
 * 不变量（INV03/INV04）：
 * - 每条 [SourceRange] 是 UTF-16 边界 `[startUtf16, endUtf16Exclusive)` 且非空；
 * - ranges **按 start 升序、互不重叠**；重叠必须由调用方合并，不允许此处静默排序掩盖问题；
 * - 边界不得落在代理对中间。
 */
data class RangeLocator(
    val version: Int = CURRENT_VERSION,
    val publicationId: String,
    val revision: String,
    /** 阅读顺序上的源区间；至少一条。 */
    val ranges: List<SourceRange>,
    val quality: ResolutionQuality = ResolutionQuality.EXACT
) {
    init {
        require(publicationId.isNotBlank()) { "publicationId must not be blank" }
        require(ranges.isNotEmpty()) { "RangeLocator must carry at least one range" }
    }

    /** 覆盖的 UTF-16 总长度（所有区间长度之和，不是首尾跨度）。 */
    val totalLengthUtf16: Int get() = ranges.sumOf { it.lengthUtf16 }

    /** 是否所有区间都属于同一文档。 */
    val isSingleDocument: Boolean get() = ranges.map { it.documentKey }.distinct().size == 1

    /**
     * 合并同一文档内**连续或重叠**的区间（主计划第 9 节：重叠批注不重复输出文字）。
     *
     * 只合并真正相接/重叠的区间；跨文档或中间有空洞的区间保持分离，
     * 以免把未选中的文字纳入范围。
     */
    fun normalized(): RangeLocator {
        val sorted = ranges.sortedWith(compareBy({ it.documentKey }, { it.startUtf16 }, { it.endUtf16Exclusive }))
        val merged = mutableListOf<SourceRange>()
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last == null || last.documentKey != range.documentKey || range.startUtf16 > last.endUtf16Exclusive) {
                merged.add(range)
            } else {
                merged[merged.size - 1] = last.copy(
                    endUtf16Exclusive = maxOf(last.endUtf16Exclusive, range.endUtf16Exclusive)
                )
            }
        }
        return copy(ranges = merged)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * [RangeLocator] 的 JSON 编解码（主计划 5.2：`{schemaVersion,publicationId,revision,ranges}`）。
 *
 * 零 Android 依赖，JVM 单测与真机行为一致。
 */
object RangeLocatorCodec {

    private const val KEY_SCHEMA = "schemaVersion"
    private const val KEY_PUBLICATION = "publicationId"
    private const val KEY_REVISION = "revision"
    private const val KEY_RANGES = "ranges"
    private const val KEY_DOCUMENT = "documentKey"
    private const val KEY_START = "startUtf16"
    private const val KEY_END = "endUtf16Exclusive"
    private const val KEY_QUALITY = "quality"

    fun toJson(locator: RangeLocator): String {
        val payload = linkedMapOf<String, Any?>(
            KEY_SCHEMA to locator.version,
            KEY_PUBLICATION to locator.publicationId,
            KEY_REVISION to locator.revision,
            KEY_QUALITY to locator.quality.name,
            KEY_RANGES to locator.ranges.map { range ->
                linkedMapOf<String, Any?>(
                    KEY_DOCUMENT to range.documentKey,
                    KEY_START to range.startUtf16,
                    KEY_END to range.endUtf16Exclusive
                )
            }
        )
        return MiniJson.stringify(payload)
    }

    /**
     * 解析失败返回 null。
     *
     * 调用方**必须**把 null 视为「无法解析」，保留旧字段并标记 UNRESOLVED，
     * 不得用空范围或 0 偏移冒充已恢复（主计划 0.3：不得将同一旧整数直接写入新字符字段）。
     */
    fun fromJson(json: String): RangeLocator? {
        val root = MiniJson.asObject(MiniJson.parse(json)) ?: return null
        val schemaVersion = MiniJson.asInt(root[KEY_SCHEMA]) ?: return null
        val publicationId = MiniJson.asString(root[KEY_PUBLICATION]) ?: return null
        val revision = MiniJson.asString(root[KEY_REVISION]) ?: return null
        val rawRanges = MiniJson.asArray(root[KEY_RANGES]) ?: return null
        if (rawRanges.isEmpty()) return null

        val ranges = rawRanges.map { raw ->
            val entry = MiniJson.asObject(raw) ?: return null
            val documentKey = MiniJson.asString(entry[KEY_DOCUMENT]) ?: return null
            val start = MiniJson.asInt(entry[KEY_START]) ?: return null
            val end = MiniJson.asInt(entry[KEY_END]) ?: return null
            // 非法范围（负数、end < start）必须整体判失败，不能截断后当成有效数据。
            if (documentKey.isBlank() || start < 0 || end < start) return null
            SourceRange(documentKey, start, end)
        }

        val quality = MiniJson.asString(root[KEY_QUALITY])
            ?.let { runCatching { ResolutionQuality.valueOf(it) }.getOrNull() }
            ?: ResolutionQuality.EXACT

        return runCatching {
            RangeLocator(
                version = schemaVersion,
                publicationId = publicationId,
                revision = revision,
                ranges = ranges,
                quality = quality
            )
        }.getOrNull()
    }
}
