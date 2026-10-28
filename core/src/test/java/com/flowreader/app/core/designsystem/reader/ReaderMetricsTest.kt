package com.flowreader.app.core.designsystem.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMetricsTest {

    @Test
    fun lineHeightScalesWithFontSizeAndSpacing() {
        assertEquals(27f, ReaderMetrics.lineHeightSp(18, 1.5f), 0.001f)
        assertEquals(24f, ReaderMetrics.lineHeightSp(24, 1.0f), 0.001f)
    }

    @Test
    fun lineHeightClampsUnreasonableSpacing() {
        assertEquals(18f, ReaderMetrics.lineHeightSp(18, 0.1f), 0.001f)
        assertEquals(45f, ReaderMetrics.lineHeightSp(18, 9f), 0.001f)
    }

    @Test
    fun paragraphSpacingFollowsFontSizeInsteadOfBeingOneDp() {
        // The v51 renderer passed the raw 1.0f multiplier to padding(dp) and produced a 1dp gap.
        assertEquals(9f, ReaderMetrics.paragraphSpacingDp(18, 1.0f), 0.001f)
        assertEquals(24f, ReaderMetrics.paragraphSpacingDp(24, 2.0f), 0.001f)
    }

    @Test
    fun paragraphSpacingMigratesLegacyDpValues() {
        // A stored 12f meant "12dp" before v52; as a multiplier it is nonsense, so it resets.
        assertEquals(9f, ReaderMetrics.paragraphSpacingDp(18, 12f), 0.001f)
    }

    @Test
    fun firstLineIndentIsTwoCharactersWhenEnabled() {
        assertEquals(36f, ReaderMetrics.firstLineIndentSp(18, true), 0.001f)
        assertEquals(0f, ReaderMetrics.firstLineIndentSp(18, false), 0.001f)
    }

    @Test
    fun contentWidthCapsTheMeasureOnWideScreens() {
        val tablet = ReaderMetrics.contentWidthDp(availableWidthDp = 1200f, fontSize = 18)
        assertEquals(18f * ReaderMetrics.IDEAL_LINE_CHARS, tablet, 0.001f)
        assertTrue(tablet < 1200f)
    }

    @Test
    fun contentWidthNeverExceedsTheAvailableSpace() {
        val phone = ReaderMetrics.contentWidthDp(availableWidthDp = 360f, fontSize = 18)
        assertEquals(360f, phone, 0.001f)
        assertEquals(0f, ReaderMetrics.contentWidthDp(availableWidthDp = 0f, fontSize = 18), 0.001f)
    }

    @Test
    fun narrowScreensAreNotSqueezedBelowTheMinimum() {
        val tiny = ReaderMetrics.contentWidthDp(availableWidthDp = 320f, fontSize = 6)
        assertEquals(ReaderMetrics.MIN_CONTENT_WIDTH_DP, tiny, 0.001f)
    }

    @Test
    fun headingsStayLargerThanBody() {
        assertTrue(ReaderMetrics.chapterTitleSizeSp(18) > ReaderMetrics.headingSizeSp(18))
        assertTrue(ReaderMetrics.headingSizeSp(18) > 18f)
    }

    @Test
    fun marginNarrowsTheMeasure() {
        val wide = ReaderMetrics.contentWidthDp(availableWidthDp = 400f, fontSize = 18, marginDp = 0f, lineChars = 34)
        val narrow = ReaderMetrics.contentWidthDp(availableWidthDp = 400f, fontSize = 18, marginDp = 40f, lineChars = 34)
        assertEquals(320f, narrow, 0.01f)
        assertTrue(narrow < wide)
    }

    @Test
    fun lineCharsCapsTheMeasureOnWideScreens() {
        val capped = ReaderMetrics.contentWidthDp(availableWidthDp = 1200f, fontSize = 18, marginDp = 20f, lineChars = 34)
        assertEquals(18f * 34f, capped, 0.01f)
    }

    @Test
    fun marginAndLineCharsAreClampedToTheirRange() {
        // Out-of-range stored values must not produce a zero-width or absurd column.
        val width = ReaderMetrics.contentWidthDp(availableWidthDp = 360f, fontSize = 18, marginDp = 999f, lineChars = 999)
        assertTrue(width > 0f)
        assertTrue(width <= 360f)
    }

    @Test
    fun titleGapIsSmallerThanTheParagraphGap() {
        // A larger title gap pushed the first line of a chapter onto the next page, so it is kept
        // below one paragraph gap; the renderer still separates the title from the body.
        val gap = ReaderMetrics.paragraphSpacingDp(18, 1f)
        val titleGap = ReaderMetrics.chapterTitleGapDp(18, 1f)
        assertTrue(titleGap > 0f)
        assertTrue(titleGap < gap)
    }
}
