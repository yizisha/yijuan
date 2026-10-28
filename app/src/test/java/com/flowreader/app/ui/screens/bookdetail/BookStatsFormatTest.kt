package com.flowreader.app.ui.screens.bookdetail

import org.junit.Assert.assertEquals
import org.junit.Test

/** The two display formatters the statistics page relies on. */
class BookStatsFormatTest {

    @Test
    fun wordCountIsShownInTenThousandsAboveTenThousand() {
        assertEquals("—", formatWordCount(0))
        assertEquals("9999", formatWordCount(9_999))
        assertEquals("1.0 万", formatWordCount(10_000))
        assertEquals("123.5 万", formatWordCount(1_234_567))
    }

    @Test
    fun fileSizeUsesReadableUnits() {
        assertEquals("—", formatFileSize(0))
        assertEquals("512 B", formatFileSize(512))
        assertEquals("2 KB", formatFileSize(2048))
        assertEquals("1.5 MB", formatFileSize(1024L * 1536L))
    }
}
