package io.personal.filemanager.ui

import io.personal.filemanager.ui.sections.nextAppTab
import org.junit.Assert.assertEquals
import org.junit.Test

class SectionSwipeTest {
    @Test fun swipesOnlyBetweenInstalledAndApkTabs() {
        assertEquals(1, nextAppTab(0, left = true))
        assertEquals(1, nextAppTab(1, left = true))
        assertEquals(0, nextAppTab(1, left = false))
        assertEquals(0, nextAppTab(0, left = false))
    }
}
