package io.personal.filemanager.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TileLayoutTest {
    @Test
    fun autoMatchesMockupOnPhoneAndGrowsOnWideScreens() {
        assertEquals(3, TileLayout.columns(0, 411, 9))   // телефон вертикально
        assertEquals(3, TileLayout.columns(0, 360, 9))   // узкий телефон
        assertEquals(5, TileLayout.columns(0, 915, 9))   // телефон горизонтально: 5 + 4
        assertEquals(5, TileLayout.columns(0, 800, 9))   // планшет вертикально
        assertEquals(9, TileLayout.columns(0, 1280, 9))  // планшет горизонтально: один ряд
        assertEquals(11, TileLayout.columns(0, 1280, 11)) // 11 разделов — тоже один ряд
        assertEquals(6, TileLayout.columns(0, 1100, 11)) // 6 + 5
    }

    @Test
    fun explicitSettingKeptUnlessTilesTooNarrow() {
        assertEquals(4, TileLayout.columns(4, 411, 9))
        assertEquals(2, TileLayout.columns(2, 1280, 9))
        assertEquals(3, TileLayout.columns(6, 320, 9))   // на 320 dp шесть не помещаются
    }
}
