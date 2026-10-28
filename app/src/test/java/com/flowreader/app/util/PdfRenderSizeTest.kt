package com.flowreader.app.util

import org.junit.Assert.*
import org.junit.Test

class PdfRenderSizeTest {
    @Test fun hugeAndNarrowPagesStayWithinBothMemoryAndDimensionLimits() {
        for ((width, height) in listOf(600 to 800, 100000 to 200000, 1 to 100000, 100000 to 1)) {
            val (w, h) = pdfBitmapSize(width, height, 1080)
            assertTrue(w > 0 && h > 0); assertTrue(w <= 4096 && h <= 4096)
            assertTrue(w.toLong() * h <= 4_000_000)
        }
    }
    @Test fun ordinaryPagesMatchViewportWithoutLosingAspectRatio() {
        val (w, h) = pdfBitmapSize(600, 800, 1080)
        assertEquals(1080, w); assertEquals(1440, h)
    }
}
