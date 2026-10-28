package com.flowreader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFontFamilyTest {

    @Test
    fun theDefaultFaceIsTheOnlyOneWithoutASystemFamilyName() {
        // Every other chip resolves a real platform family through Typeface.create.
        assertEquals(null, ReaderFontFamily.DEFAULT.systemFamilyName)
        assertTrue(
            ReaderFontFamily.entries
                .filter { it != ReaderFontFamily.DEFAULT }
                .all { !it.systemFamilyName.isNullOrBlank() }
        )
    }

    @Test
    fun moreThanTheFourGenericFamiliesAreOffered() {
        // v0.0.6 widened the list to the weights/widths Android actually ships.
        assertTrue(ReaderFontFamily.entries.size >= 8)
        assertTrue(ReaderFontFamily.LIGHT in ReaderFontFamily.entries)
        assertTrue(ReaderFontFamily.MEDIUM in ReaderFontFamily.entries)
    }

    @Test
    fun retiredCjkNamesMapOntoTheirRealFace() {
        assertEquals(ReaderFontFamily.SERIF, ReaderFontFamily.fromStoredName("SONG"))
        assertEquals(ReaderFontFamily.SERIF, ReaderFontFamily.fromStoredName("FANGSONG"))
        assertEquals(ReaderFontFamily.SERIF, ReaderFontFamily.fromStoredName("KAI"))
        assertEquals(ReaderFontFamily.SANS_SERIF, ReaderFontFamily.fromStoredName("HEI"))
    }

    @Test
    fun unknownAndMissingValuesFallBackToDefault() {
        assertEquals(ReaderFontFamily.DEFAULT, ReaderFontFamily.fromStoredName(null))
        assertEquals(ReaderFontFamily.DEFAULT, ReaderFontFamily.fromStoredName("COMIC_SANS"))
    }

    @Test
    fun storedNamesRoundTrip() {
        ReaderFontFamily.entries.forEach { family ->
            assertEquals(family, ReaderFontFamily.fromStoredName(family.name))
        }
    }

    @Test
    fun everyFaceHasADisplayName() {
        assertTrue(ReaderFontFamily.entries.all { it.displayName.isNotBlank() })
    }
}
