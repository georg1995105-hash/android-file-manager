package io.personal.filemanager.data.settings

import io.personal.filemanager.core.SectionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiConfigTest {
    @Test
    fun defaultsKeepExistingOrderAndAppendTransfer() {
        val d = UiConfig.defaults()
        assertEquals(SectionId.entries.size, d.tiles.size)
        assertEquals(
            listOf("DOWNLOADS", "RECENT", "PHOTOS", "VIDEOS", "DOCUMENTS", "ARCHIVES", "APPS", "FAVORITES", "NOTES", "TRANSFER"),
            d.tiles.filter { it.visible }.map { it.id },
        )
        assertEquals(listOf("RECENT", "FILES", "APPS", "NOTES", "FAVORITES"), d.tabs.map { it.id })
    }

    @Test
    fun roundTripKeepsUserChoices() {
        val custom = UiConfig.defaults().let { c ->
            c.copy(
                tiles = c.tiles.reversed().map { if (it.id == "PHOTOS") it.copy(visible = false, icon = "camera", color = "red") else it },
                tabs = c.tabs.take(2),
            )
        }
        val decoded = UiConfigCodec.decode(UiConfigCodec.encode(custom))
        assertEquals(custom.tiles, decoded.tiles)
        assertEquals(custom.tabs, decoded.tabs)
    }

    @Test
    fun corruptedDataFallsBackToDefaults() {
        assertEquals(UiConfig.defaults(), UiConfigCodec.decode("{not json"))
        assertEquals(UiConfig.defaults(), UiConfigCodec.decode(null))
        assertEquals(UiConfig.defaults(), UiConfigCodec.decode(""))
    }

    @Test
    fun normalizeDropsUnknownAndDuplicatesAndAppendsMissingHidden() {
        val raw = """
            {"version":1,
             "tiles":[{"id":"PHOTOS","visible":true,"icon":"no_such_icon","color":"no_such_color"},
                      {"id":"TV","visible":true,"icon":"tv","color":"blue"},
                      {"id":"PHOTOS","visible":false,"icon":"camera","color":"red"}],
             "tabs":[{"id":"TV","icon":"tv","color":"blue"},{"id":"RECENT","icon":"star","color":"green"},
                     {"id":"RECENT","icon":"star","color":"green"}],
             "futureField":42}
        """.trimIndent()
        val c = UiConfigCodec.decode(raw)
        assertEquals("PHOTOS", c.tiles.first().id)
        assertEquals("image", c.tiles.first().icon) // неизвестный значок → по умолчанию
        assertEquals("purple", c.tiles.first().color)
        assertEquals(SectionId.entries.size, c.tiles.size)
        assertEquals(listOf("TRANSFER"), c.tiles.drop(1).filter { it.visible }.map { it.id }) // новый раздел доступен после обновления
        assertEquals(listOf("RECENT"), c.tabs.map { it.id })
        assertEquals("star", c.tabs.first().icon)
    }

    @Test
    fun emptyTabsNeverLeaveAppWithoutNavigation() {
        val c = UiConfigCodec.normalize(UiConfig.defaults().copy(tabs = emptyList()))
        assertFalse(c.tabs.isEmpty())
    }

    @Test
    fun tabsLimitedToFive() {
        val all = SectionId.entries.map { UiConfig.defaultTab(it) }
        assertEquals(MAX_TABS, UiConfigCodec.normalize(UiConfig.defaults().copy(tabs = all)).tabs.size)
    }

    @Test
    fun configFromPreviousVersionGetsNewDefaults() {
        // Формат 0.7.1: без tilesPerRow и viewerControls — плитки и вкладки пользователя сохраняются.
        val old = """{"version":1,"tiles":[{"id":"PHOTOS","visible":false,"icon":"camera","color":"red"}],""" +
            """"tabs":[{"id":"FILES","icon":"folder","color":"blue"}]}"""
        val c = UiConfigCodec.decode(old)
        assertEquals(TILES_PER_ROW_AUTO, c.tilesPerRow)
        assertEquals(UiConfig.defaultViewerControls(), c.viewerControls)
        assertEquals("PHOTOS", c.tiles.first().id)
        assertFalse(c.tiles.first().visible)
        assertEquals(listOf("FILES"), c.tabs.map { it.id })
    }

    @Test
    fun viewerControlsAndTilesPerRowNormalized() {
        val c = UiConfig.defaults().copy(
            tilesPerRow = 42,
            viewerControls = listOf(
                ControlConfig("DELETE", false),
                ControlConfig("UNKNOWN", true),
                ControlConfig("DELETE", true),
                ControlConfig("SHARE", true),
            ),
        )
        val n = UiConfigCodec.decode(UiConfigCodec.encode(c))
        assertEquals(TILES_PER_ROW_AUTO, n.tilesPerRow)
        assertEquals(listOf("DELETE", "SHARE"), n.viewerControls.take(2).map { it.id })
        assertFalse(n.viewerControls.first().visible)
        assertEquals(ViewerControl.entries.size, n.viewerControls.size)
        val kept = UiConfigCodec.decode(UiConfigCodec.encode(UiConfig.defaults().copy(tilesPerRow = 4)))
        assertEquals(4, kept.tilesPerRow)
    }

    @Test
    fun fileViewPrefsDefaultsAndNormalization() {
        val d = UiConfig.defaults()
        assertEquals(FileViewMode.GRID, d.viewMode("PHOTOS"))
        assertEquals(FileViewMode.LIST, d.viewMode("RECENT"))
        assertEquals(3, d.gridColumns(tablet = false, landscape = false))
        assertEquals(6, d.gridColumns(tablet = true, landscape = true))
        val custom = d.copy(
            viewModes = mapOf("RECENT" to "GRID", "PHOTOS" to "LIST", "FILES" to "MOSAIC"),
            gridColumns = mapOf("phone_portrait" to 5, "tablet_portrait" to 7, "phone_landscape" to 9, "watch" to 2),
        )
        val n = UiConfigCodec.decode(UiConfigCodec.encode(custom))
        assertEquals(FileViewMode.GRID, n.viewMode("RECENT"))
        assertEquals(FileViewMode.LIST, n.viewMode("PHOTOS"))
        assertEquals(FileViewMode.LIST, n.viewMode("FILES"))     // неизвестный режим — по умолчанию
        assertEquals(5, n.gridColumns(tablet = false, landscape = false))
        assertEquals(7, n.gridColumns(tablet = true, landscape = false))
        assertEquals(5, n.gridColumns(tablet = false, landscape = true)) // 9 вне диапазона 2..5 — по умолчанию
        assertFalse("watch" in n.gridColumns)
    }
}
