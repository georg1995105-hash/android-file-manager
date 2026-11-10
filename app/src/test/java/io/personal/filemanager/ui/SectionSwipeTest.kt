package io.personal.filemanager.ui

import io.personal.filemanager.core.SectionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SectionSwipeTest {
    @Test fun movesWithinConfiguredTabOrder() {
        val tabs = listOf(SectionId.RECENT, SectionId.FILES, SectionId.APPS, SectionId.FAVORITES)
        assertEquals(SectionId.APPS, adjacentTab(tabs, SectionId.FILES, left = true))
        assertEquals(SectionId.FILES, adjacentTab(tabs, SectionId.APPS, left = false))
        assertNull(adjacentTab(tabs, SectionId.RECENT, left = false))
        assertNull(adjacentTab(tabs, SectionId.FAVORITES, left = true))
        assertNull(adjacentTab(tabs, SectionId.AUDIO, left = true))
    }
}
