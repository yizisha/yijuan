package com.flowreader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageModeTest {

    @Test
    fun onlyImplementedModesAreExposed() {
        // SIMULATION / CURL / SLIDE_OVER were selectable in v51 and rendered by nothing.
        assertEquals(listOf(PageMode.SLIDE, PageMode.PAGED, PageMode.NONE), PageMode.entries.toList())
    }

    @Test
    fun retiredModesFallBackToDefaultInsteadOfCrashing() {
        assertEquals(PageMode.PAGED, PageMode.fromStoredName("SIMULATION"))
        assertEquals(PageMode.PAGED, PageMode.fromStoredName("CURL"))
        assertEquals(PageMode.PAGED, PageMode.fromStoredName("SLIDE_OVER"))
        assertEquals(PageMode.PAGED, PageMode.fromStoredName(null))
    }

    @Test
    fun storedNamesRoundTrip() {
        PageMode.entries.forEach { mode ->
            assertEquals(mode, PageMode.fromStoredName(mode.name))
        }
    }

    @Test
    fun everyModeHasADisplayName() {
        assertTrue(PageMode.entries.all { it.displayName.isNotBlank() })
    }
}
