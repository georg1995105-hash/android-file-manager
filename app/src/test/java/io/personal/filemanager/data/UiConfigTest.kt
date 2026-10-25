package io.personal.filemanager.data.settings

import io.personal.filemanager.core.SectionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiConfigTest {
    @Test
    fun defaultsFollowMockupWithoutTv() {
        val d = UiConfig.defaults()
        assertEquals(SectionId.entries.size, d.tiles.size)
        assertEquals(
            listOf("DOWNLOADS", "RECENT", "PHOTOS", "VIDEOS", "DOCUMENTS", "ARCHIVES", "APPS", "FAVORITES", "NOTES"),
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
        assertTrue(c.tiles.drop(1).none { it.visible }) // добавленные разделы скрыты
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
}
