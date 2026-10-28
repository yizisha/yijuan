package com.flowreader.app.core.designsystem.reader

import com.flowreader.app.domain.model.ReadingSettings

/**
 * Pure typography math for the reader body. Everything here is deliberately free of Compose and
 * Android types so it can be unit-tested on the JVM — these are the functions that turn
 * [ReadingSettings] into real pixels, which is what the pre-v52 renderer never did.
 */
object ReaderMetrics {

    /** Ideal CJK measure. Beyond roughly this many glyphs a line becomes hard to track back. */
    const val IDEAL_LINE_CHARS = 34

    /** Allowed range for the user-facing 每行字数 setting. */
    const val MIN_LINE_CHARS = 16
    const val MAX_LINE_CHARS = 48

    /** Allowed range for the user-facing 页边距 setting, in dp. */
    const val MIN_MARGIN_DP = 8
    const val MAX_MARGIN_DP = 48

    /**
     * Height of the reading footer. The paged renderer reserves it inside the page height so the
     * last line of a page is never covered by the footer (v0.0.7 fix).
     */
    const val FOOTER_HEIGHT_DP = 28f

    /**
     * Height of the reading header (top-left subtle chapter title).
     */
    const val HEADER_HEIGHT_DP = 26f

    /**
     * Breathing space under the reading header (天头留白).
     */
    const val TOP_BREATHING_SPACE_DP = 20f

    /** Minimum body width we will ever squeeze to, in dp, before we stop clamping. */
    const val MIN_CONTENT_WIDTH_DP = 280f

    fun lineHeightSp(fontSize: Int, lineSpacing: Float): Float = fontSize.coerceAtLeast(1) * lineSpacing.coerceIn(1.0f, 2.5f)

    /**
     * Paragraph gap in dp. Before v52 the raw multiplier was passed straight to `padding(x.dp)`,
     * so the default of `1.0f` produced a 1dp gap — visually no paragraph separation at all.
     */
    fun paragraphSpacingDp(fontSize: Int, paragraphSpacing: Float): Float {
        val normalized = ReadingSettings.normalizeParagraphSpacing(paragraphSpacing)
        return fontSize.coerceAtLeast(1) * normalized * 0.5f
    }

    /**
     * Extra space under a chapter title, on top of the paragraph gap. The paginator adds exactly
     * this much to the measured title height and the renderer adds the same padding, so the two
     * stay in agreement. Kept to half a paragraph gap: a larger value pushed the first line of the
     * chapter onto the next page.
     */
    fun chapterTitleGapDp(fontSize: Int, paragraphSpacing: Float): Float = paragraphSpacingDp(fontSize, paragraphSpacing) * 0.5f

    /** First-line indent, expressed in sp: two full-width characters when enabled. */
    fun firstLineIndentSp(fontSize: Int, enabled: Boolean): Float = if (enabled) fontSize.coerceAtLeast(1) * 2f else 0f

    /**
     * Body column width.
     *
     * [marginDp] is the user's page margin (applied by the renderer as horizontal padding) and
     * [lineChars] the user's measure cap. On a tablet an unconstrained column reaches 100+
     * characters per line; the measure is capped at `fontSize * lineChars` glyphs and the remainder
     * becomes side margin.
     */
    fun contentWidthDp(availableWidthDp: Float, fontSize: Int, marginDp: Float = 0f, lineChars: Int = IDEAL_LINE_CHARS): Float {
        if (availableWidthDp <= 0f) return 0f
        val usable = (availableWidthDp - 2f * marginDp.coerceIn(0f, MAX_MARGIN_DP.toFloat())).coerceAtLeast(1f)
        val ideal = fontSize.coerceAtLeast(1) * lineChars.coerceIn(MIN_LINE_CHARS, MAX_LINE_CHARS).toFloat()
        val capped = minOf(usable, ideal)
        return maxOf(capped, minOf(usable, MIN_CONTENT_WIDTH_DP))
    }

    fun chapterTitleSizeSp(fontSize: Int): Float = fontSize.coerceAtLeast(1) + 6f

    fun headingSizeSp(fontSize: Int): Float = fontSize.coerceAtLeast(1) + 3f
}
