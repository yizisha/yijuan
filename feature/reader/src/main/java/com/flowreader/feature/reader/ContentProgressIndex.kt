package com.flowreader.feature.reader

import kotlin.math.abs

/**
 * 全书进度索引（主计划 10.2 / R3 / INV11）。
 *
 * 旧实现按**章节数等权**：`ReadingProgress.fraction()` 用 `(index + inside) / totalChapters`，
 * 于是 100 字与 900 字的两章各占 50%，滑杆逆映射也按章数反推（F03）。
 *
 * 这里改为按**文本量加权**：设 section 有效字数 `w[i]`、前缀和 `P[i]`、总量 `W`，
 *
 * ```
 * progress = W > 0 ? clamp((P[i] + u) / W, 0, 1) : Unknown
 * remainingUnits = max(0, W - P[i] - u)
 * ```
 *
 * 所有累计量用 Long，除法用 Double。分母来自**完整出版物索引**，不依赖当前已加载的章节集合
 * （INV11）；索引未就绪时 [isReady] 为 false，UI 显示“正在计算”而不是回退成等权冒充精度。
 */
class ContentProgressIndex private constructor(
    private val sectionIds: List<String>,
    private val prefixUnits: LongArray,
    private val sectionUnits: LongArray,
    private val total: Long
) {

    val totalUnits: Long get() = total
    val sectionCount: Int get() = sectionIds.size
    val isReady: Boolean get() = sectionIds.isNotEmpty() && totalUnits > 0L

    fun sectionIdAt(ordinal: Int): String? = sectionIds.getOrNull(ordinal)

    /** 单章有效字数，供 UI 显示“本节 X 字”。 */
    fun unitsOf(ordinal: Int): Long = sectionUnits.getOrNull(ordinal) ?: 0L

    /**
     * 当前内容占比，可前进也可后退。
     *
     * @param withinFraction 该 section 内已读比例 0..1。滚动模式由可见比例给出，本身是对文本量的
     *   近似（行长方差越大越粗），但**章节权重是精确的**——这正是本次要修的那一半。
     */
    fun progress(ordinal: Int, withinFraction: Double): Double {
        if (!isReady) return 0.0
        val index = ordinal.coerceIn(0, sectionIds.size - 1)
        val base = prefixUnits[index].toDouble()
        val inside = sectionUnits[index].toDouble() * clampFraction(withinFraction)
        return ((base + inside) / totalUnits.toDouble()).coerceIn(0.0, 1.0)
    }

    /** 距主线末尾还剩多少有效字。 */
    fun remainingUnits(ordinal: Int, withinFraction: Double): Long {
        if (!isReady) return 0L
        val index = ordinal.coerceIn(0, sectionIds.size - 1)
        val consumed = prefixUnits[index] + (sectionUnits[index].toDouble() * clampFraction(withinFraction)).toLong()
        return (totalUnits - consumed).coerceAtLeast(0L)
    }

    /** 滑杆逆映射：占比 → section 序号（累计权重逆查）。 */
    fun sectionAt(progress: Double): Int {
        if (!isReady) return 0
        val target = totalUnits.toDouble() * progress.coerceIn(0.0, 1.0)
        // 二分最后一个 prefix <= target 的 section。
        var low = 0
        var high = sectionIds.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) / 2
            if (prefixUnits[mid].toDouble() <= target) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    /** 滑杆逆映射：占比 → 该 section 内的比例，用于定位到 section 内具体位置。 */
    fun withinFractionAt(progress: Double): Double {
        if (!isReady) return 0.0
        val index = sectionAt(progress)
        val units = sectionUnits[index]
        if (units <= 0L) return 0.0
        val target = totalUnits.toDouble() * progress.coerceIn(0.0, 1.0)
        return ((target - prefixUnits[index].toDouble()) / units.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun clampFraction(value: Double): Double {
        val safe = if (value.isNaN()) 0.0 else value
        return safe.coerceIn(0.0, 1.0)
    }

    companion object {
        val EMPTY = of(emptyList())

        /**
         * @param sections 按**阅读顺序**排列，(sectionId, 有效字数)。零权重段允许存在
         *   （例如纯结构页），滑杆跳过它们但仍可导航（主计划 10.2 末段）。
         */
        fun of(sections: List<Pair<String, Long>>): ContentProgressIndex {
            val ids = ArrayList<String>(sections.size)
            val units = LongArray(sections.size)
            val prefix = LongArray(sections.size)
            var running = 0L
            sections.forEachIndexed { i, (id, w) ->
                ids.add(id)
                val weight = w.coerceAtLeast(0L)
                units[i] = weight
                prefix[i] = running
                running += weight
            }
            return ContentProgressIndex(ids, prefix, units, running)
        }
    }
}

/**
 * 文本量统计与边界工具（主计划 3.2 末段 / INV04）。
 *
 * 统计口径：**非空白 Unicode code point 数**，去掉空白、U+FFFC（图片占位）、辅助 role 与生成
 * 分隔符。这与 UTF-16 定位长度**不同**——一个 emoji 占 2 个 UTF-16 单位但只计 1 个字。
 */
object TextUnits {

    /** 图片/原子对象在源流中占一个 U+FFFC，不计入有效字数。 */
    const val OBJECT_REPLACEMENT = '\uFFFC'

    /** 非空白 code point 计数（主计划 10.2 golden：emoji 计 1）。 */
    fun count(text: CharSequence): Long {
        var count = 0L
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (Character.isHighSurrogate(ch) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                // 代理对：一个 code point，占两个 UTF-16 单位。
                if (!isIgnorableCodePoint(Character.toCodePoint(ch, text[i + 1]))) count++
                i += 2
                continue
            }
            if (!Character.isWhitespace(ch) && ch != OBJECT_REPLACEMENT) count++
            i++
        }
        return count
    }

    private fun isIgnorableCodePoint(codePoint: Int): Boolean {
        return Character.isWhitespace(codePoint) || codePoint == OBJECT_REPLACEMENT.code
    }

    /** 偏移是否落在代理对中间——落在中间必须被拒绝，否则会切坏 emoji（INV04）。 */
    fun splitsSurrogatePair(text: CharSequence, offset: Int): Boolean {
        if (offset <= 0 || offset >= text.length) return false
        return Character.isLowSurrogate(text[offset]) && Character.isHighSurrogate(text[offset - 1])
    }

    /** 把任意偏移夹到最近的合法边界（不切开代理对）。 */
    fun sanitizeOffset(text: CharSequence, offset: Int): Int {
        val clamped = offset.coerceIn(0, text.length)
        return if (splitsSurrogatePair(text, clamped)) clamped - 1 else clamped
    }

    /** UTF-16 偏移 → code point 序号（用于把 source offset 映射到权重检查点）。 */
    fun codePointIndex(text: CharSequence, offsetUtf16: Int): Int {
        val safe = sanitizeOffset(text, offsetUtf16)
        var index = 0
        var i = 0
        while (i < safe) {
            val ch = text[i]
            i += if (Character.isHighSurrogate(ch) && i + 1 < safe && Character.isLowSurrogate(text[i + 1])) 2 else 1
            index++
        }
        return index
    }

    /** 两个 UTF-16 范围是否几乎相同（用于上下文匹配的唯一性判定）。 */
    fun nearlyEqual(a: String, b: String, tolerance: Int = 0): Boolean {
        if (a == b) return true
        if (tolerance <= 0) return false
        return abs(a.length - b.length) <= tolerance && a.take(tolerance) == b.take(tolerance)
    }
}
