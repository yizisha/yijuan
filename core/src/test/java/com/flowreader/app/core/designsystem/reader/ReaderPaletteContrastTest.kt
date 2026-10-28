package com.flowreader.app.core.designsystem.reader

import com.flowreader.app.core.util.ColorContrast
import com.flowreader.app.domain.model.ReaderPaletteId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility gate for the reader. Any new palette that fails WCAG AA fails the build,
 * which is the automated replacement for "we eyeballed it".
 */
class ReaderPaletteContrastTest {

    @Test
    fun everyPaletteIdHasExactlyOnePalette() {
        assertEquals(ReaderPaletteId.entries.size, ReaderPalettes.all.size)
        ReaderPaletteId.entries.forEach { id ->
            assertEquals(id, ReaderPalettes.of(id).id)
        }
    }

    @Test
    fun nineteenBuiltInPalettesAreAvailable() {
        assertEquals(19, ReaderPalettes.all.size)
        assertEquals(10, ReaderPaletteId.LIGHT_PALETTES.size)
        assertEquals(9, ReaderPaletteId.DARK_PALETTES.size)
    }

    @Test
    fun bodyTextMeetsWcagAaOnEveryPalette() {
        ReaderPalettes.all.forEach { palette ->
            val ratio = ColorContrast.ratio(palette.textArgb, palette.backgroundArgb)
            assertTrue(
                "${palette.id} body contrast is $ratio, below ${ColorContrast.AA_BODY_TEXT}",
                ratio >= ColorContrast.AA_BODY_TEXT
            )
        }
    }

    @Test
    fun secondaryTextMeetsWcagAaOnEveryPalette() {
        ReaderPalettes.all.forEach { palette ->
            val ratio = ColorContrast.ratio(palette.secondaryTextArgb, palette.backgroundArgb)
            assertTrue(
                "${palette.id} secondary contrast is $ratio, below ${ColorContrast.AA_BODY_TEXT}",
                ratio >= ColorContrast.AA_BODY_TEXT
            )
        }
    }

    @Test
    fun darknessFlagMatchesMeasuredLuminance() {
        ReaderPalettes.all.forEach { palette ->
            val luminance = ColorContrast.relativeLuminance(palette.backgroundArgb)
            assertEquals("${palette.id} isDark disagrees with its background", palette.isDark, luminance < 0.2)
        }
    }
    @Test fun cleanPaperMeetsContrastWhileEveryNightPaletteRetainsItsExactColors() {
        val clean = com.flowreader.app.domain.model.AppVisualStyle.CLEAN_BLUE
        val warm = com.flowreader.app.domain.model.AppVisualStyle.WARM_PAPER
        val paper = ReaderPalettes.of(ReaderPaletteId.PAPER, clean)
        assertTrue(ColorContrast.ratio(paper.textArgb, paper.backgroundArgb) >= ColorContrast.AA_BODY_TEXT)
        assertTrue(ColorContrast.ratio(paper.secondaryTextArgb, paper.backgroundArgb) >= ColorContrast.AA_BODY_TEXT)
        ReaderPaletteId.DARK_PALETTES.forEach { assertEquals(ReaderPalettes.of(it, warm), ReaderPalettes.of(it, clean)) }
    }

}
